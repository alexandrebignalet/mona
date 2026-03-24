package mona.application.urssaf

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DomainEvent
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.PaymentMethod
import mona.domain.model.UserId
import mona.domain.model.VatAlertRecord
import mona.domain.port.Button
import mona.domain.port.IncomingMessage
import mona.domain.port.InvoiceRepository
import mona.domain.port.MenuItem
import mona.domain.port.MessagingPort
import mona.domain.port.VatAlertRepository
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CVT_USER_ID = UserId("user-vat")
private val CVT_PAID_DATE = LocalDate.of(2026, 6, 15)

private fun makePaidEvent(
    activityType: ActivityType = ActivityType.BNC,
    amount: Cents = Cents(100_00),
): DomainEvent.InvoicePaid =
    DomainEvent.InvoicePaid(
        invoiceId = InvoiceId("inv-1"),
        invoiceNumber = InvoiceNumber("F-2026-06-001"),
        amount = amount,
        paidDate = CVT_PAID_DATE,
        method = PaymentMethod.VIREMENT,
        activityType = activityType,
        userId = CVT_USER_ID,
        occurredAt = Instant.now(),
    )

private fun makeSnapshot(
    activityType: ActivityType,
    amountHt: Cents,
): PaidInvoiceSnapshot =
    PaidInvoiceSnapshot(
        invoiceId = InvoiceId("inv-snap"),
        amountHt = amountHt,
        activityType = activityType,
        paidDate = CVT_PAID_DATE,
    )

// BNC threshold = 36 800 €
// 80% = 29 440 €, 95% = 34 960 €
private val BNC_80_PCT = Cents(29_440_00)
private val BNC_95_PCT = Cents(34_960_00)
private val BNC_50_PCT = Cents(18_400_00)

