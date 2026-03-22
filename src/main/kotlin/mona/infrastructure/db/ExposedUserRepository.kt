package mona.infrastructure.db

import mona.domain.model.ActivityType
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.Email
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.Siret
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.UserRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ExposedUserRepository : UserRepository {
    override suspend fun findById(id: UserId): User? =
        newSuspendedTransaction {
            UsersTable.selectAll()
                .where { UsersTable.id eq id.value }
                .firstOrNull()
                ?.toUser()
        }

    override suspend fun findByTelegramId(telegramId: Long): User? =
        newSuspendedTransaction {
            UsersTable.selectAll()
                .where { UsersTable.telegramId eq telegramId }
                .firstOrNull()
                ?.toUser()
        }

    override suspend fun save(user: User) {
        newSuspendedTransaction {
            val existing =
                UsersTable.selectAll()
                    .where { UsersTable.id eq user.id.value }
                    .firstOrNull()
            if (existing != null) {
                UsersTable.update({ UsersTable.id eq user.id.value }) {
                    it[telegramId] = user.telegramId
                    it[email] = user.email?.value
                    it[name] = user.name
                    it[siren] = user.siren?.value
                    it[siret] = user.siret?.value
                    it[addressStreet] = user.address?.street
                    it[addressPostalCode] = user.address?.postalCode
                    it[addressCity] = user.address?.city
                    it[ibanEncrypted] = user.ibanEncrypted
                    it[activityType] = user.activityType?.name
                    it[declarationPeriodicity] = user.declarationPeriodicity?.name
                    it[confirmBeforeCreate] = user.confirmBeforeCreate
                    it[defaultPaymentDelayDays] = user.defaultPaymentDelayDays.value
                    it[createdAt] = user.createdAt
                }
            } else {
                UsersTable.insert {
                    it[id] = user.id.value
                    it[telegramId] = user.telegramId
                    it[email] = user.email?.value
                    it[name] = user.name
                    it[siren] = user.siren?.value
                    it[siret] = user.siret?.value
                    it[addressStreet] = user.address?.street
                    it[addressPostalCode] = user.address?.postalCode
                    it[addressCity] = user.address?.city
                    it[ibanEncrypted] = user.ibanEncrypted
                    it[activityType] = user.activityType?.name
                    it[declarationPeriodicity] = user.declarationPeriodicity?.name
                    it[confirmBeforeCreate] = user.confirmBeforeCreate
                    it[defaultPaymentDelayDays] = user.defaultPaymentDelayDays.value
                    it[createdAt] = user.createdAt
                }
            }
        }
    }

    private fun ResultRow.toUser(): User {
        val street = this[UsersTable.addressStreet]
        val postalCode = this[UsersTable.addressPostalCode]
        val city = this[UsersTable.addressCity]
        val address =
            if (street != null && postalCode != null && city != null) {
                PostalAddress(street, postalCode, city)
            } else {
                null
            }
        return User(
            id = UserId(this[UsersTable.id]),
            telegramId = this[UsersTable.telegramId],
            email = this[UsersTable.email]?.let { Email(it) },
            name = this[UsersTable.name],
            siren = this[UsersTable.siren]?.let { Siren(it) },
            siret = this[UsersTable.siret]?.let { Siret(it) },
            address = address,
            ibanEncrypted = this[UsersTable.ibanEncrypted],
            activityType = this[UsersTable.activityType]?.let { ActivityType.valueOf(it) },
            declarationPeriodicity = this[UsersTable.declarationPeriodicity]?.let { DeclarationPeriodicity.valueOf(it) },
            confirmBeforeCreate = this[UsersTable.confirmBeforeCreate],
            defaultPaymentDelayDays = PaymentDelayDays(this[UsersTable.defaultPaymentDelayDays]),
            createdAt = this[UsersTable.createdAt],
        )
    }
}
