package ru.simple.mycalendar.v2.sync

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.simple.mycalendar.v2.data.TaskDao
import ru.simple.mycalendar.v2.security.SecureKeys
import ru.simple.mycalendar.v2.security.SyncKeyController
import ru.simple.mycalendar.v2.notify.ReminderScheduler
import ru.simple.mycalendar.v2.notify.IncomingTaskNotifier
import ru.simple.mycalendar.v2.UiPreferences
import ru.simple.mycalendar.v2.LocalChangeTracker
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class SyncInfo(
    val configured: Boolean,
    val serverUrl: String,
    val certificateSha256: String,
    val accountId: String,
    val lastSeq: Long
)

class SyncClient(
    context: Context,
    private val dao: TaskDao,
    private val keys: SecureKeys,
    private val crypto: SyncKeyController,
    private val reminders: ReminderScheduler,
    private val uiPreferences: UiPreferences,
    private val localChanges: LocalChangeTracker
) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("sync_v2", Context.MODE_PRIVATE)
    private val incomingNotifier = IncomingTaskNotifier(appContext)
    private val syncMutex = Mutex()

    fun info(): SyncInfo {
        val token = keys.get(TOKEN_KEY)
        val configured = prefs.getString("device_id", null) != null && token != null
        token?.fill(0)
        return SyncInfo(
            configured = configured,
            serverUrl = prefs.getString("server_url", "") ?: "",
            certificateSha256 = prefs.getString("certificate_sha256", "") ?: "",
            accountId = prefs.getString("account_id", "") ?: "",
            lastSeq = prefs.getLong("last_seq", 0)
        )
    }

    fun recoveryCode(): String = crypto.recoveryCode()

    fun importRecoveryCode(code: String) {
        check(!info().configured) { "Сначала отключите текущую синхронизацию" }
        crypto.importRecoveryCode(code)
        prefs.edit().putLong("last_seq", 0).apply()
    }

    suspend fun register(serverUrl: String, invite: String, certificateSha256: String): SyncInfo = withContext(Dispatchers.IO) {
        val server = normalizeServer(serverUrl)
        val pin = normalizeCertificateSha256(certificateSha256)
        require(pin.isEmpty() || URL(server).protocol == "https") {
            "Отпечаток сертификата используется только с HTTPS"
        }
        val body = JSONObject()
            .put("invite", invite.trim())
            .put("deviceName", "Android")
        val response = request(server, pin, "register", "POST", null, body)
        val token = response.getString("token").toByteArray(Charsets.UTF_8)
        keys.put(TOKEN_KEY, token)
        token.fill(0)
        check(prefs.edit()
            .putString("server_url", server)
            .putString("certificate_sha256", pin)
            .putString("account_id", response.getString("accountId"))
            .putString("device_id", response.getString("deviceId"))
            .putLong("last_seq", 0)
            .remove(LAST_UPLOADED_GENERATION)
            .commit())
        info()
    }

    suspend fun syncNow(): Long = syncMutex.withLock {
        withContext(Dispatchers.IO) {
        val cfg = info()
        check(cfg.configured) { "Синхронизация не настроена" }
        val token = bearerToken()
        try {
            val aad = aad(cfg.accountId)
            val generationAtStart = localChanges.generation()
            if (prefs.getLong(LAST_UPLOADED_GENERATION, -1L) < generationAtStart) {
                val snapshot = SyncSnapshot(dao.snapshotTasks(), dao.snapshotPurges(), dao.snapshotProfiles())
                val plain = SnapshotCodec.encode(snapshot)
                val blobId = keyedSnapshotId(plain)
                val encrypted = try { crypto.encrypt(plain, aad) } finally { plain.fill(0) }
                val push = JSONObject()
                    .put("blobId", blobId)
                    .put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                encrypted.fill(0)
                request(cfg.serverUrl, cfg.certificateSha256, "push", "POST", token, push)
                check(prefs.edit().putLong(LAST_UPLOADED_GENERATION, generationAtStart).commit())
            }

            var after = cfg.lastSeq
            val incomingIds = linkedSetOf<String>()
            var more: Boolean
            do {
                val pulled = request(cfg.serverUrl, cfg.certificateSha256, "pull&after=$after", "GET", token, null)
                val items = pulled.getJSONArray("items")
                repeat(items.length()) { index ->
                    val item = items.getJSONObject(index)
                    val cipher = Base64.decode(item.getString("ciphertext"), Base64.DEFAULT)
                    val decoded = crypto.decrypt(cipher, aad)
                    cipher.fill(0)
                    val remote = try { SnapshotCodec.decode(decoded) } finally { decoded.fill(0) }
                    remote.tasks.forEach { task ->
                        if (task.deletedAt == null && dao.find(task.id) == null) incomingIds += task.id
                    }
                    dao.mergeSnapshot(remote.tasks, remote.purges, remote.profiles)
                    remote.tasks.mapNotNull { dao.find(it.id) }.forEach(reminders::schedule)
                    remote.purges.forEach { reminders.cancel(it.taskId) }
                }
                after = pulled.getLong("lastSeq")
                more = pulled.optBoolean("hasMore", false)
            } while (more)
            prefs.edit().putLong("last_seq", after).apply()
            if (cfg.lastSeq > 0 && uiPreferences.notifyAboutNewTasks()) {
                val incoming = incomingIds.mapNotNull { dao.find(it) }
                    .filter { it.deletedAt == null }
                incomingNotifier.show(incoming)
            }
            after
        } finally {
            token.fill(0)
        }
        }
    }

    fun disconnect() {
        keys.remove(TOKEN_KEY)
        prefs.edit().clear().apply()
        ServerSyncHealth(appContext).clear()
    }

    private fun bearerToken(): ByteArray = keys.get(TOKEN_KEY) ?: error("Токен устройства недоступен")

    private fun request(
        server: String,
        certificateSha256: String,
        action: String,
        method: String,
        token: ByteArray?,
        body: JSONObject?
    ): JSONObject {
        val separator = if ('?' in server) '&' else '?'
        val connection = URL("$server${separator}action=$action").openConnection() as HttpURLConnection
        if (connection is HttpsURLConnection && certificateSha256.isNotEmpty()) {
            connection.sslSocketFactory = pinnedSslContext(certificateSha256).socketFactory
            // The exact leaf certificate is the server identity. This also supports an
            // IP address when a private certificate has no matching IP subjectAltName.
            connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
        }
        connection.apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer ${deviceId()}.${token.toString(Charsets.UTF_8)}")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { throw IllegalStateException("Сервер вернул не JSON (HTTP $status)") }
            if (status !in 200..299 || !json.optBoolean("ok", false)) {
                throw IllegalStateException(json.optString("error", "Ошибка сервера HTTP $status"))
            }
            json
        } finally {
            connection.disconnect()
        }
    }

    private fun deviceId(): String = prefs.getString("device_id", null) ?: error("Нет id устройства")

    private fun normalizeServer(value: String): String {
        val input = value.trim().trimEnd('/')
        val clean = if ("://" in input) input else "http://$input"
        val url = URL(clean)
        require(url.protocol == "http" || url.protocol == "https") { "Нужен адрес HTTP или HTTPS" }
        require(url.host.isNotBlank()) { "Неверный адрес сервера" }
        require(url.userInfo == null && url.ref == null) { "В адресе сервера не должно быть логина или #фрагмента" }
        return clean
    }

    private fun normalizeCertificateSha256(value: String): String {
        val original = value.trim()
        val afterLabel = if ('=' in original) original.substringAfterLast('=') else original
        val withoutPrefix = afterLabel.replace(Regex("^(?i:SHA-?256)\\s*:\\s*"), "")
        val clean = withoutPrefix.replace(Regex("[:\\s-]"), "").uppercase()
        require(clean.isEmpty() || clean.matches(Regex("[0-9A-F]{64}"))) {
            "SHA-256-отпечаток должен содержать 64 шестнадцатеричных символа"
        }
        return clean
    }

    @SuppressLint("CustomX509TrustManager")
    private fun pinnedSslContext(certificateSha256: String): SSLContext {
        val expected = certificateSha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val leaf = chain?.firstOrNull() ?: throw CertificateException("Сервер не прислал сертификат")
                val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                if (!MessageDigest.isEqual(expected, actual)) {
                    throw CertificateException("SHA-256-отпечаток сертификата сервера не совпал")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
    }

    private fun aad(accountId: String) = "mycalendar-v2/snapshot/$accountId".toByteArray(Charsets.UTF_8)

    /** Same plaintext state gets one opaque server blob, even from different phones. */
    private fun keyedSnapshotId(plain: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(plain)
        val label = "familytasks/server/state-v1/".toByteArray(Charsets.UTF_8) + digest
        digest.fill(0)
        val proof = try {
            crypto.authenticationProof(label)
        } finally {
            label.fill(0)
        }
        return try {
            proof.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally {
            proof.fill(0)
        }
    }

    companion object {
        private const val TOKEN_KEY = "server_device_token"
        private const val LAST_UPLOADED_GENERATION = "last_uploaded_generation_v1"
    }
}
