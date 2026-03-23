package mona.application.client

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val USER_GCH = UserId("u-gch")
private val CLIENT_GCH_A = ClientId("gch-a")
private val CLIENT_GCH_B = ClientId("gch-b")

private fun makeClientGCH(
    id: ClientId,
    name: String,
) = Client(
    id = id,
    userId = USER_GCH,
    name = name,
    email = null,
    address = null,
    companyName = null,
    siret = null,
    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
)

private fun makeInvoiceGCH(
    id: String,
    clientId: ClientId,
    issueDate: LocalDate = LocalDate.of(2026, 3, 1),
) = Invoice(
    id = InvoiceId(id),
    userId = USER_GCH,
    clientId = clientId,
    number = InvoiceNumber("F-2026-03-00$id"),
    status = InvoiceStatus.Sent,
    issueDate = issueDate,
    dueDate = issueDate.plusDays(30),
    activityType = ActivityType.BNC,
    lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(80000))),
    pdfPath = null,
    creditNote = null,
    createdAt = Instant.parse("2026-03-01T00:00:00Z"),
)

private class StubClientRepoGCH(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }
}

private class StubInvoiceRepoGCH(vararg invoices: Invoice) : InvoiceRepository {
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

class GetClientHistoryTest {
    @Test
    fun `returns invoices for client by clientId`() =
        runBlocking {
            val clientRepo = StubClientRepoGCH(makeClientGCH(CLIENT_GCH_A, "Jean Dupont"))
            val invoiceRepo =
                StubInvoiceRepoGCH(
                    makeInvoiceGCH("1", CLIENT_GCH_A),
                    makeInvoiceGCH("2", CLIENT_GCH_A),
                )

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientId = CLIENT_GCH_A),
                )

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            val found = result.value as GetClientHistoryResult.Found
            assertEquals(2, found.invoices.size)
            assertEquals("Jean Dupont", found.client.name)
        }

    @Test
    fun `returns invoices for client by name`() =
        runBlocking {
            val clientRepo = StubClientRepoGCH(makeClientGCH(CLIENT_GCH_A, "Marie Martin"))
            val invoiceRepo = StubInvoiceRepoGCH(makeInvoiceGCH("1", CLIENT_GCH_A))

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientName = "Marie Martin"),
                )

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            assertIs<GetClientHistoryResult.Found>(result.value)
        }

    @Test
    fun `returns Ambiguous when multiple clients match name`() =
        runBlocking {
            val clientRepo =
                StubClientRepoGCH(
                    makeClientGCH(CLIENT_GCH_A, "Dupont"),
                    makeClientGCH(CLIENT_GCH_B, "Dupont"),
                )
            val invoiceRepo = StubInvoiceRepoGCH()

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientName = "Dupont"),
                )

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            val ambiguous = result.value as GetClientHistoryResult.Ambiguous
            assertEquals(2, ambiguous.matches.size)
        }

    @Test
    fun `returns ClientNotFound when clientId unknown`() =
        runBlocking {
            val clientRepo = StubClientRepoGCH()
            val invoiceRepo = StubInvoiceRepoGCH()

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientId = ClientId("unknown")),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ClientNotFound>(result.error)
        }

    @Test
    fun `returns ClientNotFound when name has no match`() =
        runBlocking {
            val clientRepo = StubClientRepoGCH()
            val invoiceRepo = StubInvoiceRepoGCH()

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientName = "Unknown"),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ClientNotFound>(result.error)
        }

    @Test
    fun `excludes invoices from other clients`() =
        runBlocking {
            val clientRepo =
                StubClientRepoGCH(
                    makeClientGCH(CLIENT_GCH_A, "Jean Dupont"),
                    makeClientGCH(CLIENT_GCH_B, "Marie Martin"),
                )
            val invoiceRepo =
                StubInvoiceRepoGCH(
                    makeInvoiceGCH("1", CLIENT_GCH_A),
                    makeInvoiceGCH("2", CLIENT_GCH_B),
                )

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientId = CLIENT_GCH_A),
                )

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            val found = result.value as GetClientHistoryResult.Found
            assertEquals(1, found.invoices.size)
            assertEquals(InvoiceId("1"), found.invoices.first().id)
        }

    @Test
    fun `invoices sorted by issue date ascending`() =
        runBlocking {
            val clientRepo = StubClientRepoGCH(makeClientGCH(CLIENT_GCH_A, "Jean Dupont"))
            val invoiceRepo =
                StubInvoiceRepoGCH(
                    makeInvoiceGCH("3", CLIENT_GCH_A, LocalDate.of(2026, 3, 15)),
                    makeInvoiceGCH("1", CLIENT_GCH_A, LocalDate.of(2026, 1, 10)),
                    makeInvoiceGCH("2", CLIENT_GCH_A, LocalDate.of(2026, 2, 5)),
                )

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientId = CLIENT_GCH_A),
                )

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            val found = result.value as GetClientHistoryResult.Found
            assertEquals(
                listOf(InvoiceId("1"), InvoiceId("2"), InvoiceId("3")),
                found.invoices.map { it.id },
            )
        }

    @Test
    fun `returns empty invoice list when client has no invoices`() =
        runBlocking {
            val clientRepo = StubClientRepoGCH(makeClientGCH(CLIENT_GCH_A, "Jean Dupont"))
            val invoiceRepo = StubInvoiceRepoGCH()

            val result =
                GetClientHistory(clientRepo, invoiceRepo).execute(
                    GetClientHistoryCommand(userId = USER_GCH, clientId = CLIENT_GCH_A),
                )

            assertIs<DomainResult.Ok<GetClientHistoryResult>>(result)
            val found = result.value as GetClientHistoryResult.Found
            assertTrue(found.invoices.isEmpty())
        }
}
