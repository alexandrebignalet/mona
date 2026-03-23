# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

The strategy is: scaffolding -> domain (pure, testable) -> infrastructure adapters -> application use cases -> LLM integration -> end-to-end wiring -> scheduled jobs -> deployment -> polish.

---

## Completed Phases

- **Phase 1.1** (Gradle Project Setup) — Done. build.gradle.kts, directory structure, App.kt, shadowJar all working.
- **Phase 2.1** (DomainResult and DomainError) — Done. Sealed hierarchy with all error types, map/flatMap, unit tests.
- **Phase 2.2** (Value Objects) — Done. Cents, Siren, Siret, Email, PostalAddress, PaymentDelayDays, DeclarationPeriod, IDs. All unit tested.
- **Phase 2.3** (Enums and InvoiceStatus FSM) — Done. PaymentMethod, ActivityType, DeclarationPeriodicity, InvoiceStatus sealed class.
- **Phase 3.1** (DomainEvent, TransitionResult, LineItem, CreditNote) — Done. All event types, LineItem with totalHt, CreditNote. Unit tested.
- **Phase 3.2** (Invoice Aggregate Root) — Done. Factory, all transitions (send, markPaid, markOverdue, cancel), comprehensive tests.
- **Phase 3.3** (Invoice Numbering) — Done. InvoiceNumbering and CreditNoteNumbering with next/validate/isContiguous. Unit tested.
- **Phase 3.4** (User and Client Domain Models) — Done. User and Client data classes with typed value objects.
- **Phase 4.1** (Revenue Calculation and URSSAF Thresholds) — Done. PaidInvoiceSnapshot, CreditNoteSnapshot, RevenueCalculation, UrssafThresholds. Unit tested.
- **Phase 4.2** (Repository Ports and MessagingPort) — Done. All ports: InvoiceRepository, ClientRepository, UserRepository, ConversationRepository, MessagingPort, EmailPort, SirenePort.

- **Phase 5.1** (Exposed Table Definitions and Database Setup) — Done. All tables, WAL mode, schema auto-creation.
- **Phase 5.2** (UserRepository Implementation) — Done. Upsert, nullable mapping, integration tested.
- **Phase 5.3** (ClientRepository Implementation) — Done. Case-insensitive name search, integration tested.
- **Phase 5.4** (InvoiceRepository Implementation) — Done. Full aggregate save/load, snapshots, all query methods, integration tested.
- **Phase 5.5** (ConversationRepository Implementation) — Done. Prune to 3 messages, integration tested.
- **Phase 6.1** (AES-256-GCM IBAN Encryption) — Done. `IbanCrypto` object with `encrypt`/`decrypt`/`loadKeyFromEnv`. Random IV prepended to ciphertext. Unit tests: round-trip, random IV, wrong-key failure, empty string, size check.
- **Issue:** SQLite WAL PRAGMA must be set via raw JDBC before Exposed transaction (cannot run PRAGMA inside transaction). Fixed in DatabaseFactory.

- **Phase 6.2** (PDF Invoice Generation) — Done. `PdfGenerator` object in `infrastructure/pdf/`. Generates A4 PDFs with all mandatory French legal mentions: issue date, invoice number, seller info (name, EI, SIREN, address), buyer info, line items table (description, qty, unit price HT, total HT), totals HT/TTC, TVA non-applicable mention, payment terms and due date, late payment penalties, payment method (if paid), IBAN section (if provided). Draft invoices get "BROUILLON" watermark (45°, light gray, drawn as background layer). Amounts formatted in French currency (e.g., "800,00 EUR") using integer arithmetic. Returns ByteArray. 7 integration tests (all status variants, with/without IBAN, with/without SIREN, multi-line items) using `Loader.loadPDF()`. Note: Payment method on invoice PDFs is only shown for `InvoiceStatus.Paid` since the current Invoice domain model only stores payment method in that status — this is consistent with the existing domain model.
- **Phase 6.3** (Credit Note PDF Generation) — Done. `generateCreditNote(creditNote, originalInvoiceNumber, user, client, plainIban)` added to `PdfGenerator`. Generates A4 PDFs with: "AVOIR" title, credit note number, issue date, reference to original invoice number, seller block, buyer block, amount line (description from reason or default, negative HT amount), totals HT/TTC, TVA non-applicable mention, mandatory late-payment penalty mentions, optional IBAN section. Blank reason falls back to "Annulation facture [originalInvoiceNumber]". 5 integration tests covering: basic PDF, with reason, with IBAN, without user SIREN, blank reason fallback.

