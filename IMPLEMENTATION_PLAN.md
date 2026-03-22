# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

The strategy is: scaffolding -> domain (pure, testable) -> infrastructure adapters -> application use cases -> LLM integration -> end-to-end wiring -> scheduled jobs -> deployment -> polish.

---

## Phase 1: Project Scaffolding

### 1.1 Gradle Project Setup
- **Layer:** all
- **Spec:** tech-spec S1
- **What:** Initialize Gradle Kotlin DSL project with JVM 21 target. Configure dependencies: Exposed (DAO + SQLite), TelegramBotAPI, PDFBox, sqlite-jdbc, kotlinx-coroutines, kotlinx-datetime, Shadow plugin, ktlint. Set up `src/main/kotlin/mona/` and `src/test/kotlin/mona/` directory trees matching CLAUDE.md project structure. Add a minimal `App.kt` with a `main()` that prints "Mona starting". Add a trivial test that passes.
- **Acceptance criteria:**
  - [ ] `build.gradle.kts` with all approved dependencies, JVM 21 target, Shadow plugin
  - [ ] Directory structure matches CLAUDE.md (`domain/model`, `domain/service`, `domain/port`, `infrastructure/db`, `infrastructure/telegram`, `infrastructure/llm`, `infrastructure/pdf`, `infrastructure/email`, `infrastructure/sirene`, `infrastructure/crypto`, `application/onboarding`, `application/invoicing`, `application/payment`, `application/revenue`, `application/urssaf`)
  - [ ] `App.kt` exists at `src/main/kotlin/mona/App.kt`
  - [ ] At least one passing test
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes
  - [ ] `./gradlew shadowJar` produces a fat JAR

---

## Phase 2: Domain Model — Value Objects & Core Types

### 2.1 DomainResult and DomainError
- **Layer:** domain
- **Spec:** tech-spec S2.1
- **What:** Implement `DomainResult<T>` sealed class with `Ok` and `Err` variants. Add `map` and `flatMap` extension functions. Implement `DomainError` sealed hierarchy with all error cases from the spec (`InvalidTransition`, `InvoiceNumberGap`, `EmptyLineItems`, `NegativeAmount`, `ClientNotFound`, `InvoiceNotCancellable`, `CreditNoteAmountMismatch`, `SirenRequired`, `ProfileIncomplete`).
- **Acceptance criteria:**
  - [ ] `domain/model/DomainResult.kt` with `Ok`, `Err`, `map`, `flatMap`
  - [ ] `domain/model/DomainError.kt` with all sealed subclasses
  - [ ] Unit tests: `map` on Ok propagates, `map` on Err short-circuits, `flatMap` chains correctly, `flatMap` short-circuits on Err
  - [ ] Zero framework imports in domain files
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 2.2 Value Objects
- **Layer:** domain
- **Spec:** tech-spec S2.2
- **What:** Implement all value objects in `domain/model/values.kt`: `Cents` (with `plus`, `minus`, `times`, `isNegative`, `isZero`, `ZERO` companion), `Siren` (9-digit validation), `Siret` (14-digit validation, `siren` property), `Email` (basic validation), `PostalAddress` (data class with `formatted()`), `PaymentDelayDays` (1-60 range), `DeclarationPeriod` (with `monthly` and `quarterly` factories), `InvoiceId`, `ClientId`, `UserId`, `InvoiceNumber`, `CreditNoteNumber`.
- **Acceptance criteria:**
  - [ ] All value objects as inline value classes (except `PostalAddress`, `DeclarationPeriod`)
  - [ ] `Cents` arithmetic uses `Math.addExact`/`subtractExact`/`multiplyExact` — overflow throws `ArithmeticException`
  - [ ] `Siren` rejects non-9-digit input
  - [ ] `Siret` rejects non-14-digit input, `.siren` extracts first 9
  - [ ] `Email` rejects strings without `@` or shorter than 5 chars
  - [ ] `PaymentDelayDays` rejects values outside 1-60
  - [ ] `DeclarationPeriod.monthly()` and `.quarterly()` produce correct date ranges
  - [ ] Unit tests for all validation rules and arithmetic edge cases (including Cents overflow)
  - [ ] Zero framework imports
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 2.3 Enums and InvoiceStatus FSM
- **Layer:** domain
- **Spec:** tech-spec S2.3
- **What:** Implement `PaymentMethod`, `ActivityType`, `DeclarationPeriodicity` enums. Implement `InvoiceStatus` sealed class with `Draft`, `Sent`, `Paid(date, method)`, `Overdue`, `Cancelled`.
- **Acceptance criteria:**
  - [ ] `PaymentMethod` enum: VIREMENT, CHEQUE, ESPECES, CARTE, AUTRE
  - [ ] `ActivityType` enum: BIC_VENTE, BIC_SERVICE, BNC
  - [ ] `DeclarationPeriodicity` enum: MONTHLY, QUARTERLY
  - [ ] `InvoiceStatus` sealed class with all five variants
  - [ ] `Paid` carries `date: LocalDate` and `method: PaymentMethod`
  - [ ] Zero framework imports
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 3: Domain Model — Aggregates & Entities

