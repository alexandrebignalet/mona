package mona

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mona.application.EventDispatcher
import mona.application.MessageRouter
import mona.application.client.GetClientHistory
import mona.application.client.ListClients
import mona.application.client.UpdateClient
import mona.application.invoicing.CancelInvoice
import mona.application.invoicing.CorrectInvoice
import mona.application.invoicing.CreateInvoice
import mona.application.invoicing.DeleteDraft
import mona.application.invoicing.HandleBouncedEmail
import mona.application.invoicing.MarkInvoicePaid
import mona.application.invoicing.SendInvoice
import mona.application.invoicing.UpdateDraft
import mona.application.onboarding.FinalizeInvoice
import mona.application.onboarding.OnboardingRecoveryJob
import mona.application.onboarding.SetupProfile
import mona.application.payment.OverdueTransitionJob
import mona.application.payment.PaymentCheckInJob
import mona.application.revenue.ExportInvoicesCsv
import mona.application.revenue.GetRevenue
import mona.application.revenue.GetUnpaidInvoices
import mona.application.settings.ConfigureSetting
import mona.application.urssaf.UrssafReminderJob
import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DomainEvent
import mona.domain.port.MenuItem
import mona.domain.service.RevenueCalculation
import mona.domain.service.UrssafThresholds
import mona.infrastructure.crypto.IbanCrypto
import mona.infrastructure.crypto.IbanCryptoAdapter
import mona.infrastructure.db.DatabaseFactory
import mona.infrastructure.db.ExposedClientRepository
import mona.infrastructure.db.ExposedConversationRepository
import mona.infrastructure.db.ExposedInvoiceRepository
import mona.infrastructure.db.ExposedOnboardingReminderRepository
import mona.infrastructure.db.ExposedUrssafReminderRepository
import mona.infrastructure.db.ExposedUserRepository
import mona.infrastructure.email.ResendEmailAdapter
import mona.infrastructure.email.ResendWebhookHandler
import mona.infrastructure.llm.ClaudeApiClient
import mona.infrastructure.pdf.PdfGenerator
import mona.infrastructure.sirene.SireneApiClient
import mona.infrastructure.telegram.TelegramBotAdapter
import java.net.InetSocketAddress
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val MENU_ITEMS =
    listOf(
        MenuItem("Nouvelle facture"),
        MenuItem("Mes impayés"),
        MenuItem("Mon CA"),
    )

private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

