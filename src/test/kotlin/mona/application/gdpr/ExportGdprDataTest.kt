package mona.application.gdpr

import kotlinx.coroutines.runBlocking
import mona.application.revenue.ExportInvoicesCsv
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.Client
import mona.domain.model.ClientId
import mona.domain.model.CreditNote
import mona.domain.model.CreditNoteNumber
import mona.domain.model.CreditNoteSnapshot
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainResult
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
import mona.domain.port.CryptoPort
import mona.domain.port.InvoiceRepository
import mona.domain.port.PdfPort
import mona.domain.port.UserRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val UID = UserId("u1")
private val CID = ClientId("c1")
private val BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z")
private val BASE_DATE = LocalDate.of(2026, 3, 1)
private val FAKE_PDF_BYTES = byteArrayOf(0x50, 0x44, 0x46)

private class FakeUserRepository(private val user: User) : UserRepository {
    override suspend fun findById(id: UserId): User? = if (id == user.id) user else null

    override suspend fun findByTelegramId(telegramId: Long): User? = null

    override suspend fun save(user: User) {}

    override suspend fun delete(userId: UserId) {}

    override suspend fun findAllWithPeriodicity(): List<User> = emptyList()

    override suspend fun findAllWithoutSiren(): List<User> = emptyList()
}

private class FakeInvoiceRepository(vararg invoices: Invoice) : InvoiceRepository {
    private val store = invoices.toMutableList()

    override suspend fun findByUser(userId: UserId): List<Invoice> = store.filter { it.userId == userId }

    override suspend fun findById(id: InvoiceId): Invoice? = store.firstOrNull { it.id == id }

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

    override suspend fun findByUserAndStatus(
        userId: UserId,
        status: InvoiceStatus,
    ): List<Invoice> = store.filter { it.userId == userId && it.status == status }

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

private class FakeClientRepository(vararg clients: Client) : ClientRepository {
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

private class FakePdfPort : PdfPort {
    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Ok(FAKE_PDF_BYTES)

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Ok(FAKE_PDF_BYTES)
}

private class FakeCryptoPort : CryptoPort {
    override fun encrypt(plaintext: String): ByteArray = plaintext.toByteArray()

    override fun decrypt(ciphertext: ByteArray): String = String(ciphertext)
}

private fun makeUser(): User =
    User(
        id = UID,
        telegramId = 100L,
        email = null,
        name = "Sophie Martin",
        siren = null,
        siret = null,
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BNC,
        declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = BASE_INSTANT,
    )

private fun makeInvoice(
    id: InvoiceId = InvoiceId("inv1"),
    number: String = "F-2026-03-001",
    status: InvoiceStatus = InvoiceStatus.Sent,
    creditNote: CreditNote? = null,
): Invoice =
    Invoice(
        id = id,
        userId = UID,
        clientId = CID,
        number = InvoiceNumber(number),
        status = status,
        issueDate = BASE_DATE,
        dueDate = BASE_DATE.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = creditNote,
        createdAt = BASE_INSTANT,
    )

private fun makeClient(): Client =
    Client(
        id = CID,
        userId = UID,
        name = "Acme",
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = BASE_INSTANT,
    )

private fun makeUseCase(
    user: User = makeUser(),
    vararg invoices: Invoice,
): ExportGdprData {
    val client = makeClient()
    val userRepo = FakeUserRepository(user)
    val invoiceRepo = FakeInvoiceRepository(*invoices)
    val clientRepo = FakeClientRepository(client)
    val exportCsv = ExportInvoicesCsv(invoiceRepo, clientRepo)
    return ExportGdprData(userRepo, invoiceRepo, clientRepo, exportCsv, FakePdfPort(), FakeCryptoPort())
}

class ExportGdprDataTest {
    @Test
    fun `zero invoices returns profile json and empty csv only`() =
        runBlocking {
            val useCase = makeUseCase()
            val result = useCase.execute(UID)

            assertTrue(result.invoicePdfs.isEmpty())
            assertTrue(result.creditNotePdfs.isEmpty())
            assertTrue(result.csvFilename.startsWith("mona-factures-"))
            assertTrue(result.profileJsonBytes.isNotEmpty())
            val profileJson = String(result.profileJsonBytes)
            assertTrue(profileJson.contains("\"nom\""), "Profile JSON should contain nom field")
            assertTrue(profileJson.contains("\"iban_enregistre\": false"), "IBAN should be false")
        }

    @Test
    fun `sent invoice generates invoice pdf`() =
        runBlocking {
            val invoice = makeInvoice(status = InvoiceStatus.Sent)
            val useCase = makeUseCase(invoices = arrayOf(invoice))
            val result = useCase.execute(UID)

            assertEquals(1, result.invoicePdfs.size)
            assertEquals("F-2026-03-001.pdf", result.invoicePdfs[0].second)
            assertTrue(result.creditNotePdfs.isEmpty())
        }

    @Test
    fun `cancelled invoice with credit note generates both pdfs`() =
        runBlocking {
            val creditNote =
                CreditNote(
                    number = CreditNoteNumber("A-2026-03-001"),
                    amountHt = Cents(80000),
                    reason = "Annulation",
                    issueDate = BASE_DATE,
                    replacementInvoiceId = null,
                    pdfPath = null,
                )
            val invoice =
                makeInvoice(
                    status = InvoiceStatus.Cancelled,
                    creditNote = creditNote,
                )
            val useCase = makeUseCase(invoices = arrayOf(invoice))
            val result = useCase.execute(UID)

            assertEquals(1, result.invoicePdfs.size)
            assertEquals("F-2026-03-001.pdf", result.invoicePdfs[0].second)
            assertEquals(1, result.creditNotePdfs.size)
            assertEquals("A-2026-03-001.pdf", result.creditNotePdfs[0].second)
        }

    @Test
    fun `draft invoices are excluded from pdfs`() =
        runBlocking {
            val draft = makeInvoice(id = InvoiceId("d1"), number = "F-2026-03-001", status = InvoiceStatus.Draft)
            val sent = makeInvoice(id = InvoiceId("s1"), number = "F-2026-03-002", status = InvoiceStatus.Sent)
            val useCase = makeUseCase(invoices = arrayOf(draft, sent))
            val result = useCase.execute(UID)

            assertEquals(1, result.invoicePdfs.size)
            assertEquals("F-2026-03-002.pdf", result.invoicePdfs[0].second)
        }

    @Test
    fun `profile json includes all fields and iban presence`() =
        runBlocking {
            val userWithIban =
                makeUser().copy(
                    ibanEncrypted = "FR7614508059000000000000000".toByteArray(),
                )
            val useCase = makeUseCase(user = userWithIban)
            val result = useCase.execute(UID)

            val profileJson = String(result.profileJsonBytes)
            assertTrue(profileJson.contains("\"nom\": \"Sophie Martin\""))
            assertTrue(profileJson.contains("\"type_activite\": \"BNC\""))
            assertTrue(profileJson.contains("\"delai_paiement_jours\": 30"))
            assertTrue(profileJson.contains("\"iban_enregistre\": true"))
        }
}
