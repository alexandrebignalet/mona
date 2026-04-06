package mona.integration.gdpr

import kotlinx.coroutines.runBlocking
import mona.application.gdpr.ExportGdprData
import mona.application.revenue.ExportInvoicesCsv
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaymentMethod
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ExportDataTest : IntegrationTestBase() {
    private fun useCase() =
        ExportGdprData(
            userRepository = userRepo,
            invoiceRepository = invoiceRepo,
            clientRepository = clientRepo,
            exportCsv = ExportInvoicesCsv(invoiceRepo, clientRepo),
            pdfPort = pdfPort,
            cryptoPort = cryptoPort,
        )

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `full export returns CSV, PDFs and profile JSON with plaintext IBAN`() =
        runBlocking {
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Paid(LocalDate.of(2026, 4, 11), PaymentMethod.VIREMENT))
            createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)

            val result = useCase().execute(TEST_USER_ID)

            // CSV non-empty and has header
            assertTrue(result.csvBytes.isNotEmpty())
            val csv = String(result.csvBytes, Charsets.UTF_8)
            assertTrue(csv.contains("invoice_number"))

            // At least 1 invoice PDF (Sent invoice generates PDF)
            assertTrue(result.invoicePdfs.isNotEmpty())
            val (pdfBytes, _) = result.invoicePdfs.first()
            assertTrue(pdfBytes.size > 4)
            val pdfHeader = byteArrayOf(0x25, 0x50, 0x44, 0x46)
            assertTrue(pdfBytes.take(4).toByteArray().contentEquals(pdfHeader))

            // Profile JSON contains IBAN flag
            val profile = String(result.profileJsonBytes, Charsets.UTF_8)
            assertTrue(profile.contains("iban_enregistre"))
            assertTrue(profile.contains("true"))
        }

    @Test
    fun `empty account export returns empty CSV and no PDFs`() =
        runBlocking {
            val result = useCase().execute(TEST_USER_ID)

            assertTrue(result.invoicePdfs.isEmpty())
            assertTrue(result.creditNotePdfs.isEmpty())
            // CSV may just have the header
            val csv = String(result.csvBytes, Charsets.UTF_8)
            assertTrue(csv.contains("invoice_number"))
        }

    @Test
    fun `profile JSON contains decrypted IBAN indicator`() =
        runBlocking {
            val result = useCase().execute(TEST_USER_ID)
            val profile = String(result.profileJsonBytes, Charsets.UTF_8)
            assertTrue(profile.contains("\"iban_enregistre\": true"))
            assertTrue(profile.contains("\"email\": \"user@example.com\""))
            assertTrue(profile.contains("\"siren\": \"123456789\""))
        }
}
