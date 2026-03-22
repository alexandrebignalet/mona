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

---

## Phase 7: Infrastructure — External APIs

### 7.1 SIRENE API Client
- **Layer:** infrastructure
- **Spec:** tech-spec S10 (SIRENE API), mvp-spec S1 (SIREN verification)
- **What:** Implement `SireneApiClient` in `infrastructure/sirene/` implementing `SirenePort`. Support lookup by SIREN and search by name+city. Map API response to domain types (Siren, Siret, PostalAddress, ActivityType via NAF/APE code mapping).
- **Acceptance criteria:**
  - [ ] Implements `SirenePort` interface
  - [ ] `lookupBySiren` calls INSEE API, returns legal name, SIREN, SIRET, address, activity type
  - [ ] `searchByNameAndCity` calls INSEE API full-text search, returns max 5 results
  - [ ] NAF/APE code -> ActivityType mapping covers common auto-entrepreneur codes
  - [ ] Returns `Err` on API failure (not exception)
  - [ ] API key loaded from `SIRENE_API_KEY` environment variable
  - [ ] Unit test with mocked HTTP responses
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 8: Application Layer — Core Use Cases

### 8.1 EventDispatcher
- **Layer:** application
- **Spec:** tech-spec S2.9
- **What:** Implement `EventDispatcher` class with `register(handler)` and `dispatch(events)`. Handlers are `suspend (DomainEvent) -> Unit`. Dispatched sequentially after persist.
- **Acceptance criteria:**
  - [ ] `register` adds handlers
  - [ ] `dispatch` calls all registered handlers for each event
  - [ ] Handlers are called in registration order
  - [ ] Unit test: register two handlers, dispatch events, verify both called
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.2 Create Invoice Use Case
- **Layer:** application
- **Spec:** mvp-spec S2.1-2.4, tech-spec S2.5
- **What:** Implement `CreateInvoice` use case in `application/invoicing/`. Orchestrates: get next invoice number, create client if new, call `Invoice.create()` factory, generate draft PDF, persist invoice, dispatch events. Handle duplicate detection (same client + amount within 48h).
- **Acceptance criteria:**
  - [ ] Generates sequential invoice number via `InvoiceNumbering.next()` + repository
  - [ ] Creates new client if name not found
  - [ ] Calls `Invoice.create()` factory (never constructs directly)
  - [ ] Generates PDF (draft watermark if user has no SIREN)
  - [ ] Persists invoice
  - [ ] Dispatches domain events
  - [ ] Returns duplicate warning if same client+amount within 48h
  - [ ] Max ~30 lines of orchestration
  - [ ] Unit test with in-memory repository stubs
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.3 Send Invoice Use Case
- **Layer:** application
- **Spec:** mvp-spec S4, tech-spec S2.5
- **What:** Implement `SendInvoice` use case. Validates user profile completeness (SIREN required). Sends email via `EmailPort`. Transitions invoice Draft->Sent. Re-generates PDF if invoice was modified since creation.
- **Acceptance criteria:**
  - [ ] Rejects if user has no SIREN (returns `SirenRequired` error)
  - [ ] Sends email with PDF attachment
  - [ ] Transitions invoice to Sent status via `invoice.send()`
  - [ ] Persists updated invoice
  - [ ] Dispatches `InvoiceSent` event
  - [ ] Unit test with mocked ports
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.4 Mark Invoice Paid Use Case
- **Layer:** application
- **Spec:** mvp-spec S5, tech-spec S2.9 (example)
- **What:** Implement `MarkInvoicePaid` use case. Finds invoice, calls `invoice.markPaid()`, persists, dispatches events.
- **Acceptance criteria:**
  - [ ] Transitions invoice to Paid with date and method
  - [ ] Returns error if invoice not found
  - [ ] Returns error if transition is invalid
  - [ ] Persists and dispatches events
  - [ ] Max ~30 lines
  - [ ] Unit test
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.5 Cancel Invoice and Credit Note Use Cases
- **Layer:** application
- **Spec:** mvp-spec S2.4, S2.9
- **What:** Implement `DeleteDraft` (deletes draft, frees number), `CancelInvoice` (cancels sent/paid with credit note), `CorrectInvoice` (cancel old + create new replacement). Credit note PDF generation included.
- **Acceptance criteria:**
  - [ ] `DeleteDraft`: deletes draft invoice from database (number freed), dispatches `DraftDeleted`
  - [ ] `CancelInvoice`: generates credit note number, creates `CreditNote`, calls `invoice.cancel(creditNote)`, generates credit note PDF, persists, dispatches events
  - [ ] `CorrectInvoice`: cancels original (with credit note), creates new corrected invoice, links replacement. Returns both credit note and new invoice
  - [ ] Unit tests for all three use cases
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.6 Update Draft Use Case
- **Layer:** application
- **Spec:** mvp-spec S2.4
- **What:** Implement `UpdateDraft` use case. Allows updating any field on a draft invoice (amount, description, client, line items). Re-generates PDF after update.
- **Acceptance criteria:**
  - [ ] Only works on Draft status invoices
  - [ ] Updates specified fields
  - [ ] Re-generates PDF
  - [ ] Persists updated invoice
  - [ ] Unit test
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.7 Revenue Query and CSV Export Use Cases
- **Layer:** application
- **Spec:** mvp-spec S6, S8
- **What:** Implement `GetRevenue` use case (queries paid invoices in period, computes breakdown via `RevenueCalculation`). Implement `GetUnpaidInvoices` (lists outstanding). Implement `ExportInvoicesCsv` (generates CSV file content from all user invoices).
- **Acceptance criteria:**
  - [ ] `GetRevenue` returns `RevenueBreakdown` for requested period (month/quarter/year)
  - [ ] `GetUnpaidInvoices` returns sent + overdue invoices sorted by due date
  - [ ] `ExportInvoicesCsv` produces CSV with columns: invoice number, status, issue date, due date, paid date, client name, line items, total HT, total TTC, payment method
  - [ ] CSV filename format: `mona-factures-YYYY-MM-DD.csv`
  - [ ] Unit tests for all three
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.8 Client Management Use Cases
- **Layer:** application
- **Spec:** mvp-spec S3
- **What:** Implement `UpdateClient` (add/update client info), `ListClients` (list all clients with invoice summary), `GetClientHistory` (invoice history for a client). Client disambiguation logic (multiple matches by name).
- **Acceptance criteria:**
  - [ ] `UpdateClient` updates client fields (email, address, company name, SIRET)
  - [ ] `ListClients` returns clients with invoice count and total amount
  - [ ] `GetClientHistory` returns all invoices for a client
  - [ ] Disambiguation: when multiple clients match a name, return the list for user selection
  - [ ] Unit tests
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 8.9 Onboarding Use Cases
- **Layer:** application
- **Spec:** mvp-spec S1
- **What:** Implement `SetupProfile` use case for progressive onboarding steps: SIREN verification (via SirenePort), profile confirmation, payment terms, IBAN collection, email collection, URSSAF periodicity. Implement `FinalizeInvoice` that re-generates PDF without watermark after SIREN is provided.
- **Acceptance criteria:**
  - [ ] SIREN lookup via SirenePort, auto-fills name/address/activity
  - [ ] SIREN search by name+city when user doesn't know SIREN
  - [ ] Profile update persisted after each step
  - [ ] IBAN encrypted before storage via IbanCrypto
  - [ ] `FinalizeInvoice` re-generates PDF without BROUILLON watermark, updates invoice
  - [ ] Unit tests with mocked SirenePort
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 9: Infrastructure — Telegram Adapter

