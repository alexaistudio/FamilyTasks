package ru.simple.mycalendar.v2.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores only AES-GCM-wrapped application keys. The wrapping key never leaves Android Keystore. */
class SecureKeys(context: Context) {
    private val prefs = context.getSharedPreferences("secure_keys_v2", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val wrappingAlias = "mycalendar.v2.local-key-wrapper"

    @Synchronized
    fun getOrCreate(name: String): ByteArray {
        get(name)?.let { return it }

        val raw = ByteArray(32).also(SecureRandom()::nextBytes)
        put(name, raw)
        return raw
    }

    @Synchronized
    fun get(name: String): ByteArray? = prefs.getString(name, null)?.let(::unwrap)

    @Synchronized
    fun put(name: String, raw: ByteArray) {
        require(raw.isNotEmpty())
        check(prefs.edit().putString(name, wrap(raw)).commit()) { "Не удалось сохранить защищённый ключ" }
    }

    @Synchronized
    fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey(wrappingAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    wrappingAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
            generateKey()
        }
    }

    private fun wrap(raw: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val encrypted = cipher.doFinal(raw)
        val envelope = ByteArray(cipher.iv.size + encrypted.size)
        cipher.iv.copyInto(envelope)
        encrypted.copyInto(envelope, cipher.iv.size)
        return Base64.encodeToString(envelope, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String): ByteArray {
        try {
            val envelope = Base64.decode(encoded, Base64.NO_WRAP)
            require(envelope.size > 12 + 16)
            val iv = envelope.copyOfRange(0, 12)
            val encrypted = envelope.copyOfRange(12, envelope.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
            return cipher.doFinal(encrypted)
        } catch (error: Exception) {
            throw IllegalStateException(
                "Ключ приложения недоступен. Данные не перезаписаны; восстановите ключ или очистите данные V2.",
                error
            )
        }
    }
}