### 3.1 DomainEvent, TransitionResult, LineItem, CreditNote
- **Layer:** domain
- **Spec:** tech-spec S2.5
- **What:** Implement `DomainEvent` sealed class with all five event types (`InvoiceSent`, `InvoicePaid`, `InvoiceOverdue`, `InvoiceCancelled`, `DraftDeleted`). Implement `TransitionResult(invoice, events)`. Implement `LineItem` data class with `description`, `quantity` (BigDecimal), `unitPriceHt` (Cents), computed `totalHt`. Implement `CreditNote` data class with number, amount, reason, issueDate, replacementInvoiceId, pdfPath.
- **Acceptance criteria:**
  - [ ] `DomainEvent` sealed class with all five event types, each carrying `occurredAt: Instant`
  - [ ] `InvoicePaid` carries amount, paidDate, method, activityType
  - [ ] `InvoiceCancelled` carries optional creditNote
  - [ ] `TransitionResult` holds invoice + events list
  - [ ] `LineItem.totalHt` computes `quantity * unitPriceHt` with HALF_UP rounding
  - [ ] `CreditNote` data class with all fields from tech-spec
  - [ ] Unit tests for LineItem totalHt calculation (integer quantity, fractional quantity, rounding)
  - [ ] Zero framework imports
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 3.2 Invoice Aggregate Root
- **Layer:** domain
- **Spec:** tech-spec S2.5
- **What:** Implement `Invoice` data class with all fields, computed `amountHt`, and aggregate methods: `send()`, `markPaid()`, `markOverdue()`, `cancel()`. Implement `Invoice.create()` companion factory that validates invariants and returns `DomainResult<Invoice>`.
- **Acceptance criteria:**
  - [ ] `Invoice.amountHt` sums all line item totals
  - [ ] `Invoice.create()` rejects empty line items, zero/negative totals, invalid invoice numbers
  - [ ] `Invoice.create()` returns Draft status
  - [ ] `send()`: Draft->Sent succeeds, emits `InvoiceSent`; non-Draft fails with `InvalidTransition`
  - [ ] `markPaid()`: Draft/Sent/Overdue->Paid succeeds, emits `InvoicePaid`; Paid/Cancelled fails
  - [ ] `markOverdue()`: Sent->Overdue succeeds, emits `InvoiceOverdue`; non-Sent fails
  - [ ] `cancel()`: Draft->Cancelled (no credit note needed), emits `DraftDeleted`; Sent/Overdue/Paid->Cancelled requires credit note, emits `InvoiceCancelled`; missing credit note for non-draft fails with `InvoiceNotCancellable`
  - [ ] All transition methods return `DomainResult<TransitionResult>`
  - [ ] Comprehensive unit tests for every valid and invalid transition
  - [ ] Zero framework imports
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 3.3 Invoice Numbering
- **Layer:** domain
- **Spec:** tech-spec S2.6, mvp-spec S2.7
- **What:** Implement `InvoiceNumbering` object with `next()`, `validate()`, `isContiguous()`. Implement `CreditNoteNumbering` object with `next()`. Format: `F-YYYY-MM-NNN` for invoices, `A-YYYY-MM-NNN` for credit notes.
- **Acceptance criteria:**
  - [ ] `next(yearMonth, null)` returns `F-YYYY-MM-001`
  - [ ] `next(yearMonth, F-2026-03-005)` returns `F-2026-03-006`
  - [ ] `validate()` accepts valid format, rejects invalid
  - [ ] `isContiguous()` detects gaps
  - [ ] Sequence overflow (>999) returns error
  - [ ] `CreditNoteNumbering.next()` follows same logic with `A-` prefix
  - [ ] Unit tests for all cases including month rollover
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 3.4 User and Client Domain Models
- **Layer:** domain
- **Spec:** tech-spec S3 (User, Client tables)
- **What:** Implement `User` data class with all fields from the data model: id (UserId), telegramId, email, name, siren, siret, address (PostalAddress?), ibanEncrypted, activityType, declarationPeriodicity, confirmBeforeCreate, defaultPaymentDelayDays, createdAt. Implement `Client` data class: id (ClientId), userId, name, email, address, companyName, siret, createdAt.
- **Acceptance criteria:**
  - [ ] `User` uses typed value objects (`UserId`, `Email?`, `Siren?`, `Siret?`, `PostalAddress?`, `ActivityType?`, `DeclarationPeriodicity?`, `PaymentDelayDays`)
  - [ ] `Client` uses typed value objects (`ClientId`, `UserId`, `Email?`, `PostalAddress?`, `Siret?`)
  - [ ] Both are plain data classes with no behavior beyond accessors
  - [ ] Zero framework imports
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 4: Domain Services & Ports

