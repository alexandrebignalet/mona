package mona.integration.revenue

import kotlinx.coroutines.runBlocking
import mona.application.revenue.ExportInvoicesCsv
import mona.application.revenue.ExportInvoicesCsvCommand
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

private val EXPORT_DATE = LocalDate.of(2026, 4, 6)

class ExportCsvTest : IntegrationTestBase() {
    private fun useCase() = ExportInvoicesCsv(invoiceRepo, clientRepo)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `happy path produces valid CSV with all invoices`() =
        runBlocking {
            createTestInvoice(
                TEST_USER_ID,
                TEST_CLIENT_ID,
                InvoiceStatus.Paid(LocalDate.of(2026, 4, 10), PaymentMethod.VIREMENT),
            )
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)

            val result = useCase().execute(ExportInvoicesCsvCommand(TEST_USER_ID, EXPORT_DATE))

            assertEquals(2, result.invoiceCount)
            assertTrue(result.csvContent.isNotEmpty())
            assertEquals("mona-factures-$EXPORT_DATE.csv", result.filename)
        }

    @Test
    fun `no invoices returns header-only CSV`() =
        runBlocking {
            val result = useCase().execute(ExportInvoicesCsvCommand(TEST_USER_ID, EXPORT_DATE))

            assertEquals(0, result.invoiceCount)
            val csv = String(result.csvContent, Charsets.UTF_8)
            assertTrue(csv.startsWith("invoice_number,"))
        }

    @Test
    fun `CSV has correct column headers`() =
        runBlocking {
            val result = useCase().execute(ExportInvoicesCsvCommand(TEST_USER_ID, EXPORT_DATE))

            val csv = String(result.csvContent, Charsets.UTF_8)
            val header = csv.lines().first()
            assertTrue(header.contains("invoice_number"))
            assertTrue(header.contains("status"))
            assertTrue(header.contains("issue_date"))
            assertTrue(header.contains("due_date"))
            assertTrue(header.contains("paid_date"))
            assertTrue(header.contains("client_name"))
            assertTrue(header.contains("total_ht"))
        }
}
