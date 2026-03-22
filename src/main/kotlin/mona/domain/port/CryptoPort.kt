package mona.domain.port

interface CryptoPort {
    fun encrypt(plaintext: String): ByteArray

    fun decrypt(ciphertext: ByteArray): String
}
