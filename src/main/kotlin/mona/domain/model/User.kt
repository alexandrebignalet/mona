package mona.domain.model

import java.time.Instant

data class User(
    val id: UserId,
    val telegramId: Long,
    val email: Email?,
    val name: String?,
    val siren: Siren?,
    val siret: Siret?,
    val address: PostalAddress?,
    val ibanEncrypted: ByteArray?,
    val activityType: ActivityType?,
    val declarationPeriodicity: DeclarationPeriodicity?,
    val confirmBeforeCreate: Boolean,
    val defaultPaymentDelayDays: PaymentDelayDays,
    val createdAt: Instant,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is User) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
