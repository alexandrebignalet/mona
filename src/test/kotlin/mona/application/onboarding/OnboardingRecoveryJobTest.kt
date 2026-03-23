package mona.application.onboarding

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.Email
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.OnboardingReminderRecord
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.PaymentDelayDays
import mona.domain.model.Siren
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.Button
import mona.domain.port.ConversationMessage
import mona.domain.port.ConversationRepository
import mona.domain.port.IncomingMessage
import mona.domain.port.InvoiceRepository
import mona.domain.port.MenuItem
import mona.domain.port.MessageRole
import mona.domain.port.MessagingPort
import mona.domain.port.OnboardingReminderRepository
import mona.domain.port.UserRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ORJ_TODAY = LocalDate.of(2026, 3, 23)
private val ORJ_USER_ID = UserId("user-onboarding")
private val ORJ_USER_ID_2 = UserId("user-onboarding-2")

private fun makeUser(
    id: UserId = ORJ_USER_ID,
    siren: Siren? = null,
): User =
    User(
        id = id,
        telegramId = 99999L,
        email = Email("user@example.com"),
        name = "Marie Dupont",
        siren = siren,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BNC,
        declarationPeriodicity = null,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeDraft(
    id: String = "inv-1",
    userId: UserId = ORJ_USER_ID,
    createdAt: Instant,
): Invoice =
    Invoice(
        id = InvoiceId(id),
        userId = userId,
        clientId = ClientId("client-1"),
        number = InvoiceNumber("F-2026-03-001"),
        status = InvoiceStatus.Draft,
        issueDate = ORJ_TODAY,
        dueDate = ORJ_TODAY.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(80000L))),
        pdfPath = null,
        creditNote = null,
        createdAt = createdAt,
    )

// --- Stub implementations ---

private class StubUserRepository(
    private val users: List<User> = emptyList(),
) : UserRepository {
    override suspend fun findById(id: UserId): User? = users.firstOrNull { it.id == id }

    override suspend fun findByTelegramId(telegramId: Long): User? = users.firstOrNull { it.telegramId == telegramId }

    override suspend fun save(user: User) {}

    override suspend fun findAllWithPeriodicity(): List<User> = users.filter { it.declarationPeriodicity != null }

    override suspend fun findAllWithoutSiren(): List<User> = users.filter { it.siren == null }
}

private class StubInvoiceRepository(
    private val draftsByUser: Map<UserId, List<Invoice>> = emptyMap(),
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

    override suspend fun findByUser(userId: UserId): List<Invoice> = draftsByUser[userId] ?: emptyList()

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> = (draftsByUser[userId] ?: emptyList()).filter { it.status == status }

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

private class StubOnboardingReminderRepository : OnboardingReminderRepository {
    private val records = mutableMapOf<UserId, OnboardingReminderRecord>()
    val saved = mutableListOf<OnboardingReminderRecord>()

    fun seed(record: OnboardingReminderRecord) {
        records[record.userId] = record
    }

    override suspend fun findByUser(userId: UserId): OnboardingReminderRecord? = records[userId]

    override suspend fun save(record: OnboardingReminderRecord) {
        records[record.userId] = record
        saved += record
    }
}

private class StubConversationRepository(
    private val messages: List<ConversationMessage> = emptyList(),
) : ConversationRepository {
    override suspend fun save(message: ConversationMessage) {}

    override suspend fun findRecent(
        userId: UserId,
        limit: Int,
    ): List<ConversationMessage> = messages.filter { it.userId == userId }.takeLast(limit)
}

private class StubMessagingPort : MessagingPort {
    val sentMessages = mutableListOf<Pair<UserId, String>>()

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        sentMessages += userId to text
    }

    override suspend fun sendDocument(
        userId: UserId,
        fileBytes: ByteArray,
        fileName: String,
        caption: String?,
    ) {}

    override suspend fun sendButtons(
        userId: UserId,
        text: String,
        buttons: List<Button>,
    ) {}

    override suspend fun setPersistentMenu(
        userId: UserId,
        items: List<MenuItem>,
    ) {}

    override suspend fun onMessage(handler: suspend (IncomingMessage) -> Unit) {}
}

private fun makeJob(
    users: List<User> = listOf(makeUser()),
    draftsByUser: Map<UserId, List<Invoice>> = emptyMap(),
    reminderRepo: StubOnboardingReminderRepository = StubOnboardingReminderRepository(),
    conversationMessages: List<ConversationMessage> = emptyList(),
    messaging: StubMessagingPort = StubMessagingPort(),
): Triple<OnboardingRecoveryJob, StubOnboardingReminderRepository, StubMessagingPort> {
    val job =
        OnboardingRecoveryJob(
            userRepository = StubUserRepository(users),
            invoiceRepository = StubInvoiceRepository(draftsByUser),
            reminderRepository = reminderRepo,
            conversationRepository = StubConversationRepository(conversationMessages),
            messagingPort = messaging,
        )
    return Triple(job, reminderRepo, messaging)
}

