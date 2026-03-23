package mona.application

import mona.application.client.GetClientHistory
import mona.application.client.GetClientHistoryCommand
import mona.application.client.GetClientHistoryResult
import mona.application.client.ListClients
import mona.application.client.ListClientsCommand
import mona.application.client.UpdateClient
import mona.application.client.UpdateClientCommand
import mona.application.client.UpdateClientResult
import mona.application.invoicing.CancelInvoice
import mona.application.invoicing.CancelInvoiceCommand
import mona.application.invoicing.CorrectInvoice
import mona.application.invoicing.CorrectInvoiceCommand
import mona.application.invoicing.CreateInvoice
import mona.application.invoicing.CreateInvoiceCommand
import mona.application.invoicing.CreateInvoiceResult
import mona.application.invoicing.DeleteDraft
import mona.application.invoicing.DeleteDraftCommand
import mona.application.invoicing.MarkInvoicePaid
import mona.application.invoicing.MarkInvoicePaidCommand
import mona.application.invoicing.SendInvoice
import mona.application.invoicing.SendInvoiceCommand
import mona.application.invoicing.UpdateDraft
import mona.application.invoicing.UpdateDraftCommand
import mona.application.onboarding.FinalizeInvoice
import mona.application.onboarding.FinalizeInvoiceCommand
import mona.application.onboarding.SetupProfile
import mona.application.onboarding.SetupProfileCommand
import mona.application.onboarding.SetupProfileResult
import mona.application.revenue.ExportInvoicesCsv
import mona.application.revenue.ExportInvoicesCsvCommand
import mona.application.revenue.GetRevenue
import mona.application.revenue.GetRevenueCommand
import mona.application.revenue.GetUnpaidInvoices
import mona.application.revenue.GetUnpaidInvoicesCommand
import mona.domain.model.ActivityType
import mona.domain.model.Cents
import mona.domain.model.DeclarationPeriod
import mona.domain.model.DeclarationPeriodicity
import mona.domain.model.DomainError
import mona.domain.model.DomainResult
import mona.domain.model.Email
import mona.domain.model.Invoice
import mona.domain.model.InvoiceStatus
import mona.domain.model.LineItem
import mona.domain.model.PaymentDelayDays
import mona.domain.model.PaymentMethod
import mona.domain.model.PostalAddress
import mona.domain.model.Siren
import mona.domain.model.Siret
import mona.domain.model.User
import mona.domain.model.UserId
import mona.domain.port.ClientRepository
import mona.domain.port.ConversationMessage
import mona.domain.port.ConversationRepository
import mona.domain.port.CryptoPort
import mona.domain.port.IncomingMessage
import mona.domain.port.InvoiceRepository
import mona.domain.port.LlmPort
import mona.domain.port.LlmResponse
import mona.domain.port.MessageRole
import mona.domain.port.MessagingPort
import mona.domain.port.UserRepository
import mona.infrastructure.llm.ActionParser
import mona.infrastructure.llm.ParsedAction
import mona.infrastructure.llm.ParsedLineItem
import mona.infrastructure.llm.PromptBuilder
import mona.infrastructure.llm.ToolDefinitions
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private typealias RouteResult = Pair<String, List<Pair<ByteArray, String>>>

private data class PendingConfirmation(
    val command: CreateInvoiceCommand,
    val displayClientName: String,
)

private val CONFIRM_TOKENS = setOf("ok", "oui", "confirme", "c'est bon", "vas-y", "go", "yes")
private val CANCEL_TOKENS = setOf("non", "annule", "annuler", "cancel", "no", "stop", "nope")
private val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

