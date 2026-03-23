package mona.application.client

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

private val USER_LC = UserId("u-lc")
private val CLIENT_LC_A = ClientId("lc-a")
private val CLIENT_LC_B = ClientId("lc-b")

private fun makeClientLC(
    id: ClientId,
    name: String,
) = Client(
    id = id,
    userId = USER_LC,
    name = name,
    email = null,
    address = null,
    companyName = null,
    siret = null,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
)

private fun makeInvoiceLC(
    id: String,
    clientId: ClientId,
    amountCents: Long,
) = Invoice(
    id = InvoiceId(id),
    userId = USER_LC,
    clientId = clientId,
    number = InvoiceNumber("F-2026-03-00$id"),
    status = InvoiceStatus.Sent,
    issueDate = LocalDate.of(2026, 3, 1),
    dueDate = LocalDate.of(2026, 4, 1),
    activityType = ActivityType.BNC,
    lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(amountCents))),
    pdfPath = null,
    creditNote = null,
    createdAt = Instant.parse("2026-03-01T00:00:00Z"),
)

private class StubClientRepoLC(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }
}

private class StubInvoiceRepoLC(vararg invoices: Invoice) : InvoiceRepository {
    private val store = invoices.toList()

    override suspend fun findById(id: InvoiceId): Invoice? = store.find { it.id == id }

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

    override suspend fun findByUser(userId: UserId): List<Invoice> = store.filter { it.userId == userId }

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> = store.filter { it.userId == userId && it.status::class == status::class }

    override suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot> = emptyList()

    override suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot> = emptyList()

    override suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice> = emptyList()

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = emptyList()

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = emptyList()
}

class ListClientsTest {
    @Test
    fun `returns empty list when no clients`() =
        runBlocking {
            val result = ListClients(StubClientRepoLC(), StubInvoiceRepoLC()).execute(ListClientsCommand(USER_LC))
            assertTrue(result.clients.isEmpty())
        }

    @Test
    fun `returns client with zero invoices`() =
        runBlocking {
            val clientRepo = StubClientRepoLC(makeClientLC(CLIENT_LC_A, "Jean Dupont"))
            val invoiceRepo = StubInvoiceRepoLC()

            val result = ListClients(clientRepo, invoiceRepo).execute(ListClientsCommand(USER_LC))

            assertEquals(1, result.clients.size)
            assertEquals(0, result.clients.first().invoiceCount)
            assertEquals(Cents.ZERO, result.clients.first().totalAmount)
        }

    @Test
    fun `counts invoices per client`() =
        runBlocking {
            val clientRepo = StubClientRepoLC(makeClientLC(CLIENT_LC_A, "Jean Dupont"))
            val invoiceRepo =
                StubInvoiceRepoLC(
                    makeInvoiceLC("1", CLIENT_LC_A, 80000),
                    makeInvoiceLC("2", CLIENT_LC_A, 90000),
                )

            val result = ListClients(clientRepo, invoiceRepo).execute(ListClientsCommand(USER_LC))

            assertEquals(2, result.clients.first().invoiceCount)
        }

    @Test
    fun `sums total amount per client`() =
        runBlocking {
            val clientRepo = StubClientRepoLC(makeClientLC(CLIENT_LC_A, "Jean Dupont"))
            val invoiceRepo =
                StubInvoiceRepoLC(
                    makeInvoiceLC("1", CLIENT_LC_A, 80000),
                    makeInvoiceLC("2", CLIENT_LC_A, 90000),
                )

            val result = ListClients(clientRepo, invoiceRepo).execute(ListClientsCommand(USER_LC))

            assertEquals(Cents(170000), result.clients.first().totalAmount)
        }

    @Test
    fun `returns multiple clients with correct counts`() =
        runBlocking {
            val clientRepo =
                StubClientRepoLC(
                    makeClientLC(CLIENT_LC_A, "Jean Dupont"),
                    makeClientLC(CLIENT_LC_B, "Marie Martin"),
                )
            val invoiceRepo =
                StubInvoiceRepoLC(
                    makeInvoiceLC("1", CLIENT_LC_A, 80000),
                    makeInvoiceLC("2", CLIENT_LC_A, 80000),
                    makeInvoiceLC("3", CLIENT_LC_B, 50000),
                )

            val result = ListClients(clientRepo, invoiceRepo).execute(ListClientsCommand(USER_LC))

            assertEquals(2, result.clients.size)
            val summaryA = result.clients.first { it.client.id == CLIENT_LC_A }
            val summaryB = result.clients.first { it.client.id == CLIENT_LC_B }
            assertEquals(2, summaryA.invoiceCount)
            assertEquals(1, summaryB.invoiceCount)
            assertEquals(Cents(160000), summaryA.totalAmount)
            assertEquals(Cents(50000), summaryB.totalAmount)
        }
}
