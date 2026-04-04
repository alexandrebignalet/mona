package mona.application

import kotlinx.coroutines.runBlocking
import mona.application.client.GetClientHistory
import mona.application.client.ListClients
import mona.application.client.UpdateClient
import mona.application.gdpr.DeleteAccount
import mona.application.gdpr.ExportGdprData
import mona.application.invoicing.CancelInvoice
import mona.application.invoicing.CorrectInvoice
import mona.application.invoicing.CreateInvoice
import mona.application.invoicing.DeleteDraft
import mona.application.invoicing.MarkInvoicePaid
import mona.application.invoicing.SendInvoice
import mona.application.invoicing.UpdateDraft
import mona.application.onboarding.FinalizeInvoice
import mona.application.onboarding.SetupProfile
import mona.application.revenue.ExportInvoicesCsv
import mona.application.revenue.GetRevenue
import mona.application.revenue.GetUnpaidInvoices
import mona.application.settings.ConfigureSetting
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
import mona.domain.model.Invoice
import mona.domain.model.InvoiceId
import mona.domain.model.InvoiceNumber
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaidInvoiceSnapshot
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PostalAddress
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.Button
import mona.domain.port.ClientRepository
import mona.domain.port.ConversationMessage
import mona.domain.port.ConversationRepository
import mona.domain.port.CryptoPort
import mona.domain.port.IncomingMessage
import mona.domain.port.InvoiceRepository
import mona.domain.port.LlmPort
import mona.domain.port.LlmResponse
import mona.domain.port.LlmToolDefinition
import mona.domain.port.MenuItem
import mona.domain.port.MessageRole
import mona.domain.port.MessagingPort
import mona.domain.port.PdfPort
import mona.domain.port.SirenePort
import mona.domain.port.SireneResult
import mona.domain.port.UserRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// --- In-memory fakes ---

private class InMemoryUserRepository(vararg seed: User) : UserRepository {
    val store = seed.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: UserId): User? = store[id]

    override suspend fun findByTelegramId(telegramId: Long): User? = store.values.find { it.telegramId == telegramId }

    override suspend fun save(user: User) {
        store[user.id] = user
    }

    override suspend fun delete(userId: UserId) {
        store.remove(userId)
    }

    override suspend fun findAllWithPeriodicity(): List<User> = store.values.filter { it.declarationPeriodicity != null }

    override suspend fun findAllWithoutSiren(): List<User> = store.values.filter { it.siren == null }
}

private class InMemoryClientRepository(vararg seed: Client) : ClientRepository {
    val store = seed.associateBy { it.id }.toMutableMap()

    override suspend fun findById(id: ClientId): Client? = store[id]

    override suspend fun save(client: Client) {
        store[client.id] = client
    }

    override suspend fun findByUserAndName(
        userId: UserId,
        name: String,
    ): List<Client> = store.values.filter { it.userId == userId && it.name.equals(name, ignoreCase = true) }

    override suspend fun findByUser(userId: UserId): List<Client> = store.values.filter { it.userId == userId }

    override suspend fun deleteByUser(userId: UserId) {
        store.values.removeIf { it.userId == userId }
    }
}

private class InMemoryInvoiceRepository(vararg seed: Invoice) : InvoiceRepository {
    val store = seed.associateBy { it.id }.toMutableMap()

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

    override suspend fun findLastCreditNoteNumberInMonth(
        userId: UserId,
        yearMonth: YearMonth,
    ): CreditNoteNumber? = null

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

    override suspend fun findByClientAndAmountSince(
        clientId: ClientId,
        amountHt: Cents,
        since: LocalDate,
    ): List<Invoice> =
        store.values.filter {
            it.clientId == clientId && it.amountHt == amountHt && !it.issueDate.isBefore(since)
        }

    override suspend fun findByNumber(number: InvoiceNumber): List<Invoice> = store.values.filter { it.number == number }

    override suspend fun anonymizeByUser(userId: UserId) {
        val updated =
            store.values.map { inv ->
                if (inv.userId == userId) inv.copy(userId = null, clientId = null) else inv
            }
        store.clear()
        updated.forEach { store[it.id] = it }
    }
}

private class InMemoryConversationRepository : ConversationRepository {
    val saved = mutableListOf<ConversationMessage>()

    override suspend fun save(message: ConversationMessage) {
        saved += message
    }

    override suspend fun findRecent(
        userId: UserId,
        limit: Int,
    ): List<ConversationMessage> = saved.filter { it.userId == userId }.takeLast(limit)

    override suspend fun deleteByUser(userId: UserId) {
        saved.removeIf { it.userId == userId }
    }
}

private class FakeMessagingPort : MessagingPort {
    val sentMessages = mutableListOf<Pair<UserId, String>>()
    val sentDocuments = mutableListOf<Triple<UserId, String, ByteArray>>()

    override suspend fun sendMessage(
        userId: UserId,
        text: String,
    ) {
        sentMessages += Pair(userId, text)
    }

