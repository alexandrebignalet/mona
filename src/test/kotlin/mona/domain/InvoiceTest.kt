package mona.domain

import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNote
import mona.domain.model.CreditNoteNumber
import mona.domain.model.DomainError
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PaymentMethod
import mona.domain.model.TransitionResult
import mona.domain.model.UserId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class InvoiceTest {
    private val now = Instant.parse("2026-03-22T10:00:00Z")
    private val userId = UserId("u1")
    private val clientId = ClientId("c1")
    private val invoiceId = InvoiceId("inv1")
    private val number = InvoiceNumber("F-2026-03-001")
    private val issueDate = LocalDate.of(2026, 3, 22)
    private val lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(80000)))
    private val paymentDelay = PaymentDelayDays(30)

    private fun createDraft(): Invoice {
        val result =
            Invoice.create(
                invoiceId, userId, clientId, number, issueDate,
                paymentDelay, ActivityType.BNC, lineItems, now,
            )
        return (result as DomainResult.Ok).value
    }

    // --- Factory tests ---
    @Test
    fun `create returns Draft invoice`() {
        val result =
            Invoice.create(
                invoiceId, userId, clientId, number, issueDate,
                paymentDelay, ActivityType.BNC, lineItems, now,
            )
        assertIs<DomainResult.Ok<Invoice>>(result)
        assertIs<InvoiceStatus.Draft>(result.value.status)
    }

    @Test
    fun `create computes correct due date`() {
        val invoice = createDraft()
        assertEquals(issueDate.plusDays(30), invoice.dueDate)
    }

    @Test
    fun `create rejects empty line items`() {
        val result =
            Invoice.create(
                invoiceId, userId, clientId, number, issueDate,
                paymentDelay, ActivityType.BNC, emptyList(), now,
            )
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.EmptyLineItems>(result.error)
    }

    @Test
    fun `create rejects zero total`() {
        val items = listOf(LineItem("Free", BigDecimal.ONE, Cents(0)))
        val result =
            Invoice.create(
                invoiceId, userId, clientId, number, issueDate,
                paymentDelay, ActivityType.BNC, items, now,
            )
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.NegativeAmount>(result.error)
    }

    @Test
    fun `create rejects invalid invoice number`() {
        val badNumber = InvoiceNumber("INVALID")
        val result =
            Invoice.create(
                invoiceId, userId, clientId, badNumber, issueDate,
                paymentDelay, ActivityType.BNC, lineItems, now,
            )
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.InvoiceNumberGap>(result.error)
    }

    @Test
    fun `amountHt sums line item totals`() {
        val items =
            listOf(
                LineItem("A", BigDecimal(2), Cents(10000)),
                LineItem("B", BigDecimal.ONE, Cents(5000)),
            )
        val result =
            Invoice.create(
                invoiceId, userId, clientId, number, issueDate,
                paymentDelay, ActivityType.BNC, items, now,
            )
        val invoice = (result as DomainResult.Ok).value
        assertEquals(Cents(25000), invoice.amountHt)
    }

    // --- send() tests ---
    @Test
    fun `send from Draft succeeds`() {
        val invoice = createDraft()
        val result = invoice.send(now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
        assertIs<InvoiceStatus.Sent>(result.value.invoice.status)
        assertEquals(1, result.value.events.size)
        assertIs<DomainEvent.InvoiceSent>(result.value.events[0])
    }

    @Test
    fun `send from Sent fails`() {
        val invoice = createDraft()
        val sent = (invoice.send(now) as DomainResult.Ok).value.invoice
        val result = sent.send(now)
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.InvalidTransition>(result.error)
    }

    // --- markPaid() tests ---
    @Test
    fun `markPaid from Draft succeeds`() {
        val invoice = createDraft()
        val result = invoice.markPaid(issueDate, PaymentMethod.VIREMENT, now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
        val paid = result.value.invoice.status
        assertIs<InvoiceStatus.Paid>(paid)
        assertEquals(PaymentMethod.VIREMENT, paid.method)
        assertIs<DomainEvent.InvoicePaid>(result.value.events[0])
    }

    @Test
    fun `markPaid from Sent succeeds`() {
        val sent = (createDraft().send(now) as DomainResult.Ok).value.invoice
        val result = sent.markPaid(issueDate, PaymentMethod.CARTE, now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
    }

    @Test
    fun `markPaid from Overdue succeeds`() {
        val sent = (createDraft().send(now) as DomainResult.Ok).value.invoice
        val overdue = (sent.markOverdue(now) as DomainResult.Ok).value.invoice
        val result = overdue.markPaid(issueDate, PaymentMethod.CHEQUE, now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
    }

    @Test
    fun `markPaid from Paid fails`() {
        val paid =
            (createDraft().markPaid(issueDate, PaymentMethod.VIREMENT, now) as DomainResult.Ok)
                .value.invoice
        val result = paid.markPaid(issueDate, PaymentMethod.CARTE, now)
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.InvalidTransition>(result.error)
    }

    @Test
    fun `markPaid from Cancelled fails`() {
        val cancelled =
            (createDraft().cancel(null, now) as DomainResult.Ok).value.invoice
        val result = cancelled.markPaid(issueDate, PaymentMethod.VIREMENT, now)
        assertIs<DomainResult.Err>(result)
    }

    // --- markOverdue() tests ---
    @Test
    fun `markOverdue from Sent succeeds`() {
        val sent = (createDraft().send(now) as DomainResult.Ok).value.invoice
        val result = sent.markOverdue(now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
        assertIs<InvoiceStatus.Overdue>(result.value.invoice.status)
        assertIs<DomainEvent.InvoiceOverdue>(result.value.events[0])
    }

    @Test
    fun `markOverdue from Draft fails`() {
        val result = createDraft().markOverdue(now)
        assertIs<DomainResult.Err>(result)
    }

    // --- cancel() tests ---
    @Test
    fun `cancel Draft without credit note succeeds`() {
        val result = createDraft().cancel(null, now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
        assertIs<InvoiceStatus.Cancelled>(result.value.invoice.status)
        assertIs<DomainEvent.DraftDeleted>(result.value.events[0])
        assertNull(result.value.invoice.creditNote)
    }

    @Test
    fun `cancel Sent with credit note succeeds`() {
        val sent = (createDraft().send(now) as DomainResult.Ok).value.invoice
        val cn =
            CreditNote(
                CreditNoteNumber("A-2026-03-001"),
                Cents(80000),
                "Erreur",
                issueDate,
                null,
                null,
            )
        val result = sent.cancel(cn, now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
        assertIs<InvoiceStatus.Cancelled>(result.value.invoice.status)
        assertIs<DomainEvent.InvoiceCancelled>(result.value.events[0])
        assertEquals(cn, result.value.invoice.creditNote)
    }

    @Test
    fun `cancel Sent without credit note fails`() {
        val sent = (createDraft().send(now) as DomainResult.Ok).value.invoice
        val result = sent.cancel(null, now)
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.InvoiceNotCancellable>(result.error)
    }

    @Test
    fun `cancel Paid with credit note succeeds`() {
        val paid =
            (createDraft().markPaid(issueDate, PaymentMethod.VIREMENT, now) as DomainResult.Ok)
                .value.invoice
        val cn =
            CreditNote(
                CreditNoteNumber("A-2026-03-001"),
                Cents(80000),
                "Annulation",
                issueDate,
                null,
                null,
            )
        val result = paid.cancel(cn, now)
        assertIs<DomainResult.Ok<TransitionResult>>(result)
    }

    @Test
    fun `cancel already Cancelled fails`() {
        val cancelled = (createDraft().cancel(null, now) as DomainResult.Ok).value.invoice
        val result = cancelled.cancel(null, now)
        assertIs<DomainResult.Err>(result)
    }

    // --- revertToDraft tests ---
    @Test
    fun `revertToDraft on Sent returns Draft invoice`() {
        val sent = (createDraft().send(now) as DomainResult.Ok).value.invoice
        val result = sent.revertToDraft()
        assertIs<DomainResult.Ok<Invoice>>(result)
        assertIs<InvoiceStatus.Draft>(result.value.status)
    }

    @Test
    fun `revertToDraft preserves invoice data`() {
        val sent = (createDraft().send(now) as DomainResult.Ok).value.invoice
        val reverted = (sent.revertToDraft() as DomainResult.Ok).value
        assertEquals(sent.id, reverted.id)
        assertEquals(sent.number, reverted.number)
        assertEquals(sent.amountHt, reverted.amountHt)
        assertEquals(sent.clientId, reverted.clientId)
    }

    @Test
    fun `revertToDraft on Draft fails`() {
        val result = createDraft().revertToDraft()
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.InvalidTransition>(result.error)
    }

    @Test
    fun `revertToDraft on Paid fails`() {
        val paid = (createDraft().markPaid(issueDate, PaymentMethod.VIREMENT, now) as DomainResult.Ok).value.invoice
        val result = paid.revertToDraft()
        assertIs<DomainResult.Err>(result)
        assertIs<DomainError.InvalidTransition>(result.error)
    }
}
