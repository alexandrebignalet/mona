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
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.PaymentDelayDays
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

private val UD_USER_ID = UserId("u1")
private val UD_CLIENT_ID = ClientId("c1")
private val UD_INVOICE_ID = InvoiceId("inv1")
private val UD_ISSUE_DATE = LocalDate.of(2026, 3, 1)
private val UD_FAKE_PDF = byteArrayOf(0x50, 0x44, 0x46)

private fun makeUdUser(): User =
    User(
        id = UD_USER_ID,
        telegramId = 1L,
        email = Email("user@example.com"),
        name = "Alice Dupont",
        siren = null,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BNC,
        declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeUdClient(
    id: ClientId = UD_CLIENT_ID,
    name: String = "ACME Corp",
): Client =
    Client(
        id = id,
        userId = UD_USER_ID,
        name = name,
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeUdDraftInvoice(status: InvoiceStatus = InvoiceStatus.Draft): Invoice =
    Invoice(
        id = UD_INVOICE_ID,
        userId = UD_USER_ID,
        clientId = UD_CLIENT_ID,
        number = InvoiceNumber("F-2026-03-001"),
        status = status,
        issueDate = UD_ISSUE_DATE,
        dueDate = UD_ISSUE_DATE.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Coaching", BigDecimal.ONE, Cents(50000))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-01T00:00:00Z"),
    )

private class UdUserRepo(vararg users: User) : UserRepository {
    private val store = users.associateBy { it.id }

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {}

    override suspend fun findAllWithPeriodicity(): List<User> = emptyList()

    override suspend fun findAllWithoutSiren(): List<User> = emptyList()

    override suspend fun delete(userId: UserId) {}
}

private class UdClientRepo(vararg clients: Client) : ClientRepository {
    val store = clients.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {
        store[client.id] = client
    }

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }

    override suspend fun deleteByUser(userId: UserId) {}
}

private class UdInvoiceRepo(vararg invoices: Invoice) : InvoiceRepository {
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
    ): InvoiceNumber? = null

    override suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): CreditNoteNumber? = null

    override suspend fun findByUser(userId: UserId): List<Invoice> = store.values.filter { it.userId == userId }

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

    override suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice> = emptyList()

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = emptyList()

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = emptyList()

    override suspend fun anonymizeByUser(userId: UserId) {}
}

private class UdPdfPort : PdfPort {
    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Ok(UD_FAKE_PDF)

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Ok(UD_FAKE_PDF)
}

private fun makeUseCase(
    userRepo: UserRepository = UdUserRepo(makeUdUser()),
    clientRepo: ClientRepository = UdClientRepo(makeUdClient()),
    invoiceRepo: InvoiceRepository = UdInvoiceRepo(makeUdDraftInvoice()),
    pdfPort: PdfPort = UdPdfPort(),
    dispatcher: EventDispatcher = EventDispatcher(),
): UpdateDraft = UpdateDraft(userRepo, clientRepo, invoiceRepo, pdfPort, dispatcher)

