package ru.simple.mycalendar.v2.security

import android.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SyncKeyController(private val keys: SecureKeys) {
    private var envelope = newEnvelope()

    @Synchronized
    fun encrypt(plain: ByteArray, aad: ByteArray): ByteArray = envelope.encrypt(plain, aad)

    @Synchronized
    fun decrypt(ciphertext: ByteArray, aad: ByteArray): ByteArray = envelope.decrypt(ciphertext, aad)

    fun authenticationProof(message: ByteArray): ByteArray {
        val key = keys.getOrCreate(KEY_NAME)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(message)
            }
        } finally {
            key.fill(0)
        }
    }

    fun recoveryCode(): String {
        val key = keys.getOrCreate(KEY_NAME)
        return try {
            Base64.encodeToString(key, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } finally {
            key.fill(0)
        }
    }

    @Synchronized
    fun importRecoveryCode(code: String) {
        val raw = try {
            Base64.decode(code.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Неверный recovery-код", error)
        }
        require(raw.size == 32) { "Recovery-код должен содержать 256-битный ключ" }
        keys.put(KEY_NAME, raw)
        envelope.close()
        envelope = EncryptedEnvelope(raw)
        raw.fill(0)
    }

    private fun newEnvelope(): EncryptedEnvelope {
        val key = keys.getOrCreate(KEY_NAME)
        return try {
            EncryptedEnvelope(key)
        } finally {
            key.fill(0)
        }
    }

    companion object { private const val KEY_NAME = "sync_key" }
}
