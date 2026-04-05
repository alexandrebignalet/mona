package mona.application.payment

import kotlinx.coroutines.runBlocking
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.UserId
import mona.domain.port.Button
import mona.domain.port.ClientRepository
import mona.domain.port.IncomingCallback
import mona.domain.port.IncomingMessage
import mona.domain.port.InvoiceRepository
import mona.domain.port.MenuItem
import mona.domain.port.MessagingPort
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val USER_1 = UserId("user-1")
private val USER_2 = UserId("user-2")
private val CLIENT_A = ClientId("client-a")
private val CLIENT_B = ClientId("client-b")
private val CLIENT_C = ClientId("client-c")

private val TODAY = LocalDate.of(2026, 3, 23)
private val YESTERDAY = TODAY.minusDays(1)

private fun makeInvoice(
    id: String,
    userId: UserId,
    clientId: ClientId,
    number: String,
    dueDate: LocalDate,
    amountCents: Long = 80000L,
): Invoice =
    Invoice(
        id = InvoiceId(id),
        userId = userId,
        clientId = clientId,
        number = InvoiceNumber(number),
        status = InvoiceStatus.Sent,
        issueDate = dueDate.minusDays(30),
        dueDate = dueDate,
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Service", BigDecimal.ONE, Cents(amountCents))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-02-21T00:00:00Z"),
    )

private fun makeClient(
    id: ClientId,
    userId: UserId,
    name: String,
): Client =
    Client(
        id = id,
        userId = userId,
        name = name,
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private class StubInvoiceRepo(
    private val dueOnResults: List<Invoice> = emptyList(),
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
    ): List<PaidInvoiceSnapshot> = emptyList()

    override suspend fun findCreditNotesInPeriod(
        userId: UserId,
        period: DeclarationPeriod,
    ): List<CreditNoteSnapshot> = emptyList()

    override suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice> = emptyList()

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = dueOnResults

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = emptyList()

    override suspend fun anonymizeByUser(userId: UserId) {}
}

private class StubClientRepo(vararg clients: Client) : ClientRepository {
    private val store = clients.toList()

    override suspend fun findById(id: ClientId): Client? = store.firstOrNull { it.id == id }

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.filter { it.userId == userId }

    override suspend fun deleteByUser(userId: UserId) {}
}

private class CapturingMessagingPort : MessagingPort {
    val messages = mutableListOf<Pair<UserId, String>>()

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        messages += userId to text
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

class PaymentCheckInJobTest {
    @Test
    fun `no messages sent when no invoices due yesterday`() =
        runBlocking {
            val messaging = CapturingMessagingPort()
            val job = PaymentCheckInJob(StubInvoiceRepo(), StubClientRepo(), messaging)

            job.execute(TODAY)

            assertTrue(messaging.messages.isEmpty())
        }

    @Test
    fun `single invoice sends check-in message to correct user`() =
        runBlocking {
            val invoice = makeInvoice("i1", USER_1, CLIENT_A, "F-2026-03-001", YESTERDAY, 80000L)
            val invoiceRepo = StubInvoiceRepo(dueOnResults = listOf(invoice))
            val clientRepo = StubClientRepo(makeClient(CLIENT_A, USER_1, "Dupont"))
            val messaging = CapturingMessagingPort()

            PaymentCheckInJob(invoiceRepo, clientRepo, messaging).execute(TODAY)

            assertEquals(1, messaging.messages.size)
            assertEquals(USER_1, messaging.messages.first().first)
        }

    @Test
    fun `single invoice message format matches spec`() =
        runBlocking {
            val invoice = makeInvoice("i1", USER_1, CLIENT_A, "F-2026-03-008", YESTERDAY, 80000L)
            val invoiceRepo = StubInvoiceRepo(dueOnResults = listOf(invoice))
            val clientRepo = StubClientRepo(makeClient(CLIENT_A, USER_1, "Dupont"))
            val messaging = CapturingMessagingPort()

            PaymentCheckInJob(invoiceRepo, clientRepo, messaging).execute(TODAY)

            val msg = messaging.messages.first().second
            assertEquals(
                "La facture F-2026-03-008 de Dupont (800€) devait être payée hier — c'est fait ?",
                msg,
            )
        }

