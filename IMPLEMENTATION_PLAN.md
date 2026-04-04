# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

The strategy is: scaffolding -> domain (pure, testable) -> infrastructure adapters -> application use cases -> LLM integration -> end-to-end wiring -> scheduled jobs -> deployment -> polish.

---

## Completed Phases

Phases 1.1–14.4 done. See git log for details.

---

## Phase 15: GDPR Compliance

### 15.1 Schema Migration for Anonymized Invoice Retention

**Spec:** §12 Right to Deletion — "Retains anonymized invoice records for the 10-year legal retention period"

**What:** Invoices, credit notes, and line items must survive user/client deletion. Currently `InvoicesTable.userId` and `InvoicesTable.clientId` are NOT NULL FK references with no `ON DELETE` clause, and `PRAGMA foreign_keys=ON` is enforced. Deleting a User or Client row would violate FK constraints. This step makes those FKs nullable so deletion sets them to NULL (anonymization).

**Layers:**
- `infrastructure/db/Tables.kt` — make `InvoicesTable.userId` and `InvoicesTable.clientId` nullable (`.nullable()`)
- `infrastructure/db/DatabaseFactory.kt` — add migration step: create new invoices table with nullable FKs, copy data, drop old, rename (SQLite ALTER TABLE cannot change column constraints, so the standard recreate-table pattern is needed)
- `infrastructure/db/ExposedInvoiceRepository.kt` — update all read queries that join on userId/clientId to handle nullable columns; map NULL userId/clientId to the domain model (e.g. return `null` for `UserId?` / `ClientId?` on reconstitution)
- `domain/model/Invoice.kt` — decide if Invoice allows nullable userId/clientId. Recommended: add a read-only `AnonymizedInvoice` data class or make Invoice.userId/clientId nullable for reconstitution only. Keep `Invoice.create()` requiring non-null values.

**Acceptance criteria:**
- Migration runs on existing database without data loss
- Invoices with NULL userId/clientId can be read back from the DB
- `Invoice.create()` still requires non-null userId and clientId
- All existing tests pass after migration
- FK constraints still enforced for non-NULL references

**Tests:**
- Migration test: insert data, run migration, verify data integrity
- Repository test: verify invoices with NULL FKs load correctly

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 15.2 GDPR Account Deletion

**Spec:** §12 Right to Deletion

**What:** Allow a user to request full account deletion via chat. Mona asks for confirmation, then permanently deletes profile data, client records, conversation history, and auxiliary records while retaining anonymized invoice records (nullified FKs from 15.1).

**Layers:**
- `domain/port/UserRepository.kt` — add `suspend fun delete(userId: UserId)`
- `domain/port/ClientRepository.kt` — add `suspend fun deleteByUser(userId: UserId)`
- `domain/port/ConversationRepository.kt` — add `suspend fun deleteByUser(userId: UserId)`
- `infrastructure/db/ExposedUserRepository.kt` — implement `delete`: within a single transaction, (1) nullify `InvoicesTable.userId` where it matches, (2) delete from `UrssafRemindersTable`, `OnboardingRemindersTable`, `VatAlertsTable` where userId matches, (3) delete the User row
- `infrastructure/db/ExposedClientRepository.kt` — implement `deleteByUser`: within a single transaction, (1) nullify `InvoicesTable.clientId` for all client IDs owned by the user, (2) delete all Client rows for the user
- `infrastructure/db/ExposedConversationRepository.kt` — implement `deleteByUser`: delete all ConversationMessagesTable rows for the user
- `application/gdpr/DeleteAccount.kt` (new package) — use case: validate user exists, call `clientRepository.deleteByUser(userId)`, call `conversationRepository.deleteByUser(userId)`, call `userRepository.delete(userId)`, return confirmation. ~20 lines. Invoice rows remain with NULL FKs (anonymized).
- `infrastructure/llm/ActionTypes.kt` — add `ParsedAction.DeleteAccount` (data object)
- `infrastructure/llm/ToolDefinitions.kt` — add `delete_account` tool (no parameters; user identity from context)
- `infrastructure/llm/ActionParser.kt` — map `delete_account` tool call to `ParsedAction.DeleteAccount`
- `application/MessageRouter.kt` — add `handleDeleteAccount`: send confirmation prompt ("Tu es sur-e ? Cette action est irreversible."), wait for confirmation token (reuse CONFIRM_TOKENS/CANCEL_TOKENS pattern via a `pendingDeletionSet: ConcurrentHashMap`), then execute `DeleteAccount` use case, send farewell message

**Important detail:** The deletion order matters because of FK constraints. Clients must be deleted (and their invoice FK references nullified) before the user row is deleted (and its invoice FK references nullified). The UrssafReminders, OnboardingReminders, and VatAlerts rows must also be deleted before the User row since they reference UsersTable.id.

**Acceptance criteria:**
- User says "supprime mon compte" and Mona responds with confirmation prompt
- On confirmation: user profile, all client records, conversation history, urssaf/onboarding/vat reminders all deleted from DB
- Invoice rows remain in DB with NULL userId and NULL clientId (anonymized retention)
- Credit notes and line items remain intact (they reference InvoicesTable, not UsersTable)
- On cancellation, Mona responds "OK, on oublie ca" and nothing is deleted
- On next contact after deletion, user is treated as brand-new (new onboarding)
- No FK constraint violations during deletion

**Golden tests:** Add `delete_account` entries to `src/test/resources/golden/parsing_cases.json` covering: "supprime mon compte", "je veux supprimer mes donnees", "efface tout"

