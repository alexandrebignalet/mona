package mona.integration

import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainEvent
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumbering
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PaymentMethod
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.Siret
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.Button
import mona.domain.port.IncomingCallback
import mona.domain.port.IncomingMessage
import mona.domain.port.MenuItem
import mona.domain.port.MessagingPort
import mona.domain.port.PdfPort
import mona.infrastructure.crypto.IbanCryptoAdapter
import mona.infrastructure.db.DatabaseFactory
import mona.infrastructure.db.ExposedClientRepository
import mona.infrastructure.db.ExposedConversationRepository
import mona.infrastructure.db.ExposedInvoiceRepository
import mona.infrastructure.db.ExposedOnboardingReminderRepository
import mona.infrastructure.db.ExposedUrssafReminderRepository
import mona.infrastructure.db.ExposedUserRepository
import mona.infrastructure.db.ExposedVatAlertRepository
import mona.infrastructure.email.HttpExecutor
import mona.infrastructure.email.ResendEmailAdapter
import mona.infrastructure.email.ResendResult
import mona.infrastructure.pdf.PdfGenerator
import mona.infrastructure.sirene.SireneApiClient
import mona.infrastructure.sirene.SireneHttpExecutor
import mona.infrastructure.sirene.SireneHttpResponse
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

// ── Fixture loading ───────────────────────────────────────────────────────────

internal fun loadFixture(path: String): String =
    object {}.javaClass.classLoader
        ?.getResourceAsStream("fixtures/$path")
        ?.bufferedReader()
        ?.readText()
        ?: error("Fixture not found on classpath: fixtures/$path")

// ── Fake: MessagingPort ───────────────────────────────────────────────────────

class FakeMessagingPort : MessagingPort {
    data class SentMessage(val userId: UserId, val text: String)

    data class SentDocument(
        val userId: UserId,
        val bytes: ByteArray,
        val fileName: String,
        val caption: String?,
    )

    data class SentButtons(val userId: UserId, val text: String, val buttons: List<Button>)

    private val sentMessages = mutableListOf<SentMessage>()
    private val sentDocuments = mutableListOf<SentDocument>()
    private val sentButtonsList = mutableListOf<SentButtons>()

    fun lastMessage(): SentMessage? = sentMessages.lastOrNull()

    fun lastDocument(): SentDocument? = sentDocuments.lastOrNull()

    fun allMessages(): List<SentMessage> = sentMessages.toList()

    fun allDocuments(): List<SentDocument> = sentDocuments.toList()

    internal fun clear() {
        sentMessages.clear()
        sentDocuments.clear()
        sentButtonsList.clear()
    }

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        sentMessages += SentMessage(userId, text)
    }

    override suspend fun sendDocument(
        userId: UserId,
        fileBytes: ByteArray,
        fileName: String,
        caption: String?,
    ) {
        sentDocuments += SentDocument(userId, fileBytes, fileName, caption)
    }

    override suspend fun sendButtons(
        userId: UserId,
        text: String,
        buttons: List<Button>,
    ) {
        sentButtonsList += SentButtons(userId, text, buttons)
    }

    override suspend fun setPersistentMenu(
        userId: UserId,
        items: List<MenuItem>,
    ) = Unit

    override suspend fun onMessage(handler: suspend (IncomingMessage) -> Unit) = Unit

    override suspend fun onCallback(handler: suspend (IncomingCallback) -> Unit) = Unit

    override suspend fun answerCallback(
        callbackQueryId: String,
        text: String?,
    ) = Unit
}

// ── Fake: SireneHttpExecutor ──────────────────────────────────────────────────

internal enum class SireneScenario {
    LookupSuccess,
    LookupNotFound,
    LookupCeased,
    SearchSingleMatch,
    SearchMultipleMatches,
    SearchNoMatch,
    SearchMalformed,
}

internal class FakeSireneHttpExecutor : SireneHttpExecutor {
    var scenario: SireneScenario = SireneScenario.LookupSuccess
    var throwOnGet: Exception? = null

    override suspend fun get(url: String): SireneHttpResponse {
        throwOnGet?.let { throw it }
        return when (scenario) {
            SireneScenario.LookupSuccess -> SireneHttpResponse(200, loadFixture("sirene/lookup_success.json"))
            SireneScenario.LookupNotFound -> SireneHttpResponse(404, loadFixture("sirene/lookup_not_found.json"))
            SireneScenario.LookupCeased -> SireneHttpResponse(404, loadFixture("sirene/lookup_ceased.json"))
            SireneScenario.SearchSingleMatch -> SireneHttpResponse(200, loadFixture("sirene/search_single_match.json"))
            SireneScenario.SearchMultipleMatches ->
                SireneHttpResponse(200, loadFixture("sirene/search_multiple_matches.json"))
            SireneScenario.SearchNoMatch -> SireneHttpResponse(200, loadFixture("sirene/search_no_match.json"))
            SireneScenario.SearchMalformed -> SireneHttpResponse(200, loadFixture("sirene/search_malformed.json"))
        }
    }
}