- **Phase 6.4** (Resend Email Adapter) — Done. `ResendEmailAdapter` in `infrastructure/email/` implementing `EmailPort`. Uses `java.net.http` + `kotlinx-serialization-json` (no new deps). Internal `HttpExecutor` fun interface enables test injection. PDF base64-encoded in Resend attachment payload. `fromEnv()` factory loads `RESEND_API_KEY`. `DomainError.EmailDeliveryFailed` added to domain. Primary constructor marked `internal` (module-visible) to keep `HttpExecutor` internal. 6 unit tests: Ok on 200, Err on 422 and 500, base64 encoding verified, recipient/sender verified, API key propagation verified.

- **Phase 7.1** (SIRENE API Client) — Done. `SireneApiClient` in `infrastructure/sirene/` implementing `SirenePort`. Uses `java.net.http` + `kotlinx-serialization-json` (no new deps). Internal `SireneHttpExecutor` fun interface enables test injection. `lookupBySiren` calls `GET /siren/{siren}`, `searchByNameAndCity` calls `GET /siret?q=...&nombre=5` with Lucene query. NAF/APE code → ActivityType mapping covers BNC (legal, healthcare, education, engineering, R&D), BIC_VENTE (manufacturing, trade/retail), BIC_SERVICE (default). Legal name extraction handles both companies (denominationUniteLegale) and individual EIs (prenomUsuelUniteLegale + nomUniteLegale). `DomainError.SirenNotFound` and `DomainError.SireneLookupFailed` added. `fromEnv()` factory loads `SIRENE_API_KEY`. 13 unit tests: Ok on 200 for company and EI, Err on 404/401/500/503, API key propagation, URL correctness, NAF mappings (BIC_VENTE/BNC/null), search result parsing, empty list on 404.

- **Phase 8.1** (EventDispatcher) — Done. `EventDispatcher` class in `application/EventDispatcher.kt`. `register(suspend (DomainEvent) -> Unit)` adds handlers. `dispatch(List<DomainEvent>)` calls all handlers for each event sequentially in registration order. 4 unit tests: two handlers receive all events, registration order preserved, no-handler no-op, empty event list calls no handlers.
- **Phase 8.2** (Create Invoice Use Case) — Done. `CreateInvoice` use case in `application/invoicing/`. `PdfPort` interface added to `domain/port/` (generateInvoice, generateCreditNote); `PdfGenerator` now implements `PdfPort`. Use case: resolves or creates client by name, gets next sequential invoice number via `InvoiceNumbering.next()` + repository, calls `Invoice.create()` factory, checks for duplicates (same client+amount within 48h via `findByClientAndAmountSince`), generates PDF via `PdfPort`, persists, dispatches empty event list. Returns `CreateInvoiceResult.Created` or `CreateInvoiceResult.DuplicateWarning`. 7 unit tests with in-memory stubs: happy path, new client creation, existing client reuse, sequential numbering, duplicate warning, user-not-found error, empty-line-items error, PDF included in result.

- **Phase 8.3** (Send Invoice Use Case) — Done. `SendInvoice` use case in `application/invoicing/`. `DomainError.InvoiceNotFound` added to domain. Use case: loads user (rejects if missing), checks SIREN present (`SirenRequired`), loads invoice (`InvoiceNotFound` if missing), loads client (`ClientNotFound` if missing), checks client has email (`ProfileIncomplete`), regenerates PDF via `PdfPort`, transitions invoice Draft→Sent via `invoice.send()`, sends email via `EmailPort`, persists updated invoice, dispatches `InvoiceSent` event. Returns `SendInvoiceResult(invoice, pdf)`. 10 unit tests: happy path (Sent status + persisted), email recipient/subject/filename, PDF in result, InvoiceSent event dispatched, SirenRequired error, user-not-found, invoice-not-found, client-no-email, invalid-transition (already Sent), email delivery failure.

