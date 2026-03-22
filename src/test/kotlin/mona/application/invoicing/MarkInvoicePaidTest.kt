package mona.application.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
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
import mona.domain.model.PaymentMethod
import mona.domain.model.UserId
import mona.domain.port.InvoiceRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private val USER_ID_PAID = UserId("u1")
private val CLIENT_ID_PAID = ClientId("c1")
private val INVOICE_ID_PAID = InvoiceId("inv1")
private val ISSUE_DATE_PAID = LocalDate.of(2026, 3, 1)
private val PAYMENT_DATE = LocalDate.of(2026, 3, 22)

private class StubInvoiceRepositoryPaid(vararg invoices: Invoice) : InvoiceRepository {
    val store = invoices.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: InvoiceId): Invoice? = store[id]

    override suspend fun save(invoice: Invoice) {
        store[invoice.id] = invoice
    }

    override suspend fun delete(id: InvoiceId) {
        store.remove(id)
    }

    override suspend fun findLastNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): InvoiceNumber? =
        store.values
            .filter { it.userId == userId && YearMonth.from(it.issueDate) == yearMonth }
            .maxByOrNull { it.number.value }
            ?.number

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

private fun makeInvoice(status: InvoiceStatus = InvoiceStatus.Draft): Invoice =
    Invoice(
        id = INVOICE_ID_PAID,
        userId = USER_ID_PAID,
        clientId = CLIENT_ID_PAID,
        number = InvoiceNumber("F-2026-03-001"),
        status = status,
        issueDate = ISSUE_DATE_PAID,
        dueDate = ISSUE_DATE_PAID.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

private fun makeUseCase(
    invoiceRepo: InvoiceRepository = StubInvoiceRepositoryPaid(makeInvoice()),
    dispatcher: EventDispatcher = EventDispatcher(),
): MarkInvoicePaid = MarkInvoicePaid(invoiceRepo, dispatcher)

private fun makeCommand(
    invoiceId: InvoiceId = INVOICE_ID_PAID,
    paymentDate: LocalDate = PAYMENT_DATE,
    paymentMethod: PaymentMethod = PaymentMethod.VIREMENT,
): MarkInvoicePaidCommand =
    MarkInvoicePaidCommand(
        userId = USER_ID_PAID,
        invoiceId = invoiceId,
        paymentDate = paymentDate,
        paymentMethod = paymentMethod,
    )

class MarkInvoicePaidTest {
    @Test
    fun `transitions draft invoice to Paid with correct date and method`() =
        runBlocking {
            val result = makeUseCase().execute(makeCommand())

            assertIs<DomainResult.Ok<Invoice>>(result)
            val paid = result.value.status
            assertIs<InvoiceStatus.Paid>(paid)
            assertEquals(PAYMENT_DATE, paid.date)
            assertEquals(PaymentMethod.VIREMENT, paid.method)
        }

    @Test
    fun `transitions sent invoice to Paid`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepositoryPaid(makeInvoice(InvoiceStatus.Sent))
            val result = makeUseCase(invoiceRepo = invoiceRepo).execute(makeCommand())

            assertIs<DomainResult.Ok<Invoice>>(result)
            assertIs<InvoiceStatus.Paid>(result.value.status)
        }

    @Test
    fun `transitions overdue invoice to Paid`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepositoryPaid(makeInvoice(InvoiceStatus.Overdue))
            val result = makeUseCase(invoiceRepo = invoiceRepo).execute(makeCommand())

            assertIs<DomainResult.Ok<Invoice>>(result)
            assertIs<InvoiceStatus.Paid>(result.value.status)
        }

    @Test
    fun `persists the updated invoice`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepositoryPaid(makeInvoice())
            makeUseCase(invoiceRepo = invoiceRepo).execute(makeCommand())

            assertIs<InvoiceStatus.Paid>(invoiceRepo.store[INVOICE_ID_PAID]!!.status)
        }

    @Test
    fun `dispatches InvoicePaid event`() =
        runBlocking {
            val dispatched = mutableListOf<DomainEvent>()
            val dispatcher = EventDispatcher()
            dispatcher.register { dispatched.add(it) }

            makeUseCase(dispatcher = dispatcher).execute(makeCommand())

            assertEquals(1, dispatched.size)
            assertIs<DomainEvent.InvoicePaid>(dispatched.first())
        }

    @Test
    fun `returns InvoiceNotFound error when invoice does not exist`() =
        runBlocking {
            val result = makeUseCase(invoiceRepo = StubInvoiceRepositoryPaid()).execute(makeCommand(invoiceId = InvoiceId("missing")))

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotFound>(result.error)
        }

    @Test
    fun `returns InvalidTransition error when invoice is already Cancelled`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepositoryPaid(makeInvoice(InvoiceStatus.Cancelled))
            val result = makeUseCase(invoiceRepo = invoiceRepo).execute(makeCommand())

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvalidTransition>(result.error)
        }
}