### 4.1 Revenue Calculation and URSSAF Thresholds
- **Layer:** domain
- **Spec:** tech-spec S2.7, S2.8
- **What:** Implement `PaidInvoiceSnapshot` and `CreditNoteSnapshot` read-model types. Implement `RevenueCalculation.compute()` producing `RevenueBreakdown` (total + byActivity). Implement `UrssafThresholds.checkTvaThreshold()` (80%/95% alerts) and `nextDeclarationDeadline()` (monthly/quarterly).
- **Acceptance criteria:**
  - [ ] `RevenueCalculation.compute()` sums paid invoices, offsets credit notes, breaks down by activity type
  - [ ] Handles empty input (zero revenue)
  - [ ] `UrssafThresholds.checkTvaThreshold()` returns `ThresholdAlert` at 80%+ of threshold, null below
  - [ ] Correct thresholds: Services/BNC = 36,800 EUR, Sales = 91,900 EUR
  - [ ] `nextDeclarationDeadline()`: monthly = end of following month; quarterly = end of month after quarter end
  - [ ] Unit tests for all calculation paths, edge cases, mixed activities
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 4.2 Repository Ports and MessagingPort
- **Layer:** domain
- **Spec:** tech-spec S2.10, S5
- **What:** Define `InvoiceRepository`, `ClientRepository`, `UserRepository` interfaces in `domain/port/`. Define `MessagingPort` interface, `IncomingMessage`, `Button`, `MenuItem` data classes. Define `ConversationRepository` interface for persisting last 3 messages. Define `EmailPort` interface. Define `SirenePort` interface.
- **Acceptance criteria:**
  - [ ] `InvoiceRepository` with: `findById`, `save`, `delete`, `findLastNumberInMonth`, `findByUser`, `findPaidInPeriod`, `findCreditNotesInPeriod`, `findSentOverdue`, `findByClientAndAmountSince` (for duplicate detection), `findByUserAndStatus`
  - [ ] `ClientRepository` with: `findById`, `save`, `findByUserAndName`, `findByUser`
  - [ ] `UserRepository` with: `findById`, `findByTelegramId`, `save`
  - [ ] `ConversationRepository` with: `save`, `findRecent(userId, limit=3)`
  - [ ] `MessagingPort` with: `sendMessage`, `sendDocument`, `sendButtons`, `setPersistentMenu`, `onMessage`
  - [ ] `EmailPort` with: `sendInvoice(to, subject, body, pdfAttachment, filename)`
  - [ ] `SirenePort` with: `lookupBySiren(siren)`, `searchByNameAndCity(name, city)`
  - [ ] All interfaces use domain types (not primitives)
  - [ ] All functions are `suspend`
  - [ ] Zero framework imports in port files
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 5: Infrastructure — Database

