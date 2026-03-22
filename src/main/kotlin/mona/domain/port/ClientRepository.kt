package mona.domain.port

import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.UserId

interface ClientRepository {
    suspend fun findById(id: ClientId): Client?

    suspend fun save(client: Client)

    suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client>

    suspend fun findByUser(userId: UserId): List<Client>
}