---

## Phase 7: Infrastructure — External APIs

---

## Phase 8: Application Layer — Core Use Cases

- **Phase 8.4** (Mark Invoice Paid Use Case) — Done. `MarkInvoicePaid` use case in `application/invoicing/`. Command holds `userId`, `invoiceId`, `paymentDate`, `paymentMethod`. Loads invoice (InvoiceNotFound if missing), calls `invoice.markPaid()`, persists, dispatches `InvoicePaid` event. Works for DRAFT|SENT|OVERDUE → PAID transitions. 7 unit tests: draft→paid, sent→paid, overdue→paid, persists updated invoice, dispatches InvoicePaid event, InvoiceNotFound, InvalidTransition (Cancelled).

- **Phase 8.5** (Cancel Invoice and Credit Note Use Cases) — Done. `DeleteDraft`, `CancelInvoice`, `CorrectInvoice` use cases in `application/invoicing/`. `findLastCreditNoteNumberInMonth` added to `InvoiceRepository` port and `ExposedInvoiceRepository`. `DeleteDraft`: calls `invoice.cancel(null)`, deletes from DB (number freed), dispatches `DraftDeleted`. `CancelInvoice`: gets next credit note number, creates `CreditNote`, calls `invoice.cancel(creditNote)`, generates credit note PDF via `PdfPort`, persists, dispatches `InvoiceCancelled`. `CorrectInvoice`: reserves new invoice ID, creates credit note linking to new invoice, cancels original, creates corrected invoice with next number, generates both PDFs, persists both, dispatches `InvoiceCancelled`. Unit tests: 5 for DeleteDraft, 8 for CancelInvoice, 8 for CorrectInvoice.

- **Phase 8.6** (Update Draft Use Case) — Done. `UpdateDraft` use case in `application/invoicing/`. `Invoice.updateDraft()` aggregate method added (validates Draft status, validates line items, preserves dueDate delta when only issueDate changes). Use case: loads user, loads invoice, resolves/creates client by name, calls `invoice.updateDraft()`, loads client for PDF, generates PDF, persists, dispatches empty events. Returns `UpdateDraftResult(invoice, pdf)`. `invalidTransition` made generic (`<T>`) to support both `DomainResult<TransitionResult>` and `DomainResult<Invoice>` returns. 10 unit tests: line items update, activity type update, issue date shift, payment delay recompute, existing client resolved, new client created, invoice persisted, InvoiceNotFound, ProfileIncomplete, InvalidTransition on Sent, EmptyLineItems.

- **Phase 8.7** (Revenue Query and CSV Export Use Cases) — Done. `GetRevenue`, `GetUnpaidInvoices`, `ExportInvoicesCsv` use cases in `application/revenue/`. `GetRevenue`: loads `PaidInvoiceSnapshot`s and `CreditNoteSnapshot`s via repository, computes `RevenueBreakdown` via `RevenueCalculation.compute()`, also returns pending count/amount from Sent+Overdue invoices. `GetUnpaidInvoices`: loads Sent+Overdue invoices, resolves client names, returns sorted by due date. `ExportInvoicesCsv`: loads all user invoices+clients, generates UTF-8 CSV with header row and one data row per invoice; line items serialized as `;`-separated sub-fields joined by ` | `; amounts formatted as euros (cents÷100, 2 decimal places); filename `mona-factures-YYYY-MM-DD.csv`. Unit tests: 5 for GetRevenue, 5 for GetUnpaidInvoices, 8 for ExportInvoicesCsv.

