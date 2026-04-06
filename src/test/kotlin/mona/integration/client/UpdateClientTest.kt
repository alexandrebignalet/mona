package mona.integration.client

import kotlinx.coroutines.runBlocking
import mona.application.client.UpdateClient
import mona.application.client.UpdateClientCommand
import mona.application.client.UpdateClientResult
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.PostalAddress
import mona.domain.model.Siret
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateClientTest : IntegrationTestBase() {
    private fun useCase() = UpdateClient(clientRepo)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `update email saves new email in DB`() =
        runBlocking {
            val newEmail = Email("updated@acme.com")
            val result =
                useCase().execute(
                    UpdateClientCommand(userId = TEST_USER_ID, clientId = TEST_CLIENT_ID, email = newEmail),
                )
            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            val updated = assertIs<UpdateClientResult.Updated>(result.value)
            assertEquals(newEmail, updated.client.email)
            val saved = clientRepo.findById(TEST_CLIENT_ID)
            assertEquals(newEmail, saved?.email)
        }

    @Test
    fun `rename client updates name in DB`() =
        runBlocking {
            val result =
                useCase().execute(
                    UpdateClientCommand(userId = TEST_USER_ID, clientId = TEST_CLIENT_ID, newName = "ACME Corporation"),
                )
            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            val updated = assertIs<UpdateClientResult.Updated>(result.value)
            assertEquals("ACME Corporation", updated.client.name)
        }

    @Test
    fun `client not found returns Err`() =
        runBlocking {
            val result =
                useCase().execute(
                    UpdateClientCommand(userId = TEST_USER_ID, clientName = "UnknownClientXYZ"),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `ambiguous name match returns Ambiguous with multiple results`() =
        runBlocking {
            // Create two clients whose names both contain "conseil"
            clientRepo.save(
                Client(
                    id = ClientId("c2"),
                    userId = TEST_USER_ID,
                    name = "Dupont Conseil",
                    email = null,
                    address = null,
                    companyName = null,
                    siret = null,
                    createdAt = Instant.now(),
                ),
            )
            clientRepo.save(
                Client(
                    id = ClientId("c3"),
                    userId = TEST_USER_ID,
                    name = "Martin Conseil",
                    email = null,
                    address = null,
                    companyName = null,
                    siret = null,
                    createdAt = Instant.now(),
                ),
            )
            // "conseil" LIKE matches both "Dupont Conseil" and "Martin Conseil"
            val result =
                useCase().execute(
                    UpdateClientCommand(userId = TEST_USER_ID, clientName = "conseil"),
                )
            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            assertIs<UpdateClientResult.Ambiguous>(result.value)
            assertTrue((result.value as UpdateClientResult.Ambiguous).matches.size == 2)
        }

    @Test
    fun `multiple fields updated in single call`() =
        runBlocking {
            val newEmail = Email("multi@acme.com")
            val newAddress = PostalAddress("100 BD HAUSSMANN", "75008", "PARIS")
            val newSiret = Siret("98765432100099")
            val result =
                useCase().execute(
                    UpdateClientCommand(
                        userId = TEST_USER_ID,
                        clientId = TEST_CLIENT_ID,
                        email = newEmail,
                        address = newAddress,
                        siret = newSiret,
                    ),
                )
            assertIs<DomainResult.Ok<UpdateClientResult>>(result)
            val updated = assertIs<UpdateClientResult.Updated>(result.value)
            assertEquals(newEmail, updated.client.email)
            assertEquals(newAddress, updated.client.address)
            assertEquals(newSiret, updated.client.siret)
        }
}