### 9.1 TelegramBotAdapter
- **Layer:** infrastructure
- **Spec:** tech-spec S5
- **What:** Implement `TelegramBotAdapter` implementing `MessagingPort`. Uses TelegramBotAPI (InsanusMokrassar). Maps Telegram chat IDs to user IDs. Handles incoming text messages, sends text replies, sends PDF documents, sets up persistent menu (Nouvelle facture, Mes impayes, Mon CA).
- **Acceptance criteria:**
  - [ ] Implements all `MessagingPort` methods
  - [ ] `onMessage` dispatches incoming Telegram messages as `IncomingMessage`
  - [ ] `sendMessage` sends text to correct Telegram chat
  - [ ] `sendDocument` sends file (PDF) to chat
  - [ ] `setPersistentMenu` configures reply keyboard with 3 items
  - [ ] Maps `telegram_id` to internal `UserId` via `UserRepository`
  - [ ] Handles `/start` command (including deep links like `?start=siren_123456789`)
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 10: LLM Integration

### 10.1 Claude API Client
- **Layer:** infrastructure
- **Spec:** tech-spec S4
- **What:** Implement `ClaudeApiClient` in `infrastructure/llm/`. HTTP client for Claude Messages API with tool use. Handles request construction (system prompt, conversation history, tool definitions) and response parsing (tool calls, text responses). API key from environment variable.
- **Acceptance criteria:**
  - [ ] Sends requests to Claude Messages API with correct format
  - [ ] Includes system prompt, user context, conversation history
  - [ ] Parses tool_use responses into structured action types
  - [ ] Parses text responses for conversational messages
  - [ ] Handles API errors gracefully (returns error, does not throw)
  - [ ] API key from `ANTHROPIC_API_KEY` environment variable
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 10.2 Tool Definitions and Action Types
- **Layer:** infrastructure
- **Spec:** mvp-spec S9 (structured actions)
- **What:** Define all Claude tool schemas mapping to structured actions: `create_invoice`, `send_invoice`, `mark_paid`, `update_draft`, `delete_draft`, `cancel_invoice`, `correct_invoice`, `get_revenue`, `export_invoices`, `get_unpaid`, `update_client`, `update_profile`, `configure_setting`, `list_clients`, `client_history`, `conversational`, `unknown`. Define Kotlin data classes for each action's parameters. Map tool call responses to action data classes.
- **Acceptance criteria:**
  - [ ] All 17 action types have corresponding tool definitions with JSON schemas
  - [ ] `create_invoice` tool accepts: client name, line items (description, quantity, unit price), payment method (optional), date (optional)
  - [ ] `mark_paid` tool accepts: invoice reference (number or client name), payment method, paid date (optional)
  - [ ] `correct_invoice` tool accepts: invoice reference, corrections
  - [ ] `get_revenue` tool accepts: period (month/quarter/year), comparison flag
  - [ ] Each tool has a Kotlin data class for its parameters
  - [ ] Parsing from Claude response JSON to data classes works for all tools
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 10.3 System Prompt and Conversation Management
- **Layer:** infrastructure
- **Spec:** tech-spec S4, mvp-spec S9
- **What:** Craft the system prompt for Claude: Mona's personality (French, conversational, brief), user context injection (name, SIREN status, onboarding state), tool usage instructions. Implement conversation context builder that assembles system prompt + user context + last 3 messages for each request.
- **Acceptance criteria:**
  - [ ] System prompt defines Mona's persona (French, tutoyante, brief, redirects to invoicing)
  - [ ] User context injected as structured JSON (name, has_siren, onboarding_step)
  - [ ] Last 3 conversation messages included in request
  - [ ] Total prompt stays under ~800 tokens (system + context)
  - [ ] Unit test: context builder produces expected message structure
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 10.4 Golden Test Suite — Parsing
- **Layer:** test
- **Spec:** tech-spec S11
- **What:** Create initial golden test suite with 50+ French invoice parsing test cases. Each test case has an input message and expected structured action output. Categories: simple invoices, multi-line, slang/informal, ambiguous clients, corrections, revenue queries, payment marking, conversational, unparseable. Tests validate tool selection and parameter extraction.
- **Acceptance criteria:**
  - [ ] Minimum 50 parsing test cases covering all action types
  - [ ] Minimum 15 context resolution test cases (anaphora, corrections, ambiguity)
  - [ ] Test cases stored as data (not embedded in test code) for easy maintenance
  - [ ] Test harness that runs each case against Claude API and validates output
  - [ ] Tests are tagged/categorized for selective execution
  - [ ] CI-compatible (can be run with `./gradlew test`)
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 11: Message Router and End-to-End Wiring