### 5.1 Exposed Table Definitions and Database Setup
- **Layer:** infrastructure
- **Spec:** tech-spec S3
- **What:** Define Exposed table objects for Users, Clients, Invoices, InvoiceLineItems, CreditNotes, ConversationMessages. Set up SQLite database connection factory with WAL mode. Include schema creation on startup.
- **Acceptance criteria:**
  - [ ] All tables match tech-spec S3 column definitions exactly
  - [ ] Foreign key relationships defined (Client->User, Invoice->User, Invoice->Client, LineItem->Invoice, CreditNote->Invoice)
  - [ ] SQLite connection with WAL mode enabled
  - [ ] Schema auto-creation on startup
  - [ ] Integration test: database initializes without errors
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 5.2 UserRepository Implementation
- **Layer:** infrastructure
- **Spec:** tech-spec S3 (User table), S2.10
- **What:** Implement `ExposedUserRepository` backed by the Users table. Map between Exposed rows and domain `User` objects. Handle nullable fields (email, siren, address, etc.).
- **Acceptance criteria:**
  - [ ] `findById` returns domain `User` with correct value object mapping
  - [ ] `findByTelegramId` queries by telegram_id
  - [ ] `save` performs upsert (insert or update)
  - [ ] Nullable domain fields map to nullable columns
  - [ ] Integration test: save and retrieve a user round-trip
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 5.3 ClientRepository Implementation
- **Layer:** infrastructure
- **Spec:** tech-spec S3 (Client table), S2.10
- **What:** Implement `ExposedClientRepository`. Map between Exposed rows and domain `Client` objects. Implement `findByUserAndName` with case-insensitive partial matching.
- **Acceptance criteria:**
  - [ ] `findById`, `save`, `findByUserAndName`, `findByUser` all work
  - [ ] `findByUserAndName` does case-insensitive LIKE matching
  - [ ] Integration test: save client, find by name fragment
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 5.4 InvoiceRepository Implementation
- **Layer:** infrastructure
- **Spec:** tech-spec S3 (Invoice, LineItem, CreditNote tables), S2.10
- **What:** Implement `ExposedInvoiceRepository`. Handle the full aggregate: save/load Invoice with its LineItems and optional CreditNote. Implement all query methods including `findPaidInPeriod` (returning snapshots), `findCreditNotesInPeriod`, `findSentOverdue`, `findLastNumberInMonth`, `findByClientAndAmountSince`.
- **Acceptance criteria:**
  - [ ] `save` persists Invoice + LineItems + CreditNote in a transaction
  - [ ] `findById` reconstitutes full aggregate (Invoice + LineItems + CreditNote)
  - [ ] `delete` removes Invoice + associated LineItems and CreditNote
  - [ ] `findPaidInPeriod` returns `PaidInvoiceSnapshot` list filtered by paid_date within period
  - [ ] `findCreditNotesInPeriod` returns `CreditNoteSnapshot` list
  - [ ] `findSentOverdue` returns invoices where status=Sent and dueDate < cutoff
  - [ ] `findLastNumberInMonth` returns the highest invoice number for a user in a given month
  - [ ] `findByClientAndAmountSince` supports duplicate detection (same client + amount within 48h)
  - [ ] Integration tests for all query methods
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 5.5 ConversationRepository Implementation
- **Layer:** infrastructure
- **Spec:** tech-spec S3 (ConversationMessage table)
- **What:** Implement `ExposedConversationRepository`. On save, prune messages beyond the 3 most recent per user.
- **Acceptance criteria:**
  - [ ] `save` persists a conversation message
  - [ ] After save, only the 3 most recent messages per user are retained (older messages deleted)
  - [ ] `findRecent` returns last N messages ordered by timestamp
  - [ ] Integration test: save 5 messages, verify only 3 remain
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