- **Phase 8.8** (Client Management Use Cases) — Done. `UpdateClient`, `ListClients`, `GetClientHistory` use cases in `application/client/`. `UpdateClient`: resolves client by `clientId` or `clientName`, returns `Ambiguous(matches)` when multiple name matches, updates email/address/companyName/siret/name fields, persists. `ListClients`: loads all clients, groups invoices by clientId, returns `ClientSummary(client, invoiceCount, totalAmount)` per client. `GetClientHistory`: resolves client by `clientId` or `clientName` (disambiguation supported), filters invoices by client, sorted by issue date. Unit tests: 7 for UpdateClient, 5 for ListClients, 7 for GetClientHistory.

- **Phase 8.9** (Onboarding Use Cases) — Done. `SetupProfile` use case in `application/onboarding/` with sealed command: `LookupSiren` (SIRENE lookup, auto-fills name/siret/address/activityType, returns `SirenFound`), `SearchSiren` (search by name+city, returns `SirenMatches`), `ApplySireneResult` (apply chosen match from search), `UpdateFields` (name, activityType, address, paymentDelay, email, periodicity), `SetIban` (encrypts via `CryptoPort`, stores `ibanEncrypted`). `FinalizeInvoice` use case re-generates PDF without BROUILLON watermark once user has SIREN; requires user with SIREN, invoice, and client. `CryptoPort` interface added to `domain/port/`; `IbanCryptoAdapter` added to `infrastructure/crypto/` implementing it. 10 unit tests for `SetupProfile`, 6 for `FinalizeInvoice`.

---

## Phase 9: Infrastructure — Telegram Adapter

- **Phase 9.1** (TelegramBotAdapter) — Done. `TelegramBotAdapter` in `infrastructure/telegram/` implementing `MessagingPort`. Uses `telegramBotWithBehaviourAndLongPolling` + `onText` trigger to receive all text messages (including commands and deep links). Caches `UserId → telegramId` on first contact and via `UserRepository.findById`. `sendMessage` includes stored `ReplyKeyboardMarkup` (set via `setPersistentMenu`) on every message. `sendButtons` uses `InlineKeyboardMarkup`. `sendDocument` uses `asMultipartFile(ByteArray, fileName)`. Note: `sendDocument` text parameter is named `text` (not `caption`) in TelegramBotAPI v18.

- **Phase 10.1** (Claude API Client) — Done. `ClaudeApiClient` in `infrastructure/llm/` implementing `LlmPort`. Internal `ClaudeHttpExecutor` fun interface for test injection. `RealClaudeHttpExecutor` uses `java.net.http`. Builds request JSON with system prompt + optional user context JSON, conversation history (user/assistant roles), and tool definitions (with `input_schema`). Response parsing: prefers `tool_use` blocks over `text` blocks; returns `LlmResponse.ToolUse` or `LlmResponse.Text`. Non-200 responses and parse errors return `DomainError.LlmUnavailable`. `fromEnv()` loads `ANTHROPIC_API_KEY`. `DomainError.LlmUnavailable` added to domain. 11 unit tests.

---

## Phase 10: LLM Integration

- **Phase 10.2** (Tool Definitions and Action Types) — Done. `ToolDefinitions` object in `infrastructure/llm/` with all 17 `LlmToolDefinition` instances (JSON schemas). `ParsedAction` sealed class with 17 subtypes and `ParsedLineItem` data class in `ActionTypes.kt`. `ActionParser` object parses `LlmResponse.ToolUse` input JSON to the correct `ParsedAction` subtype using kotlinx.serialization.json. Amounts in euros (BigDecimal) at parsing layer; use cases convert to Cents. 32 unit tests covering all 17 tool parsers, optional/required fields, ToolDefinitions count/names/JSON validity.

- **Phase 10.3** (System Prompt and Conversation Management) — Done. `PromptBuilder` object in `infrastructure/llm/`. `SYSTEM_PROMPT` constant (~300 tokens, French, tutoyante, brief, unknown-tool fallback, context-resolution from history). `buildUserContext(user)` produces `{"user_context": {"name"?, "has_siren", "onboarding_step"}}` JSON (new_user / awaiting_siren / complete). `buildContext(user, recentMessages)` assembles `ConversationContext(systemPrompt, userContextJson, messages.takeLast(3))`. 13 unit tests: system prompt content, all 3 onboarding steps, has_siren true/false, name present/absent, message trimming to 3, order preserved, token budget.

