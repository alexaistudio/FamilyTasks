package ru.simple.mycalendar.v2.security

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned, compressed AES-256-GCM envelope used before anything is sent to a server. */
class EncryptedEnvelope(key: ByteArray) {
    private val secret = key.copyOf().also { require(it.size == 32) }

    fun encrypt(plain: ByteArray, associatedData: ByteArray): ByteArray {
        val packed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(plain) }
            output.toByteArray()
        }
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        val encrypted = cipher.doFinal(packed)
        packed.fill(0)
        return MAGIC + byteArrayOf(VERSION) + nonce + encrypted
    }

    @Throws(AEADBadTagException::class)
    fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
        require(envelope.size > MAGIC.size + 1 + NONCE_BYTES + 16) { "Повреждённый пакет" }
        require(envelope.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Чужой формат пакета" }
        require(envelope[MAGIC.size] == VERSION) { "Неподдерживаемая версия пакета" }
        val nonceAt = MAGIC.size + 1
        val nonce = envelope.copyOfRange(nonceAt, nonceAt + NONCE_BYTES)
        val encrypted = envelope.copyOfRange(nonceAt + NONCE_BYTES, envelope.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        val packed = cipher.doFinal(encrypted)
        return try {
            GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
        } finally {
            packed.fill(0)
        }
    }

    fun close() = secret.fill(0)

    companion object {
        private val MAGIC = byteArrayOf(0x4d, 0x43, 0x56, 0x32) // MCV2
        private const val VERSION: Byte = 1
        private const val NONCE_BYTES = 12
    }
}
