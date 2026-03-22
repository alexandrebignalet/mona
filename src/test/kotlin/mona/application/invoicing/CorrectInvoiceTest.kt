package mona.application.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.CreditNote
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainError
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.PaymentDelayDays
import mona.domain.model.Siren
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val CORR_USER_ID = UserId("u1")
private val CORR_CLIENT_ID = ClientId("c1")
private val CORR_INVOICE_ID = InvoiceId("inv1")
private val CORR_ISSUE_DATE = LocalDate.of(2026, 3, 22)
private val CORR_FAKE_PDF = byteArrayOf(0x50, 0x44, 0x46)

private class StubUserRepoCorrect(vararg users: User) : UserRepository {
    private val store = users.associateBy { it.id }

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {}
}

private class StubClientRepoCorrect(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = emptyList()

    override suspend fun findByUser(userId: UserId): List<Client> = emptyList()
}

private class StubInvoiceRepoCorrect(vararg invoices: Invoice) : InvoiceRepository {
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

private class StubPdfPortCorrect : PdfPort {
    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray = CORR_FAKE_PDF

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray = CORR_FAKE_PDF
}

private fun makeCorrectUser(): User =
    User(
        id = CORR_USER_ID,
        telegramId = 1L,
        email = Email("alice@example.com"),
        name = "Alice",
        siren = Siren("123456789"),
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BNC,
        declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeCorrectClient(): Client =
    Client(
        id = CORR_CLIENT_ID,
        userId = CORR_USER_ID,
        name = "Dupont",
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeOriginalInvoice(status: InvoiceStatus = InvoiceStatus.Sent): Invoice =
    Invoice(
        id = CORR_INVOICE_ID,
        userId = CORR_USER_ID,
        clientId = CORR_CLIENT_ID,
        number = InvoiceNumber("F-2026-03-001"),
        status = status,
        issueDate = LocalDate.of(2026, 3, 1),
        dueDate = LocalDate.of(2026, 3, 31),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Coaching", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

private val CORRECTED_ITEMS = listOf(LineItem("Coaching", BigDecimal.ONE, Cents(90000)))

private fun makeCorrectUseCase(
    userRepo: UserRepository = StubUserRepoCorrect(makeCorrectUser()),
    clientRepo: ClientRepository = StubClientRepoCorrect(makeCorrectClient()),
    invoiceRepo: InvoiceRepository = StubInvoiceRepoCorrect(makeOriginalInvoice()),
    pdfPort: PdfPort = StubPdfPortCorrect(),
    dispatcher: EventDispatcher = EventDispatcher(),
): CorrectInvoice = CorrectInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, dispatcher)

private fun makeCorrectCommand(
    invoiceId: InvoiceId = CORR_INVOICE_ID,
    items: List<LineItem> = CORRECTED_ITEMS,
): CorrectInvoiceCommand =
    CorrectInvoiceCommand(
        userId = CORR_USER_ID,
        invoiceId = invoiceId,
        correctedLineItems = items,
        issueDate = CORR_ISSUE_DATE,
    )

class CorrectInvoiceTest {
    @Test
    fun `original invoice is cancelled`() =
        runBlocking {
            val result = makeCorrectUseCase().execute(makeCorrectCommand())

            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            assertEquals(InvoiceStatus.Cancelled, result.value.cancelledInvoice.status)
        }

    @Test
    fun `new corrected invoice is in Draft status`() =
        runBlocking {
            val result = makeCorrectUseCase().execute(makeCorrectCommand())

            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            assertEquals(InvoiceStatus.Draft, result.value.newInvoice.status)
        }

    @Test
    fun `new invoice has corrected line items`() =
        runBlocking {
            val result = makeCorrectUseCase().execute(makeCorrectCommand())

            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            assertEquals(Cents(90000), result.value.newInvoice.amountHt)
        }

    @Test
    fun `new invoice gets next sequential number`() =
        runBlocking {
            val result = makeCorrectUseCase().execute(makeCorrectCommand())

            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            assertEquals("F-2026-03-002", result.value.newInvoice.number.value)
        }

    @Test
    fun `credit note links to new invoice`() =
        runBlocking {
            val result = makeCorrectUseCase().execute(makeCorrectCommand())

            assertIs<DomainResult.Ok<CorrectInvoiceResult>>(result)
            val creditNote = result.value.cancelledInvoice.creditNote
            assertNotNull(creditNote)
            assertEquals(result.value.newInvoice.id, creditNote.replacementInvoiceId)
        }

    @Test
    fun `both invoices are persisted`() =
        runBlocking {
            val repo = StubInvoiceRepoCorrect(makeOriginalInvoice())
            makeCorrectUseCase(invoiceRepo = repo).execute(makeCorrectCommand())

            assertEquals(2, repo.store.size)
            val cancelled = repo.store[CORR_INVOICE_ID]!!
            assertEquals(InvoiceStatus.Cancelled, cancelled.status)
        }

    @Test
    fun `dispatches InvoiceCancelled event`() =
        runBlocking {
            val dispatched = mutableListOf<DomainEvent>()
            val dispatcher = EventDispatcher()
            dispatcher.register { dispatched.add(it) }

            makeCorrectUseCase(dispatcher = dispatcher).execute(makeCorrectCommand())

            assertEquals(1, dispatched.size)
            assertIs<DomainEvent.InvoiceCancelled>(dispatched.first())
        }

    @Test
    fun `returns InvoiceNotFound when original invoice missing`() =
        runBlocking {
            val result =
                makeCorrectUseCase(invoiceRepo = StubInvoiceRepoCorrect()).execute(
                    makeCorrectCommand(invoiceId = InvoiceId("missing")),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotFound>(result.error)
        }
}