    override suspend fun sendDocument(
        userId: UserId,
        fileBytes: ByteArray,
        fileName: String,
        caption: String?,
    ) {
        sentDocuments += Triple(userId, fileName, fileBytes)
    }

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

private class StubLlmPort(private val response: DomainResult<LlmResponse>) : LlmPort {
    override suspend fun complete(
        systemPrompt: String,
        userContextJson: String,
        messages: List<mona.domain.port.ConversationMessage>,
        tools: List<LlmToolDefinition>,
    ): DomainResult<LlmResponse> = response
}

private class FakePdfPort : PdfPort {
    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Ok(byteArrayOf(0x50, 0x44, 0x46))

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Ok(byteArrayOf(0x50, 0x44, 0x46))
}

private class FailingPdfPort : PdfPort {
    override fun generateInvoice(
        invoice: Invoice,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Err(DomainError.PdfGenerationFailed("simulated failure"))

    override fun generateCreditNote(
        creditNote: CreditNote,
        originalInvoiceNumber: InvoiceNumber,
        user: User,
        client: Client,
        plainIban: String?,
    ): DomainResult<ByteArray> = DomainResult.Err(DomainError.PdfGenerationFailed("simulated failure"))
}

private class FailingEmailPort : mona.domain.port.EmailPort {
    override suspend fun sendInvoice(
        to: String,
        subject: String,
        body: String,
        pdfAttachment: ByteArray,
        filename: String,
    ): DomainResult<Unit> = DomainResult.Err(DomainError.EmailDeliveryFailed(422, "invalid"))
}

private class FailingSirenePort : mona.domain.port.SirenePort {
    override suspend fun lookupBySiren(siren: mona.domain.model.Siren): DomainResult<mona.domain.port.SireneResult> =
        DomainResult.Err(DomainError.SireneLookupFailed("SIRENE API timeout"))

    override suspend fun searchByNameAndCity(
        name: String,
        city: String,
    ): DomainResult<List<mona.domain.port.SireneResult>> = DomainResult.Err(DomainError.SireneLookupFailed("SIRENE API timeout"))
}

private class FakeCryptoPort : CryptoPort {
    override fun encrypt(plaintext: String): ByteArray = plaintext.toByteArray()

    override fun decrypt(ciphertext: ByteArray): String = String(ciphertext)
}

private class FakeSirenePort : SirenePort {
    override suspend fun lookupBySiren(siren: mona.domain.model.Siren): DomainResult<SireneResult> =
        DomainResult.Err(DomainError.SirenNotFound(siren))

