package mona.integration.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.CancelInvoice
import mona.application.invoicing.CancelInvoiceCommand
import mona.application.invoicing.CancelInvoiceResult
import mona.domain.model.CreditNoteNumber
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceStatus
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CancelInvoiceTest : IntegrationTestBase() {
    private fun useCase() = CancelInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, eventDispatcher)

    private val issueDate = LocalDate.of(2026, 4, 1)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `happy path cancels Sent invoice and creates credit note A-001`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate)
            val result =
                useCase().execute(
                    CancelInvoiceCommand(
                        userId = TEST_USER_ID,
                        invoiceId = invoice.id,
                        reason = "Erreur de facturation",
                        issueDate = issueDate,
                    ),
                )
            assertIs<DomainResult.Ok<CancelInvoiceResult>>(result)
            assertIs<InvoiceStatus.Cancelled>(result.value.invoice.status)
            assertEquals(CreditNoteNumber("A-2026-04-001"), result.value.invoice.creditNote?.number)
            assertTrue(result.value.creditNotePdf.size > 4)
            // Event dispatched
            assertTrue(eventCollector.events.any { it is DomainEvent.InvoiceCancelled })
            // Persisted in DB
            val saved = invoiceRepo.findById(invoice.id)
            assertIs<InvoiceStatus.Cancelled>(saved?.status)
        }

    @Test
    fun `cancelling two invoices same month gives sequential credit note numbers`() =
        runBlocking {
            val inv1 = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate)
            val inv2 = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate)
            val cmd = { id: mona.domain.model.InvoiceId ->
                CancelInvoiceCommand(TEST_USER_ID, id, "Erreur", issueDate)
            }
            val r1 = useCase().execute(cmd(inv1.id))
            val r2 = useCase().execute(cmd(inv2.id))
            assertIs<DomainResult.Ok<CancelInvoiceResult>>(r1)
            assertIs<DomainResult.Ok<CancelInvoiceResult>>(r2)
            assertEquals(CreditNoteNumber("A-2026-04-001"), r1.value.invoice.creditNote?.number)
            assertEquals(CreditNoteNumber("A-2026-04-002"), r2.value.invoice.creditNote?.number)
        }

    @Test
    fun `cancel Draft invoice returns Err`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft, issueDate)
            val result =
                useCase().execute(
                    CancelInvoiceCommand(TEST_USER_ID, invoice.id, "reason", issueDate),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `cancel already-cancelled invoice returns Err`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Cancelled, issueDate)
            val result =
                useCase().execute(
                    CancelInvoiceCommand(TEST_USER_ID, invoice.id, "reason", issueDate),
                )
            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `credit note amount equals original invoice amount`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate, amountCents = 85000L)
            val result =
                useCase().execute(
                    CancelInvoiceCommand(TEST_USER_ID, invoice.id, "correction", issueDate),
                )
            assertIs<DomainResult.Ok<CancelInvoiceResult>>(result)
            assertEquals(invoice.amountHt, result.value.invoice.creditNote?.amountHt)
        }
}
