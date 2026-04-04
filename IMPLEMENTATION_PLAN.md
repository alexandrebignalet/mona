# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1.1–14.4 done. See git log for details.

---

## Phase 15: GDPR Compliance

### 15.1 Schema Migration for Anonymized Invoice Retention

**Spec:** §12 Right to Deletion — "Retains anonymized invoice records for the 10-year legal retention period"

**What:** Invoices, credit notes, and line items must survive user/client deletion. Currently `InvoicesTable.userId` (line 44) and `InvoicesTable.clientId` (line 45) in `Tables.kt` are NOT NULL FK references declared via `.references(...)` with no `.nullable()` and no `ON DELETE` clause. Deleting a User or Client row would violate FK constraints. This step makes those FKs nullable so deletion sets them to NULL (anonymization).

**Layers:**

- **`domain/model/Invoice.kt`** — Change `userId: UserId` to `userId: UserId?` and `clientId: ClientId` to `clientId: ClientId?` in the `Invoice` data class constructor. This is required for reconstitution of anonymized rows. `Invoice.create()` keeps non-null parameters — its signature does not change. All call sites that pattern-match or access `invoice.userId` / `invoice.clientId` must handle nullability.
- **`infrastructure/db/Tables.kt`** — Add `.nullable()` to `InvoicesTable.userId` and `InvoicesTable.clientId`.
- **`infrastructure/db/DatabaseFactory.kt`** — Add migration step. SQLite `ALTER TABLE` cannot change column constraints, so use the standard recreate-table pattern: create new invoices table with nullable FKs, copy data, drop old, rename. Currently `DatabaseFactory` uses `SchemaUtils.create(...)` only — add a migration mechanism that runs after `SchemaUtils.create`.
- **`infrastructure/db/ExposedInvoiceRepository.kt`** — Update `toInvoice()`: currently reads `this[InvoicesTable.userId]` and `this[InvoicesTable.clientId]` as non-nullable — will throw if columns are null. Change to read nullable and map to `UserId?` / `ClientId?`. The queries `findLastNumberInMonth` (filters on `userId eq`) and `findByClientAndAmountSince` (filters on `clientId eq`) remain correct — NULL rows are simply excluded by the `eq` condition.

**Acceptance criteria:**
- Migration runs on existing database without data loss
- Invoices with NULL userId/clientId can be read back from the DB and reconstituted as `Invoice` with null fields
- `Invoice.create()` still requires non-null userId and clientId
- All existing tests pass after migration
- FK constraints still enforced for non-NULL references

**Tests:**
- Migration test: insert data, run migration, verify data integrity
- Repository test: verify invoices with NULL FKs load correctly as `Invoice` with null userId/clientId

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 15.2 GDPR Account Deletion

**Spec:** §12 Right to Deletion

**What:** Allow a user to request full account deletion via chat. Mona asks for confirmation, then permanently deletes profile data, client records, conversation history, and auxiliary records while retaining anonymized invoice records (nullified FKs from 15.1).

**Layers:**

- **`domain/port/UserRepository.kt`** — Add `suspend fun delete(userId: UserId)`.
- **`domain/port/ClientRepository.kt`** — Add `suspend fun deleteByUser(userId: UserId)`.
- **`domain/port/ConversationRepository.kt`** — Add `suspend fun deleteByUser(userId: UserId)`.
- **`infrastructure/db/ExposedInvoiceRepository.kt`** — Add `suspend fun anonymizeByUser(userId: UserId)`: within a transaction, (1) set `InvoicesTable.userId = null` for all invoices where userId matches, (2) set `InvoicesTable.clientId = null` for all invoices whose clientId is in the set of clients owned by this user. Called by `DeleteAccount` before any row deletion.
- **`infrastructure/db/ExposedClientRepository.kt`** — Implement `deleteByUser`: delete all Client rows for the given user. (Invoice clientId FKs must be nullified before this runs.)
- **`infrastructure/db/ExposedConversationRepository.kt`** — Implement `deleteByUser`: delete all `ConversationMessagesTable` rows for the user.
- **`infrastructure/db/ExposedUserRepository.kt`** — Implement `delete`: delete from `UrssafRemindersTable`, `OnboardingRemindersTable`, `VatAlertsTable` where userId matches, then delete the User row.
- **`application/gdpr/DeleteAccount.kt`** (new package) — Use case (~20 lines): validate user exists, then execute in order:
  1. `anonymizeByUser` — nullify `InvoicesTable.userId` for this user and `InvoicesTable.clientId` for all clients of this user
  2. Delete from `UrssafRemindersTable`, `OnboardingRemindersTable`, `VatAlertsTable`
  3. Delete all Client rows for user (safe — invoices no longer FK-reference them)
  4. Delete all ConversationMessages for user
  5. Delete User row

  This order satisfies all FK constraints: `ClientsTable.userId`, `ConversationMessagesTable.userId`, and the three reminder tables all reference `UsersTable.id`.
