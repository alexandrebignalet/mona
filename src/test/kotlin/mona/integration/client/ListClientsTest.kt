package mona.integration.client

import kotlinx.coroutines.runBlocking
import mona.application.client.ListClients
import mona.application.client.ListClientsCommand
import mona.domain.model.Cents
import mona.domain.model.InvoiceStatus
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListClientsTest : IntegrationTestBase() {
    private fun useCase() = ListClients(clientRepo, invoiceRepo)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
        }

    @Test
    fun `user with clients and invoices returns summaries with counts and amounts`() =
        runBlocking {
            createTestClient()
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)

            val result = useCase().execute(ListClientsCommand(TEST_USER_ID))

            assertEquals(1, result.clients.size)
            val summary = result.clients.first()
            assertEquals(TEST_CLIENT_ID, summary.client.id)
            assertEquals(2, summary.invoiceCount)
            assertEquals(Cents(200000L), summary.totalAmount) // 2 x 100000
        }

    @Test
    fun `user with no clients returns empty list`() =
        runBlocking {
            val result = useCase().execute(ListClientsCommand(TEST_USER_ID))
            assertTrue(result.clients.isEmpty())
        }

    @Test
    fun `client with no invoices has zero count and zero amount`() =
        runBlocking {
            createTestClient()

            val result = useCase().execute(ListClientsCommand(TEST_USER_ID))

            assertEquals(1, result.clients.size)
            val summary = result.clients.first()
            assertEquals(0, summary.invoiceCount)
            assertEquals(Cents.ZERO, summary.totalAmount)
        }
}