class UpdateDraftTest {
    @Test
    fun `updates line items and returns new PDF`() =
        runBlocking {
            val newItems = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(90000)))
            val result =
                makeUseCase().execute(
                    UpdateDraftCommand(userId = UD_USER_ID, invoiceId = UD_INVOICE_ID, lineItems = newItems),
                )

            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(newItems, result.value.invoice.lineItems)
            assertEquals(Cents(90000), result.value.invoice.amountHt)
            assertContentEquals(UD_FAKE_PDF, result.value.pdf)
        }

    @Test
    fun `updates activity type`() =
        runBlocking {
            val result =
                makeUseCase().execute(
                    UpdateDraftCommand(
                        userId = UD_USER_ID,
                        invoiceId = UD_INVOICE_ID,
                        activityType = ActivityType.BIC_SERVICE,
                    ),
                )

            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(ActivityType.BIC_SERVICE, result.value.invoice.activityType)
        }

    @Test
    fun `updates issue date and shifts due date by existing delta`() =
        runBlocking {
            val newIssueDate = LocalDate.of(2026, 4, 1)
            val result =
                makeUseCase().execute(
                    UpdateDraftCommand(userId = UD_USER_ID, invoiceId = UD_INVOICE_ID, issueDate = newIssueDate),
                )

            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(newIssueDate, result.value.invoice.issueDate)
            // original delta was 30 days → new dueDate should be 30 days later
            assertEquals(newIssueDate.plusDays(30), result.value.invoice.dueDate)
        }

    @Test
    fun `updates payment delay and recomputes due date`() =
        runBlocking {
            val result =
                makeUseCase().execute(
                    UpdateDraftCommand(
                        userId = UD_USER_ID,
                        invoiceId = UD_INVOICE_ID,
                        paymentDelay = PaymentDelayDays(15),
                    ),
                )

            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(UD_ISSUE_DATE.plusDays(15), result.value.invoice.dueDate)
        }

    @Test
    fun `resolves existing client by name when clientName provided`() =
        runBlocking {
            val clientRepo = UdClientRepo(makeUdClient(name = "ACME Corp"))
            val result =
                makeUseCase(clientRepo = clientRepo).execute(
                    UpdateDraftCommand(userId = UD_USER_ID, invoiceId = UD_INVOICE_ID, clientName = "ACME Corp"),
                )

            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(UD_CLIENT_ID, result.value.invoice.clientId)
            assertEquals(1, clientRepo.store.size) // no new client created
        }

    @Test
    fun `creates new client when clientName not found`() =
        runBlocking {
            val clientRepo = UdClientRepo(makeUdClient(name = "ACME Corp"))
            val result =
                makeUseCase(clientRepo = clientRepo).execute(
                    UpdateDraftCommand(userId = UD_USER_ID, invoiceId = UD_INVOICE_ID, clientName = "Nouveau Client"),
                )

            assertIs<DomainResult.Ok<UpdateDraftResult>>(result)
            assertEquals(2, clientRepo.store.size)
            assertNotNull(clientRepo.store.values.find { it.name == "Nouveau Client" })
        }

    @Test
    fun `persists updated invoice`() =
        runBlocking {
            val invoiceRepo = UdInvoiceRepo(makeUdDraftInvoice())
            val newItems = listOf(LineItem("New Service", BigDecimal.ONE, Cents(120000)))
            makeUseCase(invoiceRepo = invoiceRepo).execute(
                UpdateDraftCommand(userId = UD_USER_ID, invoiceId = UD_INVOICE_ID, lineItems = newItems),
            )

            val saved = invoiceRepo.store[UD_INVOICE_ID]
            assertNotNull(saved)
            assertEquals(newItems, saved.lineItems)
        }

    @Test
    fun `returns InvoiceNotFound when invoice does not exist`() =
        runBlocking {
            val result =
                makeUseCase(invoiceRepo = UdInvoiceRepo()).execute(
                    UpdateDraftCommand(userId = UD_USER_ID, invoiceId = InvoiceId("missing")),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotFound>(result.error)
        }

    @Test
    fun `returns ProfileIncomplete when user does not exist`() =
        runBlocking {
            val result =
                makeUseCase(userRepo = UdUserRepo()).execute(
                    UpdateDraftCommand(userId = UD_USER_ID, invoiceId = UD_INVOICE_ID),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ProfileIncomplete>(result.error)
        }

    @Test
    fun `returns InvalidTransition when invoice is Sent`() =
        runBlocking {
            val invoiceRepo = UdInvoiceRepo(makeUdDraftInvoice(status = InvoiceStatus.Sent))
            val result =
                makeUseCase(invoiceRepo = invoiceRepo).execute(
                    UpdateDraftCommand(
                        userId = UD_USER_ID,
                        invoiceId = UD_INVOICE_ID,
                        lineItems = listOf(LineItem("x", BigDecimal.ONE, Cents(1000))),
                    ),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvalidTransition>(result.error)
        }

    @Test
    fun `returns EmptyLineItems when new line items list is empty`() =
        runBlocking {
            val result =
                makeUseCase().execute(
                    UpdateDraftCommand(userId = UD_USER_ID, invoiceId = UD_INVOICE_ID, lineItems = emptyList()),
                )

            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.EmptyLineItems>(result.error)
        }
}
