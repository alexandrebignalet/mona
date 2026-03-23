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
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

private val FAKE_PDF = byteArrayOf(0x50, 0x44, 0x46) // "PDF"

private class InMemoryUserRepository(vararg users: User) : UserRepository {
    private val store = users.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {
        store[user.id] = user
    }

    override suspend fun findAllWithPeriodicity(): List<User> = store.values.filter { it.declarationPeriodicity != null }
}

private class InMemoryClientRepository : ClientRepository {
    val store = mutableMapOf<ClientId, Client>()

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

private class InMemoryInvoiceRepository : InvoiceRepository {
    val store = mutableMapOf<InvoiceId, Invoice>()

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
    ): List<Invoice> = store.values.filter { it.clientId == clientId && it.amountHt == amountHt && !it.issueDate.isBefore(since) }
}

private class FakePdfPort : PdfPort {
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

private fun makeUser(id: UserId = UserId("u1")): User =
    User(
        id = id,
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

private fun makeCommand(userId: UserId = UserId("u1")): CreateInvoiceCommand =
    CreateInvoiceCommand(
        userId = userId,
        clientName = "ACME Corp",
        lineItems = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(80000))),
        issueDate = LocalDate.of(2026, 3, 22),
        activityType = ActivityType.BNC,
        paymentDelay = PaymentDelayDays(30),
    )

class CreateInvoiceTest {
    private val userId = UserId("u1")
    private val user = makeUser(userId)

    @Test
    fun `creates invoice and returns Created result`() =
        runBlocking {
            val invoiceRepo = InMemoryInvoiceRepository()
            val clientRepo = InMemoryClientRepository()
            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(user),
                    clientRepo,
                    invoiceRepo,
                    FakePdfPort(),
                    EventDispatcher(),
                )

            val result = useCase.execute(makeCommand())

            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            val created = assertIs<CreateInvoiceResult.Created>(result.value)
            assertIs<InvoiceStatus.Draft>(created.invoice.status)
            assertEquals(InvoiceNumber("F-2026-03-001"), created.invoice.number)
            assertEquals(Cents(80000), created.invoice.amountHt)
            assertNotNull(invoiceRepo.store[created.invoice.id])
        }

    @Test
    fun `creates new client when name not found`() =
        runBlocking {
            val clientRepo = InMemoryClientRepository()
            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(user),
                    clientRepo,
                    InMemoryInvoiceRepository(),
                    FakePdfPort(),
                    EventDispatcher(),
                )

            val result = useCase.execute(makeCommand())

            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            assertEquals(1, clientRepo.store.size)
            assertEquals("ACME Corp", clientRepo.store.values.first().name)
        }

    @Test
    fun `reuses existing client when name matches`() =
        runBlocking {
            val clientRepo = InMemoryClientRepository()
            val existingClient =
                Client(
                    id = ClientId("existing-c"),
                    userId = userId,
                    name = "ACME Corp",
                    email = null,
                    address = null,
                    companyName = null,
                    siret = null,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            clientRepo.save(existingClient)

            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(user),
                    clientRepo,
                    InMemoryInvoiceRepository(),
                    FakePdfPort(),
                    EventDispatcher(),
                )

            val result = useCase.execute(makeCommand())

            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            // No new client created
            assertEquals(1, clientRepo.store.size)
            assertEquals(ClientId("existing-c"), clientRepo.store.values.first().id)
        }

    @Test
    fun `assigns sequential invoice numbers`() =
        runBlocking {
            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(user),
                    InMemoryClientRepository(),
                    InMemoryInvoiceRepository(),
                    FakePdfPort(),
                    EventDispatcher(),
                )

            val cmd1 = makeCommand()
            val cmd2 =
                makeCommand().copy(
                    clientName = "Another Client",
                    lineItems = listOf(LineItem("Work", BigDecimal.ONE, Cents(50000))),
                )
            val r1 = useCase.execute(cmd1)
            val r2 = useCase.execute(cmd2)

            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r1)
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r2)
            val inv1 = (r1.value as CreateInvoiceResult.Created).invoice
            val inv2 = (r2.value as CreateInvoiceResult.Created).invoice
            assertEquals(InvoiceNumber("F-2026-03-001"), inv1.number)
            assertEquals(InvoiceNumber("F-2026-03-002"), inv2.number)
        }

    @Test
    fun `returns DuplicateWarning when same client and amount exist within 48h`() =
        runBlocking {
            val invoiceRepo = InMemoryInvoiceRepository()
            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(user),
                    InMemoryClientRepository(),
                    invoiceRepo,
                    FakePdfPort(),
                    EventDispatcher(),
                )

            // Create first invoice
            val r1 = useCase.execute(makeCommand())
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r1)
            val firstNumber = (r1.value as CreateInvoiceResult.Created).invoice.number

            // Create second identical invoice same day
            val r2 = useCase.execute(makeCommand())
            assertIs<DomainResult.Ok<CreateInvoiceResult>>(r2)
            val warning = assertIs<CreateInvoiceResult.DuplicateWarning>(r2.value)
            assertEquals(firstNumber, warning.existingNumber)
        }

    @Test
    fun `returns Err when user not found`() =
        runBlocking {
            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(),
                    InMemoryClientRepository(),
                    InMemoryInvoiceRepository(),
                    FakePdfPort(),
                    EventDispatcher(),
                )

            val result = useCase.execute(makeCommand())

            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `returns Err when line items are empty`() =
        runBlocking {
            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(user),
                    InMemoryClientRepository(),
                    InMemoryInvoiceRepository(),
                    FakePdfPort(),
                    EventDispatcher(),
                )
            val command = makeCommand().copy(lineItems = emptyList())

            val result = useCase.execute(command)

            assertIs<DomainResult.Err>(result)
        }

    @Test
    fun `generated PDF is included in result`() =
        runBlocking {
            val useCase =
                CreateInvoice(
                    InMemoryUserRepository(user),
                    InMemoryClientRepository(),
                    InMemoryInvoiceRepository(),
                    FakePdfPort(),
                    EventDispatcher(),
                )

            val result = useCase.execute(makeCommand())

            assertIs<DomainResult.Ok<CreateInvoiceResult>>(result)
            val created = assertIs<CreateInvoiceResult.Created>(result.value)
            assertEquals(FAKE_PDF.toList(), created.pdf.toList())
        }
}
