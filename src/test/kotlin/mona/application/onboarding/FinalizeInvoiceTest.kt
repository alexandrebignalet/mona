package mona.application.onboarding

import kotlinx.coroutines.runBlocking
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
import mona.domain.model.Siren
import mona.domain.model.Siret
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

private val FI_USER_ID = UserId("u1")
private val FI_CLIENT_ID = ClientId("c1")
private val FI_INVOICE_ID = InvoiceId("inv1")
private val FI_SIREN = Siren("123456789")

private fun makeFiUser(siren: Siren? = FI_SIREN): User =
    User(
        id = FI_USER_ID,
        telegramId = 1L,
        email = Email("user@example.com"),
        name = "Sophie Martin",
        siren = siren,
        siret = Siret("12345678900001"),
        address = null,
        ibanEncrypted = null,
        activityType = ActivityType.BNC,
        declarationPeriodicity = DeclarationPeriodicity.MONTHLY,
        confirmBeforeCreate = true,
        defaultPaymentDelayDays = PaymentDelayDays(30),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeFiClient(): Client =
    Client(
        id = FI_CLIENT_ID,
        userId = FI_USER_ID,
        name = "Acme",
        email = null,
        address = null,
        companyName = null,
        siret = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

private fun makeFiInvoice(): Invoice =
    Invoice(
        id = FI_INVOICE_ID,
        userId = FI_USER_ID,
        clientId = FI_CLIENT_ID,
        number = InvoiceNumber("F-2026-03-001"),
        status = InvoiceStatus.Draft,
        issueDate = LocalDate.of(2026, 3, 22),
        dueDate = LocalDate.of(2026, 4, 21),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = null,
        createdAt = Instant.parse("2026-03-22T00:00:00Z"),
    )

private class FiUserRepo(private val user: User?) : UserRepository {
    override suspend fun findById(id: UserId): User? = user?.takeIf { it.id == id }

    override suspend fun findByTelegramId(telegramId: Long): User? = user?.takeIf { it.telegramId == telegramId }

    override suspend fun save(user: User) {}

    override suspend fun findAllWithPeriodicity(): List<User> = emptyList()

    override suspend fun findAllWithoutSiren(): List<User> = emptyList()

    override suspend fun delete(userId: UserId) {}
}

private class FiClientRepo(private val client: Client?) : ClientRepository {
    override suspend fun findById(id: ClientId): Client? = client?.takeIf { it.id == id }

    override suspend fun save(client: Client) {}

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = emptyList()

    override suspend fun findByUser(userId: UserId): List<Client> = emptyList()

    override suspend fun deleteByUser(userId: UserId) {}
}

private class FiInvoiceRepo(private val invoice: Invoice?) : InvoiceRepository {
    override suspend fun findById(id: InvoiceId): Invoice? = invoice?.takeIf { it.id == id }

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

    override suspend fun findSentDueOn(date: LocalDate): List<Invoice> = emptyList()

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> = emptyList()

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = emptyList()

    override suspend fun anonymizeByUser(userId: UserId) {}
}

private class CapturingPdfPort : PdfPort {
    var capturedIban: String? = null
    var capturedUser: User? = null
    val fakePdf = byteArrayOf(0x50, 0x44, 0x46)

    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> {
        capturedIban = plainIban
        capturedUser = user
        return DomainResult.Ok(fakePdf)
    }

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Ok(fakePdf)
}

private fun makeUseCase(
    user: User? = makeFiUser(),
    client: Client? = makeFiClient(),
    invoice: Invoice? = makeFiInvoice(),
    pdf: CapturingPdfPort = CapturingPdfPort(),
): Pair<FinalizeInvoice, CapturingPdfPort> {
    val uc =
        FinalizeInvoice(
            FiUserRepo(user),
            FiClientRepo(client),
            FiInvoiceRepo(invoice),
            pdf,
        )
    return Pair(uc, pdf)
}

class FinalizeInvoiceTest {
    @Test
    fun `returns FinalizeInvoiceResult with invoice and PDF`() =
        runBlocking {
            val (uc, pdf) = makeUseCase()
            val result = uc.execute(FinalizeInvoiceCommand(FI_USER_ID, FI_INVOICE_ID, null))
            assertIs<DomainResult.Ok<FinalizeInvoiceResult>>(result)
            assertNotNull(result.value.pdf)
            assertContentEquals(pdf.fakePdf, result.value.pdf)
            assertEquals(FI_INVOICE_ID, result.value.invoice.id)
        }

    @Test
    fun `passes plainIban to PDF generation`() =
        runBlocking {
            val (uc, pdfPort) = makeUseCase()
            uc.execute(FinalizeInvoiceCommand(FI_USER_ID, FI_INVOICE_ID, "FR7630001007941234567890185"))
            assertEquals("FR7630001007941234567890185", pdfPort.capturedIban)
        }

    @Test
    fun `generates PDF with user who has SIREN (no watermark context)`() =
        runBlocking {
            val (uc, pdfPort) = makeUseCase()
            uc.execute(FinalizeInvoiceCommand(FI_USER_ID, FI_INVOICE_ID, null))
            assertNotNull(pdfPort.capturedUser?.siren)
        }

    @Test
    fun `returns ProfileIncomplete when user not found`() =
        runBlocking {
            val (uc, _) = makeUseCase(user = null)
            val result = uc.execute(FinalizeInvoiceCommand(FI_USER_ID, FI_INVOICE_ID, null))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ProfileIncomplete>(result.error)
        }

    @Test
    fun `returns SirenRequired when user has no SIREN`() =
        runBlocking {
            val (uc, _) = makeUseCase(user = makeFiUser(siren = null))
            val result = uc.execute(FinalizeInvoiceCommand(FI_USER_ID, FI_INVOICE_ID, null))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.SirenRequired>(result.error)
        }

    @Test
    fun `returns InvoiceNotFound when invoice does not exist`() =
        runBlocking {
            val (uc, _) = makeUseCase(invoice = null)
            val result = uc.execute(FinalizeInvoiceCommand(FI_USER_ID, FI_INVOICE_ID, null))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.InvoiceNotFound>(result.error)
        }

    @Test
    fun `returns ClientNotFound when client does not exist`() =
        runBlocking {
            val (uc, _) = makeUseCase(client = null)
            val result = uc.execute(FinalizeInvoiceCommand(FI_USER_ID, FI_INVOICE_ID, null))
            assertIs<DomainResult.Err>(result)
            assertIs<DomainError.ClientNotFound>(result.error)
        }
}