- **Phase 10.4** (Golden Test Suite — Parsing) — Done. 67 parsing test cases in `src/test/resources/golden/parsing_cases.json` covering all 17 tool types (create_invoice with simple/multi-line/slang/delay/activity variants, send_invoice, mark_paid, update_draft, delete_draft, cancel_invoice, correct_invoice, get_revenue, export_invoices, get_unpaid, update_client, update_profile, configure_setting, list_clients, client_history, conversational, unknown). 16 context resolution cases in `context_cases.json` covering: pronoun resolution (envoie-la, l'envoyer, annule-la, supprime-la), correction after creation, client clarification, same-for-other-client, anaphora (son email), follow-up period queries, cross-reference from prior turn, mark-paid referencing prior invoice. `GoldenTestLoader` (object) in `src/test/kotlin/mona/golden/`: loads JSON via kotlinx.serialization raw API, calls `ClaudeApiClient.fromEnv()`, validates tool name (exact) and top-level string params (case-insensitive contains). `GoldenParsingTest` (`@Tag("golden-parsing")`) and `GoldenContextTest` (`@Tag("golden-context")`) use JUnit 5 `@TestFactory` generating dynamic tests, each named `[category] id: description`. Both skip automatically via `Assumptions.assumeTrue` when `ANTHROPIC_API_KEY` is not set. Selective execution: `./gradlew test --tests "*.GoldenParsingTest"` or `-Dgolden.categories=create_invoice,mark_paid`.

---

## Phase 11: Message Router and End-to-End Wiring

- **Phase 11.1** (Message Router) — Done. `MessageRouter` in `application/` orchestrates all 17 action types. User resolved or created by telegramId on first contact. Loads last 3 conversation messages for Claude context via `PromptBuilder`. Calls `LlmPort` → parses `LlmResponse` via `ActionParser` → routes to appropriate use case. Formats French response strings inline. Sends text + PDF/CSV documents via `MessagingPort`. Persists USER + ASSISTANT messages to `ConversationRepository`. In-memory rate limiting: 200 messages/user/day (resets at midnight). Top-level `try-catch` in `handleAction` returns friendly French error. `formatDomainError` maps all 14 `DomainError` subtypes to French. 11 unit tests covering: user creation, rate limit, LLM error, text forwarding, conversational routing, unknown routing, create_invoice routing + PDF, get_unpaid empty, configure_setting persistence, conversation message persistence.

- **Phase 11.2** (Onboarding Flow Integration) — Done. Progressive onboarding integrated into `MessageRouter`. `search_siren` tool added to `ToolDefinitions` (18 tools total) and `ParsedAction`. `handleStartCommand` handles `/start` and `/start siren_XXXXXXXXX` deep links — pre-fills SIREN via `SetupProfile.LookupSiren` and returns welcome with company name. `handleCreateInvoice` detects user has no SIREN and returns BROUILLON message + SIREN request instead of the usual "créée ✓" message. `handleUpdateProfile` with SIREN: builds profile confirmation text with auto-filled name/address/activity, then calls `finalizeDraftInvoices` to regenerate draft PDFs without BROUILLON watermark. `handleSearchSiren` calls `SetupProfile.SearchSiren` and formats numbered matches with SIREN numbers visible for Claude to resolve selection. `PromptBuilder.SYSTEM_PROMPT` extended with onboarding guidance (search_siren for unknown SIREN, progressive payment terms → IBAN → email after SIREN confirmation). `buildUserContext` adds `has_iban` and `has_email` flags. `saveConversation` extracted as helper, used by both `/start` handler and main flow. 3 golden test cases added for `search_siren`. `ActionParserTest` updated to 18 tools. 6 new unit tests covering: `/start` welcome, `/start` deep link SIREN pre-fill, create invoice without SIREN (BROUILLON prompt), create invoice with SIREN (normal response), SIREN lookup finalizes drafts + profile confirm, search_siren routing with matches.

- **Phase 11.3** (Confirmation Flow) — Done. In-memory `pendingConfirmationMap` in `MessageRouter`. When user has SIREN and `confirmBeforeCreate=true`, `handleCreateInvoice` stores `PendingConfirmation` and returns a summary ("Je crée cette facture ? → Client … → Total … Confirme avec OK ou corrige-moi ✏️"). `handle()` intercepts next message before LLM: standalone confirm tokens (ok/oui/confirme/…) execute the stored command; standalone cancel tokens (non/annule/…) discard it; any other text falls through to LLM (correction path — LLM returns new `create_invoice`, replacing the pending state). When SIREN is absent or `confirmBeforeCreate=false`, invoice created immediately. 5 new unit tests: shows confirmation with SIREN, creates immediately without confirmation flag, ok confirms, annule cancels, correction replaces pending summary.

- **Phase 11.4** (App.kt — Full Wiring) — Done. All dependencies wired manually in `App.kt`. Database initialized via `DatabaseFactory.init(dbPath)`. All repositories, adapters (Telegram, Claude, PDF, Resend, SIRENE, Crypto), and use cases constructed. EventDispatcher registered with two handlers: `InvoicePaid` → year-to-date revenue query + `UrssafThresholds.checkTvaThreshold()` + send TVA alert at 80%/95%; `InvoiceOverdue` → notify user with invoice number and due date. Persistent menu (Nouvelle facture / Mes impayés / Mon CA) lazily set per-user on first message (tracked in-process via `ConcurrentHashMap`). Health check HTTP server at `/health` (port 8080, JDK `com.sun.net.httpserver`). Environment variables: `TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`, `RESEND_API_KEY`, `IBAN_ENCRYPTION_KEY`, `SIRENE_API_KEY`, `DATABASE_PATH`. Graceful shutdown via `Runtime.addShutdownHook` (stops health server, cancels bot job and coroutine scope). Main thread blocks on bot job via `runBlocking { botJob.join() }`.

---

## Phase 12: Scheduled Jobs

- **Phase 12.1** (Payment Check-In Job) — Done. `PaymentCheckInJob` in `application/payment/`. `findSentDueOn(date: LocalDate)` added to `InvoiceRepository` port and `ExposedInvoiceRepository`. Job queries invoices where `due_date = yesterday AND status = SENT`, groups by userId, sends per-user message via `MessagingPort`. Single invoice: "La facture X de Client (montant) devait être payée hier — c'est fait ?". Multiple invoices: "N factures arrivaient à échéance hier : → Client — montant (F-...) Lesquels t'ont payé ?". Scheduled daily at 09:00 Paris time via coroutine in `App.kt`. 6 unit tests: no messages when no invoices, single invoice message to correct user, message format matches spec, multiple invoices batched per user, multiple users get separate messages, amount with cents formatted correctly.

### 12.2 Overdue Transition Job

- **Phase 12.2** (Overdue Transition Job) — Done. `OverdueTransitionJob` in `application/payment/`. Queries `findSentOverdue(today.minusDays(3))`, calls `invoice.markOverdue()`, persists, dispatches `InvoiceOverdue` events. Notification sent via existing App.kt event handler. Scheduled daily at 08:00 Paris time (before payment check-in at 09:00). 6 unit tests: no-op on empty, cutoff = today-3, Overdue status persisted, InvoiceOverdue event dispatched with correct details, multiple invoices all processed, idempotent second run.

- **Phase 12.3** (URSSAF Reminder Jobs) — Done. `UrssafReminderJob` in `application/urssaf/`. `UrssafReminderRecord` domain model and `UrssafReminderRepository` port added. `UrssafRemindersTable` added (composite PK: user_id + period_key). `ExposedUrssafReminderRepository` implemented. `UserRepository.findAllWithPeriodicity()` added. Job: for each user with periodicity, computes deadline via `UrssafThresholds.nextDeclarationDeadline(periodicity, today.minusMonths(1))` (offset ensures approaching deadline is found), checks daysUntil == 7 or 1, loads revenue via `findPaidInPeriod` + `findCreditNotesInPeriod` (cash basis), sends formatted French message with URSSAF portal link. D-1 skipped if user sent any conversation message after D-7. Both D-7 and D-1 are idempotent (sent-at timestamps stored). Scheduled daily at 10:00 Paris time in App.kt. 8 unit tests: no users, D-7 fires with revenue, D-1 fires when not acknowledged, D-1 skipped when acknowledged, D-7 idempotent, D-1 idempotent, mixed-activity breakdown, cash-basis period correctness.

- **Phase 12.4** (Onboarding Recovery Job) — Done. `OnboardingRecoveryJob` in `application/onboarding/`. `OnboardingReminderRecord` domain model and `OnboardingReminderRepository` port added. `OnboardingRemindersTable` added (PK: user_id). `ExposedOnboardingReminderRepository` implemented. `UserRepository.findAllWithoutSiren()` added (port + implementation). Job: for each user without SIREN who has Draft invoices, checks oldest draft age (Paris timezone): at 1+ days sends Reminder 1 (SIREN nudge); at 3+ days, if R1 was sent and user hasn't replied to it, sends Reminder 2 (proactive name+city search offer). Both reminders are idempotent (sent-at timestamps stored). Scheduled daily at 11:00 Paris time in App.kt. 8 unit tests: no drafts, has SIREN skipped, R1 sent at 1+ days, R2 sent when unacknowledged, R2 skipped when acknowledged, R1 idempotent, R2 idempotent, multiple users independently processed.

---

## Phase 13: Deployment

- **Phase 13.1** (Dockerfile and Fly.io Configuration) — Done. `Dockerfile` (multi-stage: eclipse-temurin:21-jdk-alpine builder + eclipse-temurin:21-jre-alpine runtime, Litestream v0.3.13 binary installed from GitHub release). `fly.toml` (app=mona, region=cdg, persistent volume `mona_data` → `/data`, `[http_service]` with `/health` check every 15s, `auto_stop_machines=false`, 1 shared CPU / 512 MB). `litestream.yml` (S3-compatible replica with env-var interpolation for bucket/endpoint/keys, sync-interval=1s for RPO < 1 min). `start.sh` (restores from Litestream if DB absent, then `litestream replicate -exec "java -jar /app/mona.jar"`). Env vars documented: `TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`, `RESEND_API_KEY`, `IBAN_ENCRYPTION_KEY`, `SIRENE_API_KEY`, `DATABASE_PATH` (non-secret, set in fly.toml), `LITESTREAM_BUCKET`, `LITESTREAM_ACCESS_KEY_ID`, `LITESTREAM_SECRET_ACCESS_KEY`, `LITESTREAM_ENDPOINT`, `LITESTREAM_REGION` (secrets, set via `fly secrets set`).

- **Phase 13.2** (Email Bounce Webhook) — Done. `Invoice.revertToDraft()` added (SENT→DRAFT transition). `InvoiceRepository.findByNumber(InvoiceNumber): List<Invoice>` added to port and `ExposedInvoiceRepository`. `HandleBouncedEmail` use case in `application/invoicing/`: finds invoices by number, reverts each Sent invoice to Draft, notifies user via MessagingPort. `ResendWebhookHandler` in `infrastructure/email/`: verifies Svix HMAC-SHA256 signature (`whsec_` prefix), parses `email.bounced` event JSON, extracts invoice number from subject via regex, dispatches to `HandleBouncedEmail` via coroutine scope. `/webhook/resend` POST endpoint wired into existing HttpServer in App.kt; `RESEND_WEBHOOK_SECRET` env var (empty = skip signature check). 4 domain tests (revertToDraft: Sent→Draft, preserves data, fails on Draft/Paid). 6 use case tests (happy path, message content, invoice not found, non-Sent skip, multi-user, data preserved). 7 webhook handler tests (valid bounce, invalid sig→400, non-bounce event, malformed JSON, missing number, empty secret skip, correct extraction).

---

## Phase 14: Polish and Hardening

- **Phase 14.1** (User Settings Management) — Done. `ConfigureSetting` use case in `application/settings/`. `DomainError.UnknownSetting` and `DomainError.InvalidSettingValue` added. Handles `confirm_before_create` (any of true/oui/1/yes → true, else false) and `default_payment_delay_days` (int, clamped 1–60). `MessageRouter.handleConfigureSetting` delegates to use case; `formatDomainError` extended for both new errors. 8 unit tests: confirm off, confirm on via oui, delay update, clamp to 60, invalid non-integer, unknown setting, user not found, persists to repo.

### 14.2 VAT Threshold Alerts
- **Layer:** application
- **Spec:** mvp-spec S7
- **What:** Wire threshold alerts into the InvoicePaid event handler. After each payment, check cumulative annual revenue against franchise en base limits. Alert at 80% and 95%.
- **Acceptance criteria:**
  - [ ] On `InvoicePaid` event, compute year-to-date revenue for user's activity type
  - [ ] Alert at 80%: "Tu approches du seuil de TVA..."
  - [ ] Alert at 95%: "Attention, tu es tres proche du seuil..."
  - [ ] Alert sent via MessagingPort
  - [ ] No duplicate alerts for same threshold crossing
  - [ ] Unit test with mock revenue data at various thresholds
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 14.3 Duplicate Detection Integration
- **Layer:** application
- **Spec:** mvp-spec S2.5
- **What:** Integrate duplicate detection into invoice creation flow. Before creating, check for same client + same amount within last 48 hours. If found, warn user and ask for confirmation.
- **Acceptance criteria:**
  - [ ] Queries `findByClientAndAmountSince` before creation
  - [ ] If duplicate found, returns warning with existing invoice reference
  - [ ] User can confirm "new invoice" or "doublon"
  - [ ] "Doublon" response cancels creation
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 14.4 Error Messages and Edge Cases
- **Layer:** all
- **Spec:** mvp-spec S10
- **What:** Review and implement all user-facing error messages from mvp-spec S10. LLM unavailable, SIRENE down (manual fallback), email failure, PDF failure. Non-EUR currency rejection. Ensure all errors are in French, conversational, and actionable.
- **Acceptance criteria:**
  - [ ] LLM unavailable: "Je suis momentanement indisponible..."
  - [ ] SIRENE down: falls back to manual data collection
  - [ ] Email failure: "L'envoi a echoue, je reessaie automatiquement."
  - [ ] PDF failure: "J'ai un souci pour generer le PDF..."
  - [ ] Non-EUR currency: "Pour l'instant je ne gere que l'euro..."
  - [ ] All error messages match mvp-spec S10 table
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Prevention Rules

These rules are accumulated from implementation experience. Initially seeded from specs.

1. **No floating point for money.** All amounts in `Cents` (Long). Violations caught in code review.
2. **Domain purity.** Any import of Exposed, TelegramBotAPI, or HTTP client in `domain/` is a build failure.
3. **No raw primitives for domain concepts.** If you write `String` where `InvoiceId` should go, fix it.
4. **State transitions via aggregate only.** Never `invoice.copy(status = ...)` outside `Invoice.kt`.
5. **Factory for creation.** Never construct `Invoice(...)` directly for new invoices — use `Invoice.create()`.
6. **Events returned, not published.** Aggregate methods return `TransitionResult`. Application dispatches.
7. **One repo per aggregate root.** No `LineItemRepository` or `CreditNoteRepository`.
8. **CreditNote inside Invoice aggregate.** Never query credit notes independently.
9. **Invoice numbers: no gaps.** Draft deletion frees the number. Cancellation preserves it.
10. **URSSAF = cash basis.** Revenue computed from `paid_date`, never `issue_date`.
11. **Application use cases: max ~30 lines.** If it's longer, you're putting business logic in the wrong layer.
12. **Last 3 messages only.** No separate "last action" pointer. Claude resolves from conversation history.
13. **No unapproved libraries.** Check CLAUDE.md tech stack before adding any dependency.
14. **Golden tests gate prompt changes.** Never deploy a prompt change without running the golden suite.
