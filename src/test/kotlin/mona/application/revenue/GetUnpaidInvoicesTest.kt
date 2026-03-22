package mona.application.revenue

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
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
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val USER_UNPAID = UserId("u1")
private val CLIENT_A_ID = ClientId("ca")
private val CLIENT_B_ID = ClientId("cb")

private fun makeUnpaidInvoice(
    id: String,
    clientId: ClientId,
    status: InvoiceStatus,
    dueDate: LocalDate,
): Invoice =
    Invoice(
        id = InvoiceId(id),
        userId = USER_UNPAID,
        clientId = clientId,
        number = InvoiceNumber("F-2026-03-00$id"),
        status = status,
        issueDate = LocalDate.of(2026, 3, 1),
        dueDate = dueDate,
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

private fun makeClient(
    id: ClientId,
    name: String,
): Client =
    Client(
        id = id,
        userId = USER_UNPAID,
        name = name,
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private class StubInvoiceRepoUnpaid(
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

private class StubClientRepoUnpaid(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }
}

class GetUnpaidInvoicesTest {
    @Test
    fun `returns both Sent and Overdue invoices`() =
        runBlocking {
            val sent = makeUnpaidInvoice("1", CLIENT_A_ID, InvoiceStatus.Sent, LocalDate.of(2026, 4, 15))
            val overdue = makeUnpaidInvoice("2", CLIENT_A_ID, InvoiceStatus.Overdue, LocalDate.of(2026, 3, 10))
            val invoiceRepo = StubInvoiceRepoUnpaid(sentInvoices = listOf(sent), overdueInvoices = listOf(overdue))
            val clientRepo = StubClientRepoUnpaid(makeClient(CLIENT_A_ID, "Jean Dupont"))

            val result = GetUnpaidInvoices(invoiceRepo, clientRepo).execute(GetUnpaidInvoicesCommand(USER_UNPAID))

            assertEquals(2, result.items.size)
        }

    @Test
    fun `items sorted by due date ascending`() =
        runBlocking {
            val later = makeUnpaidInvoice("1", CLIENT_A_ID, InvoiceStatus.Sent, LocalDate.of(2026, 4, 30))
            val sooner = makeUnpaidInvoice("2", CLIENT_A_ID, InvoiceStatus.Overdue, LocalDate.of(2026, 3, 10))
            val invoiceRepo = StubInvoiceRepoUnpaid(sentInvoices = listOf(later), overdueInvoices = listOf(sooner))
            val clientRepo = StubClientRepoUnpaid(makeClient(CLIENT_A_ID, "Jean Dupont"))

            val result = GetUnpaidInvoices(invoiceRepo, clientRepo).execute(GetUnpaidInvoicesCommand(USER_UNPAID))

            assertEquals(InvoiceId("2"), result.items[0].invoice.id)
            assertEquals(InvoiceId("1"), result.items[1].invoice.id)
        }

    @Test
    fun `includes client name from repository`() =
        runBlocking {
            val inv = makeUnpaidInvoice("1", CLIENT_A_ID, InvoiceStatus.Sent, LocalDate.of(2026, 4, 30))
            val invoiceRepo = StubInvoiceRepoUnpaid(sentInvoices = listOf(inv))
            val clientRepo = StubClientRepoUnpaid(makeClient(CLIENT_A_ID, "Marie Martin"))

            val result = GetUnpaidInvoices(invoiceRepo, clientRepo).execute(GetUnpaidInvoicesCommand(USER_UNPAID))

            assertEquals("Marie Martin", result.items.first().clientName)
        }

    @Test
    fun `returns empty result when no unpaid invoices`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepoUnpaid()
            val clientRepo = StubClientRepoUnpaid()

            val result = GetUnpaidInvoices(invoiceRepo, clientRepo).execute(GetUnpaidInvoicesCommand(USER_UNPAID))

            assertTrue(result.items.isEmpty())
        }

    @Test
    fun `includes invoices from multiple clients`() =
        runBlocking {
            val inv1 = makeUnpaidInvoice("1", CLIENT_A_ID, InvoiceStatus.Sent, LocalDate.of(2026, 4, 30))
            val inv2 = makeUnpaidInvoice("2", CLIENT_B_ID, InvoiceStatus.Sent, LocalDate.of(2026, 4, 15))
            val invoiceRepo = StubInvoiceRepoUnpaid(sentInvoices = listOf(inv1, inv2))
            val clientRepo =
                StubClientRepoUnpaid(
                    makeClient(CLIENT_A_ID, "Jean Dupont"),
                    makeClient(CLIENT_B_ID, "Marie Martin"),
                )

            val result = GetUnpaidInvoices(invoiceRepo, clientRepo).execute(GetUnpaidInvoicesCommand(USER_UNPAID))

            assertEquals(2, result.items.size)
            val names = result.items.map { it.clientName }.toSet()
            assertEquals(setOf("Jean Dupont", "Marie Martin"), names)
        }
}
