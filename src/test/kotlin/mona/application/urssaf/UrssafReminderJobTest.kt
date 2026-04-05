package mona.application.urssaf

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.Email
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.PaymentDelayDays
import mona.domain.model.UrssafReminderRecord
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.Button
import mona.domain.port.ConversationMessage
import mona.domain.port.ConversationRepository
import mona.domain.port.IncomingCallback
import mona.domain.port.IncomingMessage
import mona.domain.port.InvoiceRepository
import mona.domain.port.MenuItem
import mona.domain.port.MessageRole
import mona.domain.port.MessagingPort
import mona.domain.port.UrssafReminderRepository
import mona.domain.port.UserRepository
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Dates: QUARTERLY deadline for Q1 2026 is April 30.
// D-7 = April 23, D-1 = April 29.
private val URJ_D7_DATE = LocalDate.of(2026, 4, 23)
private val URJ_D1_DATE = LocalDate.of(2026, 4, 29)
private val URJ_NEUTRAL_DATE = LocalDate.of(2026, 3, 23) // 38 days before deadline

private val URJ_USER_ID = UserId("user-urssaf")

private fun makeUser(
    periodicity: DeclarationPeriodicity? = DeclarationPeriodicity.QUARTERLY,
    name: String? = "Sophie Martin",
): User =
    User(
        id = URJ_USER_ID,
        telegramId = 12345L,
        email = Email("sophie@example.com"),
        name = name,
        siren = null,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BNC,
        declarationPeriodicity = periodicity,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
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

    override suspend fun delete(userId: UserId) {}
}

