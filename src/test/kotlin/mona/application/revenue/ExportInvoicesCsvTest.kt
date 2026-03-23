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
import mona.domain.model.PaymentMethod
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

private val USER_CSV = UserId("u1")
private val CLIENT_CSV_ID = ClientId("c1")
private val EXPORT_DATE = LocalDate.of(2026, 3, 22)

private fun makeCsvInvoice(
    id: String,
    number: String,
    status: InvoiceStatus = InvoiceStatus.Draft,
    lineItems: List<LineItem> = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(80000))),
): Invoice =
    Invoice(
        id = InvoiceId(id),
        userId = USER_CSV,
        clientId = CLIENT_CSV_ID,
        number = InvoiceNumber(number),
        status = status,
        issueDate = LocalDate.of(2026, 3, 1),
        dueDate = LocalDate.of(2026, 3, 31),
        activityType = ActivityType.BNC,
        lineItems = lineItems,
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

private fun makeCsvClient(): Client =
    Client(
        id = CLIENT_CSV_ID,
        userId = USER_CSV,
        name = "Jean Dupont",
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private class StubInvoiceRepoCsv(vararg invoices: Invoice) : InvoiceRepository {
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
    ): List<Invoice> = store.filter { it.userId == userId && it.status == status }

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
}

private class StubClientRepoCsv(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }
}

class ExportInvoicesCsvTest {
    @Test
    fun `filename uses export date`() =
        runBlocking {
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(), StubClientRepoCsv())
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            assertEquals("mona-factures-2026-03-22.csv", result.filename)
        }

    @Test
    fun `invoiceCount matches number of invoices`() =
        runBlocking {
            val repo =
                StubInvoiceRepoCsv(
                    makeCsvInvoice("1", "F-2026-03-001"),
                    makeCsvInvoice("2", "F-2026-03-002"),
                )
            val result =
                ExportInvoicesCsv(repo, StubClientRepoCsv(makeCsvClient()))
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            assertEquals(2, result.invoiceCount)
        }

    @Test
    fun `CSV has header row as first line`() =
        runBlocking {
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(), StubClientRepoCsv())
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            val firstLine = result.csvContent.toString(Charsets.UTF_8).lines().first()
            val expected =
                "invoice_number,status,issue_date,due_date,paid_date,client_name,line_items,total_ht,total_ttc,payment_method"
            assertEquals(expected, firstLine)
        }

    @Test
    fun `CSV row contains invoice number and status`() =
        runBlocking {
            val invoice = makeCsvInvoice("1", "F-2026-03-001", InvoiceStatus.Sent)
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(invoice), StubClientRepoCsv(makeCsvClient()))
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            val csv = result.csvContent.toString(Charsets.UTF_8)
            assertTrue(csv.contains("F-2026-03-001"))
            assertTrue(csv.contains("SENT"))
        }

    @Test
    fun `CSV row contains client name`() =
        runBlocking {
            val invoice = makeCsvInvoice("1", "F-2026-03-001")
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(invoice), StubClientRepoCsv(makeCsvClient()))
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            val csv = result.csvContent.toString(Charsets.UTF_8)
            assertTrue(csv.contains("Jean Dupont"))
        }

    @Test
    fun `CSV row contains paid date and payment method for Paid invoices`() =
        runBlocking {
            val paidStatus = InvoiceStatus.Paid(LocalDate.of(2026, 3, 20), PaymentMethod.VIREMENT)
            val invoice = makeCsvInvoice("1", "F-2026-03-001", paidStatus)
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(invoice), StubClientRepoCsv(makeCsvClient()))
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            val csv = result.csvContent.toString(Charsets.UTF_8)
            assertTrue(csv.contains("2026-03-20"))
            assertTrue(csv.contains("VIREMENT"))
        }

    @Test
    fun `CSV row contains total_ht formatted as euros`() =
        runBlocking {
            val invoice = makeCsvInvoice("1", "F-2026-03-001", lineItems = listOf(LineItem("Dev", BigDecimal.ONE, Cents(80000))))
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(invoice), StubClientRepoCsv(makeCsvClient()))
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            val csv = result.csvContent.toString(Charsets.UTF_8)
            assertTrue(csv.contains("800.00"))
        }

    @Test
    fun `empty export when user has no invoices`() =
        runBlocking {
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(), StubClientRepoCsv())
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            assertEquals(0, result.invoiceCount)
            val lines = result.csvContent.toString(Charsets.UTF_8).lines()
            assertEquals(1, lines.size)
        }

    @Test
    fun `CSV row contains line item description`() =
        runBlocking {
            val invoice =
                makeCsvInvoice(
                    "1",
                    "F-2026-03-001",
                    lineItems = listOf(LineItem("Développement web", BigDecimal("3"), Cents(50000))),
                )
            val result =
                ExportInvoicesCsv(StubInvoiceRepoCsv(invoice), StubClientRepoCsv(makeCsvClient()))
                    .execute(ExportInvoicesCsvCommand(USER_CSV, EXPORT_DATE))

            val csv = result.csvContent.toString(Charsets.UTF_8)
            assertTrue(csv.contains("Développement web"))
        }
}
