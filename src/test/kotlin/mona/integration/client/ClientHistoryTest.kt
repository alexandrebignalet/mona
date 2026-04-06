package mona.integration.client

import kotlinx.coroutines.runBlocking
import mona.application.client.GetClientHistory
import mona.application.client.GetClientHistoryCommand
import mona.application.client.GetClientHistoryResult
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.InvoiceStatus
import mona.domain.model.PostalAddress
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_CLIENT_SIRET
import mona.integration.TEST_USER_ID
import java.time.Instant
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ClientHistoryTest : IntegrationTestBase() {
    private fun useCase() = GetClientHistory(clientRepo, invoiceRepo)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `happy path returns client with invoices ordered by date`() =
        runBlocking {
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate = LocalDate.of(2026, 4, 1))
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft, issueDate = LocalDate.of(2026, 3, 1))

            val result = useCase().execute(GetClientHistoryCommand(TEST_USER_ID, clientId = TEST_CLIENT_ID))

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            val found = assertIs<GetClientHistoryResult.Found>(result.value)
            assertEquals(TEST_CLIENT_ID, found.client.id)
            assertEquals(2, found.invoices.size)
            // ordered by issueDate ascending
            assertTrue(found.invoices[0].issueDate <= found.invoices[1].issueDate)
        }

    @Test
    fun `not found returns Err`() =
        runBlocking {
            val result =
                useCase().execute(
                    GetClientHistoryCommand(TEST_USER_ID, clientId = ClientId("nonexistent")),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `ambiguous name returns Ambiguous with matches`() =
        runBlocking {
            // Save a second client with same name prefix
            val client2 =
                Client(
                    id = ClientId("test-client-002"),
                    userId = TEST_USER_ID,
                    name = "ACME Services",
                    email = Email("acme2@example.com"),
                    address = PostalAddress("2 RUE DU CLIENT", "75008", "PARIS"),
                    companyName = null,
                    siret = TEST_CLIENT_SIRET,
                    createdAt = Instant.parse("2026-01-01T10:00:00Z"),
                )
            clientRepo.save(client2)

            val result =
                useCase().execute(
                    GetClientHistoryCommand(TEST_USER_ID, clientName = "ACME"),
                )

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            assertIs<GetClientHistoryResult.Ambiguous>(result.value)
        }

    @Test
    fun `client with many invoices returns all in order`() =
        runBlocking {
            repeat(5) { i ->
                createTestInvoice(
                    TEST_USER_ID,
                    TEST_CLIENT_ID,
                    InvoiceStatus.Sent,
                    issueDate = LocalDate.of(2026, 1 + i, 1),
                )
            }

            val result = useCase().execute(GetClientHistoryCommand(TEST_USER_ID, clientId = TEST_CLIENT_ID))

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            val found = assertIs<GetClientHistoryResult.Found>(result.value)
            assertEquals(5, found.invoices.size)
        }
}