    @Test
    fun `multiple invoices for same user sends batched message`() =
        runBlocking {
            val inv1 = makeInvoice("i1", USER_1, CLIENT_A, "F-2026-03-008", YESTERDAY, 80000L)
            val inv2 = makeInvoice("i2", USER_1, CLIENT_B, "F-2026-03-010", YESTERDAY, 60000L)
            val invoiceRepo = StubInvoiceRepo(dueOnResults = listOf(inv1, inv2))
            val clientRepo =
                StubClientRepo(
                    makeClient(CLIENT_A, USER_1, "Dupont"),
                    makeClient(CLIENT_B, USER_1, "Martin"),
                )
            val messaging = CapturingMessagingPort()

            PaymentCheckInJob(invoiceRepo, clientRepo, messaging).execute(TODAY)

            assertEquals(1, messaging.messages.size)
            val msg = messaging.messages.first().second
            assertTrue(msg.contains("2 factures arrivaient à échéance hier"))
            assertTrue(msg.contains("Dupont"))
            assertTrue(msg.contains("Martin"))
            assertTrue(msg.contains("Lesquels t'ont payé ?"))
        }

    @Test
    fun `multiple users each receive their own message`() =
        runBlocking {
            val inv1 = makeInvoice("i1", USER_1, CLIENT_A, "F-2026-03-001", YESTERDAY, 80000L)
            val inv2 = makeInvoice("i2", USER_2, CLIENT_C, "F-2026-03-002", YESTERDAY, 30000L)
            val invoiceRepo = StubInvoiceRepo(dueOnResults = listOf(inv1, inv2))
            val clientRepo =
                StubClientRepo(
                    makeClient(CLIENT_A, USER_1, "Dupont"),
                    makeClient(CLIENT_C, USER_2, "Bernard"),
                )
            val messaging = CapturingMessagingPort()

            PaymentCheckInJob(invoiceRepo, clientRepo, messaging).execute(TODAY)

            assertEquals(2, messaging.messages.size)
            val recipients = messaging.messages.map { it.first }.toSet()
            assertEquals(setOf(USER_1, USER_2), recipients)
        }

    @Test
    fun `amount with cents formatted correctly`() =
        runBlocking {
            val invoice = makeInvoice("i1", USER_1, CLIENT_A, "F-2026-03-001", YESTERDAY, 150050L)
            val invoiceRepo = StubInvoiceRepo(dueOnResults = listOf(invoice))
            val clientRepo = StubClientRepo(makeClient(CLIENT_A, USER_1, "Dupont"))
            val messaging = CapturingMessagingPort()

            PaymentCheckInJob(invoiceRepo, clientRepo, messaging).execute(TODAY)

            val msg = messaging.messages.first().second
            assertTrue(msg.contains("1500,50€"))
        }

    @Test
    fun `batched message lists all invoice numbers`() =
        runBlocking {
            val inv1 = makeInvoice("i1", USER_1, CLIENT_A, "F-2026-03-008", YESTERDAY, 80000L)
            val inv2 = makeInvoice("i2", USER_1, CLIENT_B, "F-2026-03-010", YESTERDAY, 60000L)
            val inv3 = makeInvoice("i3", USER_1, CLIENT_C, "F-2026-03-012", YESTERDAY, 30000L)
            val invoiceRepo = StubInvoiceRepo(dueOnResults = listOf(inv1, inv2, inv3))
            val clientRepo =
                StubClientRepo(
                    makeClient(CLIENT_A, USER_1, "Dupont"),
                    makeClient(CLIENT_B, USER_1, "Martin"),
                    makeClient(CLIENT_C, USER_1, "Bernard"),
                )
            val messaging = CapturingMessagingPort()

            PaymentCheckInJob(invoiceRepo, clientRepo, messaging).execute(TODAY)

            val msg = messaging.messages.first().second
            assertTrue(msg.contains("3 factures"))
            assertTrue(msg.contains("F-2026-03-008"))
            assertTrue(msg.contains("F-2026-03-010"))
            assertTrue(msg.contains("F-2026-03-012"))
        }
}