- **`infrastructure/llm/ActionTypes.kt`** — Add `data object DeleteAccount : ParsedAction()` to sealed class.
- **`infrastructure/llm/ToolDefinitions.kt`** — Add `delete_account` tool (no parameters; user identity from context). Add to `all` list.
- **`infrastructure/llm/ActionParser.kt`** — Map `"delete_account"` tool call to `ParsedAction.DeleteAccount`.
- **`application/MessageRouter.kt`** — Add `is ParsedAction.DeleteAccount -> handleDeleteAccount(user)` to the `when` expression in `handleAction`. Add `pendingDeletionSet: ConcurrentHashMap<String, Boolean>` (follows existing pattern of `pendingConfirmationMap`/`pendingDuplicateMap`). `handleDeleteAccount`: send confirmation prompt ("Tu es sur·e ? Cette action est irréversible."), wait for confirmation token (reuse `CONFIRM_TOKENS`/`CANCEL_TOKENS` pattern via `pendingDeletionSet`), then execute `DeleteAccount` use case, send farewell message. On cancellation respond "OK, on oublie ça".

**Acceptance criteria:**
- User says "supprime mon compte" and Mona responds with confirmation prompt
- On confirmation: user profile, all client records, conversation history, urssaf/onboarding/vat reminders all deleted from DB
- Invoice rows remain in DB with NULL userId and NULL clientId (anonymized retention)
- Credit notes and line items remain intact (they reference InvoicesTable, not UsersTable)
- On cancellation, Mona responds "OK, on oublie ça" and nothing is deleted
- On next contact after deletion, user is treated as brand-new (new onboarding)
- No FK constraint violations during deletion

**Golden tests:** Add `delete_account` entries to `src/test/resources/golden/parsing_cases.json` covering: "supprime mon compte", "je veux supprimer mes données", "efface tout"

**Tests:**
- `DeleteAccountTest` — verifies full deletion sequence: user + clients + conversations + reminders deleted, invoices remain with null FKs
- MessageRouter tests: confirm path + cancel path

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 15.3 GDPR Data Export (PDFs + Profile JSON)

**Spec:** §12 Data Export

**What:** Allow a user to export all their data via chat. Sends: CSV of all invoices, all invoice and credit note PDFs, and a profile JSON file.

**Layers:**

- **`application/gdpr/ExportGdprData.kt`** (new) — Use case. Dependencies: `InvoiceRepository`, `ClientRepository`, `UserRepository`, `ExportInvoicesCsv` (already exists at `application/revenue/ExportInvoicesCsv.kt`), `PdfPort`, `CryptoPort` (to decrypt IBAN for PDF generation). Steps: (a) delegate to `ExportInvoicesCsv` for the CSV, (b) load all non-draft invoices with their clients, regenerate each PDF via `PdfPort.generateInvoice(invoice, user, client, plainIban)` which returns `DomainResult<ByteArray>`, (c) for cancelled invoices with credit notes, generate credit note PDFs via `PdfPort.generateCreditNote(creditNote, originalInvoiceNumber, user, client, plainIban)`, (d) serialize user profile to JSON (name, email, activityType, SIREN, SIRET, address, declarationPeriodicity, defaultPaymentDelay; IBAN presence as `"iban_enregistre": true/false`, never the raw value).
- **`infrastructure/llm/ActionTypes.kt`** — Add `data object ExportData : ParsedAction()` to sealed class.
- **`infrastructure/llm/ToolDefinitions.kt`** — Add `export_data` tool (no parameters). Add to `all` list.
- **`infrastructure/llm/ActionParser.kt`** — Map `"export_data"` tool call to `ParsedAction.ExportData`.
- **`application/MessageRouter.kt`** — Add `is ParsedAction.ExportData -> handleExportData(user)` to `handleAction`. `handleExportData`: calls `ExportGdprData`, sends summary text message with counts, then CSV file, then each PDF, then profile JSON as documents via `MessagingPort.sendDocument(userId, fileBytes, fileName, caption)`.

**Important:** `PdfPort.generateInvoice` requires `User`, `Client`, and `plainIban`. Load client per invoice via clientId. This path runs only for active (non-deleted) users, so clients should always exist. `ExportInvoicesCsv` already handles nullable clientId gracefully (`clients[invoice.clientId]?.name ?: ""`).

**Acceptance criteria:**
- User says "exporte mes données" and receives: summary message + CSV + all invoice PDFs + all credit note PDFs + profile JSON
- Profile JSON contains all profile fields + IBAN presence indicator (never raw IBAN)
- Each PDF filename matches the invoice/credit note number
- Summary message states counts before files are sent
- Works for a user with zero invoices (sends profile JSON + empty CSV only)