// ── Fake: ResendHttpExecutor ──────────────────────────────────────────────────

internal enum class ResendScenario { Success, RateLimited, InvalidEmail }

internal class FakeResendHttpExecutor : HttpExecutor {
    var scenario: ResendScenario = ResendScenario.Success

    override suspend fun post(
        url: String,
        apiKey: String,
        jsonBody: String,
    ): ResendResult =
        when (scenario) {
            ResendScenario.Success -> ResendResult.Success
            ResendScenario.RateLimited -> ResendResult.Failure(429, loadFixture("resend/send_rate_limited.json"))
            ResendScenario.InvalidEmail -> ResendResult.Failure(422, loadFixture("resend/send_invalid_email.json"))
        }
}

// ── Test Event Collector ──────────────────────────────────────────────────────

class TestEventCollector {
    private val _events = mutableListOf<DomainEvent>()
    val events: List<DomainEvent> get() = _events.toList()

    fun capture(event: DomainEvent) {
        _events.add(event)
    }

    internal fun clear() {
        _events.clear()
    }
}

// ── Test constants ────────────────────────────────────────────────────────────

val TEST_USER_ID = UserId("test-user-001")
val TEST_USER_TELEGRAM_ID = 100000001L
val TEST_USER_MINIMAL_ID = UserId("test-user-002")
val TEST_USER_MINIMAL_TELEGRAM_ID = 100000002L
val TEST_CLIENT_ID = ClientId("test-client-001")
val TEST_SIREN = Siren("123456789")
val TEST_SIRET = Siret("12345678900012")
val TEST_CLIENT_SIRET = Siret("98765432100012")
val TEST_IBAN_PLAIN = "FR7630001007941234567890185"
val TEST_AES_KEY: ByteArray = ByteArray(32) { it.toByte() }
private val BASE_INSTANT: Instant = Instant.parse("2026-01-01T10:00:00Z")

// ── Integration test base ─────────────────────────────────────────────────────

abstract class IntegrationTestBase {
    protected lateinit var dbPath: String

    // Repositories — stateless adapters, safe to reuse across DB initialisations
    protected val userRepo = ExposedUserRepository()
    protected val clientRepo = ExposedClientRepository()
    protected val invoiceRepo = ExposedInvoiceRepository()
    protected val conversationRepo = ExposedConversationRepository()
    protected val urssafReminderRepo = ExposedUrssafReminderRepository()
    protected val onboardingReminderRepo = ExposedOnboardingReminderRepository()
    protected val vatAlertRepo = ExposedVatAlertRepository()

    // Fakes (re-created per test via property init — JUnit5 per-method lifecycle)
    protected val messagingPort = FakeMessagingPort()
    protected val pdfPort: PdfPort = PdfGenerator
    protected val cryptoPort = IbanCryptoAdapter(TEST_AES_KEY)
    protected val eventCollector = TestEventCollector()
    protected val eventDispatcher =
        EventDispatcher().also { dispatcher ->
            dispatcher.register { event -> eventCollector.capture(event) }
        }

    @Suppress("EXPOSED_PROPERTY_TYPE")
    protected val fakeSireneExecutor = FakeSireneHttpExecutor()

    @Suppress("EXPOSED_PROPERTY_TYPE")
    protected val fakeResendExecutor = FakeResendHttpExecutor()
    protected val sirenePort = SireneApiClient(httpExecutor = fakeSireneExecutor)
    protected val emailPort = ResendEmailAdapter(apiKey = "test-resend-key", httpExecutor = fakeResendExecutor)

    @BeforeTest
    fun setupDatabase() {
        dbPath = "test-integration-${UUID.randomUUID()}.db"
        DatabaseFactory.init(dbPath)
    }

    @AfterTest
    fun teardownDatabase() {
        java.io.File(dbPath).delete()
        java.io.File("$dbPath-wal").delete()
        java.io.File("$dbPath-shm").delete()
        messagingPort.clear()
        eventCollector.clear()
        fakeSireneExecutor.scenario = SireneScenario.LookupSuccess
        fakeSireneExecutor.throwOnGet = null
        fakeResendExecutor.scenario = ResendScenario.Success
    }

    // ── Pre-built domain objects ──────────────────────────────────────────────