### 11.1 Message Router
- **Layer:** application
- **Spec:** mvp-spec S9, S10
- **What:** Implement `MessageRouter` that receives an `IncomingMessage`, looks up or creates the user, loads conversation context, calls Claude for parsing, routes the resulting action to the appropriate use case, formats the response, sends via `MessagingPort`, and persists conversation messages. Handle rate limiting (200 messages/day).
- **Acceptance criteria:**
  - [ ] Routes all 17 action types to their corresponding use cases
  - [ ] Creates user on first message (from Telegram ID)
  - [ ] Loads last 3 conversation messages for Claude context
  - [ ] Persists both user message and bot response in conversation history
  - [ ] Formats response in French with Mona's personality
  - [ ] Sends PDF documents when invoices are created/updated
  - [ ] Rate limiting: 200 messages/user/day, friendly French message when exceeded
  - [ ] Error handling: catches infrastructure exceptions, returns user-friendly French error messages
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 11.2 Onboarding Flow Integration
- **Layer:** application
- **Spec:** mvp-spec S1
- **What:** Integrate progressive onboarding into the message router. After first invoice creation, prompt for SIREN. Handle SIREN entry (direct or search). Walk through profile confirmation, payment terms, IBAN, email, URSSAF periodicity. Handle `/start` with deep link pre-fill.
- **Acceptance criteria:**
  - [ ] First invoice creates draft with BROUILLON watermark
  - [ ] After first invoice, Mona asks for SIREN
  - [ ] Direct SIREN entry triggers SIRENE lookup and profile auto-fill
  - [ ] "Je sais pas" triggers name+city search flow
  - [ ] Profile confirmation, payment terms, IBAN, email collected in sequence
  - [ ] `/start=siren_XXXXXXXXX` deep link pre-fills SIREN
  - [ ] Draft invoice re-generated without watermark after SIREN provided
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 11.3 Confirmation Flow
- **Layer:** application
- **Spec:** mvp-spec S2.3
- **What:** Implement confirmation flow for invoice creation. When `confirm_before_create` is true (default), show invoice summary and wait for OK/correction. When false, create immediately. Handle corrections during confirmation ("non, c'est 900 euros").
- **Acceptance criteria:**
  - [ ] Default: show confirmation with invoice details before creating
  - [ ] "OK" / "oui" / "confirme" triggers creation
  - [ ] Corrections during confirmation update the pending invoice
  - [ ] "Annule" cancels without creating
  - [ ] Setting `confirm_before_create=false` skips confirmation
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 11.4 App.kt — Full Wiring
- **Layer:** all
- **Spec:** tech-spec S2.9 (event handlers at startup)
- **What:** Wire everything together in `App.kt`: database initialization, repository creation, adapter creation (Telegram, Claude, PDF, Email, SIRENE, Crypto), use case construction, event handler registration, message router setup, bot startup. Load configuration from environment variables.
- **Acceptance criteria:**
  - [ ] All dependencies wired manually (no DI container)
  - [ ] Database initialized with schema creation
  - [ ] Event handlers registered: InvoicePaid -> threshold check, InvoiceOverdue -> user notification
  - [ ] Persistent menu set up on bot start
  - [ ] Health check endpoint at `/health`
  - [ ] Environment variables: `TELEGRAM_BOT_TOKEN`, `ANTHROPIC_API_KEY`, `RESEND_API_KEY`, `IBAN_ENCRYPTION_KEY`, `SIRENE_API_KEY`, `DATABASE_PATH`
  - [ ] Graceful shutdown (close database, stop bot)
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 12: Scheduled Jobs

