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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val CI_USER_ID = UserId("u1")
private val CI_CLIENT_ID = ClientId("c1")
private val CI_INVOICE_ID = InvoiceId("inv1")
private val CI_ISSUE_DATE = LocalDate.of(2026, 3, 22)
private val CI_FAKE_PDF = byteArrayOf(0x50, 0x44, 0x46)

private class StubUserRepoCancelInvoice(vararg users: User) : UserRepository {
    private val store = users.associateBy { it.id }

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {}

    override suspend fun findAllWithPeriodicity(): List<User> = emptyList()
}

private class StubClientRepoCancelInvoice(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = emptyList()

    override suspend fun findByUser(userId: UserId): List<Client> = emptyList()
}

private class StubInvoiceRepoCancelInvoice(vararg invoices: Invoice) : InvoiceRepository {
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
    ): CreditNoteNumber? =
        store.values
            .mapNotNull { it.creditNote?.number }
            .filter { it.value.startsWith("A-${yearMonth.year}-${"%02d".format(yearMonth.monthValue)}-") }
            .maxByOrNull { it.value }

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

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = emptyList()

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()
}

private class StubPdfPortCancelInvoice : PdfPort {
    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray = CI_FAKE_PDF

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray = CI_FAKE_PDF
}

private fun makeCancelUser(): User =
    User(
        id = CI_USER_ID,
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

private fun makeCancelClient(): Client =
    Client(
        id = CI_CLIENT_ID,
        userId = CI_USER_ID,
        name = "Dupont",
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeSentInvoice(status: InvoiceStatus = InvoiceStatus.Sent): Invoice =
    Invoice(
        id = CI_INVOICE_ID,
        userId = CI_USER_ID,
        clientId = CI_CLIENT_ID,
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

private fun makeUseCase(
    userRepo: UserRepository = StubUserRepoCancelInvoice(makeCancelUser()),
    clientRepo: ClientRepository = StubClientRepoCancelInvoice(makeCancelClient()),
    invoiceRepo: InvoiceRepository = StubInvoiceRepoCancelInvoice(makeSentInvoice()),
    pdfPort: PdfPort = StubPdfPortCancelInvoice(),
    dispatcher: EventDispatcher = EventDispatcher(),
): CancelInvoice = CancelInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, dispatcher)

private fun makeCommand(
    invoiceId: InvoiceId = CI_INVOICE_ID,
    reason: String = "Erreur de montant",
): CancelInvoiceCommand = CancelInvoiceCommand(CI_USER_ID, invoiceId, reason, CI_ISSUE_DATE)

class CancelInvoiceTest {
    @Test
    fun `cancels sent invoice and returns Cancelled status`() =
        runBlocking {
            val result = makeUseCase().execute(makeCommand())

            assertIs<DomainResult.Ok<CancelInvoiceResult>>(result)
            assertEquals(InvoiceStatus.Cancelled, result.value.invoice.status)
        }

    @Test
    fun `persists the cancelled invoice`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepoCancelInvoice(makeSentInvoice())
            makeUseCase(invoiceRepo = invoiceRepo).execute(makeCommand())

            val saved = invoiceRepo.store[CI_INVOICE_ID]!!
            assertEquals(InvoiceStatus.Cancelled, saved.status)
        }

    @Test
    fun `attaches credit note to cancelled invoice`() =
        runBlocking {
            val result = makeUseCase().execute(makeCommand())

            assertIs<DomainResult.Ok<CancelInvoiceResult>>(result)
            val creditNote = result.value.invoice.creditNote
            assertNotNull(creditNote)
            assertEquals(Cents(80000), creditNote.amountHt)
            assertEquals("A-2026-03-001", creditNote.number.value)
        }

    @Test
    fun `generates credit note PDF`() =
        runBlocking {
            val result = makeUseCase().execute(makeCommand())

            assertIs<DomainResult.Ok<CancelInvoiceResult>>(result)
            assertContentEquals(CI_FAKE_PDF, result.value.creditNotePdf)
        }

    @Test
    fun `dispatches InvoiceCancelled event`() =
        runBlocking {
            val dispatched = mutableListOf<DomainEvent>()
            val dispatcher = EventDispatcher()
            dispatcher.register { dispatched.add(it) }

            makeUseCase(dispatcher = dispatcher).execute(makeCommand())

            assertEquals(1, dispatched.size)
            assertIs<DomainEvent.InvoiceCancelled>(dispatched.first())
        }

    @Test
    fun `returns InvoiceNotFound when invoice missing`() =
        runBlocking {
            val result =
                makeUseCase(invoiceRepo = StubInvoiceRepoCancelInvoice()).execute(
                    makeCommand(invoiceId = InvoiceId("missing")),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotFound>(result.error)
        }

    @Test
    fun `returns error when trying to cancel a draft`() =
        runBlocking {
            val repo = StubInvoiceRepoCancelInvoice(makeSentInvoice(InvoiceStatus.Draft))
            val result = makeUseCase(invoiceRepo = repo).execute(makeCommand())

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotCancellable>(result.error)
        }

    @Test
    fun `sequential credit note numbering`() =
        runBlocking {
            val invoiceRepo =
                StubInvoiceRepoCancelInvoice(
                    makeSentInvoice().copy(id = InvoiceId("inv1")),
                    makeSentInvoice().copy(
                        id = InvoiceId("inv2"),
                        number = InvoiceNumber("F-2026-03-002"),
                        creditNote =
                            mona.domain.model.CreditNote(
                                number = CreditNoteNumber("A-2026-03-001"),
                                amountHt = Cents(80000),
                                reason = "",
                                issueDate = CI_ISSUE_DATE,
                                replacementInvoiceId = null,
                                pdfPath = null,
                            ),
                    ),
                )
            val result = makeUseCase(invoiceRepo = invoiceRepo).execute(makeCommand())

            assertIs<DomainResult.Ok<CancelInvoiceResult>>(result)
            assertEquals("A-2026-03-002", result.value.invoice.creditNote!!.number.value)
        }
}
