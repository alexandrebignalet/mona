package mona.application.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DomainError
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.UserId
import mona.domain.port.InvoiceRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val DD_USER_ID = UserId("u1")
private val DD_CLIENT_ID = ClientId("c1")
private val DD_INVOICE_ID = InvoiceId("inv1")
private val DD_ISSUE_DATE = LocalDate.of(2026, 3, 1)

private class StubInvoiceRepoDraft(vararg invoices: Invoice) : InvoiceRepository {
    val store = invoices.associateBy { it.id }.toMutableMap()
    val deleted = mutableListOf<InvoiceId>()

    override suspend fun findById(id: InvoiceId): Invoice? = store[id]

    override suspend fun save(invoice: Invoice) {
        store[invoice.id] = invoice
    }

    override suspend fun delete(id: InvoiceId) {
        store.remove(id)
        deleted.add(id)
    }

    override suspend fun findLastNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): InvoiceNumber? =
        store.values
            .filter { it.userId == userId && YearMonth.from(it.issueDate) == yearMonth }
            .maxByOrNull { it.number.value }
            ?.number

    override suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): CreditNoteNumber? = null

    override suspend fun findByUser(userId: UserId): List<Invoice> = store.values.filter { it.userId == userId }

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> = store.values.filter { it.userId == userId && it.status == status }

    override suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot> = emptyList()

    override suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot> = emptyList()

    override suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice> = emptyList()

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()
}

private fun makeDraftInvoice(status: InvoiceStatus = InvoiceStatus.Draft): Invoice =
    Invoice(
        id = DD_INVOICE_ID,
        userId = DD_USER_ID,
        clientId = DD_CLIENT_ID,
        number = InvoiceNumber("F-2026-03-001"),
        status = status,
        issueDate = DD_ISSUE_DATE,
        dueDate = DD_ISSUE_DATE.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Coaching", BigDecimal.ONE, Cents(50000))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

private fun makeDeleteDraftUseCase(
    invoiceRepo: InvoiceRepository = StubInvoiceRepoDraft(makeDraftInvoice()),
    dispatcher: EventDispatcher = EventDispatcher(),
): DeleteDraft = DeleteDraft(invoiceRepo, dispatcher)

class DeleteDraftTest {
    @Test
    fun `deletes draft invoice from repository`() =
        runBlocking {
            val repo = StubInvoiceRepoDraft(makeDraftInvoice())
            makeDeleteDraftUseCase(repo).execute(DeleteDraftCommand(DD_USER_ID, DD_INVOICE_ID))

            assertNull(repo.store[DD_INVOICE_ID])
            assertEquals(listOf(DD_INVOICE_ID), repo.deleted)
        }

    @Test
    fun `dispatches DraftDeleted event`() =
        runBlocking {
            val dispatched = mutableListOf<DomainEvent>()
            val dispatcher = EventDispatcher()
            dispatcher.register { dispatched.add(it) }

            makeDeleteDraftUseCase(dispatcher = dispatcher).execute(DeleteDraftCommand(DD_USER_ID, DD_INVOICE_ID))

            assertEquals(1, dispatched.size)
            assertIs<DomainEvent.DraftDeleted>(dispatched.first())
        }

    @Test
    fun `returns InvoiceNotFound when invoice does not exist`() =
        runBlocking {
            val result =
                makeDeleteDraftUseCase(StubInvoiceRepoDraft()).execute(
                    DeleteDraftCommand(DD_USER_ID, InvoiceId("missing")),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotFound>(result.error)
        }

    @Test
    fun `returns InvalidTransition when invoice is Sent`() =
        runBlocking {
            val repo = StubInvoiceRepoDraft(makeDraftInvoice(InvoiceStatus.Sent))
            val result = makeDeleteDraftUseCase(repo).execute(DeleteDraftCommand(DD_USER_ID, DD_INVOICE_ID))

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotCancellable>(result.error)
        }

    @Test
    fun `returns Cancelled invoice as result`() =
        runBlocking {
            val result = makeDeleteDraftUseCase().execute(DeleteDraftCommand(DD_USER_ID, DD_INVOICE_ID))

            assertIs<DomainResult.Ok<Invoice>>(result)
            assertEquals(InvoiceStatus.Cancelled, result.value.status)
        }
}