fun main() {
    val dbPath = System.getenv("DATABASE_PATH") ?: "mona.db"
    val telegramToken =
        System.getenv("TELEGRAM_BOT_TOKEN")
            ?: error("TELEGRAM_BOT_TOKEN environment variable is not set")

    // Database
    DatabaseFactory.init(dbPath)

    // Repositories
    val userRepository = ExposedUserRepository()
    val clientRepository = ExposedClientRepository()
    val invoiceRepository = ExposedInvoiceRepository()
    val conversationRepository = ExposedConversationRepository()
    val urssafReminderRepository = ExposedUrssafReminderRepository()
    val onboardingReminderRepository = ExposedOnboardingReminderRepository()

    // Infrastructure adapters
    val cryptoAdapter = IbanCryptoAdapter(IbanCrypto.loadKeyFromEnv())
    val llmPort = ClaudeApiClient.fromEnv()
    val emailPort = ResendEmailAdapter.fromEnv()
    val sirenePort = SireneApiClient.fromEnv()
    val pdfPort = PdfGenerator

    // Coroutine scope for the application
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val telegramAdapter = TelegramBotAdapter(telegramToken, userRepository, scope)

    // Event dispatcher
    val eventDispatcher = EventDispatcher()

    // Use cases
    val createInvoice = CreateInvoice(userRepository, clientRepository, invoiceRepository, pdfPort, eventDispatcher)
    val sendInvoice = SendInvoice(userRepository, clientRepository, invoiceRepository, pdfPort, emailPort, eventDispatcher)
    val markInvoicePaid = MarkInvoicePaid(invoiceRepository, eventDispatcher)
    val updateDraft = UpdateDraft(userRepository, clientRepository, invoiceRepository, pdfPort, eventDispatcher)
    val deleteDraft = DeleteDraft(invoiceRepository, eventDispatcher)
    val cancelInvoice = CancelInvoice(userRepository, clientRepository, invoiceRepository, pdfPort, eventDispatcher)
    val correctInvoice = CorrectInvoice(userRepository, clientRepository, invoiceRepository, pdfPort, eventDispatcher)
    val getRevenue = GetRevenue(invoiceRepository)
    val getUnpaid = GetUnpaidInvoices(invoiceRepository, clientRepository)
    val exportCsv = ExportInvoicesCsv(invoiceRepository, clientRepository)
    val updateClient = UpdateClient(clientRepository)
    val listClients = ListClients(clientRepository, invoiceRepository)
    val getClientHistory = GetClientHistory(clientRepository, invoiceRepository)
    val setupProfile = SetupProfile(userRepository, sirenePort, cryptoAdapter)
    val finalizeInvoice = FinalizeInvoice(userRepository, clientRepository, invoiceRepository, pdfPort)
    val configureSetting = ConfigureSetting(userRepository)

    // Register event handlers
    eventDispatcher.register { event ->
        when (event) {
            is DomainEvent.InvoicePaid -> {
                val yearStart = LocalDate.of(event.paidDate.year, 1, 1)
                val yearEnd = LocalDate.of(event.paidDate.year, 12, 31)
                val period = DeclarationPeriod(yearStart, yearEnd)
                val snapshots = invoiceRepository.findPaidInPeriod(event.userId, period)
                val creditNotes = invoiceRepository.findCreditNotesInPeriod(event.userId, period)
                val breakdown = RevenueCalculation.compute(snapshots, creditNotes)
                val revenue = breakdown.byActivity[event.activityType] ?: Cents.ZERO
                val alert = UrssafThresholds.checkTvaThreshold(revenue, event.activityType)
                if (alert != null) {
                    val pct = alert.percentReached
                    val thresholdEuros = alert.threshold.value / 100
                    val msg =
                        if (pct >= 95) {
                            "⚠️ Attention ! Tu es à $pct% du seuil de franchise TVA ($thresholdEuros €). " +
                                "Tu risques de le dépasser — pense à consulter un comptable."
                        } else {
                            "📊 Tu approches du seuil TVA : $pct% atteint (seuil : $thresholdEuros €). " +
                                "Garde un œil sur ton CA !"
                        }
                    telegramAdapter.sendMessage(event.userId, msg)
                }
            }
            is DomainEvent.InvoiceOverdue -> {
                telegramAdapter.sendMessage(
                    event.userId,
                    "⏰ La facture ${event.invoiceNumber.value} est en retard de paiement " +
                        "(échéance le ${event.dueDate.format(DATE_FMT)}). Tu veux que je relance le client ?",
                )
            }
            else -> Unit
        }
    }

    // Scheduled jobs
    val overdueTransitionJob = OverdueTransitionJob(invoiceRepository, eventDispatcher)
    scope.launch {
        val paris = ZoneId.of("Europe/Paris")
        while (isActive) {
            val now = ZonedDateTime.now(paris)
            val nextRun = now.toLocalDate().plusDays(1).atTime(LocalTime.of(8, 0)).atZone(paris)
            delay(Duration.between(now, nextRun).toMillis().coerceAtLeast(0L))
            try {
                overdueTransitionJob.execute(LocalDate.now(paris))
            } catch (_: Exception) {
            }
        }
    }

    val paymentCheckInJob = PaymentCheckInJob(invoiceRepository, clientRepository, telegramAdapter)
    scope.launch {
        val paris = ZoneId.of("Europe/Paris")
        while (isActive) {
            val now = ZonedDateTime.now(paris)
            val nextRun = now.toLocalDate().plusDays(1).atTime(LocalTime.of(9, 0)).atZone(paris)
            delay(Duration.between(now, nextRun).toMillis().coerceAtLeast(0L))
            try {
                paymentCheckInJob.execute(LocalDate.now(paris))
            } catch (_: Exception) {
            }
        }
    }

    val urssafReminderJob =
        UrssafReminderJob(
            userRepository = userRepository,
            invoiceRepository = invoiceRepository,
            reminderRepository = urssafReminderRepository,
            conversationRepository = conversationRepository,
            messagingPort = telegramAdapter,
        )
    scope.launch {
        val paris = ZoneId.of("Europe/Paris")
        while (isActive) {
            val now = ZonedDateTime.now(paris)
            val nextRun = now.toLocalDate().plusDays(1).atTime(LocalTime.of(10, 0)).atZone(paris)
            delay(Duration.between(now, nextRun).toMillis().coerceAtLeast(0L))
            try {
                urssafReminderJob.execute(LocalDate.now(paris))
            } catch (_: Exception) {
            }
        }
    }

    val onboardingRecoveryJob =
        OnboardingRecoveryJob(
            userRepository = userRepository,
            invoiceRepository = invoiceRepository,
            reminderRepository = onboardingReminderRepository,
            conversationRepository = conversationRepository,
            messagingPort = telegramAdapter,
        )
    scope.launch {
        val paris = ZoneId.of("Europe/Paris")
        while (isActive) {
            val now = ZonedDateTime.now(paris)
            val nextRun = now.toLocalDate().plusDays(1).atTime(LocalTime.of(11, 0)).atZone(paris)
            delay(Duration.between(now, nextRun).toMillis().coerceAtLeast(0L))
            try {
                onboardingRecoveryJob.execute(LocalDate.now(paris))
            } catch (_: Exception) {
            }
        }
    }

    // Message router
    val router =
        MessageRouter(
            userRepository = userRepository,
            invoiceRepository = invoiceRepository,
            clientRepository = clientRepository,
            conversationRepository = conversationRepository,
            llmPort = llmPort,
            messagingPort = telegramAdapter,
            cryptoPort = cryptoAdapter,
            createInvoice = createInvoice,
            sendInvoice = sendInvoice,
            markInvoicePaid = markInvoicePaid,
            updateDraft = updateDraft,
            deleteDraft = deleteDraft,
            cancelInvoice = cancelInvoice,
            correctInvoice = correctInvoice,
            getRevenue = getRevenue,
            getUnpaid = getUnpaid,
            exportCsv = exportCsv,
            updateClient = updateClient,
            setupProfile = setupProfile,
            finalizeInvoice = finalizeInvoice,
            listClients = listClients,
            getClientHistory = getClientHistory,
            configureSetting = configureSetting,
        )

    // Track users whose persistent menu has been initialized this session
    val menuInitialized = ConcurrentHashMap.newKeySet<String>()

    // Email bounce handling
    val handleBouncedEmail = HandleBouncedEmail(invoiceRepository, telegramAdapter)
    val webhookHandler =
        ResendWebhookHandler(
            signingSecret = System.getenv("RESEND_WEBHOOK_SECRET") ?: "",
            handleBouncedEmail = handleBouncedEmail,
            scope = scope,
        )

    // Health check + webhook endpoints
    val healthServer = HttpServer.create(InetSocketAddress(8080), 0)
    healthServer.createContext("/health") { exchange ->
        val response = "OK"
        exchange.sendResponseHeaders(200, response.length.toLong())
        exchange.responseBody.use { it.write(response.toByteArray()) }
    }
    healthServer.createContext("/webhook/resend") { exchange ->
        if (exchange.requestMethod == "POST") {
            webhookHandler.handle(exchange)
        } else {
            exchange.sendResponseHeaders(405, 0)
            exchange.responseBody.close()
        }
    }
    healthServer.start()

    // Register message handler and start bot
    val botJob =
        runBlocking {
            telegramAdapter.onMessage { message ->
                message.userId?.let { userId ->
                    if (menuInitialized.add(userId.value)) {
                        telegramAdapter.setPersistentMenu(userId, MENU_ITEMS)
                    }
                }
                router.handle(message)
            }
            telegramAdapter.start()
        }

    // Graceful shutdown
    Runtime.getRuntime().addShutdownHook(
        Thread {
            healthServer.stop(1)
            botJob.cancel()
            scope.cancel()
        },
    )

    // Block main thread until bot stops
    runBlocking { botJob.join() }
}
