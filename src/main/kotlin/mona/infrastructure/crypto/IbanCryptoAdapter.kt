package mona.infrastructure.crypto

import mona.domain.port.CryptoPort

class IbanCryptoAdapter(private val key: ByteArray) : CryptoPort {
    override fun encrypt(plaintext: String): ByteArray = IbanCrypto.encrypt(plaintext, key)

    override fun decrypt(ciphertext: ByteArray): String = IbanCrypto.decrypt(ciphertext, key)
}