class OnboardingRecoveryJobTest {
    @Test
    fun `no messages when user has no draft invoices`() =
        runBlocking {
            val (job, _, messaging) = makeJob(draftsByUser = emptyMap())
            job.execute(ORJ_TODAY)
            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `no messages when user has SIREN even with drafts`() =
        runBlocking {
            val userWithSiren = makeUser(siren = Siren("123456789"))
            val draft = makeDraft(createdAt = Instant.parse("2026-03-20T10:00:00Z"))
            val (job, _, messaging) =
                makeJob(
                    users = listOf(userWithSiren),
                    draftsByUser = mapOf(ORJ_USER_ID to listOf(draft)),
                )
            job.execute(ORJ_TODAY)
            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `Reminder 1 sent when draft is 1+ days old and not yet reminded`() =
        runBlocking {
            // Draft created 2 days ago
            val draft = makeDraft(createdAt = Instant.parse("2026-03-21T10:00:00Z"))
            val (job, reminderRepo, messaging) =
                makeJob(draftsByUser = mapOf(ORJ_USER_ID to listOf(draft)))

            job.execute(ORJ_TODAY)

            assertEquals(1, messaging.sentMessages.size)
            val (userId, text) = messaging.sentMessages.first()
            assertEquals(ORJ_USER_ID, userId)
            assertTrue(text.contains("brouillon"))
            assertTrue(text.contains("SIREN"))
            assertNotNull(reminderRepo.saved.firstOrNull()?.r1SentAt)
            assertNull(reminderRepo.saved.firstOrNull()?.r2SentAt)
        }

    @Test
    fun `Reminder 2 sent when draft is 3+ days old, R1 sent, and not acknowledged`() =
        runBlocking {
            val r1SentAt = Instant.parse("2026-03-20T11:00:00Z")
            val reminderRepo = StubOnboardingReminderRepository()
            reminderRepo.seed(OnboardingReminderRecord(ORJ_USER_ID, r1SentAt = r1SentAt))
            // Draft created 4 days ago
            val draft = makeDraft(createdAt = Instant.parse("2026-03-19T10:00:00Z"))
            val (job, _, messaging) =
                makeJob(
                    draftsByUser = mapOf(ORJ_USER_ID to listOf(draft)),
                    reminderRepo = reminderRepo,
                )

            job.execute(ORJ_TODAY)

            assertEquals(1, messaging.sentMessages.size)
            val (userId, text) = messaging.sentMessages.first()
            assertEquals(ORJ_USER_ID, userId)
            assertTrue(text.contains("nom et ta ville"))
            assertNotNull(reminderRepo.saved.firstOrNull()?.r2SentAt)
        }

    @Test
    fun `Reminder 2 skipped when user responded to Reminder 1`() =
        runBlocking {
            val r1SentAt = Instant.parse("2026-03-20T11:00:00Z")
            val reminderRepo = StubOnboardingReminderRepository()
            reminderRepo.seed(OnboardingReminderRecord(ORJ_USER_ID, r1SentAt = r1SentAt))
            // Draft created 4 days ago
            val draft = makeDraft(createdAt = Instant.parse("2026-03-19T10:00:00Z"))
            // User replied after R1
            val userMsg =
                ConversationMessage(
                    id = "msg-1",
                    userId = ORJ_USER_ID,
                    role = MessageRole.USER,
                    content = "mon SIREN c'est 123456789",
                    createdAt = Instant.parse("2026-03-21T09:00:00Z"),
                )
            val (job, _, messaging) =
                makeJob(
                    draftsByUser = mapOf(ORJ_USER_ID to listOf(draft)),
                    reminderRepo = reminderRepo,
                    conversationMessages = listOf(userMsg),
                )

            job.execute(ORJ_TODAY)

            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `Reminder 1 not resent when already sent (idempotent)`() =
        runBlocking {
            val reminderRepo = StubOnboardingReminderRepository()
            reminderRepo.seed(
                OnboardingReminderRecord(
                    ORJ_USER_ID,
                    r1SentAt = Instant.parse("2026-03-22T11:00:00Z"),
                ),
            )
            val draft = makeDraft(createdAt = Instant.parse("2026-03-21T10:00:00Z"))
            val (job, _, messaging) =
                makeJob(
                    draftsByUser = mapOf(ORJ_USER_ID to listOf(draft)),
                    reminderRepo = reminderRepo,
                )

            job.execute(ORJ_TODAY)

            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `Reminder 2 not resent when already sent (idempotent)`() =
        runBlocking {
            val reminderRepo = StubOnboardingReminderRepository()
            reminderRepo.seed(
                OnboardingReminderRecord(
                    ORJ_USER_ID,
                    r1SentAt = Instant.parse("2026-03-20T11:00:00Z"),
                    r2SentAt = Instant.parse("2026-03-22T11:00:00Z"),
                ),
            )
            val draft = makeDraft(createdAt = Instant.parse("2026-03-19T10:00:00Z"))
            val (job, _, messaging) =
                makeJob(
                    draftsByUser = mapOf(ORJ_USER_ID to listOf(draft)),
                    reminderRepo = reminderRepo,
                )

            job.execute(ORJ_TODAY)

            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `multiple users each get their own independent reminders`() =
        runBlocking {
            val user2 = makeUser(id = ORJ_USER_ID_2)
            val draft1 = makeDraft(id = "inv-1", userId = ORJ_USER_ID, createdAt = Instant.parse("2026-03-21T10:00:00Z"))
            val draft2 = makeDraft(id = "inv-2", userId = ORJ_USER_ID_2, createdAt = Instant.parse("2026-03-21T10:00:00Z"))
            val (job, reminderRepo, messaging) =
                makeJob(
                    users = listOf(makeUser(), user2),
                    draftsByUser =
                        mapOf(
                            ORJ_USER_ID to listOf(draft1),
                            ORJ_USER_ID_2 to listOf(draft2),
                        ),
                )

            job.execute(ORJ_TODAY)

            assertEquals(2, messaging.sentMessages.size)
            assertEquals(setOf(ORJ_USER_ID, ORJ_USER_ID_2), messaging.sentMessages.map { it.first }.toSet())
            assertEquals(2, reminderRepo.saved.size)
        }
}
