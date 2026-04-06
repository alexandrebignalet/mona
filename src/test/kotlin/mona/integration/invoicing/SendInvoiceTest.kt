package mona.integration.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.SendInvoice
import mona.application.invoicing.SendInvoiceCommand
import mona.application.invoicing.SendInvoiceResult
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceStatus
import mona.integration.IntegrationTestBase
import mona.integration.ResendScenario
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_IBAN_PLAIN
import mona.integration.TEST_USER_ID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46) // %PDF

class SendInvoiceTest : IntegrationTestBase() {
    private fun useCase() = SendInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, emailPort, eventDispatcher)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `happy path transitions invoice to Sent and dispatches InvoiceSent event`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    SendInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = null),
                )
            assertIs<DomainResult.Ok<SendInvoiceResult>>(result)
            assertIs<InvoiceStatus.Sent>(result.value.invoice.status)
            // Persisted as Sent
            val saved = invoiceRepo.findById(invoice.id)
            assertIs<InvoiceStatus.Sent>(saved?.status)
            // Event dispatched
            assertTrue(eventCollector.events.any { it is DomainEvent.InvoiceSent })
        }

    @Test
    fun `client without email returns Err`() =
        runBlocking {
            // Update client to have no email
            val clientWithNoEmail = testClient().copy(email = null)
            clientRepo.save(clientWithNoEmail)
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    SendInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = null),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `email failure leaves invoice in Draft`() =
        runBlocking {
            fakeResendExecutor.scenario = ResendScenario.RateLimited
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    SendInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = null),
                )
            assertIs<DomainResult.Err>(result)
            // Invoice still Draft in DB (not saved after email failure)
            val saved = invoiceRepo.findById(invoice.id)
            assertIs<InvoiceStatus.Draft>(saved?.status)
        }

    @Test
    fun `non-Draft invoice returns Err`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            val result =
                useCase().execute(
                    SendInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = null),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `PDF includes IBAN content when IBAN provided`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    SendInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = TEST_IBAN_PLAIN),
                )
            assertIs<DomainResult.Ok<SendInvoiceResult>>(result)
            assertTrue(result.value.pdf.size > 4)
            assertTrue(result.value.pdf.take(4).toByteArray().contentEquals(PDF_HEADER))
        }

    @Test
    fun `PDF generated without IBAN when none provided`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result =
                useCase().execute(
                    SendInvoiceCommand(userId = TEST_USER_ID, invoiceId = invoice.id, plainIban = null),
                )
            assertIs<DomainResult.Ok<SendInvoiceResult>>(result)
            assertTrue(result.value.pdf.size > 4)
        }
}
