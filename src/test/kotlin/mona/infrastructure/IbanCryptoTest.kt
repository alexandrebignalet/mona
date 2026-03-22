package mona.infrastructure

import mona.infrastructure.crypto.IbanCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class IbanCryptoTest {
    // 32-byte test key (AES-256)
    private val key = ByteArray(32) { it.toByte() }

    @Test
    fun `round-trip encrypt then decrypt returns original IBAN`() {
        val iban = "FR7630006000011234567890189"
        val encrypted = IbanCrypto.encrypt(iban, key)
        val decrypted = IbanCrypto.decrypt(encrypted, key)
        assertEquals(iban, decrypted)
    }

    @Test
    fun `different encryptions of same IBAN produce different ciphertext due to random IV`() {
        val iban = "FR7630006000011234567890189"
        val first = IbanCrypto.encrypt(iban, key)
        val second = IbanCrypto.encrypt(iban, key)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun `decryption with wrong key throws exception`() {
        val iban = "FR7630006000011234567890189"
        val encrypted = IbanCrypto.encrypt(iban, key)
        val wrongKey = ByteArray(32) { (it + 1).toByte() }
        assertFailsWith<Exception> {
            IbanCrypto.decrypt(encrypted, wrongKey)
        }
    }

    @Test
    fun `empty string round-trips successfully`() {
        val encrypted = IbanCrypto.encrypt("", key)
        val decrypted = IbanCrypto.decrypt(encrypted, key)
        assertEquals("", decrypted)
    }

    @Test
    fun `encrypted output is longer than input by IV plus tag`() {
        val iban = "FR7630006000011234567890189"
        val encrypted = IbanCrypto.encrypt(iban, key)
        // IV (12) + plaintext bytes + GCM tag (16) = 12 + plaintextLen + 16
        val expectedMin = 12 + iban.toByteArray().size + 16
        assertEquals(expectedMin, encrypted.size)
    }
}