**Tests:**
- `DeleteAccountTest` — verifies user + clients + conversations + reminders deleted, invoices remain with null FKs
- MessageRouter tests: confirm path + cancel path

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 15.3 GDPR Data Export (PDFs + Profile JSON)

**Spec:** §12 Data Export

**What:** Allow a user to export all their data via chat. Sends: CSV of all invoices, all invoice and credit note PDFs, and a profile JSON file.

**Layers:**
- `application/gdpr/ExportGdprData.kt` (new) — use case: (a) delegates to `ExportInvoicesCsv` for the CSV, (b) loads all non-draft invoices with their clients, regenerates each PDF via `PdfPort.generateInvoice`, (c) for cancelled invoices with credit notes, generates credit note PDFs via `PdfPort.generateCreditNote`, (d) serializes user profile to JSON (name, email, activityType, SIREN, SIRET, address, declarationPeriodicity, defaultPaymentDelay; IBAN presence as `"iban_enregistre": true/false`, never the raw value)
- `infrastructure/llm/ActionTypes.kt` — add `ParsedAction.ExportData` (data object)
- `infrastructure/llm/ToolDefinitions.kt` — add `export_data` tool (no parameters)
- `infrastructure/llm/ActionParser.kt` — map `export_data` tool call to `ParsedAction.ExportData`
- `application/MessageRouter.kt` — add `handleExportData`: calls `ExportGdprData`, sends summary text message with counts, then CSV file, then each PDF, then profile JSON as documents via `MessagingPort`

**Important:** `PdfPort.generateInvoice` requires `User`, `Client`, and `plainIban`. Load client per invoice; pass decrypted IBAN (user's own data). Skip PDFs for invoices whose client was already deleted (clientId is NULL after account deletion — but this path only runs for active users, so clients should always exist).

**Acceptance criteria:**
- User says "exporte mes donnees" and receives: summary message + CSV + all invoice PDFs + all credit note PDFs + profile JSON
- Profile JSON contains all profile fields + IBAN presence indicator (never raw IBAN)
- Each PDF filename matches the invoice/credit note number
- Summary message states counts before files are sent
- Works for a user with zero invoices (sends profile JSON + empty CSV only)

**Golden tests:** Add `export_data` entries to `src/test/resources/golden/parsing_cases.json` covering: "exporte mes donnees", "je veux telecharger toutes mes donnees", "export RGPD"

**Tests:**
- `ExportGdprDataTest` — verifies CSV, PDFs, and JSON generated with correct content
- MessageRouter test: verifies all documents sent in correct order

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

## Phase 16: Revenue Polish

### 16.1 Revenue Period Label Fix

**Spec:** §6 ("Ce mois : 3 200 EUR encaisses sur 4 factures.")

**What:** Fix `handleGetRevenue` in `MessageRouter` (currently at lines 612-639) to use human-readable French period labels instead of raw year numbers. Currently the format is "$year : $total encaisse..." which is unhelpful for monthly/quarterly queries.

**Layers:**
- `application/revenue/GetRevenue.kt` — add `periodType: String` field to `GetRevenueCommand` (values: "month", "quarter", "year"). Pass through from MessageRouter so the response can be formatted correctly. Add `period` passthrough to `GetRevenueResult` so MessageRouter has the date range for labeling.
- `application/MessageRouter.kt` — add private `formatPeriodLabel(periodType: String, period: DeclarationPeriod): String` function. Uses `java.time.LocalDate.now()` for "current" comparisons and `java.time.format.TextStyle.FULL` with `Locale.FRENCH` for month names.

**Target behavior:**
- `periodType == "month"` + current month -> "Ce mois"
- `periodType == "month"` + past month -> "Mars 2026" (month name + year)
- `periodType == "quarter"` + current quarter -> "Ce trimestre"
- `periodType == "quarter"` + past quarter -> "T1 2026"
- `periodType == "year"` + current year -> "Cette annee"
- `periodType == "year"` + past year -> just the year number

**Acceptance criteria:**
- Monthly revenue query for current month shows "Ce mois : ..."
- Monthly query for a past month shows "Fevrier 2026 : ..."
- Quarterly query for current quarter shows "Ce trimestre : ..."
- Yearly query shows "Cette annee : ..." or just the year

**Tests:** MessageRouter tests for each periodType variant (current + past)

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 16.2 Revenue Comparison to Previous Period

**Spec:** §6 "Tu es a 68% de ton CA du mois dernier"

**What:** When showing monthly or quarterly revenue, compute the previous period's total and show a percentage comparison.

**Layers:**
- `application/revenue/GetRevenue.kt` — add `previousBreakdown: RevenueBreakdown?` to `GetRevenueResult`. In `execute()`, for month/quarter periodTypes only, compute the previous period (`period.start.minusMonths(1)` for monthly, `period.start.minusMonths(3)` for quarterly), run `RevenueCalculation.compute()` for that period, attach to result. Yearly queries get `null`.
- `application/MessageRouter.kt` — if `result.previousBreakdown` is non-null and its total > 0, append comparison line: "Tu es a X% de ton CA [du mois dernier / du trimestre dernier] [emoji]". Emoji logic: >=100% -> rocket, >=60% -> thumbs up, <60% -> flexed bicep. If previous total is zero, omit the comparison line entirely. Percentage rounded to nearest integer.

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
15. **FK-aware deletion order.** When deleting user data, nullify invoice FK references and delete child tables (reminders, conversations, clients) before deleting the User row.