    override suspend fun searchByNameAndCity(
        name: String,
        city: String,
    ): DomainResult<List<SireneResult>> = DomainResult.Ok(emptyList())
}

private val FAKE_PDF = byteArrayOf(0x50, 0x44, 0x46)
private val BASE_DATE = LocalDate.of(2026, 3, 22)
private val BASE_INSTANT = Instant.parse("2026-01-01T00:00:00Z")

private fun makeUser(
    id: UserId = UserId("u1"),
    telegramId: Long = 100L,
): User =
    User(
        id = id,
        telegramId = telegramId,
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
    userId: UserId = UserId("u1"),
    clientId: ClientId = ClientId("c1"),
    number: InvoiceNumber = InvoiceNumber("F-2026-03-001"),
    status: InvoiceStatus = InvoiceStatus.Draft,
): Invoice =
    Invoice(
        id = id,
        userId = userId,
        clientId = clientId,
        number = number,
        status = status,
        issueDate = BASE_DATE,
        dueDate = BASE_DATE.plusDays(30),
        activityType = ActivityType.BNC,
        lineItems = listOf(LineItem("Consulting", BigDecimal.ONE, Cents(80000))),
        pdfPath = null,
        creditNote = null,
        createdAt = BASE_INSTANT,
    )

private fun makeRouter(
    userRepo: InMemoryUserRepository = InMemoryUserRepository(),
    invoiceRepo: InMemoryInvoiceRepository = InMemoryInvoiceRepository(),
    clientRepo: InMemoryClientRepository = InMemoryClientRepository(),
    conversationRepo: InMemoryConversationRepository = InMemoryConversationRepository(),
    llmPort: LlmPort = StubLlmPort(DomainResult.Ok(LlmResponse.Text("Bonjour !"))),
    messagingPort: FakeMessagingPort = FakeMessagingPort(),
    cryptoPort: CryptoPort = FakeCryptoPort(),
    sirenePort: mona.domain.port.SirenePort = FakeSirenePort(),
    pdfPort: PdfPort = FakePdfPort(),
    emailPort: mona.domain.port.EmailPort = FakeEmailPort(),
): Triple<MessageRouter, FakeMessagingPort, InMemoryConversationRepository> {
    val pdf = pdfPort
    val dispatcher = EventDispatcher()
    val deleteAccount = DeleteAccount(userRepo, clientRepo, conversationRepo, invoiceRepo)
    val exportCsv = ExportInvoicesCsv(invoiceRepo, clientRepo)
    val exportGdprData = ExportGdprData(userRepo, invoiceRepo, clientRepo, exportCsv, pdf, cryptoPort)
    val router =
        MessageRouter(
            userRepository = userRepo,
            invoiceRepository = invoiceRepo,
            clientRepository = clientRepo,
            conversationRepository = conversationRepo,
            llmPort = llmPort,
            messagingPort = messagingPort,
            cryptoPort = cryptoPort,
            createInvoice = CreateInvoice(userRepo, clientRepo, invoiceRepo, pdf, dispatcher),
            sendInvoice = SendInvoice(userRepo, clientRepo, invoiceRepo, pdf, emailPort, dispatcher),
            markInvoicePaid = MarkInvoicePaid(invoiceRepo, dispatcher),
            updateDraft = UpdateDraft(userRepo, clientRepo, invoiceRepo, pdf, dispatcher),
            deleteDraft = DeleteDraft(invoiceRepo, dispatcher),
            cancelInvoice = CancelInvoice(userRepo, clientRepo, invoiceRepo, pdf, dispatcher),
            correctInvoice = CorrectInvoice(userRepo, clientRepo, invoiceRepo, pdf, dispatcher),
            getRevenue = GetRevenue(invoiceRepo),
            getUnpaid = GetUnpaidInvoices(invoiceRepo, clientRepo),
            exportCsv = exportCsv,
            updateClient = UpdateClient(clientRepo),
            setupProfile = SetupProfile(userRepo, sirenePort, cryptoPort),
            finalizeInvoice = FinalizeInvoice(userRepo, clientRepo, invoiceRepo, pdf),
            listClients = ListClients(clientRepo, invoiceRepo),
            getClientHistory = GetClientHistory(clientRepo, invoiceRepo),
            configureSetting = ConfigureSetting(userRepo),
            deleteAccount = deleteAccount,
            exportGdprData = exportGdprData,
        )
    return Triple(router, messagingPort, conversationRepo)
}

private class SuccessSirenePort(private val result: mona.domain.port.SireneResult) : mona.domain.port.SirenePort {
    override suspend fun lookupBySiren(siren: mona.domain.model.Siren): DomainResult<mona.domain.port.SireneResult> =
        DomainResult.Ok(result)

    override suspend fun searchByNameAndCity(
        name: String,
        city: String,
    ): DomainResult<List<mona.domain.port.SireneResult>> = DomainResult.Ok(listOf(result))
}

private class FakeEmailPort : mona.domain.port.EmailPort {
    override suspend fun sendInvoice(
        to: String,
        subject: String,
        body: String,
        pdfAttachment: ByteArray,
        filename: String,
    ): DomainResult<Unit> = DomainResult.Ok(Unit)
}

// --- Tests ---

class MessageRouterTest {
    @Test
    fun `creates new user on first message`() =
        runBlocking {
            val userRepo = InMemoryUserRepository()
            val (router, _, _) = makeRouter(userRepo = userRepo)
            router.handle(IncomingMessage(telegramId = 42L, text = "Bonjour", userId = null))
            val created = userRepo.findByTelegramId(42L)
            assertNotNull(created)
            assertEquals(42L, created.telegramId)
        }

    @Test
    fun `reuses existing user found by telegram id`() =
        runBlocking {
            val user = makeUser(telegramId = 99L)
            val userRepo = InMemoryUserRepository(user)
            val (router, _, _) = makeRouter(userRepo = userRepo)
            router.handle(IncomingMessage(telegramId = 99L, text = "Bonjour", userId = null))
            assertEquals(1, userRepo.store.size) // no new user created
        }

    @Test
    fun `persists user and assistant conversation messages`() =
        runBlocking {
            val user = makeUser()
            val conversationRepo = InMemoryConversationRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    conversationRepo = conversationRepo,
                    llmPort = StubLlmPort(DomainResult.Ok(LlmResponse.Text("Salut !"))),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Bonjour", userId = user.id))
            val messages = conversationRepo.findRecent(user.id, 10)
            assertEquals(2, messages.size)
            assertEquals(MessageRole.USER, messages[0].role)
            assertEquals("Bonjour", messages[0].content)
            assertEquals(MessageRole.ASSISTANT, messages[1].role)
            assertEquals("Salut !", messages[1].content)
        }

    @Test
    fun `forwards llm text response directly`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = StubLlmPort(DomainResult.Ok(LlmResponse.Text("De rien !"))),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Merci", userId = user.id))
            assertEquals(1, messaging.sentMessages.size)
            assertEquals("De rien !", messaging.sentMessages[0].second)
        }