private class VatStubInvoiceRepository(
    private val snapshots: List<PaidInvoiceSnapshot> = emptyList(),
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

    override suspend fun findByUser(userId: UserId): List<Invoice> = emptyList()

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> = emptyList()

    override suspend fun findPaidInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<PaidInvoiceSnapshot> = snapshots

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

private class StubVatAlertRepository : VatAlertRepository {
    private val records = mutableMapOf<Pair<String, String>, VatAlertRecord>()
    val saved = mutableListOf<VatAlertRecord>()

    fun seed(record: VatAlertRecord) {
        records[record.userId.value to record.yearKey] = record
    }

    override suspend fun findByUserAndYear(
        userId: UserId,
        yearKey: String,
    ): VatAlertRecord? = records[userId.value to yearKey]

    override suspend fun save(record: VatAlertRecord) {
        records[record.userId.value to record.yearKey] = record
        saved.add(record)
    }
}

private class VatStubMessagingPort : MessagingPort {
    val messages = mutableListOf<Pair<UserId, String>>()

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        messages.add(userId to text)
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

class CheckVatThresholdTest {
    @Test
    fun `no alert when revenue is below 80 percent threshold`() =
        runBlocking {
            val invoiceRepo = VatStubInvoiceRepository(listOf(makeSnapshot(ActivityType.BNC, BNC_50_PCT)))
            val alertRepo = StubVatAlertRepository()
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertTrue(messaging.messages.isEmpty())
            assertTrue(alertRepo.saved.isEmpty())
        }

    @Test
    fun `sends 80 percent alert when revenue crosses 80 percent threshold`() =
        runBlocking {
            val invoiceRepo = VatStubInvoiceRepository(listOf(makeSnapshot(ActivityType.BNC, BNC_80_PCT)))
            val alertRepo = StubVatAlertRepository()
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertEquals(1, messaging.messages.size)
            val msg = messaging.messages.first().second.replace('\u202F', ' ')
            assertTrue(msg.contains("approches du seuil TVA"))
            assertTrue(msg.contains("36 800"))
            assertEquals(1, alertRepo.saved.size)
            assertNotNull(alertRepo.saved.first().p80SentAt)
            assertNull(alertRepo.saved.first().p95SentAt)
        }

    @Test
    fun `sends 95 percent alert when revenue crosses 95 percent threshold`() =
        runBlocking {
            val invoiceRepo = VatStubInvoiceRepository(listOf(makeSnapshot(ActivityType.BNC, BNC_95_PCT)))
            val alertRepo = StubVatAlertRepository()
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertEquals(1, messaging.messages.size)
            val msg = messaging.messages.first().second.replace('\u202F', ' ')
            assertTrue(msg.contains("Attention"))
            assertTrue(msg.contains("36 800"))
            assertEquals(1, alertRepo.saved.size)
            assertNotNull(alertRepo.saved.first().p95SentAt)
            assertNotNull(alertRepo.saved.first().p80SentAt)
        }

    @Test
    fun `no duplicate 80 percent alert when already sent`() =
        runBlocking {
            val invoiceRepo = VatStubInvoiceRepository(listOf(makeSnapshot(ActivityType.BNC, BNC_80_PCT)))
            val alertRepo = StubVatAlertRepository()
            alertRepo.seed(
                VatAlertRecord(
                    userId = CVT_USER_ID,
                    yearKey = "2026-BNC",
                    p80SentAt = Instant.parse("2026-06-01T10:00:00Z"),
                ),
            )
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertTrue(messaging.messages.isEmpty())
            assertTrue(alertRepo.saved.isEmpty())
        }

    @Test
    fun `no duplicate 95 percent alert when already sent`() =
        runBlocking {
            val invoiceRepo = VatStubInvoiceRepository(listOf(makeSnapshot(ActivityType.BNC, BNC_95_PCT)))
            val alertRepo = StubVatAlertRepository()
            alertRepo.seed(
                VatAlertRecord(
                    userId = CVT_USER_ID,
                    yearKey = "2026-BNC",
                    p80SentAt = Instant.parse("2026-06-01T10:00:00Z"),
                    p95SentAt = Instant.parse("2026-06-10T10:00:00Z"),
                ),
            )
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertTrue(messaging.messages.isEmpty())
            assertTrue(alertRepo.saved.isEmpty())
        }

    @Test
    fun `sends 95 percent alert but not 80 percent when 80 already sent`() =
        runBlocking {
            val invoiceRepo = VatStubInvoiceRepository(listOf(makeSnapshot(ActivityType.BNC, BNC_95_PCT)))
            val alertRepo = StubVatAlertRepository()
            alertRepo.seed(
                VatAlertRecord(
                    userId = CVT_USER_ID,
                    yearKey = "2026-BNC",
                    p80SentAt = Instant.parse("2026-06-01T10:00:00Z"),
                ),
            )
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertEquals(1, messaging.messages.size)
            assertTrue(messaging.messages.first().second.contains("Attention"))
            assertNotNull(alertRepo.saved.first().p95SentAt)
        }

    @Test
    fun `year key includes activity type for multi-activity users`() =
        runBlocking {
            val bncSnapshot = makeSnapshot(ActivityType.BNC, BNC_80_PCT)
            val bicSnapshot = makeSnapshot(ActivityType.BIC_VENTE, Cents(10_000_00))
            val invoiceRepo = VatStubInvoiceRepository(listOf(bncSnapshot, bicSnapshot))
            val alertRepo = StubVatAlertRepository()
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertEquals(1, alertRepo.saved.size)
            assertEquals("2026-BNC", alertRepo.saved.first().yearKey)
        }

    @Test
    fun `uses correct year from paid date for annual revenue`() =
        runBlocking {
            val invoiceRepo = VatStubInvoiceRepository(listOf(makeSnapshot(ActivityType.BNC, BNC_80_PCT)))
            val alertRepo = StubVatAlertRepository()
            val messaging = VatStubMessagingPort()
            val useCase = CheckVatThreshold(invoiceRepo, alertRepo, messaging)

            useCase.execute(makePaidEvent(ActivityType.BNC))

            assertEquals(1, alertRepo.saved.size)
            assertTrue(alertRepo.saved.first().yearKey.startsWith("2026"))
        }
}
