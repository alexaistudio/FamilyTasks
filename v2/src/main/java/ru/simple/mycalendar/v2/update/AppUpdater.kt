package ru.simple.mycalendar.v2.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import ru.simple.mycalendar.v2.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

sealed interface UpdateResult {
    data class Ready(val version: String, val file: File) : UpdateResult
    data class UpToDate(val version: String) : UpdateResult
    data class Error(val message: String) : UpdateResult
}

sealed interface UpdateProgress {
    data object CheckingGitHub : UpdateProgress
    data class LatestVersion(val version: String) : UpdateProgress
    data class UpdateFound(val version: String, val bytes: Long) : UpdateProgress
    data class Downloading(val version: String, val downloaded: Long, val total: Long) : UpdateProgress
    data class Verifying(val version: String) : UpdateProgress
}

/** Downloads only APKs published in the official GitHub Releases feed. */
class AppUpdater(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    fun checkAndDownload(onProgress: (UpdateProgress) -> Unit = {}): UpdateResult {
        return try {
            onProgress(UpdateProgress.CheckingGitHub)
            val release = loadLatestRelease()
            val version = release.getString("tag_name").trim().removePrefix("v")
            if (compareVersions(version, BuildConfig.VERSION_NAME) <= 0) {
                onProgress(UpdateProgress.LatestVersion(version))
                UpdateResult.UpToDate(version)
            } else {
                downloadNewerRelease(release, version, onProgress)
            }
        } catch (e: PrivateReleaseException) {
            UpdateResult.Error("Релизы пока недоступны: приватный GitHub-репозиторий не отдаёт их приложению.")
        } catch (e: Exception) {
            UpdateResult.Error(e.message?.take(180) ?: "Не удалось проверить обновления.")
        }
    }

    private fun downloadNewerRelease(
        release: JSONObject,
        version: String,
        onProgress: (UpdateProgress) -> Unit
    ): UpdateResult {
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length()).map(assets::getJSONObject).firstOrNull {
            val name = it.optString("name")
            name.endsWith("-release.apk", ignoreCase = true) ||
                name.equals("FTasks.apk", ignoreCase = true)
        } ?: return UpdateResult.Error("В релизе $version нет release APK.")
        val size = asset.optLong("size", -1L)
        if (size <= 0L || size > MAX_APK_BYTES) {
            return UpdateResult.Error("GitHub сообщил некорректный размер APK.")
        }
        onProgress(UpdateProgress.UpdateFound(version, size))
        val directory = File(appContext.cacheDir, UPDATE_DIRECTORY).apply { mkdirs() }
        directory.listFiles()?.forEach { if (it.name != READY_APK) it.delete() }
        val temporary = File(directory, "$READY_APK.part")
        val ready = File(directory, READY_APK)
        temporary.delete()
        download(asset.getString("browser_download_url"), temporary, size) { downloaded ->
            onProgress(UpdateProgress.Downloading(version, downloaded, size))
        }
        onProgress(UpdateProgress.Verifying(version))
        verifyDigestIfPresent(temporary, asset.optString("digest"))
        verifyOfficialApk(temporary)
        if (ready.exists() && !ready.delete()) error("Не удалось заменить прошлую загрузку.")
        if (!temporary.renameTo(ready)) error("Не удалось подготовить APK к установке.")
        return UpdateResult.Ready(version, ready)
    }

    fun readyApk(): File? = File(File(appContext.cacheDir, UPDATE_DIRECTORY), READY_APK)
        .takeIf { it.isFile }

    fun verifyOfficialApk(file: File) {
        val archive = packageInfo(file.absolutePath)
            ?: error("Скачанный файл не является Android APK.")
        if (archive.packageName != appContext.packageName) {
            error("APK имеет другой идентификатор приложения.")
        }
        if (archive.longVersionCodeCompat() <= BuildConfig.VERSION_CODE.toLong()) {
            error("APK не новее установленной версии.")
        }
        val current = packageInfo(appContext.packageName)
            ?: error("Не удалось проверить подпись установленного приложения.")
        if (signerDigests(archive) != signerDigests(current)) {
            error("APK подписан не официальным ключом FamilyTasks.")
        }
    }

    private fun loadLatestRelease(): JSONObject {
        val connection = open(LATEST_RELEASE_URL)
        return connection.useConnection { code ->
            when (code) {
                HttpURLConnection.HTTP_OK -> JSONObject(inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> throw PrivateReleaseException()
                else -> error("GitHub вернул HTTP $code при проверке релиза.")
            }
        }
    }

    private fun download(
        url: String,
        destination: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit
    ) {
        val connection = open(url)
        connection.useConnection { code ->
            if (code !in 200..299) error("Не удалось скачать APK: HTTP $code.")
            val announced = contentLengthLong
            if (announced > MAX_APK_BYTES) error("APK превышает допустимый размер.")
            inputStream.use { input ->
                FileOutputStream(destination).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    var lastPercent = -1
                    fun reportProgress() {
                        val percent = progressPercent(total, expectedSize)
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(total)
                        }
                    }
                    reportProgress()
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_APK_BYTES) error("APK превышает допустимый размер.")
                        output.write(buffer, 0, read)
                        reportProgress()
                    }
                    output.fd.sync()
                    if (total != expectedSize) error("Загрузка APK завершилась не полностью.")
                }
            }
        }
    }

    private fun verifyDigestIfPresent(file: File, digest: String) {
        if (!digest.startsWith("sha256:", ignoreCase = true)) return
        val expected = digest.substringAfter(':').lowercase(Locale.ROOT)
        val algorithm = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                algorithm.update(buffer, 0, read)
            }
        }
        val actual = algorithm.digest().joinToString("") { "%02x".format(it) }
        if (actual != expected) error("Контрольная сумма APK не совпала.")
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "FamilyTasks/${BuildConfig.VERSION_NAME}")
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(value: String): PackageInfo? = if (Build.VERSION.SDK_INT >= 28) {
        if (value == appContext.packageName) {
            packageManager.getPackageInfo(value, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            packageManager.getPackageArchiveInfo(value, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    } else {
        if (value == appContext.packageName) {
            packageManager.getPackageInfo(value, PackageManager.GET_SIGNATURES)
        } else {
            packageManager.getPackageArchiveInfo(value, PackageManager.GET_SIGNATURES)
        }
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: error("В APK нет подписи.")
            if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory
        } else {
            info.signatures.orEmpty()
        }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

    private inline fun <T> HttpURLConnection.useConnection(block: HttpURLConnection.(Int) -> T): T = try {
        block(responseCode)
    } finally {
        disconnect()
    }

    private class PrivateReleaseException : Exception()

    companion object {
        const val UPDATE_DIRECTORY = "updates"
        const val READY_APK = "FamilyTasks-latest.apk"
        private const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/alexaistudio/FamilyTasks/releases/latest"
        private const val MAX_APK_BYTES = 250L * 1024L * 1024L

        internal fun compareVersions(left: String, right: String): Int {
            val a = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val b = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            repeat(maxOf(a.size, b.size)) { index ->
                val compared = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
                if (compared != 0) return compared
            }
            return 0
        }

        internal fun progressPercent(downloaded: Long, total: Long): Int =
            if (total <= 0L) 0 else ((downloaded.coerceIn(0L, total) * 100L) / total).toInt()

        internal fun asciiProgress(downloaded: Long, total: Long, width: Int = 16): String {
            val safeWidth = width.coerceAtLeast(1)
            val percent = progressPercent(downloaded, total)
            val filled = (percent * safeWidth) / 100
            return "[${"#".repeat(filled)}${"-".repeat(safeWidth - filled)}] $percent%"
        }
    }
}
