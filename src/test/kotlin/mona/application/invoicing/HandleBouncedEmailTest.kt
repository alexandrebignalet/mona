package mona.application.invoicing

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.UserId
import mona.domain.port.IncomingCallback
import mona.domain.port.InvoiceRepository
import mona.domain.port.MessagingPort
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private val NOW = Instant.parse("2026-03-22T10:00:00Z")
private val ISSUE_DATE = LocalDate.of(2026, 3, 1)
private val USER_ID = UserId("u1")
private val CLIENT_ID = ClientId("c1")
private val INVOICE_ID = InvoiceId("inv1")
private val NUMBER = InvoiceNumber("F-2026-03-001")

private fun makeSentInvoice(
    id: InvoiceId = INVOICE_ID,
    number: InvoiceNumber = NUMBER,
    userId: UserId = USER_ID,
): Invoice =
    Invoice(
        id = id,
        userId = userId,
        clientId = CLIENT_ID,
        number = number,
        status = InvoiceStatus.Sent,
        issueDate = ISSUE_DATE,
        dueDate = ISSUE_DATE.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = null,
        createdAt = NOW,
    )

private class StubInvoiceRepoByNumber(private vararg val invoices: Invoice) : InvoiceRepository {
    val saved = mutableListOf<Invoice>()

    override suspend fun findById(id: InvoiceId): Invoice? = invoices.find { it.id == id }

    override suspend fun save(invoice: Invoice) {
        saved += invoice
    }

    override suspend fun delete(id: InvoiceId) {}

    override suspend fun findLastNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): InvoiceNumber? = null

    override suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): mona.domain.model.CreditNoteNumber? = null

    override suspend fun findByUser(userId: UserId): List<Invoice> = invoices.filter { it.userId == userId }

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

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = invoices.filter { it.number == number }

    override suspend fun anonymizeByUser(userId: UserId) {}
}

private class SpyMessagingPort : MessagingPort {
    val messages = mutableListOf<Pair<UserId, String>>()

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        messages += Pair(userId, text)
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
        buttons: List<mona.domain.port.Button>,
    ) {}

    override suspend fun setPersistentMenu(
        userId: UserId,
        items: List<mona.domain.port.MenuItem>,
    ) {}

    override suspend fun onMessage(handler: suspend (mona.domain.port.IncomingMessage) -> Unit) {}

    override suspend fun onCallback(handler: suspend (IncomingCallback) -> Unit) {}

    override suspend fun answerCallback(
        callbackQueryId: String,
        text: String?,
    ) {}
}

class HandleBouncedEmailTest {
    @Test
    fun `reverts Sent invoice to Draft and notifies user`() =
        runBlocking {
            val invoice = makeSentInvoice()
            val repo = StubInvoiceRepoByNumber(invoice)
            val messaging = SpyMessagingPort()
            HandleBouncedEmail(repo, messaging).execute(NUMBER, "client@example.com")

            assertEquals(1, repo.saved.size)
            assertIs<InvoiceStatus.Draft>(repo.saved[0].status)
            assertEquals(1, messaging.messages.size)
            assertEquals(USER_ID, messaging.messages[0].first)
        }

    @Test
    fun `notifies with correct invoice number and recipient email`() =
        runBlocking {
            val invoice = makeSentInvoice()
            val repo = StubInvoiceRepoByNumber(invoice)
            val messaging = SpyMessagingPort()
            HandleBouncedEmail(repo, messaging).execute(NUMBER, "jean.dupont@gmail.com")

            val message = messaging.messages[0].second
            assert(message.contains("jean.dupont@gmail.com")) { "Missing recipient email in message" }
            assert(message.contains("F-2026-03-001")) { "Missing invoice number in message" }
        }

    @Test
    fun `no-op when invoice not found for number`() =
        runBlocking {
            val repo = StubInvoiceRepoByNumber()
            val messaging = SpyMessagingPort()
            HandleBouncedEmail(repo, messaging).execute(NUMBER, "client@example.com")

            assertEquals(0, repo.saved.size)
            assertEquals(0, messaging.messages.size)
        }

    @Test
    fun `skips non-Sent invoices gracefully`() =
        runBlocking {
            val draftInvoice =
                makeSentInvoice().copy(status = InvoiceStatus.Draft)
            val repo = StubInvoiceRepoByNumber(draftInvoice)
            val messaging = SpyMessagingPort()
            HandleBouncedEmail(repo, messaging).execute(NUMBER, "client@example.com")

            assertEquals(0, repo.saved.size)
            assertEquals(0, messaging.messages.size)
        }

    @Test
    fun `processes multiple invoices with same number across users`() =
        runBlocking {
            val user2 = UserId("u2")
            val inv1 = makeSentInvoice(id = InvoiceId("inv1"), userId = USER_ID)
            val inv2 = makeSentInvoice(id = InvoiceId("inv2"), userId = user2)
            val repo = StubInvoiceRepoByNumber(inv1, inv2)
            val messaging = SpyMessagingPort()
            HandleBouncedEmail(repo, messaging).execute(NUMBER, "client@example.com")

            assertEquals(2, repo.saved.size)
            assertEquals(2, messaging.messages.size)
        }

    @Test
    fun `reverted invoice has same data except Draft status`() =
        runBlocking {
            val invoice = makeSentInvoice()
            val repo = StubInvoiceRepoByNumber(invoice)
            val messaging = SpyMessagingPort()
            HandleBouncedEmail(repo, messaging).execute(NUMBER, "client@example.com")

            val saved = repo.saved[0]
            assertIs<InvoiceStatus.Draft>(saved.status)
            assertEquals(invoice.id, saved.id)
            assertEquals(invoice.number, saved.number)
            assertEquals(invoice.amountHt, saved.amountHt)
            assertNull(saved.creditNote)
        }
}
