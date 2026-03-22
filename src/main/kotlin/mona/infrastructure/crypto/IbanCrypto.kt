package mona.infrastructure.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object IbanCrypto {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val IV_LENGTH_BYTES = 12
    private const val TAG_LENGTH_BITS = 128

    fun encrypt(
        plaintext: String,
        key: ByteArray,
    ): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return iv + ciphertext
    }

    fun decrypt(
        ciphertext: ByteArray,
        key: ByteArray,
    ): String {
        require(ciphertext.size > IV_LENGTH_BYTES) { "Ciphertext too short" }
        val iv = ciphertext.copyOfRange(0, IV_LENGTH_BYTES)
        val encrypted = ciphertext.copyOfRange(IV_LENGTH_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    fun loadKeyFromEnv(): ByteArray {
        val encoded =
            System.getenv("IBAN_ENCRYPTION_KEY")
                ?: error("IBAN_ENCRYPTION_KEY environment variable is not set")
        val key = Base64.getDecoder().decode(encoded)
        require(key.size == 32) { "IBAN_ENCRYPTION_KEY must be 32 bytes (256-bit) when base64-decoded" }
        return key
    }
}