---

## Phase 6: Infrastructure — Crypto, PDF, Email

### 6.1 AES-256-GCM IBAN Encryption
- **Layer:** infrastructure
- **Spec:** tech-spec S7
- **What:** Implement `IbanCrypto` in `infrastructure/crypto/` with `encrypt(plaintext: String, key: ByteArray): ByteArray` and `decrypt(ciphertext: ByteArray, key: ByteArray): String`. Key is read from environment variable. Generate random IV per encryption. Prepend IV to ciphertext.
- **Acceptance criteria:**
  - [ ] Encrypt then decrypt round-trips to original IBAN
  - [ ] Different encryptions of the same IBAN produce different ciphertext (random IV)
  - [ ] Decryption with wrong key fails
  - [ ] Key loaded from `IBAN_ENCRYPTION_KEY` environment variable
  - [ ] Unit tests for round-trip, wrong key, empty input
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 6.2 PDF Invoice Generation
- **Layer:** infrastructure
- **Spec:** tech-spec S6, mvp-spec S2.8
- **What:** Implement `PdfGenerator` in `infrastructure/pdf/` using Apache PDFBox. Generate a text-based invoice PDF with all mandatory French legal mentions. Support draft watermark ("BROUILLON"). Accept domain types as input (Invoice, User, Client).
- **Acceptance criteria:**
  - [ ] Generated PDF contains: issue date, invoice number, seller info (name, EI, SIREN, address), buyer info (name, address if available), line items table (description, quantity, unit price HT, line total HT), total HT/TTC, "TVA non applicable, article 293 B du CGI", payment terms and due date, late payment penalties mention, payment method (if specified), IBAN section (if provided)
  - [ ] Draft invoices get "BROUILLON" watermark
  - [ ] Non-draft invoices have no watermark
  - [ ] Amounts formatted as French currency (e.g., "800,00 EUR")
  - [ ] PDF is returned as ByteArray
  - [ ] Integration test: generate PDF, verify it is valid (non-zero size, parseable by PDFBox)
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 6.3 Credit Note PDF Generation
- **Layer:** infrastructure
- **Spec:** mvp-spec S2.9
- **What:** Extend `PdfGenerator` to generate credit note PDFs. Reference to original invoice, negative amount, all mandatory mentions, optional reason.
- **Acceptance criteria:**
  - [ ] Credit note PDF references original invoice number
  - [ ] Shows negative amount
  - [ ] Contains all mandatory invoice mentions
  - [ ] Shows reason if provided
  - [ ] Integration test: generate credit note PDF, verify valid
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

### 6.4 Resend Email Adapter
- **Layer:** infrastructure
- **Spec:** tech-spec S10 (Resend)
- **What:** Implement `ResendEmailAdapter` in `infrastructure/email/` implementing `EmailPort`. Uses java.net.http to call Resend HTTP API. Send email with PDF attachment. Handle delivery failures.
- **Acceptance criteria:**
  - [ ] Implements `EmailPort` interface
  - [ ] Calls Resend HTTP API with correct payload (to, from, subject, body, attachment)
  - [ ] API key loaded from `RESEND_API_KEY` environment variable
  - [ ] Sender address configurable (default: `factures@mona-app.fr`)
  - [ ] Returns `Err` on HTTP failure
  - [ ] Unit test with mocked HTTP responses (success and failure)
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

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
