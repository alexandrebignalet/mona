package mona.infrastructure.db

import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.Email
import mona.domain.model.PostalAddress
import mona.domain.model.Siret
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import org.jetbrains.exposed.sql.LowerCase
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update

class ExposedClientRepository : ClientRepository {
    override suspend fun findById(id: ClientId): Client? =
        newSuspendedTransaction {
            ClientsTable.selectAll()
                .where { ClientsTable.id eq id.value }
                .firstOrNull()
                ?.toClient()
        }

    override suspend fun save(client: Client) {
        newSuspendedTransaction {
            val existing =
                ClientsTable.selectAll()
                    .where { ClientsTable.id eq client.id.value }
                    .firstOrNull()
            if (existing != null) {
                ClientsTable.update({ ClientsTable.id eq client.id.value }) {
                    it[name] = client.name
                    it[email] = client.email?.value
                    it[addressStreet] = client.address?.street
                    it[addressPostalCode] = client.address?.postalCode
                    it[addressCity] = client.address?.city
                    it[companyName] = client.companyName
                    it[siret] = client.siret?.value
                }
            } else {
                ClientsTable.insert {
                    it[id] = client.id.value
                    it[userId] = client.userId.value
                    it[name] = client.name
                    it[email] = client.email?.value
                    it[addressStreet] = client.address?.street
                    it[addressPostalCode] = client.address?.postalCode
                    it[addressCity] = client.address?.city
                    it[companyName] = client.companyName
                    it[siret] = client.siret?.value
                    it[createdAt] = client.createdAt
                }
            }
        }
    }

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> =
        newSuspendedTransaction {
            ClientsTable.selectAll()
                .where {
                    (ClientsTable.userId eq userId.value) and
                        (LowerCase(ClientsTable.name) like "%${name.lowercase()}%")
                }
                .map { it.toClient() }
        }

    override suspend fun findByUser(userId: UserId): List<Client> =
        newSuspendedTransaction {
            ClientsTable.selectAll()
                .where { ClientsTable.userId eq userId.value }
                .map { it.toClient() }
        }

    private fun ResultRow.toClient(): Client {
        val street = this[ClientsTable.addressStreet]
        val postalCode = this[ClientsTable.addressPostalCode]
        val city = this[ClientsTable.addressCity]
        val address =
            if (street != null && postalCode != null && city != null) {
                PostalAddress(street, postalCode, city)
            } else {
                null
            }
        return Client(
            id = ClientId(this[ClientsTable.id]),
            userId = UserId(this[ClientsTable.userId]),
            name = this[ClientsTable.name],
            email = this[ClientsTable.email]?.let { Email(it) },
            address = address,
            companyName = this[ClientsTable.companyName],
            siret = this[ClientsTable.siret]?.let { Siret(it) },
            createdAt = this[ClientsTable.createdAt],
        )
    }
}