private class StubInvoiceRepository(
    private val paidSnapshots: List<PaidInvoiceSnapshot> = emptyList(),
    private val creditNoteSnapshots: List<CreditNoteSnapshot> = emptyList(),
) : InvoiceRepository {
    var capturedPeriod: DeclarationPeriod? = null

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

    override suspend fun findByUser(userId: UserId): List<Invoice> = emptyList()

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> = emptyList()

    override suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot> {
        capturedPeriod = period
        return paidSnapshots
    }

    override suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot> = creditNoteSnapshots

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

private class StubUrssafReminderRepository : UrssafReminderRepository {
    private val records = mutableMapOf<Pair<String, String>, UrssafReminderRecord>()
    val saved = mutableListOf<UrssafReminderRecord>()

    fun seed(record: UrssafReminderRecord) {
        records[record.userId.value to record.periodKey] = record
    }

    override suspend fun findByUserAndPeriod(
        userId: UserId,
        periodKey: String,
    ): UrssafReminderRecord? = records[userId.value to periodKey]

    override suspend fun save(record: UrssafReminderRecord) {
        records[record.userId.value to record.periodKey] = record
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
    ): List<ConversationMessage> = messages.takeLast(limit)

    override suspend fun deleteByUser(userId: UserId) {}
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

    override suspend fun onCallback(handler: suspend (IncomingCallback) -> Unit) {}

    override suspend fun answerCallback(
        callbackQueryId: String,
        text: String?,
    ) {}
}

private fun makeJob(
    users: List<User> = listOf(makeUser()),
    paidSnapshots: List<PaidInvoiceSnapshot> = emptyList(),
    reminderRepo: StubUrssafReminderRepository = StubUrssafReminderRepository(),
    conversationMessages: List<ConversationMessage> = emptyList(),
    messaging: StubMessagingPort = StubMessagingPort(),
): Triple<UrssafReminderJob, StubUrssafReminderRepository, StubMessagingPort> {
    val job =
        UrssafReminderJob(
            userRepository = StubUserRepository(users),
            invoiceRepository = StubInvoiceRepository(paidSnapshots),
            reminderRepository = reminderRepo,
            conversationRepository = StubConversationRepository(conversationMessages),
            messagingPort = messaging,
        )
    return Triple(job, reminderRepo, messaging)
}

class UrssafReminderJobTest {
    @Test
    fun `no messages when no users with periodicity`() =
        runBlocking {
            val (job, _, messaging) = makeJob(users = emptyList())
            job.execute(URJ_D7_DATE)
            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `D-7 reminder sent 7 days before quarterly deadline with revenue`() =
        runBlocking {
            val snapshot =
                PaidInvoiceSnapshot(
                    invoiceId = InvoiceId("i1"),
                    amountHt = Cents(480000L),
                    paidDate = LocalDate.of(2026, 3, 15),
                    activityType = ActivityType.BNC,
                )
            val (job, reminderRepo, messaging) = makeJob(paidSnapshots = listOf(snapshot))

            job.execute(URJ_D7_DATE)

            assertEquals(1, messaging.sentMessages.size)
            val (userId, text) = messaging.sentMessages.first()
            assertEquals(URJ_USER_ID, userId)
            assertTrue(text.contains("7 jours"))
            assertTrue(text.replace('\u202F', ' ').contains("4 800€"))
            assertTrue(text.contains("autoentrepreneur.urssaf.fr"))
            assertTrue(text.contains("OK"))
            assertNotNull(reminderRepo.saved.firstOrNull()?.d7SentAt)
        }

    @Test
    fun `D-1 reminder sent day before deadline when D-7 not acknowledged`() =
        runBlocking {
            val d7SentAt = Instant.parse("2026-04-23T10:00:00Z")
            val reminderRepo = StubUrssafReminderRepository()
            reminderRepo.seed(UrssafReminderRecord(URJ_USER_ID, "2026-Q1", d7SentAt = d7SentAt))
            // No user messages after D-7 → not acknowledged
            val (job, _, messaging) = makeJob(reminderRepo = reminderRepo)

            job.execute(URJ_D1_DATE)

            assertEquals(1, messaging.sentMessages.size)
            val (_, text) = messaging.sentMessages.first()
            assertTrue(text.contains("demain"))
            assertTrue(text.contains("autoentrepreneur.urssaf.fr"))
        }

    @Test
    fun `D-1 skipped when user sent message after D-7 acknowledgment`() =
        runBlocking {
            val d7SentAt = Instant.parse("2026-04-23T10:00:00Z")
            val reminderRepo = StubUrssafReminderRepository()
            reminderRepo.seed(UrssafReminderRecord(URJ_USER_ID, "2026-Q1", d7SentAt = d7SentAt))
            // User replied after D-7 → acknowledged
            val userMsg =
                ConversationMessage(
                    id = "msg-1",
                    userId = URJ_USER_ID,
                    role = MessageRole.USER,
                    content = "OK c'est fait",
                    createdAt = Instant.parse("2026-04-24T09:00:00Z"),
                )
            val (job, _, messaging) = makeJob(reminderRepo = reminderRepo, conversationMessages = listOf(userMsg))

            job.execute(URJ_D1_DATE)

            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `D-7 not resent when already sent (idempotent)`() =
        runBlocking {
            val reminderRepo = StubUrssafReminderRepository()
            reminderRepo.seed(
                UrssafReminderRecord(
                    URJ_USER_ID,
                    "2026-Q1",
                    d7SentAt = Instant.parse("2026-04-23T10:00:00Z"),
                ),
            )
            val (job, _, messaging) = makeJob(reminderRepo = reminderRepo)

            job.execute(URJ_D7_DATE)

            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `D-1 not resent when already sent (idempotent)`() =
        runBlocking {
            val reminderRepo = StubUrssafReminderRepository()
            reminderRepo.seed(
                UrssafReminderRecord(
                    URJ_USER_ID,
                    "2026-Q1",
                    d7SentAt = Instant.parse("2026-04-23T10:00:00Z"),
                    d1SentAt = Instant.parse("2026-04-29T10:00:00Z"),
                ),
            )
            val (job, _, messaging) = makeJob(reminderRepo = reminderRepo)

            job.execute(URJ_D1_DATE)

            assertTrue(messaging.sentMessages.isEmpty())
        }

    @Test
    fun `revenue breakdown shown for mixed activity types`() =
        runBlocking {
            val snapshots =
                listOf(
                    PaidInvoiceSnapshot(InvoiceId("i1"), Cents(320000L), LocalDate.of(2026, 2, 10), ActivityType.BNC),
                    PaidInvoiceSnapshot(InvoiceId("i2"), Cents(160000L), LocalDate.of(2026, 3, 5), ActivityType.BIC_SERVICE),
                )
            val (job, _, messaging) = makeJob(paidSnapshots = snapshots)

            job.execute(URJ_D7_DATE)

            assertEquals(1, messaging.sentMessages.size)
            val text = messaging.sentMessages.first().second
            assertTrue(text.contains("BNC"))
            assertTrue(text.contains("BIC"))
            assertTrue(text.replace('\u202F', ' ').contains("3 200€"))
            assertTrue(text.replace('\u202F', ' ').contains("1 600€"))
        }

    @Test
    fun `revenue period uses cash basis (paid_date within declaration period)`() =
        runBlocking {
            val invoiceRepo = StubInvoiceRepository()
            val job =
                UrssafReminderJob(
                    userRepository = StubUserRepository(listOf(makeUser())),
                    invoiceRepository = invoiceRepo,
                    reminderRepository = StubUrssafReminderRepository(),
                    conversationRepository = StubConversationRepository(),
                    messagingPort = StubMessagingPort(),
                )

            job.execute(URJ_D7_DATE)

            // For Q1 (deadline April 30), period should be Jan 1 – Mar 31
            val period = invoiceRepo.capturedPeriod
            assertNotNull(period)
            assertEquals(LocalDate.of(2026, 1, 1), period.start)
            assertEquals(LocalDate.of(2026, 3, 31), period.endInclusive)
        }

    @Test
    fun `D-7 reminder uses correct period label for monthly periodicity`() =
        runBlocking {
            // For monthly, using April 23 as today:
            // today.minusMonths(1) = March 23
            // nextDeclarationDeadline(MONTHLY, March 23) = April 30
            // daysUntil = 7 → D-7 fires
            // periodLabel = "mois de mars"
            val user = makeUser(periodicity = DeclarationPeriodicity.MONTHLY)
            val (job, _, messaging) = makeJob(users = listOf(user))

            job.execute(URJ_D7_DATE) // April 23

            assertEquals(1, messaging.sentMessages.size)
            val text = messaging.sentMessages.first().second
            assertTrue(text.contains("mars"), "Expected 'mars' in: $text")
        }
}
