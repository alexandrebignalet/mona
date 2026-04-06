package mona.integration.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.CorrectInvoice
import mona.application.invoicing.CorrectInvoiceCommand
import mona.application.invoicing.CorrectInvoiceResult
import mona.domain.model.Cents
import mona.domain.model.CreditNoteNumber
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CorrectInvoiceTest : IntegrationTestBase() {
    private fun useCase() = CorrectInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, eventDispatcher)

    private val issueDate = LocalDate.of(2026, 4, 1)
    private val correctedItems = listOf(LineItem("Correction", BigDecimal.ONE, Cents(60000)))

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `happy path cancels original and creates new draft with two PDFs`() =
        runBlocking {
            val original = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate)
            val result =
                useCase().execute(
                    CorrectInvoiceCommand(
                        userId = TEST_USER_ID,
                        invoiceId = original.id,
                        correctedLineItems = correctedItems,
                        creditNoteReason = "Montant incorrect",
                        issueDate = issueDate,
                    ),
                )
            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            assertIs<InvoiceStatus.Cancelled>(result.value.cancelledInvoice.status)
            assertIs<InvoiceStatus.Draft>(result.value.newInvoice.status)
            assertTrue(result.value.creditNotePdf.size > 4)
            assertTrue(result.value.newInvoicePdf.size > 4)
        }

    @Test
    fun `corrected invoice has new amount independent of original`() =
        runBlocking {
            val original = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate, amountCents = 50000L)
            val result =
                useCase().execute(
                    CorrectInvoiceCommand(
                        userId = TEST_USER_ID,
                        invoiceId = original.id,
                        correctedLineItems = listOf(LineItem("Corrected", BigDecimal.ONE, Cents(60000))),
                        creditNoteReason = "Adjustment",
                        issueDate = issueDate,
                    ),
                )
            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            // Credit note = original amount
            assertEquals(Cents(50000), result.value.cancelledInvoice.creditNote?.amountHt)
            // New invoice = corrected amount
            assertEquals(Cents(60000), result.value.newInvoice.amountHt)
        }

    @Test
    fun `numbering assigns A-001 for credit note and 002 for new invoice`() =
        runBlocking {
            // F-2026-04-001 is the original (first invoice in month)
            val original = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate)
            assertEquals(InvoiceNumber("F-2026-04-001"), original.number)

            val result =
                useCase().execute(
                    CorrectInvoiceCommand(
                        userId = TEST_USER_ID,
                        invoiceId = original.id,
                        correctedLineItems = correctedItems,
                        creditNoteReason = "Correction",
                        issueDate = issueDate,
                    ),
                )
            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            assertEquals(CreditNoteNumber("A-2026-04-001"), result.value.cancelledInvoice.creditNote?.number)
            assertEquals(InvoiceNumber("F-2026-04-002"), result.value.newInvoice.number)
        }

    @Test
    fun `both records persisted in DB`() =
        runBlocking {
            val original = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent, issueDate)
            val result =
                useCase().execute(
                    CorrectInvoiceCommand(
                        userId = TEST_USER_ID,
                        invoiceId = original.id,
                        correctedLineItems = correctedItems,
                        issueDate = issueDate,
                    ),
                )
            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            val cancelled = invoiceRepo.findById(original.id)
            assertIs<InvoiceStatus.Cancelled>(cancelled?.status)
            val newInvoice = invoiceRepo.findById(result.value.newInvoice.id)
            assertIs<InvoiceStatus.Draft>(newInvoice?.status)
        }
}