class MessageRouter(
    private val userRepository: UserRepository,
    private val invoiceRepository: InvoiceRepository,
    private val clientRepository: ClientRepository,
    private val conversationRepository: ConversationRepository,
    private val llmPort: LlmPort,
    private val messagingPort: MessagingPort,
    private val cryptoPort: CryptoPort,
    private val createInvoice: CreateInvoice,
    private val sendInvoice: SendInvoice,
    private val markInvoicePaid: MarkInvoicePaid,
    private val updateDraft: UpdateDraft,
    private val deleteDraft: DeleteDraft,
    private val cancelInvoice: CancelInvoice,
    private val correctInvoice: CorrectInvoice,
    private val getRevenue: GetRevenue,
    private val getUnpaid: GetUnpaidInvoices,
    private val exportCsv: ExportInvoicesCsv,
    private val updateClient: UpdateClient,
    private val setupProfile: SetupProfile,
    private val finalizeInvoice: FinalizeInvoice,
    private val listClients: ListClients,
    private val getClientHistory: GetClientHistory,
) {
    private val rateLimitMap = ConcurrentHashMap<String, Pair<LocalDate, Int>>()
    private val rateLimitLock = Any()
    private val pendingConfirmationMap = ConcurrentHashMap<String, PendingConfirmation>()

    suspend fun handle(message: IncomingMessage) {
        val today = LocalDate.now()
        val user = resolveOrCreateUser(message.telegramId, message.userId) ?: return

        if (!checkRateLimit(user.id, today)) {
            messagingPort.sendMessage(
                user.id,
                "Tu as atteint la limite de messages pour aujourd'hui — on se retrouve demain !",
            )
            return
        }

        // Handle /start command (bypasses LLM)
        if (message.text.trimStart().startsWith("/start")) {
            handleStartCommand(user, message.text)
            return
        }

        // Handle pending invoice confirmation (bypasses LLM for simple yes/no)
        val pending = pendingConfirmationMap[user.id.value]
        if (pending != null) {
            val lower = message.text.trim().lowercase()

            // A token matches as a standalone yes/no only if nothing meaningful follows it.
            // "non, c'est 900€" must fall through to LLM as a correction, not be treated as cancel.
            fun isStandaloneToken(tokens: Set<String>): Boolean =
                tokens.any { token ->
                    lower.startsWith(token) &&
                        lower.substring(token.length).all { c -> !c.isLetterOrDigit() }
                }
            when {
                isStandaloneToken(CONFIRM_TOKENS) -> {
                    pendingConfirmationMap.remove(user.id.value)
                    val (responseText, documents) = executePendingCreate(user, pending)
                    messagingPort.sendMessage(user.id, responseText)
                    documents.forEach { (bytes, filename) ->
                        messagingPort.sendDocument(user.id, bytes, filename, null)
                    }
                    saveConversation(user.id, message.text, responseText)
                    return
                }
                isStandaloneToken(CANCEL_TOKENS) -> {
                    pendingConfirmationMap.remove(user.id.value)
                    val cancelText = "Annulé ✓ La facture n'a pas été créée."
                    messagingPort.sendMessage(user.id, cancelText)
                    saveConversation(user.id, message.text, cancelText)
                    return
                }
                // Otherwise fall through to LLM (user is correcting the pending invoice)
            }
        }

        val recentMessages = conversationRepository.findRecent(user.id, 3)
        val context = PromptBuilder.buildContext(user, recentMessages)
        val llmResult =
            llmPort.complete(context.systemPrompt, context.userContextJson, context.messages, ToolDefinitions.all)

        val (responseText, documents) =
            when (llmResult) {
                is DomainResult.Err ->
                    Pair("Je suis momentanément indisponible — réessaie dans quelques minutes 🔄", emptyList())
                is DomainResult.Ok -> route(user, llmResult.value)
            }

        messagingPort.sendMessage(user.id, responseText)
        documents.forEach { (bytes, filename) ->
            messagingPort.sendDocument(user.id, bytes, filename, null)
        }

        saveConversation(user.id, message.text, responseText)
    }

    private suspend fun saveConversation(
        userId: UserId,
        userText: String,
        assistantText: String,
    ) {
        val now = Instant.now()
        conversationRepository.save(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                userId = userId,
                role = MessageRole.USER,
                content = userText,
                createdAt = now,
            ),
        )
        conversationRepository.save(
            ConversationMessage(
                id = UUID.randomUUID().toString(),
                userId = userId,
                role = MessageRole.ASSISTANT,
                content = assistantText,
                createdAt = now.plusMillis(1),
            ),
        )
    }

    private suspend fun handleStartCommand(
        user: User,
        text: String,
    ) {
        val param = text.trimStart().removePrefix("/start").trim()
        val responseText: String
        val documents = mutableListOf<Pair<ByteArray, String>>()

        if (param.startsWith("siren_")) {
            val sirenDigits = param.removePrefix("siren_").filter { it.isDigit() }
            val siren = runCatching { Siren(sirenDigits) }.getOrNull()
            if (siren != null) {
                when (val r = setupProfile.execute(SetupProfileCommand.LookupSiren(user.id, siren))) {
                    is DomainResult.Ok -> {
                        val found = (r.value as? SetupProfileResult.SirenFound)?.user
                        val name = found?.name ?: "ton entreprise"
                        responseText =
                            "Bienvenue sur Mona ! J'ai retrouvé $name. " +
                            "Dis-moi « Facture 500€ pour [Client], [prestation] » pour créer ta première facture ✓"
                    }
                    is DomainResult.Err -> responseText = formatDomainError(r.error)
                }
            } else {
                responseText = "Ce SIREN n'est pas valide — il doit contenir 9 chiffres."
            }
        } else {
            responseText =
                "Salut ! Moi c'est Mona, je gère ta facturation en 2 secondes. " +
                "Essaie : dis-moi « Facture 500€ pour Dupont, consulting »"
        }

        messagingPort.sendMessage(user.id, responseText)
        documents.forEach { (bytes, filename) -> messagingPort.sendDocument(user.id, bytes, filename, null) }
        saveConversation(user.id, text, responseText)
    }

    private suspend fun resolveOrCreateUser(
        telegramId: Long,
        userId: UserId?,
    ): User? {
        if (userId != null) return userRepository.findById(userId)
        val existing = userRepository.findByTelegramId(telegramId)
        if (existing != null) return existing
        val newUser =
            User(
                id = UserId(UUID.randomUUID().toString()),
                telegramId = telegramId,
                email = null,
                name = null,
                siren = null,
                siret = null,
                address = null,
                ibanEncrypted = null,
                activityType = null,
                declarationPeriodicity = null,
                confirmBeforeCreate = true,
                defaultPaymentDelayDays = PaymentDelayDays(30),
                createdAt = Instant.now(),
            )
        userRepository.save(newUser)
        return newUser
    }

    private fun checkRateLimit(
        userId: UserId,
        today: LocalDate,
    ): Boolean {
        synchronized(rateLimitLock) {
            val current = rateLimitMap[userId.value]
            return when {
                current == null || current.first != today -> {
                    rateLimitMap[userId.value] = Pair(today, 1)
                    true
                }
                current.second < 200 -> {
                    rateLimitMap[userId.value] = Pair(today, current.second + 1)
                    true
                }
                else -> false
            }
        }
    }

    private suspend fun route(
        user: User,
        llmResponse: LlmResponse,
    ): RouteResult =
        when (llmResponse) {
            is LlmResponse.Text -> Pair(llmResponse.text, emptyList())
            is LlmResponse.ToolUse -> {
                val action = ActionParser.parse(llmResponse.toolName, llmResponse.inputJson)
                handleAction(user, action)
            }
        }

    private suspend fun handleAction(
        user: User,
        action: ParsedAction,
    ): RouteResult =
        try {
            when (action) {
                is ParsedAction.Conversational -> Pair(action.response, emptyList())
                is ParsedAction.Unknown -> Pair(action.clarification, emptyList())
                is ParsedAction.CreateInvoice -> handleCreateInvoice(user, action)
                is ParsedAction.SendInvoice -> handleSendInvoice(user, action)
                is ParsedAction.MarkPaid -> handleMarkPaid(user, action)
                is ParsedAction.UpdateDraft -> handleUpdateDraft(user, action)
                is ParsedAction.DeleteDraft -> handleDeleteDraft(user, action)
                is ParsedAction.CancelInvoice -> handleCancelInvoice(user, action)
                is ParsedAction.CorrectInvoice -> handleCorrectInvoice(user, action)
                is ParsedAction.GetRevenue -> handleGetRevenue(user, action)
                is ParsedAction.ExportInvoices -> handleExportInvoices(user)
                is ParsedAction.GetUnpaid -> handleGetUnpaid(user)
                is ParsedAction.UpdateClient -> handleUpdateClient(user, action)
                is ParsedAction.UpdateProfile -> handleUpdateProfile(user, action)
                is ParsedAction.SearchSiren -> handleSearchSiren(user, action)
                is ParsedAction.ConfigureSetting -> handleConfigureSetting(user, action)
                is ParsedAction.ListClients -> handleListClients(user)
                is ParsedAction.ClientHistory -> handleClientHistory(user, action)
            }
        } catch (e: Exception) {
            Pair("J'ai eu un problème inattendu — réessaie dans un instant.", emptyList())
        }

    // --- Invoice handlers ---

    private suspend fun handleCreateInvoice(
        user: User,
        action: ParsedAction.CreateInvoice,
    ): RouteResult {
        val today = LocalDate.now()
        val issueDate = parseDate(action.issueDate) ?: today
        val activityType = parseActivityType(action.activityType) ?: user.activityType ?: ActivityType.BNC
        val paymentDelay =
            action.paymentDelayDays?.coerceIn(1, 60)?.let { PaymentDelayDays(it) }
                ?: user.defaultPaymentDelayDays
        val lineItems = convertLineItems(action.lineItems)
        if (lineItems.isEmpty()) return Pair("Il faut au moins une ligne pour créer une facture.", emptyList())

        val command =
            CreateInvoiceCommand(
                userId = user.id,
                clientName = action.clientName,
                lineItems = lineItems,
                issueDate = issueDate,
                activityType = activityType,
                paymentDelay = paymentDelay,
            )

        // Show confirmation summary when user is fully onboarded and confirmBeforeCreate is enabled
        if (user.siren != null && user.confirmBeforeCreate) {
            pendingConfirmationMap[user.id.value] = PendingConfirmation(command, action.clientName)
            return Pair(buildConfirmationText(action.clientName, lineItems, issueDate), emptyList())
        }

        return executeCreate(user, action.clientName, command)
    }

    private suspend fun executePendingCreate(
        user: User,
        pending: PendingConfirmation,
    ): RouteResult = executeCreate(user, pending.displayClientName, pending.command)

    private suspend fun executeCreate(
        user: User,
        displayClientName: String,
        command: CreateInvoiceCommand,
    ): RouteResult =
        when (val result = createInvoice.execute(command)) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok ->
                when (val r = result.value) {
                    is CreateInvoiceResult.Created -> {
                        val text =
                            if (user.siren == null) {
                                "Voilà un aperçu de ta facture ✓\n📄 PDF BROUILLON en pièce jointe.\n" +
                                    "Pour la finaliser, j'ai besoin de ton numéro SIREN — c'est quoi ?\n" +
                                    "(Tu le trouves sur autoentrepreneur.urssaf.fr ou ton certificat INSEE)"
                            } else {
                                "Facture ${r.invoice.number.value} créée ✓\n" +
                                    "📄 PDF en pièce jointe.\n" +
                                    "Je l'envoie par mail à $displayClientName ?"
                            }
                        Pair(text, listOf(Pair(r.pdf, "${r.invoice.number.value}.pdf")))
                    }
                    is CreateInvoiceResult.DuplicateWarning -> {
                        val amount = formatCents(r.invoice.amountHt)
                        val text =
                            "Tu m'as déjà demandé une facture de $amount pour $displayClientName " +
                                "(${r.existingNumber.value}). C'est une deuxième facture ou un doublon ?"
                        Pair(text, listOf(Pair(r.pdf, "${r.invoice.number.value}.pdf")))
                    }
                }
        }

    private fun buildConfirmationText(
        clientName: String,
        lineItems: List<LineItem>,
        issueDate: LocalDate,
    ): String {
        val sb = StringBuilder()
        sb.append("Je crée cette facture ?\n")
        sb.append("→ Client : $clientName\n")
        lineItems.forEach { item ->
            val qty = item.quantity.stripTrailingZeros().toPlainString()
            sb.append("→ ${qty}x ${item.description} — ${formatCents(item.unitPriceHt)} HT\n")
        }
        val total = lineItems.fold(Cents(0)) { acc, item -> acc + item.totalHt }
        sb.append("→ Total : ${formatCents(total)} HT\n")
        sb.append("→ Date : ${issueDate.format(DATE_FORMATTER)}\n")
        sb.append("Confirme avec OK ou corrige-moi ✏️")
        return sb.toString()
    }

    private suspend fun handleSendInvoice(
        user: User,
        action: ParsedAction.SendInvoice,
    ): RouteResult {
        val invoice =
            findInvoiceByRef(user.id, action.invoiceNumber, action.clientName)
                ?: return Pair("Je ne trouve pas cette facture.", emptyList())
        val plainIban = user.ibanEncrypted?.let { cryptoPort.decrypt(it) }
        return when (val result = sendInvoice.execute(SendInvoiceCommand(user.id, invoice.id, plainIban))) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok -> {
                val client = clientRepository.findById(result.value.invoice.clientId)
                val emailStr = client?.email?.value ?: ""
                Pair("Envoyé à $emailStr ✓", emptyList())
            }
        }
    }

    private suspend fun handleMarkPaid(
        user: User,
        action: ParsedAction.MarkPaid,
    ): RouteResult {
        val invoice =
            findInvoiceByRef(user.id, action.invoiceNumber, action.clientName)
                ?: return Pair("Je ne trouve pas cette facture.", emptyList())
        val paymentDate = parseDate(action.paymentDate) ?: LocalDate.now()
        val method = parsePaymentMethod(action.paymentMethod)
        return when (val result = markInvoicePaid.execute(MarkInvoicePaidCommand(user.id, invoice.id, paymentDate, method))) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok ->
                Pair(
                    "Noté ✓ Facture ${result.value.number.value} marquée comme payée par ${paymentMethodLabel(method)}.",
                    emptyList(),
                )
        }
    }

    private suspend fun handleUpdateDraft(
        user: User,
        action: ParsedAction.UpdateDraft,
    ): RouteResult {
        val invoice =
            findInvoiceByRef(user.id, action.invoiceNumber, action.clientName)
                ?: return Pair("Je ne trouve pas cette facture.", emptyList())
        val command =
            UpdateDraftCommand(
                userId = user.id,
                invoiceId = invoice.id,
                clientName = action.newClientName,
                lineItems = action.lineItems?.let { convertLineItems(it) },
                issueDate = parseDate(action.issueDate),
                paymentDelay = action.paymentDelayDays?.coerceIn(1, 60)?.let { PaymentDelayDays(it) },
                activityType = parseActivityType(action.activityType),
            )
        return when (val result = updateDraft.execute(command)) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok -> {
                val r = result.value
                Pair(
                    "Mis à jour ✓ Facture ${r.invoice.number.value} — ${formatCents(r.invoice.amountHt)} HT.\n" +
                        "📄 Nouveau PDF en pièce jointe.",
                    listOf(Pair(r.pdf, "${r.invoice.number.value}.pdf")),
                )
            }
        }
    }

    private suspend fun handleDeleteDraft(
        user: User,
        action: ParsedAction.DeleteDraft,
    ): RouteResult {
        val invoice =
            findInvoiceByRef(user.id, action.invoiceNumber, action.clientName)
                ?: return Pair("Je ne trouve pas cette facture.", emptyList())
        return when (val result = deleteDraft.execute(DeleteDraftCommand(user.id, invoice.id))) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok -> Pair("Facture ${result.value.number.value} supprimée ✓", emptyList())
        }
    }

    private suspend fun handleCancelInvoice(
        user: User,
        action: ParsedAction.CancelInvoice,
    ): RouteResult {
        val invoice =
            findInvoiceByRef(user.id, action.invoiceNumber, action.clientName)
                ?: return Pair("Je ne trouve pas cette facture.", emptyList())
        val command = CancelInvoiceCommand(user.id, invoice.id, action.reason ?: "", LocalDate.now())
        return when (val result = cancelInvoice.execute(command)) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok -> {
                val r = result.value
                val cnNumber = r.invoice.creditNote?.number?.value ?: ""
                Pair(
                    "Facture ${r.invoice.number.value} annulée ✓ Avoir $cnNumber créé.\n📄 Avoir en pièce jointe.",
                    listOf(Pair(r.creditNotePdf, "$cnNumber.pdf")),
                )
            }
        }
    }

    private suspend fun handleCorrectInvoice(
        user: User,
        action: ParsedAction.CorrectInvoice,
    ): RouteResult {
        val invoice =
            findInvoiceByRef(user.id, action.invoiceNumber, action.clientName)
                ?: return Pair("Je ne trouve pas cette facture.", emptyList())
        val lineItems = action.lineItems?.let { convertLineItems(it) } ?: invoice.lineItems
        if (lineItems.isEmpty()) return Pair("Il faut au moins une ligne pour corriger la facture.", emptyList())
        val command =
            CorrectInvoiceCommand(
                userId = user.id,
                invoiceId = invoice.id,
                correctedLineItems = lineItems,
                correctedActivityType = parseActivityType(action.activityType),
                correctedPaymentDelay = action.paymentDelayDays?.coerceIn(1, 60)?.let { PaymentDelayDays(it) },
                creditNoteReason = "",
                issueDate = LocalDate.now(),
            )
        return when (val result = correctInvoice.execute(command)) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok -> {
                val r = result.value
                val cnNumber = r.cancelledInvoice.creditNote?.number?.value ?: ""
                Pair(
                    "Corrigé ✓ Nouvelle facture ${r.newInvoice.number.value} — ${formatCents(r.newInvoice.amountHt)} HT.\n" +
                        "📄 Les documents en pièce jointe (facture corrigée + avoir d'annulation).",
                    listOf(
                        Pair(r.newInvoicePdf, "${r.newInvoice.number.value}.pdf"),
                        Pair(r.creditNotePdf, "$cnNumber.pdf"),
                    ),
                )
            }
        }
    }

    // --- Revenue handlers ---

    private suspend fun handleGetRevenue(
        user: User,
        action: ParsedAction.GetRevenue,
    ): RouteResult {
        val today = LocalDate.now()
        val year = action.year ?: today.year
        val period =
            when (action.periodType) {
                "month" -> DeclarationPeriod.monthly(year, action.month ?: today.monthValue)
                "quarter" -> DeclarationPeriod.quarterly(year, action.quarter ?: ((today.monthValue - 1) / 3 + 1))
                else -> DeclarationPeriod(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
            }
        val result = getRevenue.execute(GetRevenueCommand(user.id, period))
        val total = formatCents(result.breakdown.total)
        val paidSuffix = if (result.paidCount == 1) "" else "s"
        val lines = mutableListOf<String>()
        lines += "$year : $total encaissé sur ${result.paidCount} facture$paidSuffix."
        if (result.pendingCount > 0) {
            val pendingSuffix = if (result.pendingCount == 1) "" else "s"
            lines += "${result.pendingCount} facture$pendingSuffix en attente (${formatCents(result.pendingAmount)})."
        }
        if (result.breakdown.byActivity.size > 1) {
            result.breakdown.byActivity.forEach { (type, amount) ->
                lines += "→ ${activityTypeLabel(type)} : ${formatCents(amount)}"
            }
        }
        return Pair(lines.joinToString("\n"), emptyList())
    }

    private suspend fun handleExportInvoices(user: User): RouteResult {
        val result = exportCsv.execute(ExportInvoicesCsvCommand(user.id, LocalDate.now()))
        val text = "Voici l'export de tes ${result.invoiceCount} factures ✓\n📎 ${result.filename}"
        return Pair(text, listOf(Pair(result.csvContent, result.filename)))
    }

    private suspend fun handleGetUnpaid(user: User): RouteResult {
        val result = getUnpaid.execute(GetUnpaidInvoicesCommand(user.id))
        if (result.items.isEmpty()) return Pair("Aucune facture impayée 🎉", emptyList())
        val today = LocalDate.now()
        val lines = mutableListOf<String>()
        val count = result.items.size
        lines += "$count facture${if (count > 1) "s" else ""} impayée${if (count > 1) "s" else ""} :"
        result.items.forEach { item ->
            val daysRemaining = item.invoice.dueDate.toEpochDay() - today.toEpochDay()
            val dueLabel =
                when {
                    daysRemaining < 0 -> "échue depuis ${-daysRemaining} jour${if (-daysRemaining > 1) "s" else ""}"
                    daysRemaining == 0L -> "échéance aujourd'hui"
                    else -> "échéance dans $daysRemaining jour${if (daysRemaining > 1) "s" else ""}"
                }
            lines += "→ ${item.clientName} — ${formatCents(item.invoice.amountHt)} — ${item.invoice.number.value} ($dueLabel)"
        }
        return Pair(lines.joinToString("\n"), emptyList())
    }

    // --- Client handlers ---

    private suspend fun handleUpdateClient(
        user: User,
        action: ParsedAction.UpdateClient,
    ): RouteResult {
        val siret =
            action.siret?.let { raw ->
                runCatching { Siret(raw.filter { it.isDigit() }) }.getOrNull()
            }
        val address = buildAddress(action.addressStreet, action.addressPostalCode, action.addressCity)
        val command =
            UpdateClientCommand(
                userId = user.id,
                clientName = action.clientName,
                newName = action.newName,
                email = action.email?.let { runCatching { Email(it) }.getOrNull() },
                address = address,
                companyName = action.companyName,
                siret = siret,
            )
        return when (val result = updateClient.execute(command)) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok ->
                when (val r = result.value) {
                    is UpdateClientResult.Updated -> Pair("Client ${r.client.name} mis à jour ✓", emptyList())
                    is UpdateClientResult.Ambiguous -> {
                        val names = r.matches.joinToString(" ou ") { it.name }
                        Pair("J'ai plusieurs clients avec ce nom : $names — lequel ?", emptyList())
                    }
                }
        }
    }

    private suspend fun handleUpdateProfile(
        user: User,
        action: ParsedAction.UpdateProfile,
    ): RouteResult {
        if (action.siren != null) {
            val siren =
                runCatching { Siren(action.siren.filter { it.isDigit() }) }.getOrNull()
                    ?: return Pair("Ce SIREN n'est pas valide — il doit contenir 9 chiffres.", emptyList())
            return when (val r = setupProfile.execute(SetupProfileCommand.LookupSiren(user.id, siren))) {
                is DomainResult.Err -> Pair(formatDomainError(r.error), emptyList())
                is DomainResult.Ok -> {
                    val found = (r.value as? SetupProfileResult.SirenFound)?.user
                    val profileText = buildProfileConfirmText(found)
                    val plainIban = found?.ibanEncrypted?.let { cryptoPort.decrypt(it) }
                    val draftPdfs = finalizeDraftInvoices(user.id, plainIban)
                    Pair(profileText, draftPdfs)
                }
            }
        }

        if (action.iban != null) {
            val ibanResult = setupProfile.execute(SetupProfileCommand.SetIban(user.id, action.iban))
            if (ibanResult is DomainResult.Err) return Pair(formatDomainError(ibanResult.error), emptyList())
        }

        val address = buildAddress(action.addressStreet, action.addressPostalCode, action.addressCity)
        val hasOtherFields =
            action.name != null || action.activityType != null || action.email != null ||
                action.paymentDelayDays != null || action.declarationPeriodicity != null || address != null

        if (!hasOtherFields) return Pair(if (action.iban != null) "IBAN enregistré ✓" else "Profil mis à jour ✓", emptyList())

        val updateCmd =
            SetupProfileCommand.UpdateFields(
                userId = user.id,
                name = action.name,
                activityType = parseActivityType(action.activityType),
                address = address,
                defaultPaymentDelayDays = action.paymentDelayDays?.coerceIn(1, 60)?.let { PaymentDelayDays(it) },
                email = action.email?.let { runCatching { Email(it) }.getOrNull() },
                declarationPeriodicity = action.declarationPeriodicity?.let { parsePeriodicity(it) },
            )
        return when (val r = setupProfile.execute(updateCmd)) {
            is DomainResult.Err -> Pair(formatDomainError(r.error), emptyList())
            is DomainResult.Ok -> Pair("Profil mis à jour ✓", emptyList())
        }
    }

    private suspend fun handleSearchSiren(
        user: User,
        action: ParsedAction.SearchSiren,
    ): RouteResult {
        return when (val r = setupProfile.execute(SetupProfileCommand.SearchSiren(user.id, action.name, action.city))) {
            is DomainResult.Err -> Pair(formatDomainError(r.error), emptyList())
            is DomainResult.Ok -> {
                val matches = (r.value as? SetupProfileResult.SirenMatches)?.matches ?: emptyList()
                if (matches.isEmpty()) {
                    return Pair(
                        "Je n'ai rien trouvé sous ce nom dans cette ville. " +
                            "Tu peux vérifier l'orthographe ou me donner ton SIREN directement ?",
                        emptyList(),
                    )
                }
                val lines = mutableListOf<String>()
                lines += "J'ai trouvé ${matches.size} résultat${if (matches.size > 1) "s" else ""} :"
                matches.forEachIndexed { i, match ->
                    val addr = match.address?.let { ", ${it.street}, ${it.postalCode} ${it.city}" } ?: ""
                    val activity = match.activityType?.let { ", ${activityTypeLabel(it)}" } ?: ""
                    lines += "${i + 1}. ${match.legalName} (SIREN : ${match.siren.value})$addr$activity"
                }
                lines += "C'est laquelle ?"
                Pair(lines.joinToString("\n"), emptyList())
            }
        }
    }

    private fun buildProfileConfirmText(user: User?): String {
        if (user == null) return "Parfait ✓ SIREN enregistré. On part là-dessus ?"
        val parts = mutableListOf<String>()
        if (user.address != null) parts += "${user.address.street}, ${user.address.postalCode} ${user.address.city}"
        if (user.activityType != null) parts += activityTypeLabel(user.activityType)
        val name = user.name ?: "ton entreprise"
        val details = if (parts.isNotEmpty()) " — ${parts.joinToString(", ")}" else ""
        return "J'ai trouvé $name$details. On part là-dessus ?"
    }

    private suspend fun finalizeDraftInvoices(
        userId: UserId,
        plainIban: String?,
    ): List<Pair<ByteArray, String>> {
        val drafts = invoiceRepository.findByUser(userId).filter { it.status is InvoiceStatus.Draft }
        return drafts.mapNotNull { invoice ->
            when (val fr = finalizeInvoice.execute(FinalizeInvoiceCommand(userId, invoice.id, plainIban))) {
                is DomainResult.Ok -> Pair(fr.value.pdf, "${invoice.number.value}.pdf")
                is DomainResult.Err -> null
            }
        }
    }

    private suspend fun handleConfigureSetting(
        user: User,
        action: ParsedAction.ConfigureSetting,
    ): RouteResult {
        val updated =
            when (action.setting) {
                "confirm_before_create" -> {
                    val on = action.value.lowercase() in listOf("true", "oui", "1", "yes")
                    user.copy(confirmBeforeCreate = on)
                }
                "default_payment_delay_days" -> {
                    val days =
                        action.value.toIntOrNull()?.coerceIn(1, 60)
                            ?: return Pair("Délai invalide — utilise un nombre entre 1 et 60.", emptyList())
                    user.copy(defaultPaymentDelayDays = PaymentDelayDays(days))
                }
                else -> return Pair("Je ne connais pas ce paramètre.", emptyList())
            }
        userRepository.save(updated)
        return Pair("Paramètre mis à jour ✓", emptyList())
    }

    private suspend fun handleListClients(user: User): RouteResult {
        val result = listClients.execute(ListClientsCommand(user.id))
        if (result.clients.isEmpty()) return Pair("Tu n'as pas encore de clients.", emptyList())
        val count = result.clients.size
        val lines = mutableListOf<String>()
        lines += "Tu as $count client${if (count > 1) "s" else ""} :"
        result.clients.forEach { summary ->
            val n = summary.invoiceCount
            lines += "→ ${summary.client.name} — $n facture${if (n > 1) "s" else ""} (${formatCents(summary.totalAmount)} total)"
        }
        return Pair(lines.joinToString("\n"), emptyList())
    }

    private suspend fun handleClientHistory(
        user: User,
        action: ParsedAction.ClientHistory,
    ): RouteResult {
        val command = GetClientHistoryCommand(userId = user.id, clientName = action.clientName)
        return when (val result = getClientHistory.execute(command)) {
            is DomainResult.Err -> Pair(formatDomainError(result.error), emptyList())
            is DomainResult.Ok ->
                when (val r = result.value) {
                    is GetClientHistoryResult.Found -> {
                        if (r.invoices.isEmpty()) return Pair("Aucune facture pour ${r.client.name}.", emptyList())
                        val n = r.invoices.size
                        val lines = mutableListOf<String>()
                        lines += "${r.client.name} — $n facture${if (n > 1) "s" else ""} :"
                        r.invoices.forEach { invoice ->
                            lines += "→ ${invoice.number.value} — ${formatCents(invoice.amountHt)} — ${statusLabel(invoice.status)}"
                        }
                        Pair(lines.joinToString("\n"), emptyList())
                    }
                    is GetClientHistoryResult.Ambiguous -> {
                        val names = r.matches.joinToString(" ou ") { it.name }
                        Pair("J'ai plusieurs clients avec ce nom : $names — lequel ?", emptyList())
                    }
                }
        }
    }

    // --- Helpers ---

    private suspend fun findInvoiceByRef(
        userId: UserId,
        invoiceNumber: String?,
        clientName: String?,
    ): Invoice? {
        val all = invoiceRepository.findByUser(userId)
        if (invoiceNumber != null) return all.firstOrNull { it.number.value == invoiceNumber }
        if (clientName != null) {
            val clients = clientRepository.findByUserAndName(userId, clientName)
            if (clients.isEmpty()) return null
            val clientId = clients.first().id
            return all
                .filter { it.clientId == clientId && it.status !is InvoiceStatus.Cancelled }
                .maxByOrNull { it.issueDate }
        }
        return all.filter { it.status !is InvoiceStatus.Cancelled }.maxByOrNull { it.issueDate }
    }

    private fun convertLineItems(items: List<ParsedLineItem>): List<LineItem> =
        items.map { item ->
            val unitCents =
                item.unitPriceEuros
                    .multiply(BigDecimal(100))
                    .setScale(0, RoundingMode.HALF_UP)
                    .toLong()
            LineItem(item.description, item.quantity, Cents(unitCents))
        }

    private fun parseDate(iso: String?): LocalDate? = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private fun parseActivityType(s: String?): ActivityType? =
        when (s?.uppercase()?.replace("-", "_")) {
            "BNC" -> ActivityType.BNC
            "BIC_VENTE" -> ActivityType.BIC_VENTE
            "BIC_SERVICE" -> ActivityType.BIC_SERVICE
            else -> null
        }

    private fun parsePeriodicity(s: String): DeclarationPeriodicity? =
        when (s.uppercase()) {
            "MONTHLY", "MENSUEL" -> DeclarationPeriodicity.MONTHLY
            "QUARTERLY", "TRIMESTRIEL" -> DeclarationPeriodicity.QUARTERLY
            else -> null
        }

    private fun parsePaymentMethod(s: String): PaymentMethod =
        runCatching { PaymentMethod.valueOf(s.uppercase()) }.getOrDefault(PaymentMethod.AUTRE)

    private fun buildAddress(
        street: String?,
        postalCode: String?,
        city: String?,
    ): PostalAddress? {
        if (street == null && postalCode == null && city == null) return null
        return PostalAddress(street ?: "", postalCode ?: "", city ?: "")
    }

    private fun formatCents(cents: Cents): String {
        val v = cents.value
        val abs = if (v < 0) -v else v
        val euros = abs / 100
        val centsPart = abs % 100
        val prefix = if (v < 0) "-" else ""
        return if (centsPart == 0L) "$prefix$euros€" else "$prefix$euros,${centsPart.toString().padStart(2, '0')}€"
    }

    private fun activityTypeLabel(type: ActivityType): String =
        when (type) {
            ActivityType.BNC -> "Prestations libérales (BNC)"
            ActivityType.BIC_SERVICE -> "Prestations de services (BIC)"
            ActivityType.BIC_VENTE -> "Vente de marchandises (BIC)"
        }

    private fun statusLabel(status: InvoiceStatus): String =
        when (status) {
            is InvoiceStatus.Draft -> "BROUILLON"
            is InvoiceStatus.Sent -> "ENVOYÉE"
            is InvoiceStatus.Paid -> "PAYÉE ✓"
            is InvoiceStatus.Overdue -> "EN RETARD"
            is InvoiceStatus.Cancelled -> "ANNULÉE"
        }

    private fun paymentMethodLabel(method: PaymentMethod): String =
        when (method) {
            PaymentMethod.VIREMENT -> "virement"
            PaymentMethod.CHEQUE -> "chèque"
            PaymentMethod.ESPECES -> "espèces"
            PaymentMethod.CARTE -> "carte"
            PaymentMethod.AUTRE -> "autre moyen"
        }

    private fun formatDomainError(error: DomainError): String =
        when (error) {
            is DomainError.InvoiceNotFound -> "Je ne trouve pas cette facture."
            is DomainError.ClientNotFound -> "Je ne trouve pas ce client."
            is DomainError.InvalidTransition -> "Cette facture ne peut pas être modifiée dans son état actuel."
            is DomainError.ProfileIncomplete ->
                "Ton profil est incomplet — il manque : ${error.missing.joinToString(", ")}."
            is DomainError.SirenRequired -> "J'ai besoin de ton SIREN pour faire ça. Tu peux me le donner ?"
            is DomainError.EmptyLineItems -> "Il faut au moins une ligne pour créer une facture."
            is DomainError.EmailDeliveryFailed -> "L'envoi a échoué, je réessaie automatiquement."
            is DomainError.SirenNotFound ->
                "Je n'ai pas trouvé ce SIREN dans la base INSEE. Tu peux vérifier le numéro ?"
            is DomainError.SireneLookupFailed ->
                "Je n'arrive pas à vérifier ton SIREN — tu peux me donner ton nom, adresse et type d'activité ?"
            is DomainError.LlmUnavailable ->
                "Je suis momentanément indisponible — réessaie dans quelques minutes 🔄"
            is DomainError.NegativeAmount -> "Les montants ne peuvent pas être négatifs."
            is DomainError.InvoiceNumberGap -> "Problème de numérotation — réessaie."
            is DomainError.InvoiceNotCancellable ->
                "Cette facture ne peut pas être annulée directement — utilise la correction."
            is DomainError.CreditNoteAmountMismatch -> "Problème avec le montant de l'avoir."
        }
}
