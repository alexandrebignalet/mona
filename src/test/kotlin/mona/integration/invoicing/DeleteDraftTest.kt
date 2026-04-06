package mona.integration.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.invoicing.CreateInvoice
import mona.application.invoicing.CreateInvoiceCommand
import mona.application.invoicing.CreateInvoiceResult
import mona.application.invoicing.DeleteDraft
import mona.application.invoicing.DeleteDraftCommand
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.integration.IntegrationTestBase
import mona.integration.TEST_CLIENT_ID
import mona.integration.TEST_USER_ID
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeleteDraftTest : IntegrationTestBase() {
    private fun deleteDraft() = DeleteDraft(invoiceRepo, eventDispatcher)

    private fun createInvoice() = CreateInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, eventDispatcher)

    @BeforeTest
    fun seedData() =
        setup {
            createTestUser()
            createTestClient()
        }

    @Test
    fun `happy path deletes invoice and dispatches DraftDeleted event`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Draft)
            val result = deleteDraft().execute(DeleteDraftCommand(userId = TEST_USER_ID, invoiceId = invoice.id))
            assertIs<DomainResult.Ok<*>>(result)
            assertNull(invoiceRepo.findById(invoice.id))
            assertTrue(eventCollector.events.any { it is DomainEvent.DraftDeleted })
        }

    @Test
    fun `non-Draft invoice returns Err without deleting`() =
        runBlocking {
            val invoice = createTestInvoice(TEST_USER_ID, TEST_CLIENT_ID, InvoiceStatus.Sent)
            val result = deleteDraft().execute(DeleteDraftCommand(userId = TEST_USER_ID, invoiceId = invoice.id))
            assertIs<DomainResult.Err>(result)
            // Invoice still exists
            assertTrue(invoiceRepo.findById(invoice.id) != null)
        }

    @Test
    fun `after deleting F-001, next invoice gets F-001 again`() =
        runBlocking {
            val date = LocalDate.of(2026, 4, 1)
            val cmd =
                CreateInvoiceCommand(
                    userId = TEST_USER_ID,
                    clientName = "Client X",
                    lineItems = listOf(LineItem("Svc", BigDecimal.ONE, Cents(10000))),
                    issueDate = date,
                    activityType = ActivityType.BIC_SERVICE,
                    paymentDelay = PaymentDelayDays(30),
                )
            val r1 = createInvoice().execute(cmd)
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r1)
            val first = (r1.value as CreateInvoiceResult.Created).invoice
            assertEquals(InvoiceNumber("F-2026-04-001"), first.number)

            // Delete the first invoice
            deleteDraft().execute(DeleteDraftCommand(userId = TEST_USER_ID, invoiceId = first.id))
            assertNull(invoiceRepo.findById(first.id))

            // Create a new invoice — should reuse 001 since gap was eliminated
            val r2 = createInvoice().execute(cmd.copy(clientName = "Client Y"))
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r2)
            val second = (r2.value as CreateInvoiceResult.Created).invoice
            assertEquals(InvoiceNumber("F-2026-04-001"), second.number)
        }
}