### 12.1 Payment Check-In Job
- **Layer:** application
- **Spec:** mvp-spec S5, tech-spec S9
- **What:** Implement daily coroutine job that queries invoices where `due_date = yesterday` and `status = SENT`. Sends batched notification per user via MessagingPort. Single invoice: asks if paid. Multiple: batched list.
- **Acceptance criteria:**
  - [ ] Runs daily (configurable time)
  - [ ] Finds invoices due yesterday that are still Sent
  - [ ] Batches notifications per user
  - [ ] Single invoice: "La facture X de Client (montant) devait etre payee hier -- c'est fait ?"
  - [ ] Multiple invoices: batched list format per mvp-spec S5
  - [ ] Idempotent (safe to re-run)
  - [ ] Unit test with in-memory repos
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 12.2 Overdue Transition Job
- **Layer:** application
- **Spec:** mvp-spec S5, tech-spec S9
- **What:** Implement daily job that transitions Sent invoices to Overdue if `due_date < today - 3 days`. Calls `invoice.markOverdue()`, persists, dispatches events. Event handler sends one-time notification to user.
- **Acceptance criteria:**
  - [ ] Finds Sent invoices with due_date more than 3 days ago
  - [ ] Transitions each to Overdue via aggregate method
  - [ ] Persists and dispatches events
  - [ ] User notified once per overdue invoice
  - [ ] Idempotent
  - [ ] Unit test
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 12.3 URSSAF Reminder Jobs
- **Layer:** application
- **Spec:** mvp-spec S7, tech-spec S9
- **What:** Implement D-7 and D-1 reminder jobs. Compute next deadline per user via `UrssafThresholds.nextDeclarationDeadline()`. Compute revenue to declare via `RevenueCalculation`. Send formatted reminder with breakdown by activity type. Track acknowledgment to skip D-1 if D-7 acknowledged.
- **Acceptance criteria:**
  - [ ] D-7 reminder sent 7 days before deadline with pre-calculated revenue
  - [ ] D-1 reminder sent day before deadline (skipped if D-7 acknowledged)
  - [ ] Revenue broken down by activity type for mixed-activity users
  - [ ] Revenue calculated on cash basis (paid_date within declaration period)
  - [ ] Links to URSSAF portal included in response
  - [ ] Idempotent
  - [ ] Unit test
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 12.4 Onboarding Recovery Job
- **Layer:** application
- **Spec:** mvp-spec S1
- **What:** Implement daily job for onboarding drop-off recovery. Query users with draft invoices and no SIREN. Send Reminder 1 at 24h, Reminder 2 at 72h (with name+city search offer). Max 2 reminders. Skip Reminder 2 if user responded to Reminder 1.
- **Acceptance criteria:**
  - [ ] Finds users with drafts, no SIREN, created 24h+ ago
  - [ ] Reminder 1 at 24h: basic nudge about pending draft
  - [ ] Reminder 2 at 72h: proactive name+city search offer
  - [ ] Max 2 reminders per drop-off (no nagging)
  - [ ] Reminder 2 skipped if user responded to Reminder 1
  - [ ] Idempotent
  - [ ] Unit test
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 13: Deployment

