package mona.application.invoicing

import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.CreditNote
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
import mona.domain.port.EmailPort
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

private val FAKE_PDF = byteArrayOf(0x50, 0x44, 0x46)

private class StubUserRepository(vararg users: User) : UserRepository {
    private val store = users.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {
        store[user.id] = user
    }

    override suspend fun findAllWithPeriodicity(): List<User> = store.values.filter { it.declarationPeriodicity != null }

    override suspend fun findAllWithoutSiren(): List<User> = store.values.filter { it.siren == null }
}

private class StubClientRepository(vararg clients: Client) : ClientRepository {
    private val store = clients.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {
        store[client.id] = client
    }

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }
}

private class StubInvoiceRepository(vararg invoices: Invoice) : InvoiceRepository {
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

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = emptyList()

    override suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): mona.domain.model.CreditNoteNumber? = null

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = store.values.filter { it.number == number }
}

private class StubPdfPort : PdfPort {
    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray = FAKE_PDF

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): ByteArray = FAKE_PDF
}

private class StubEmailPort(private val result: DomainResult<Unit> = DomainResult.Ok(Unit)) : EmailPort {
    val calls = mutableListOf<Triple<String, String, String>>() // to, subject, filename

    override suspend fun sendInvoice(
        to: String,
        subject: String,
        body: String,
        pdfAttachment: ByteArray,
        filename: String,
    ): DomainResult<Unit> {
        calls.add(Triple(to, subject, filename))
        return result
    }
}

private val USER_ID = UserId("u1")
private val CLIENT_ID = ClientId("c1")
private val INVOICE_ID = InvoiceId("inv1")
private val ISSUE_DATE = LocalDate.of(2026, 3, 22)

private fun makeUserWithSiren(): User =
    User(
        id = USER_ID,
        telegramId = 1L,
        email = Email("alice@example.com"),
        name = "Alice Dupont",
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

private fun makeUserWithoutSiren(): User = makeUserWithSiren().copy(siren = null)

private fun makeClient(email: Email? = Email("client@example.com")): Client =
    Client(
        id = CLIENT_ID,
        userId = USER_ID,
        name = "ACME Corp",
        email = email,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeDraftInvoice(): Invoice =
    Invoice(
        id = INVOICE_ID,
        userId = USER_ID,
        clientId = CLIENT_ID,
        number = InvoiceNumber("F-2026-03-001"),
        status = InvoiceStatus.Draft,
        issueDate = ISSUE_DATE,
        dueDate = ISSUE_DATE.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-22T00:00:00Z"),
    )

private fun makeSentInvoice(): Invoice = makeDraftInvoice().copy(status = InvoiceStatus.Sent)

private fun makeUseCase(
    userRepo: UserRepository = StubUserRepository(makeUserWithSiren()),
    clientRepo: ClientRepository = StubClientRepository(makeClient()),
    invoiceRepo: InvoiceRepository = StubInvoiceRepository(makeDraftInvoice()),
    pdfPort: PdfPort = StubPdfPort(),
    emailPort: EmailPort = StubEmailPort(),
    dispatcher: EventDispatcher = EventDispatcher(),
): SendInvoice = SendInvoice(userRepo, clientRepo, invoiceRepo, pdfPort, emailPort, dispatcher)

private fun makeCommand(invoiceId: InvoiceId = INVOICE_ID): SendInvoiceCommand =
    SendInvoiceCommand(userId = USER_ID, invoiceId = invoiceId, plainIban = null)

class SendInvoiceTest {
    @Test
    fun `transitions draft invoice to Sent and returns result`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepository(makeDraftInvoice())
            val result = makeUseCase(invoiceRepo = invoiceRepo).execute(makeCommand())

            assertIs<DomainResult.Ok<SendInvoiceResult>>(result)
            assertIs<InvoiceStatus.Sent>(result.value.invoice.status)
            assertIs<InvoiceStatus.Sent>(invoiceRepo.store[INVOICE_ID]!!.status)
        }

    @Test
    fun `sends email to client address with PDF and correct filename`() =
        runBlocking {
            val emailPort = StubEmailPort()
            makeUseCase(emailPort = emailPort).execute(makeCommand())

            assertEquals(1, emailPort.calls.size)
            val (to, subject, filename) = emailPort.calls.first()
            assertEquals("client@example.com", to)
            assertEquals("Facture F-2026-03-001", subject)
            assertEquals("F-2026-03-001.pdf", filename)
        }

    @Test
    fun `PDF is included in result`() =
        runBlocking {
            val result = makeUseCase().execute(makeCommand())

            assertIs<DomainResult.Ok<SendInvoiceResult>>(result)
            assertContentEquals(FAKE_PDF, result.value.pdf)
        }

    @Test
    fun `dispatches InvoiceSent event`() =
        runBlocking {
            val dispatched = mutableListOf<DomainEvent>()
            val dispatcher = EventDispatcher()
            dispatcher.register { dispatched.add(it) }

            makeUseCase(dispatcher = dispatcher).execute(makeCommand())

            assertEquals(1, dispatched.size)
            assertIs<DomainEvent.InvoiceSent>(dispatched.first())
        }

    @Test
    fun `returns SirenRequired error when user has no SIREN`() =
        runBlocking {
            val result =
                makeUseCase(userRepo = StubUserRepository(makeUserWithoutSiren())).execute(makeCommand())

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SirenRequired>(result.error)
        }

    @Test
    fun `returns error when user not found`() =
        runBlocking {
            val result = makeUseCase(userRepo = StubUserRepository()).execute(makeCommand())

            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `returns error when invoice not found`() =
        runBlocking {
            val result =
                makeUseCase(invoiceRepo = StubInvoiceRepository()).execute(makeCommand(InvoiceId("missing")))

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotFound>(result.error)
        }

    @Test
    fun `returns error when client has no email`() =
        runBlocking {
            val result =
                makeUseCase(clientRepo = StubClientRepository(makeClient(email = null))).execute(makeCommand())

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ProfileIncomplete>(result.error)
        }

    @Test
    fun `returns error when invoice is not in Draft status`() =
        runBlocking {
            val result =
                makeUseCase(invoiceRepo = StubInvoiceRepository(makeSentInvoice())).execute(makeCommand())

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvalidTransition>(result.error)
        }

    @Test
    fun `returns email delivery error when email port fails`() =
        runBlocking {
            val emailError = DomainError.EmailDeliveryFailed(422, "invalid email")
            val result =
                makeUseCase(emailPort = StubEmailPort(DomainResult.Err(emailError))).execute(makeCommand())

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.EmailDeliveryFailed>(result.error)
        }
}