    protected fun testUser(): User =
        User(
            id = TEST_USER_ID,
            telegramId = TEST_USER_TELEGRAM_ID,
            email = Email("user@example.com"),
            name = "Jean Dupont",
            siren = TEST_SIREN,
            siret = TEST_SIRET,
            address = PostalAddress("12 RUE DE LA PAIX", "75001", "PARIS"),
            ibanEncrypted = cryptoPort.encrypt(TEST_IBAN_PLAIN),
            activityType = ActivityType.BIC_SERVICE,
            declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
            confirmBeforeCreate = false,
            defaultPaymentDelayDays = PaymentDelayDays(30),
            createdAt = BASE_INSTANT,
        )

    protected fun testUserMinimal(): User =
        User(
            id = TEST_USER_MINIMAL_ID,
            telegramId = TEST_USER_MINIMAL_TELEGRAM_ID,
            email = null,
            name = null,
            siren = null,
            siret = null,
            address = null,
            ibanEncrypted = null,
            activityType = null,
            declarationPeriodicity = null,
            confirmBeforeCreate = false,
            defaultPaymentDelayDays = PaymentDelayDays(30),
            createdAt = BASE_INSTANT,
        )

    protected fun testClient(userId: UserId = TEST_USER_ID): Client =
        Client(
            id = TEST_CLIENT_ID,
            userId = userId,
            name = "ACME Corp",
            email = Email("acme@example.com"),
            address = PostalAddress("1 RUE DU CLIENT", "75008", "PARIS"),
            companyName = "ACME Corporation",
            siret = TEST_CLIENT_SIRET,
            createdAt = BASE_INSTANT,
        )

    // ── Persistence helpers ───────────────────────────────────────────────────

    /** Creates and persists a fully-onboarded user. */
    protected suspend fun createTestUser(): User {
        val user = testUser()
        userRepo.save(user)
        return user
    }

    /** Creates and persists a minimal (fresh) user. */
    protected suspend fun createTestUserMinimal(): User {
        val user = testUserMinimal()
        userRepo.save(user)
        return user
    }

    /** Creates and persists a client linked to the given user (defaults to TestUser). */
    protected suspend fun createTestClient(userId: UserId = TEST_USER_ID): Client {
        val client = testClient(userId)
        clientRepo.save(client)
        return client
    }

    /**
     * Creates and persists an invoice in the requested [status].
     *
     * Invoice number is assigned sequentially based on the current DB state for [issueDate]'s month.
     * Supported statuses: Draft, Sent, Overdue, Paid, Cancelled.
     */
    protected suspend fun createTestInvoice(
        userId: UserId,
        clientId: ClientId,
        status: InvoiceStatus,
        issueDate: LocalDate = LocalDate.of(2026, 4, 1),
        paymentDelay: PaymentDelayDays = PaymentDelayDays(30),
        amountCents: Long = 100000L,
        activityType: ActivityType = ActivityType.BIC_SERVICE,
    ): Invoice {
        val yearMonth = YearMonth.from(issueDate)
        val lastNumber = invoiceRepo.findLastNumberInMonth(userId, yearMonth)
        val number = (InvoiceNumbering.next(yearMonth, lastNumber) as DomainResult.Ok).value

        val lineItems = listOf(LineItem("Service de test", BigDecimal.ONE, Cents(amountCents)))
        val now = BASE_INSTANT

        val draft =
            (
                Invoice.create(
                    InvoiceId(UUID.randomUUID().toString()),
                    userId,
                    clientId,
                    number,
                    issueDate,
                    paymentDelay,
                    activityType,
                    lineItems,
                    now,
                ) as DomainResult.Ok
            ).value

        val invoice =
            when (status) {
                is InvoiceStatus.Draft -> draft
                is InvoiceStatus.Sent -> (draft.send(now) as DomainResult.Ok).value.invoice
                is InvoiceStatus.Overdue -> {
                    val sent = (draft.send(now) as DomainResult.Ok).value.invoice
                    (sent.markOverdue(now) as DomainResult.Ok).value.invoice
                }
                is InvoiceStatus.Paid -> {
                    val sent = (draft.send(now) as DomainResult.Ok).value.invoice
                    (
                        sent.markPaid(
                            issueDate.plusDays(10),
                            PaymentMethod.VIREMENT,
                            now,
                        ) as DomainResult.Ok
                    ).value.invoice
                }
                is InvoiceStatus.Cancelled -> (draft.cancel(null, now) as DomainResult.Ok).value.invoice
            }

        invoiceRepo.save(invoice)
        return invoice
    }

    /** Convenience: run a suspend block in a blocking context (for use in @BeforeTest). */
    protected fun setup(block: suspend () -> Unit) = runBlocking { block() }
}
