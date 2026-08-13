package ru.simple.mycalendar.v2.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.SecureRandom

class EncryptedEnvelopeTest {
    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val aad = "account-1/calendar/v1".toByteArray()

    @Test
    fun roundTripAndCompression() {
        val crypto = EncryptedEnvelope(key)
        val plain = "секретное дело ".repeat(100).toByteArray()
        val encrypted = crypto.encrypt(plain, aad)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("секретное"))
        assertArrayEquals(plain, crypto.decrypt(encrypted, aad))
    }

    @Test(expected = Exception::class)
    fun tamperingIsRejected() {
        val crypto = EncryptedEnvelope(key)
        val encrypted = crypto.encrypt("дело".toByteArray(), aad)
        encrypted[encrypted.lastIndex] = (encrypted.last() + 1).toByte()
        crypto.decrypt(encrypted, aad)
    }

    @Test
    fun nonceIsNeverReused() {
        val crypto = EncryptedEnvelope(key)
        val one = crypto.encrypt("x".toByteArray(), aad)
        val two = crypto.encrypt("x".toByteArray(), aad)
        assertFalse(one.contentEquals(two))
    }

    @Test(expected = Exception::class)
    fun associatedAccountCannotBeChanged() {
        val crypto = EncryptedEnvelope(key)
        val encrypted = crypto.encrypt("дело".toByteArray(), aad)
        crypto.decrypt(encrypted, "other-account/calendar/v1".toByteArray())
    }
}
