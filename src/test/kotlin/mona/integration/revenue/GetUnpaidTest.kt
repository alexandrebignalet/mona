package mona.integration.revenue

import kotlinx.coroutines.runBlocking
import mona.application.revenue.GetUnpaidInvoices
import mona.application.revenue.GetUnpaidInvoicesCommand
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentMethod
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GetUnpaidTest : IntegrationTestBase() {
    private fun useCase() = GetUnpaidInvoices(invoiceRepo, clientRepo)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `sent and overdue invoices are returned as unpaid`() =
        runBlocking {
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Overdue)

            val result = useCase().execute(GetUnpaidInvoicesCommand(TEST_USER_ID))

            assertEquals(2, result.items.size)
            assertTrue(result.items.all { it.clientName == "ACME Corp" })
        }

    @Test
    fun `all paid invoices returns empty unpaid list`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
            )

            val result = useCase().execute(GetUnpaidInvoicesCommand(TEST_USER_ID))

            assertTrue(result.items.isEmpty())
        }

    @Test
    fun `mixed statuses returns only sent and overdue`() =
        runBlocking {
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
            )
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Cancelled)

            val result = useCase().execute(GetUnpaidInvoicesCommand(TEST_USER_ID))

            assertEquals(1, result.items.size)
        }
}