**Golden tests:** Add `export_data` entries to `src/test/resources/golden/parsing_cases.json` covering: "exporte mes données", "je veux télécharger toutes mes données", "export RGPD"

**Tests:**
- `ExportGdprDataTest` — verifies CSV, PDFs, and JSON generated with correct content
- MessageRouter test: verifies all documents sent in correct order

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

## Phase 16: Revenue Polish

### 16.1 Revenue Period Label Fix

**Spec:** §6 ("Ce mois : 3 200 EUR encaissés sur 4 factures.")

**What:** Fix `handleGetRevenue` in `MessageRouter` (lines 612-639) to use human-readable French period labels instead of raw year numbers. Currently line 628 formats as `"$year : $total encaissé..."`. The `periodType` and the constructed `period` are already available in `handleGetRevenue` but not passed through to `GetRevenueResult` or used for labeling.

**Layers:**

- **`application/revenue/GetRevenue.kt`** — Add `periodType: String` field to `GetRevenueCommand`. Add `period: DeclarationPeriod` field to `GetRevenueResult` — `period` is already available inside `execute()` from `command.period`, it just needs to be included in the returned result. No logic change needed in `execute()`.
- **`application/MessageRouter.kt`** — Pass `action.periodType` into `GetRevenueCommand`. Add private `formatPeriodLabel(periodType: String, period: DeclarationPeriod): String` function using `java.time.LocalDate.now()` for "current" comparisons and `java.time.format.TextStyle.FULL` with `Locale.FRENCH` for month names. Replace `"$year : ..."` on line 628 with `"${formatPeriodLabel(...)} : ..."`.

**Target behavior:**
- `periodType == "month"` + current month → "Ce mois"
- `periodType == "month"` + past month → "Mars 2026" (month name capitalized + year)
- `periodType == "quarter"` + current quarter → "Ce trimestre"
- `periodType == "quarter"` + past quarter → "T1 2026"
- `periodType == "year"` + current year → "Cette année"
- `periodType == "year"` + past year → just the year number

**Acceptance criteria:**
- Monthly revenue query for current month shows "Ce mois : ..."
- Monthly query for a past month shows "Février 2026 : ..."
- Quarterly query for current quarter shows "Ce trimestre : ..."
- Yearly query shows "Cette année : ..." or just the year

**Tests:** MessageRouter tests for each periodType variant (current + past)

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 16.2 Revenue Comparison to Previous Period

**Spec:** §6 "Tu es à 68% de ton CA du mois dernier"

**What:** When showing monthly or quarterly revenue, compute the previous period's total and show a percentage comparison.

**Layers:**

- **`application/revenue/GetRevenue.kt`** — Add `previousBreakdown: RevenueBreakdown?` to `GetRevenueResult`. In `execute()`, for month/quarter periodTypes only, compute the previous period: for monthly use `DeclarationPeriod.monthly(prevStart.year, prevStart.monthValue)` where `prevStart = command.period.start.minusMonths(1)`; for quarterly use `DeclarationPeriod.quarterly(prevStart.year, quarter)` where `prevStart = command.period.start.minusMonths(3)` and `quarter = (prevStart.monthValue - 1) / 3 + 1`. Call `invoiceRepository.findPaidInPeriod` and `invoiceRepository.findCreditNotesInPeriod` for the previous period, then `RevenueCalculation.compute()` (pure function in `domain/service/RevenueCalculation.kt`). Yearly queries get `previousBreakdown = null`.
- **`application/MessageRouter.kt`** — If `result.previousBreakdown` is non-null and its total > 0, append comparison line: "Tu es à X% de ton CA [du mois dernier / du trimestre dernier] [emoji]". Emoji: >=100% → rocket, >=60% → thumbs up, <60% → flexed bicep. If previous total is zero, omit comparison line entirely. Percentage rounded to nearest integer.

**Acceptance criteria:**
- Monthly revenue query includes comparison to previous month when previous month has data
- Quarterly revenue query includes comparison to previous quarter
- Yearly query has NO comparison line
- No error when previous period has zero revenue (comparison line simply omitted)
- Percentage rounded to nearest integer

**Tests:**
- `GetRevenueTest` — verify `previousBreakdown` populated for monthly/quarterly, null for yearly
- MessageRouter test: comparison line appears/disappears based on previous data

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

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
15. **FK-aware deletion order.** When deleting user data: (1) nullify invoice userId FK, (2) nullify invoice clientId FKs for user's clients, (3) delete reminder tables (urssaf/onboarding/vat), (4) delete clients, (5) delete conversations, (6) delete user row.
