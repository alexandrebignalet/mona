package mona.application.payment

import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DomainEvent
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
import kotlin.test.assertTrue

private val OTJ_USER_1 = UserId("user-1")
private val OTJ_USER_2 = UserId("user-2")
private val OTJ_CLIENT_A = ClientId("client-a")
private val OTJ_CLIENT_B = ClientId("client-b")

private val OTJ_TODAY = LocalDate.of(2026, 3, 23)

private fun makeOverdueInvoice(
    id: String,
    userId: UserId,
    clientId: ClientId,
    number: String,
    dueDate: LocalDate,
): Invoice =
    Invoice(
        id = InvoiceId(id),
        userId = userId,
        clientId = clientId,
        number = InvoiceNumber(number),
        status = InvoiceStatus.Sent,
        issueDate = dueDate.minusDays(30),
        dueDate = dueDate,
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(80000L))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-02-21T00:00:00Z"),
    )

private class OverdueInvoiceRepo(
    private val overdueResults: List<Invoice> = emptyList(),
) : InvoiceRepository {
    val saved = mutableListOf<Invoice>()
    var capturedCutoff: LocalDate? = null

    override suspend fun findById(id: InvoiceId): Invoice? = null

    override suspend fun save(invoice: Invoice) {
        saved += invoice
    }

    override suspend fun delete(id: InvoiceId) {}

    override suspend fun findLastNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): InvoiceNumber? = null

    override suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): CreditNoteNumber? = null

    override suspend fun findByUser(userId: UserId): List<Invoice> = emptyList()

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> = emptyList()

    override suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot> = emptyList()

    override suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot> = emptyList()

    override suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice> {
        capturedCutoff = cutoffDate
        return overdueResults
    }

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = emptyList()

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = emptyList()
}

class OverdueTransitionJobTest {
    @Test
    fun `no actions when no overdue invoices`() =
        runBlocking {
            val repo = OverdueInvoiceRepo()
            val dispatcher = EventDispatcher()

            OverdueTransitionJob(repo, dispatcher).execute(OTJ_TODAY)

            assertTrue(repo.saved.isEmpty())
        }

    @Test
    fun `cutoff date is today minus 3 days`() =
        runBlocking {
            val repo = OverdueInvoiceRepo()
            val dispatcher = EventDispatcher()

            OverdueTransitionJob(repo, dispatcher).execute(OTJ_TODAY)

            assertEquals(OTJ_TODAY.minusDays(3), repo.capturedCutoff)
        }

    @Test
    fun `overdue invoice is transitioned to Overdue status and persisted`() =
        runBlocking {
            val invoice = makeOverdueInvoice("i1", OTJ_USER_1, OTJ_CLIENT_A, "F-2026-03-001", OTJ_TODAY.minusDays(5))
            val repo = OverdueInvoiceRepo(overdueResults = listOf(invoice))
            val dispatcher = EventDispatcher()

            OverdueTransitionJob(repo, dispatcher).execute(OTJ_TODAY)

            assertEquals(1, repo.saved.size)
            assertIs<InvoiceStatus.Overdue>(repo.saved.first().status)
        }

    @Test
    fun `InvoiceOverdue event dispatched with correct invoice details`() =
        runBlocking {
            val invoice = makeOverdueInvoice("i1", OTJ_USER_1, OTJ_CLIENT_A, "F-2026-03-001", OTJ_TODAY.minusDays(5))
            val repo = OverdueInvoiceRepo(overdueResults = listOf(invoice))
            val dispatched = mutableListOf<DomainEvent>()
            val dispatcher = EventDispatcher()
            dispatcher.register { event -> dispatched += event }

            OverdueTransitionJob(repo, dispatcher).execute(OTJ_TODAY)

            assertEquals(1, dispatched.size)
            val event = dispatched.first()
            assertIs<DomainEvent.InvoiceOverdue>(event)
            assertEquals(InvoiceNumber("F-2026-03-001"), event.invoiceNumber)
            assertEquals(OTJ_USER_1, event.userId)
        }

    @Test
    fun `multiple overdue invoices all transitioned and persisted`() =
        runBlocking {
            val inv1 = makeOverdueInvoice("i1", OTJ_USER_1, OTJ_CLIENT_A, "F-2026-03-001", OTJ_TODAY.minusDays(5))
            val inv2 = makeOverdueInvoice("i2", OTJ_USER_2, OTJ_CLIENT_B, "F-2026-03-002", OTJ_TODAY.minusDays(10))
            val repo = OverdueInvoiceRepo(overdueResults = listOf(inv1, inv2))
            val dispatched = mutableListOf<DomainEvent>()
            val dispatcher = EventDispatcher()
            dispatcher.register { event -> dispatched += event }

            OverdueTransitionJob(repo, dispatcher).execute(OTJ_TODAY)

            assertEquals(2, repo.saved.size)
            assertEquals(2, dispatched.size)
            assertTrue(repo.saved.all { it.status is InvoiceStatus.Overdue })
        }

    @Test
    fun `idempotent second run finds no new invoices`() =
        runBlocking {
            // Second run with empty repo (already-transitioned invoices are OVERDUE, not SENT)
            val repo = OverdueInvoiceRepo(overdueResults = emptyList())
            val dispatcher = EventDispatcher()

            OverdueTransitionJob(repo, dispatcher).execute(OTJ_TODAY)

            assertTrue(repo.saved.isEmpty())
        }
}
