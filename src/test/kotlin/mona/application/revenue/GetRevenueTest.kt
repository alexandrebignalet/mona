package mona.application.revenue

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
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

private val USER_REV = UserId("u1")
private val CLIENT_REV = ClientId("c1")
private val PERIOD = DeclarationPeriod.monthly(2026, 3)

private fun makeInvoice(
    id: String,
    status: InvoiceStatus,
    dueDate: LocalDate = LocalDate.of(2026, 4, 30),
    amountCents: Long = 80000,
): Invoice =
    Invoice(
        id = InvoiceId(id),
        userId = USER_REV,
        clientId = CLIENT_REV,
        number = InvoiceNumber("F-2026-03-00$id"),
        status = status,
        issueDate = LocalDate.of(2026, 3, 1),
        dueDate = dueDate,
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(amountCents))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

private class StubInvoiceRepoRevenue(
    val paidSnapshots: List<PaidInvoiceSnapshot> = emptyList(),
    val creditSnapshots: List<CreditNoteSnapshot> = emptyList(),
    val sentInvoices: List<Invoice> = emptyList(),
    val overdueInvoices: List<Invoice> = emptyList(),
) : InvoiceRepository {
    override suspend fun findById(id: InvoiceId): Invoice? = null

    override suspend fun save(invoice: Invoice) {}

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
    ): List<Invoice> =
        when (status) {
            is InvoiceStatus.Sent -> sentInvoices
            is InvoiceStatus.Overdue -> overdueInvoices
            else -> emptyList()
        }

    override suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot> = paidSnapshots

    override suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot> = creditSnapshots

    override suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice> = emptyList()

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = emptyList()

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()
}

class GetRevenueTest {
    @Test
    fun `returns correct breakdown from paid snapshots`() =
        runBlocking {
            val snapshots =
                listOf(
                    PaidInvoiceSnapshot(InvoiceId("i1"), Cents(80000), LocalDate.of(2026, 3, 10), ActivityType.BNC),
                    PaidInvoiceSnapshot(InvoiceId("i2"), Cents(120000), LocalDate.of(2026, 3, 15), ActivityType.BNC),
                )
            val repo = StubInvoiceRepoRevenue(paidSnapshots = snapshots)
            val result = GetRevenue(repo).execute(GetRevenueCommand(USER_REV, PERIOD))

            assertEquals(Cents(200000), result.breakdown.total)
            assertEquals(Cents(200000), result.breakdown.byActivity[ActivityType.BNC])
        }

    @Test
    fun `returns correct paidCount`() =
        runBlocking {
            val snapshots =
                listOf(
                    PaidInvoiceSnapshot(InvoiceId("i1"), Cents(80000), LocalDate.of(2026, 3, 10), ActivityType.BNC),
                    PaidInvoiceSnapshot(InvoiceId("i2"), Cents(50000), LocalDate.of(2026, 3, 20), ActivityType.BIC_SERVICE),
                )
            val repo = StubInvoiceRepoRevenue(paidSnapshots = snapshots)
            val result = GetRevenue(repo).execute(GetRevenueCommand(USER_REV, PERIOD))

            assertEquals(2, result.paidCount)
        }

    @Test
    fun `returns correct pendingCount combining Sent and Overdue`() =
        runBlocking {
            val sent = listOf(makeInvoice("1", InvoiceStatus.Sent), makeInvoice("2", InvoiceStatus.Sent))
            val overdue = listOf(makeInvoice("3", InvoiceStatus.Overdue))
            val repo = StubInvoiceRepoRevenue(sentInvoices = sent, overdueInvoices = overdue)
            val result = GetRevenue(repo).execute(GetRevenueCommand(USER_REV, PERIOD))

            assertEquals(3, result.pendingCount)
        }

    @Test
    fun `returns correct pendingAmount`() =
        runBlocking {
            val sent = listOf(makeInvoice("1", InvoiceStatus.Sent, amountCents = 80000))
            val overdue = listOf(makeInvoice("2", InvoiceStatus.Overdue, amountCents = 60000))
            val repo = StubInvoiceRepoRevenue(sentInvoices = sent, overdueInvoices = overdue)
            val result = GetRevenue(repo).execute(GetRevenueCommand(USER_REV, PERIOD))

            assertEquals(Cents(140000), result.pendingAmount)
        }

    @Test
    fun `returns zero breakdown when no paid invoices`() =
        runBlocking {
            val repo = StubInvoiceRepoRevenue()
            val result = GetRevenue(repo).execute(GetRevenueCommand(USER_REV, PERIOD))

            assertEquals(Cents(0), result.breakdown.total)
            assertEquals(0, result.paidCount)
            assertEquals(0, result.pendingCount)
            assertEquals(Cents(0), result.pendingAmount)
        }

    @Test
    fun `deducts credit notes from breakdown`() =
        runBlocking {
            val snapshots =
                listOf(PaidInvoiceSnapshot(InvoiceId("i1"), Cents(100000), LocalDate.of(2026, 3, 10), ActivityType.BNC))
            val credits =
                listOf(
                    CreditNoteSnapshot(
                        CreditNoteNumber("A-2026-03-001"),
                        Cents(-80000),
                        LocalDate.of(2026, 3, 15),
                        ActivityType.BNC,
                    ),
                )
            val repo = StubInvoiceRepoRevenue(paidSnapshots = snapshots, creditSnapshots = credits)
            val result = GetRevenue(repo).execute(GetRevenueCommand(USER_REV, PERIOD))

            assertEquals(Cents(20000), result.breakdown.total)
        }
}