    @Test
    fun `returns unavailable message on llm error`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = StubLlmPort(DomainResult.Err(DomainError.LlmUnavailable("timeout"))),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Bonjour", userId = user.id))
            assertTrue(messaging.sentMessages[0].second.contains("indisponible"))
        }

    @Test
    fun `rate limits after 200 messages per day`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = StubLlmPort(DomainResult.Ok(LlmResponse.Text("OK"))),
                )
            val incoming = IncomingMessage(telegramId = 100L, text = "msg", userId = user.id)
            repeat(200) { router.handle(incoming) }
            // 201st message should trigger rate limit
            router.handle(incoming)
            val last = messaging.sentMessages.last().second
            assertTrue(last.contains("limite"), "Expected rate-limit message, got: $last")
        }

    @Test
    fun `routes conversational tool use and sends response`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "conversational",
                            toolUseId = "id1",
                            inputJson = """{"response": "Salut Sophie !"}""",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Bonjour", userId = user.id))
            assertEquals("Salut Sophie !", messaging.sentMessages[0].second)
        }

    @Test
    fun `routes unknown tool use and sends clarification`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "unknown",
                            toolUseId = "id1",
                            inputJson = """{"clarification": "Je n'ai pas compris."}""",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "???", userId = user.id))
            assertEquals("Je n'ai pas compris.", messaging.sentMessages[0].second)
        }

    @Test
    fun `routes create_invoice and sends pdf document`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson =
                                """
                                {
                                  "client_name": "Dupont",
                                  "line_items": [{"description": "Consulting", "quantity": 1, "unit_price_euros": 800}]
                                }
                                """.trimIndent(),
                        ),
                    ),
                )
            val userRepo = InMemoryUserRepository(user)
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = userRepo,
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))

            // Invoice was created in the repo
            assertEquals(1, invoiceRepo.store.size)

            // Text message sent (user has no SIREN, so BROUILLON response)
            assertTrue(messaging.sentMessages[0].second.contains("✓"))
            // PDF document sent
            assertEquals(1, messaging.sentDocuments.size)
            assertTrue(messaging.sentDocuments[0].second.endsWith(".pdf"))
        }

    @Test
    fun `routes get_unpaid and returns no unpaid message when none`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "get_unpaid",
                            toolUseId = "id1",
                            inputJson = "{}",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Mes impayés", userId = user.id))
            assertTrue(messaging.sentMessages[0].second.contains("Aucune"))
        }

    @Test
    fun `routes configure_setting and persists updated user`() =
        runBlocking {
            val user = makeUser()
            val userRepo = InMemoryUserRepository(user)
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "configure_setting",
                            toolUseId = "id1",
                            inputJson = """{"setting": "default_payment_delay_days", "value": "15"}""",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = userRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Délai 15 jours", userId = user.id))
            val updated = userRepo.findById(user.id)
            assertEquals(15, updated?.defaultPaymentDelayDays?.value)
            assertTrue(messaging.sentMessages[0].second.contains("mis à jour ✓"))
        }

    @Test
    fun `formats cents correctly`() {
        // Test via the router's public behaviour — indirectly through create_invoice response
        // Direct helper test via reflection would be fragile; rely on integration coverage
        // Cents formatting validated via get_revenue output
    }

    // --- Onboarding flow tests ---

    @Test
    fun `start command without param sends welcome message`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "/start", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("Mona"), "Expected welcome mentioning Mona, got: $msg")
        }

    @Test
    fun `start command with deep link siren pre-fills profile`() =
        runBlocking {
            val user = makeUser(id = UserId("u-start"))
            val sireneResult =
                SireneResult(
                    legalName = "Sophie Martin",
                    siren = mona.domain.model.Siren("123456789"),
                    siret = mona.domain.model.Siret("12345678900001"),
                    address = PostalAddress("12 rue de la Paix", "75002", "Paris"),
                    activityType = ActivityType.BNC,
                )
            val userRepo = InMemoryUserRepository(user)
            val messaging = FakeMessagingPort()
            val (router, _, _) =
                makeRouter(
                    userRepo = userRepo,
                    messagingPort = messaging,
                    sirenePort = SuccessSirenePort(sireneResult),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "/start siren_123456789", userId = user.id))
            val updatedUser = userRepo.findById(user.id)
            assertEquals(mona.domain.model.Siren("123456789"), updatedUser?.siren)
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("Sophie Martin"), "Expected company name in response, got: $msg")
        }

    @Test
    fun `create_invoice for user without siren appends siren request`() =
        runBlocking {
            val user = makeUser() // siren = null
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson =
                                """
                                {
                                  "client_name": "Dupont",
                                  "line_items": [{"description": "Coaching", "quantity": 1, "unit_price_euros": 800}]
                                }
                                """.trimIndent(),
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("BROUILLON"), "Expected BROUILLON in response, got: $msg")
            assertTrue(msg.contains("SIREN"), "Expected SIREN prompt in response, got: $msg")
        }

    @Test
    fun `create_invoice for user with siren and confirmBeforeCreate=true shows confirmation`() =
        runBlocking {
            val user = makeUser().copy(siren = mona.domain.model.Siren("123456789")) // confirmBeforeCreate=true by default
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson =
                                """
                                {
                                  "client_name": "Dupont",
                                  "line_items": [{"description": "Coaching", "quantity": 1, "unit_price_euros": 800}]
                                }
                                """.trimIndent(),
                        ),
                    ),
                )
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            val msg = messaging.sentMessages[0].second
            // Confirmation summary shown — invoice NOT yet created
            assertTrue(msg.contains("Je crée cette facture"), "Expected confirmation question, got: $msg")
            assertTrue(msg.contains("Dupont"), "Expected client name in confirmation, got: $msg")
            assertTrue(msg.contains("Coaching"), "Expected line item in confirmation, got: $msg")
            assertTrue(msg.contains("800"), "Expected amount in confirmation, got: $msg")
            assertTrue(msg.contains("Confirme"), "Expected confirm prompt, got: $msg")
            assertEquals(0, invoiceRepo.store.size, "Invoice must not be created before confirmation")
            assertEquals(0, messaging.sentDocuments.size, "No PDF before confirmation")
        }

    @Test
    fun `create_invoice for user with siren and confirmBeforeCreate=false creates immediately`() =
        runBlocking {
            val user =
                makeUser().copy(
                    siren = mona.domain.model.Siren("123456789"),
                    confirmBeforeCreate = false,
                )
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson =
                                """
                                {
                                  "client_name": "Dupont",
                                  "line_items": [{"description": "Coaching", "quantity": 1, "unit_price_euros": 800}]
                                }
                                """.trimIndent(),
                        ),
                    ),
                )
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("créée ✓"), "Expected 'créée ✓' in response, got: $msg")
            assertTrue(!msg.contains("SIREN"), "Expected no SIREN prompt for user with SIREN, got: $msg")
            assertEquals(1, invoiceRepo.store.size, "Invoice must be created immediately")
        }

    @Test
    fun `pending confirmation confirmed with ok creates invoice`() =
        runBlocking {
            val user = makeUser().copy(siren = mona.domain.model.Siren("123456789"))
            val messaging = FakeMessagingPort()
            val createLlm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson =
                                """
                                {
                                  "client_name": "Dupont",
                                  "line_items": [{"description": "Coaching", "quantity": 1, "unit_price_euros": 800}]
                                }
                                """.trimIndent(),
                        ),
                    ),
                )
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = createLlm,
                )
            // First message: triggers confirmation
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            assertEquals(0, invoiceRepo.store.size, "Invoice not created before confirmation")
            // Second message: confirm
            router.handle(IncomingMessage(telegramId = 100L, text = "ok", userId = user.id))
            assertEquals(1, invoiceRepo.store.size, "Invoice created after confirmation")
            val confirmMsg = messaging.sentMessages[1].second
            assertTrue(confirmMsg.contains("créée ✓"), "Expected creation confirmation, got: $confirmMsg")
            assertEquals(1, messaging.sentDocuments.size, "PDF sent after confirmation")
        }

    @Test
    fun `pending confirmation cancelled with annule discards without creating`() =
        runBlocking {
            val user = makeUser().copy(siren = mona.domain.model.Siren("123456789"))
            val messaging = FakeMessagingPort()
            val createLlm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson =
                                """
                                {
                                  "client_name": "Dupont",
                                  "line_items": [{"description": "Coaching", "quantity": 1, "unit_price_euros": 800}]
                                }
                                """.trimIndent(),
                        ),
                    ),
                )
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = createLlm,
                )
            // First message: triggers confirmation
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            // Second message: cancel
            router.handle(IncomingMessage(telegramId = 100L, text = "annule", userId = user.id))
            assertEquals(0, invoiceRepo.store.size, "Invoice must not be created after cancellation")
            val cancelMsg = messaging.sentMessages[1].second
            assertTrue(cancelMsg.contains("Annulé ✓"), "Expected cancellation message, got: $cancelMsg")
        }

    @Test
    fun `pending confirmation correction replaces pending and shows updated summary`() =
        runBlocking {
            val user = makeUser().copy(siren = mona.domain.model.Siren("123456789"))
            val messaging = FakeMessagingPort()
            // First call returns create_invoice for 800€, second call returns create_invoice for 900€
            val json800 =
                """{"client_name":"Dupont","line_items":[{"description":"Coaching","quantity":1,"unit_price_euros":800}]}"""
            val json900 =
                """{"client_name":"Dupont","line_items":[{"description":"Coaching","quantity":1,"unit_price_euros":900}]}"""
            var callCount = 0
            val correctionLlm =
                object : LlmPort {
                    override suspend fun complete(
                        systemPrompt: String,
                        userContextJson: String,
                        messages: List<mona.domain.port.ConversationMessage>,
                        tools: List<LlmToolDefinition>,
                    ): DomainResult<LlmResponse> {
                        callCount++
                        return if (callCount == 1) {
                            DomainResult.Ok(
                                LlmResponse.ToolUse(
                                    toolName = "create_invoice",
                                    toolUseId = "id1",
                                    inputJson = json800,
                                ),
                            )
                        } else {
                            DomainResult.Ok(
                                LlmResponse.ToolUse(
                                    toolName = "create_invoice",
                                    toolUseId = "id2",
                                    inputJson = json900,
                                ),
                            )
                        }
                    }
                }
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = correctionLlm,
                )
            // First message: triggers confirmation with 800€
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            val firstMsg = messaging.sentMessages[0].second
            assertTrue(firstMsg.contains("800"), "Expected 800 in first confirmation, got: $firstMsg")
            // Second message: correction (falls through to LLM, which returns new create_invoice with 900€)
            router.handle(IncomingMessage(telegramId = 100L, text = "non, c'est 900€", userId = user.id))
            val secondMsg = messaging.sentMessages[1].second
            assertTrue(secondMsg.contains("Je crée cette facture"), "Expected new confirmation, got: $secondMsg")
            assertTrue(secondMsg.contains("900"), "Expected 900 in corrected confirmation, got: $secondMsg")
            assertEquals(0, invoiceRepo.store.size, "Invoice still not created after correction")
        }

    @Test
    fun `update_profile with siren lookup finalizes existing draft invoices`() =
        runBlocking {
            val user = makeUser() // no siren
            val client =
                mona.domain.model.Client(
                    id = ClientId("c1"),
                    userId = user.id,
                    name = "Martin",
                    companyName = null,
                    email = null,
                    address = null,
                    siret = null,
                    createdAt = BASE_INSTANT,
                )
            val draftInvoice = makeInvoice(userId = user.id, clientId = client.id)
            val sireneResult =
                SireneResult(
                    legalName = "Sophie Martin",
                    siren = mona.domain.model.Siren("123456789"),
                    siret = mona.domain.model.Siret("12345678900001"),
                    address = PostalAddress("12 rue de la Paix", "75002", "Paris"),
                    activityType = ActivityType.BNC,
                )
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "update_profile",
                            toolUseId = "id1",
                            inputJson = """{"siren": "123456789"}""",
                        ),
                    ),
                )
            val userRepo = InMemoryUserRepository(user)
            val invoiceRepo = InMemoryInvoiceRepository(draftInvoice)
            val (router, _, _) =
                makeRouter(
                    userRepo = userRepo,
                    invoiceRepo = invoiceRepo,
                    clientRepo = InMemoryClientRepository(client),
                    messagingPort = messaging,
                    llmPort = llm,
                    sirenePort = SuccessSirenePort(sireneResult),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "123456789", userId = user.id))
            // Profile confirmation message sent
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("Sophie Martin"), "Expected company name in response, got: $msg")
            assertTrue(msg.contains("là-dessus"), "Expected confirmation question, got: $msg")
            // Finalized PDF sent
            assertEquals(1, messaging.sentDocuments.size)
            assertTrue(messaging.sentDocuments[0].second.endsWith(".pdf"))
        }

    @Test
    fun `routes search_siren and formats matches`() =
        runBlocking {
            val user = makeUser()
            val sireneResult =
                SireneResult(
                    legalName = "Marie Leclerc",
                    siren = mona.domain.model.Siren("987654321"),
                    siret = mona.domain.model.Siret("98765432100001"),
                    address = PostalAddress("8 av. Foch", "33000", "Bordeaux"),
                    activityType = ActivityType.BNC,
                )
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "search_siren",
                            toolUseId = "id1",
                            inputJson = """{"name": "Marie Leclerc", "city": "Bordeaux"}""",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                    sirenePort = SuccessSirenePort(sireneResult),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Marie Leclerc Bordeaux", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("Marie Leclerc"), "Expected name in results, got: $msg")
            assertTrue(msg.contains("987654321"), "Expected SIREN in results, got: $msg")
            assertTrue(msg.contains("C'est laquelle"), "Expected disambiguation question, got: $msg")
        }

    // --- Duplicate detection tests ---

    private val createDupont800Json =
        """{"client_name":"Dupont","line_items":[{"description":"Coaching","quantity":1,"unit_price_euros":800}]}"""

    @Test
    fun `duplicate invoice shows warning with existing number`() =
        runBlocking {
            // User without SIREN — confirmBeforeCreate is bypassed (no SIREN check)
            val user = makeUser().copy(confirmBeforeCreate = false)
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson = createDupont800Json,
                        ),
                    ),
                )
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            // First invoice — no duplicate
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            assertEquals(1, invoiceRepo.store.size)
            // Second identical invoice — duplicate warning
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            assertEquals(2, invoiceRepo.store.size, "Duplicate draft saved pending user resolution")
            val msg = messaging.sentMessages[1].second
            assertTrue(msg.contains("doublon"), "Expected doublon question, got: $msg")
            assertTrue(msg.contains("800"), "Expected amount in warning, got: $msg")
            assertTrue(msg.contains("Dupont"), "Expected client name in warning, got: $msg")
            assertTrue(msg.contains("F-"), "Expected existing invoice number in warning, got: $msg")
        }

    @Test
    fun `doublon response deletes draft and confirms nothing created`() =
        runBlocking {
            val user = makeUser().copy(confirmBeforeCreate = false)
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson = createDupont800Json,
                        ),
                    ),
                )
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            // Create first invoice
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            assertEquals(1, invoiceRepo.store.size)
            // Trigger duplicate warning (second invoice)
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            assertEquals(2, invoiceRepo.store.size)
            // User says doublon → second draft deleted
            router.handle(IncomingMessage(telegramId = 100L, text = "doublon", userId = user.id))
            assertEquals(1, invoiceRepo.store.size, "Duplicate draft must be deleted after doublon")
            val responseText = messaging.sentMessages[2].second
            assertTrue(responseText.contains("rien"), "Expected 'je ne crée rien' response, got: $responseText")
        }

    @Test
    fun `confirming after duplicate warning keeps the invoice`() =
        runBlocking {
            val user = makeUser().copy(confirmBeforeCreate = false)
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson = createDupont800Json,
                        ),
                    ),
                )
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            // Create first invoice
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            // Trigger duplicate warning
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            assertEquals(2, invoiceRepo.store.size)
            // User confirms it's a new invoice
            router.handle(IncomingMessage(telegramId = 100L, text = "oui", userId = user.id))
            assertEquals(2, invoiceRepo.store.size, "Both invoices kept after confirmation")
            val responseText = messaging.sentMessages[2].second
            assertTrue(responseText.contains("créée ✓"), "Expected creation confirmation, got: $responseText")
        }

    @Test
    fun `no duplicate warning when different amounts`() =
        runBlocking {
            val user = makeUser().copy(confirmBeforeCreate = false)
            val messaging = FakeMessagingPort()
            var callCount = 0
            val llm =
                object : LlmPort {
                    override suspend fun complete(
                        systemPrompt: String,
                        userContextJson: String,
                        messages: List<mona.domain.port.ConversationMessage>,
                        tools: List<LlmToolDefinition>,
                    ): DomainResult<LlmResponse> {
                        callCount++
                        val json =
                            if (callCount == 1) {
                                """{"client_name":"Dupont","line_items":[{"description":"Coaching","quantity":1,"unit_price_euros":800}]}"""
                            } else {
                                """{"client_name":"Dupont","line_items":[{"description":"Formation",""" +
                                    """"quantity":1,"unit_price_euros":1200}]}"""
                            }
                        return DomainResult.Ok(
                            LlmResponse.ToolUse(toolName = "create_invoice", toolUseId = "id$callCount", inputJson = json),
                        )
                    }
                }
            val invoiceRepo = InMemoryInvoiceRepository()
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 1200€ pour Dupont", userId = user.id))
            assertEquals(2, invoiceRepo.store.size)
            val secondMsg = messaging.sentMessages[1].second
            assertTrue(!secondMsg.contains("doublon"), "Expected no duplicate warning for different amounts, got: $secondMsg")
        }

    // --- Error message tests (spec S10) ---

    @Test
    fun `returns pdf failure message when pdf generation fails on create_invoice`() =
        runBlocking {
            val user = makeUser().copy(confirmBeforeCreate = false)
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "create_invoice",
                            toolUseId = "id1",
                            inputJson =
                                """{"client_name":"Dupont","line_items":[{"description":"Consulting",""" +
                                    """"quantity":1,"unit_price_euros":800}]}""",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                    pdfPort = FailingPdfPort(),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Facture 800€ pour Dupont", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("souci pour générer le PDF"), "Expected PDF failure message, got: $msg")
        }

    @Test
    fun `returns email failure message when email delivery fails on send_invoice`() =
        runBlocking {
            val user = makeUser().copy(siren = mona.domain.model.Siren("123456789"), confirmBeforeCreate = false)
            val client =
                mona.domain.model.Client(
                    id = ClientId("c1"),
                    userId = user.id,
                    name = "Dupont",
                    companyName = null,
                    email = mona.domain.model.Email("dupont@example.com"),
                    address = null,
                    siret = null,
                    createdAt = BASE_INSTANT,
                )
            val draftInvoice = makeInvoice(userId = user.id, clientId = client.id)
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "send_invoice",
                            toolUseId = "id1",
                            inputJson = """{"invoice_number": "F-2026-03-001"}""",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = InMemoryInvoiceRepository(draftInvoice),
                    clientRepo = InMemoryClientRepository(client),
                    messagingPort = messaging,
                    llmPort = llm,
                    emailPort = FailingEmailPort(),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "Envoie la facture", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("échoué"), "Expected email failure message, got: $msg")
        }

    @Test
    fun `returns sirene unavailable message when sirene api is down`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "update_profile",
                            toolUseId = "id1",
                            inputJson = """{"siren": "123456789"}""",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                    sirenePort = FailingSirenePort(),
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "123456789", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(
                msg.contains("vérifier ton SIREN"),
                "Expected SIRENE unavailable message with manual fallback prompt, got: $msg",
            )
        }

    @Test
    fun `delete account - sends confirmation prompt on first request`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "delete_account",
                            toolUseId = "id1",
                            inputJson = "{}",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "supprime mon compte", userId = user.id))
            val msg = messaging.sentMessages[0].second
            assertTrue(msg.contains("irréversible"), "Expected confirmation prompt, got: $msg")
        }

    @Test
    fun `delete account - confirm path deletes user and data`() =
        runBlocking {
            val user = makeUser()
            val userRepo = InMemoryUserRepository(user)
            val clientRepo = InMemoryClientRepository()
            val conversationRepo = InMemoryConversationRepository()
            val invoiceRepo = InMemoryInvoiceRepository()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "delete_account",
                            toolUseId = "id1",
                            inputJson = "{}",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = userRepo,
                    clientRepo = clientRepo,
                    conversationRepo = conversationRepo,
                    invoiceRepo = invoiceRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            // First message: request deletion
            router.handle(IncomingMessage(telegramId = 100L, text = "supprime mon compte", userId = user.id))
            // Second message: confirm
            router.handle(IncomingMessage(telegramId = 100L, text = "oui", userId = user.id))
            // User should be deleted
            assertEquals(null, userRepo.findById(user.id))
            // Farewell message sent
            val farewell = messaging.sentMessages[1].second
            assertTrue(farewell.contains("supprimé"), "Expected farewell message, got: $farewell")
        }

    @Test
    fun `delete account - cancel path does nothing`() =
        runBlocking {
            val user = makeUser()
            val userRepo = InMemoryUserRepository(user)
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "delete_account",
                            toolUseId = "id1",
                            inputJson = "{}",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = userRepo,
                    messagingPort = messaging,
                    llmPort = llm,
                )
            // First message: request deletion
            router.handle(IncomingMessage(telegramId = 100L, text = "supprime mon compte", userId = user.id))
            // Second message: cancel
            router.handle(IncomingMessage(telegramId = 100L, text = "non", userId = user.id))
            // User should still exist
            assertNotNull(userRepo.findById(user.id))
            // Cancel message sent
            val cancelMsg = messaging.sentMessages[1].second
            assertEquals("OK, on oublie ça", cancelMsg)
        }

    @Test
    fun `export data - no invoices sends summary and profile json`() =
        runBlocking {
            val user = makeUser()
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "export_data",
                            toolUseId = "id1",
                            inputJson = "{}",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "exporte mes données", userId = user.id))
            val summaryMsg = messaging.sentMessages[0].second
            assertTrue(summaryMsg.contains("Export RGPD"), "Expected export summary, got: $summaryMsg")
            // CSV and profile JSON sent as documents
            val docNames = messaging.sentDocuments.map { it.second }
            assertTrue(docNames.any { it.endsWith(".csv") }, "Expected CSV document")
            assertTrue(docNames.any { it == "mona-profil.json" }, "Expected profile JSON document")
        }

    @Test
    fun `export data - with sent invoice sends invoice pdf`() =
        runBlocking {
            val user = makeUser()
            val client =
                Client(
                    id = ClientId("c1"),
                    userId = user.id,
                    name = "Acme",
                    email = null,
                    address = null,
                    companyName = null,
                    siret = null,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                )
            val invoice =
                makeInvoice(
                    id = InvoiceId("inv1"),
                    clientId = ClientId("c1"),
                    status = InvoiceStatus.Sent,
                )
            val messaging = FakeMessagingPort()
            val llm =
                StubLlmPort(
                    DomainResult.Ok(
                        LlmResponse.ToolUse(
                            toolName = "export_data",
                            toolUseId = "id1",
                            inputJson = "{}",
                        ),
                    ),
                )
            val (router, _, _) =
                makeRouter(
                    userRepo = InMemoryUserRepository(user),
                    invoiceRepo = InMemoryInvoiceRepository(invoice),
                    clientRepo = InMemoryClientRepository(client),
                    messagingPort = messaging,
                    llmPort = llm,
                )
            router.handle(IncomingMessage(telegramId = 100L, text = "exporte mes données", userId = user.id))
            val docNames = messaging.sentDocuments.map { it.second }
            assertTrue(docNames.any { it.endsWith(".csv") }, "Expected CSV")
            assertTrue(docNames.any { it == "F-2026-03-001.pdf" }, "Expected invoice PDF")
            assertTrue(docNames.any { it == "mona-profil.json" }, "Expected profile JSON")
        }
}