### 13.1 Dockerfile and Fly.io Configuration
- **Layer:** all
- **Spec:** tech-spec S8
- **What:** Create `Dockerfile` with JVM 21 (Eclipse Temurin) base, Litestream binary. Create `fly.toml` with persistent volume, health check, environment variable configuration. Create Litestream config for S3 backup. Create startup script that starts Litestream replication then launches the app.
- **Acceptance criteria:**
  - [ ] Dockerfile builds fat JAR and creates runnable image
  - [ ] Image includes Litestream binary
  - [ ] `fly.toml` configures persistent volume for SQLite
  - [ ] Health check configured at `/health`
  - [ ] Litestream config points to S3-compatible storage
  - [ ] Startup script: restore from Litestream (if first deploy) -> start replication -> start app
  - [ ] Environment variables documented
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 13.2 Email Bounce Webhook
- **Layer:** infrastructure
- **Spec:** mvp-spec S4, tech-spec S10
- **What:** Add a minimal HTTP endpoint to receive Resend webhook callbacks for bounce/delivery events. On bounce, notify user via Telegram that email failed. Invoice stays Draft (not Sent) on bounce.
- **Acceptance criteria:**
  - [ ] HTTP endpoint receives Resend webhook POST
  - [ ] Parses bounce event, identifies invoice
  - [ ] Notifies user: "L'email a echoue" with option to provide new address
  - [ ] Invoice remains in Draft status on bounce
  - [ ] Endpoint secured (webhook signing verification)
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 14: Polish and Hardening

### 14.1 User Settings Management
- **Layer:** application
- **Spec:** mvp-spec S11
- **What:** Implement `ConfigureSetting` use case for `confirm_before_create` and `default_payment_delay_days`. Integrate with message router so users can change settings via chat.
- **Acceptance criteria:**
  - [ ] "Desactive la confirmation" sets `confirm_before_create = false`
  - [ ] "Delai de paiement 15 jours" updates `default_payment_delay_days`
  - [ ] Changes persisted and take effect immediately
  - [ ] Confirmation message sent to user
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

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
