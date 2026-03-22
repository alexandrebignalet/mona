package mona.application.client

import kotlinx.coroutines.runBlocking
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val USER_UC = UserId("u1")
private val CLIENT_ID_A = ClientId("ca")
private val CLIENT_ID_B = ClientId("cb")

private fun makeClient(
    id: ClientId,
    name: String,
    email: Email? = null,
) = Client(
    id = id,
    userId = USER_UC,
    name = name,
    email = email,
    address = null,
    companyName = null,
    siret = null,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
)

private class StubClientRepoUC(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {
        store[client.id] = client
    }

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }

    fun get(id: ClientId): Client? = store[id]
}

class UpdateClientTest {
    @Test
    fun `updates email by clientId`() =
        runBlocking {
            val repo = StubClientRepoUC(makeClient(CLIENT_ID_A, "Jean Dupont"))
            val uc = UpdateClient(repo)

            val result =
                uc.execute(
                    UpdateClientCommand(
                        userId = USER_UC,
                        clientId = CLIENT_ID_A,
                        email = Email("jean@example.com"),
                    ),
                )

            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            val updated = (result.value as UpdateClientResult.Updated).client
            assertEquals(Email("jean@example.com"), updated.email)
        }

    @Test
    fun `updates name by clientId`() =
        runBlocking {
            val repo = StubClientRepoUC(makeClient(CLIENT_ID_A, "Jean Dupont"))
            val uc = UpdateClient(repo)

            val result =
                uc.execute(
                    UpdateClientCommand(
                        userId = USER_UC,
                        clientId = CLIENT_ID_A,
                        newName = "Jean-Pierre Dupont",
                    ),
                )

            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            val updated = (result.value as UpdateClientResult.Updated).client
            assertEquals("Jean-Pierre Dupont", updated.name)
        }

    @Test
    fun `resolves client by name when unique match`() =
        runBlocking {
            val repo = StubClientRepoUC(makeClient(CLIENT_ID_A, "Marie Martin"))
            val uc = UpdateClient(repo)

            val result =
                uc.execute(
                    UpdateClientCommand(
                        userId = USER_UC,
                        clientName = "Marie Martin",
                        email = Email("marie@example.com"),
                    ),
                )

            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            assertIs<UpdateClientResult.Updated>(result.value)
        }

    @Test
    fun `returns Ambiguous when multiple clients match name`() =
        runBlocking {
            val client1 = makeClient(CLIENT_ID_A, "Dupont")
            val client2 = makeClient(CLIENT_ID_B, "Dupont")
            val repo = StubClientRepoUC(client1, client2)
            val uc = UpdateClient(repo)

            val result =
                uc.execute(
                    UpdateClientCommand(
                        userId = USER_UC,
                        clientName = "Dupont",
                        email = Email("x@example.com"),
                    ),
                )

            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            val ambiguous = result.value as UpdateClientResult.Ambiguous
            assertEquals(2, ambiguous.matches.size)
        }

    @Test
    fun `returns ClientNotFound when clientId not found`() =
        runBlocking {
            val repo = StubClientRepoUC()
            val uc = UpdateClient(repo)

            val result =
                uc.execute(
                    UpdateClientCommand(
                        userId = USER_UC,
                        clientId = ClientId("unknown"),
                    ),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ClientNotFound>(result.error)
        }

    @Test
    fun `returns ClientNotFound when name has no match`() =
        runBlocking {
            val repo = StubClientRepoUC()
            val uc = UpdateClient(repo)

            val result =
                uc.execute(
                    UpdateClientCommand(
                        userId = USER_UC,
                        clientName = "Nobody",
                    ),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ClientNotFound>(result.error)
        }

    @Test
    fun `persists updated client`() =
        runBlocking {
            val repo = StubClientRepoUC(makeClient(CLIENT_ID_A, "Jean Dupont"))
            val uc = UpdateClient(repo)

            uc.execute(
                UpdateClientCommand(
                    userId = USER_UC,
                    clientId = CLIENT_ID_A,
                    email = Email("jean@example.com"),
                ),
            )

            val saved = repo.get(CLIENT_ID_A)
            assertNotNull(saved)
            assertEquals(Email("jean@example.com"), saved.email)
        }

    @Test
    fun `preserves existing fields when not updated`() =
        runBlocking {
            val repo = StubClientRepoUC(makeClient(CLIENT_ID_A, "Jean Dupont", email = Email("existing@example.com")))
            val uc = UpdateClient(repo)

            val result =
                uc.execute(
                    UpdateClientCommand(
                        userId = USER_UC,
                        clientId = CLIENT_ID_A,
                        newName = "Jean-Pierre Dupont",
                    ),
                )

            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            val updated = (result.value as UpdateClientResult.Updated).client
            assertEquals(Email("existing@example.com"), updated.email)
            assertEquals("Jean-Pierre Dupont", updated.name)
        }
}
