# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

The strategy is: scaffolding -> domain (pure, testable) -> infrastructure adapters -> application use cases -> LLM integration -> end-to-end wiring -> scheduled jobs -> deployment -> polish.

---

## Completed Phases

Phases 1.1–14.4 done. See git log for details.

---

## Phase 15: GDPR Compliance

### 15.1 GDPR Account Deletion

**Spec:** §12 Right to Deletion

**What:** Allow a user to request full account deletion via chat. Mona asks for confirmation, then permanently deletes profile data and client records while retaining anonymized invoice records for the 10-year legal retention period.

**Layers:**
- `domain/port/` — add `delete(userId: UserId)` to `UserRepository`; add `deleteByUser(userId: UserId)` to `ClientRepository`
- `infrastructure/db/` — implement deletion methods in `ExposedUserRepository` and `ExposedClientRepository`; delete User row; delete all Client rows for the user; delete conversation history rows
- `application/gdpr/` (new package) — `DeleteAccount` use case: validates user exists, deletes user profile, deletes all client records, deletes conversation history, returns confirmation. Invoice rows remain (their `userId` and `clientId` FKs reference now-deleted rows — this is the "anonymized" retention the spec requires)
- `infrastructure/llm/ActionTypes.kt` — add `ParsedAction.DeleteAccount` (data object)
- `infrastructure/llm/ToolDefinitions.kt` — add `delete_account` tool (no parameters; user identity comes from context)
- `infrastructure/llm/ActionParser.kt` — map `delete_account` tool call to `ParsedAction.DeleteAccount`
- `application/MessageRouter.kt` — add `handleDeleteAccount`: send confirmation prompt ("Tu es sûr·e ? Cette action est irréversible."), wait for confirmation token (reuse CONFIRM_TOKENS/CANCEL_TOKENS pattern from duplicate detection via a `pendingDeletionSet`), then execute `DeleteAccount` use case, send farewell message ("Compte supprimé. Tes factures sont conservées de manière anonyme pendant 10 ans (obligation légale). Au revoir ! 👋")

**Acceptance criteria:**
- User says "supprime mon compte" and Mona responds with confirmation prompt
- On confirmation, user profile and all client records are deleted from DB
- Invoice rows remain in DB (orphaned FKs — no personal data exposed)
- Conversation history is deleted
- On cancellation, Mona responds "OK, on oublie ça" and nothing is deleted
- On next contact after deletion, user is treated as a brand-new user

**Golden tests:** Add `delete_account` entries to `src/test/resources/golden/parsing_cases.json` covering: "supprime mon compte", "je veux supprimer mes données", "efface tout"

**Tests:**
- `DeleteAccountTest` — verifies user + clients deleted, invoices untouched
- MessageRouter tests: confirm path + cancel path

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 15.2 GDPR Data Export (PDFs + Profile JSON)

**Spec:** §12 Data Export

**What:** Allow a user to export all their data via chat. Extends the existing CSV export with: all invoice and credit note PDFs regenerated and sent as documents, plus a profile JSON file.

**Layers:**
- `application/gdpr/` — `ExportGdprData` use case: (a) delegates to `ExportInvoicesCsv` for the CSV, (b) loads all non-draft invoices, regenerates each PDF via `PdfPort.generateInvoice` and credit note PDFs via `PdfPort.generateCreditNote` for cancelled invoices with credit notes, (c) serializes user profile to JSON (name, email, activityType, SIREN, SIRET, address, declarationPeriodicity, defaultPaymentDelay; IBAN → `"iban_enregistre": true/false`, never the raw value)
- `infrastructure/llm/ActionTypes.kt` — add `ParsedAction.ExportData` (data object)
- `infrastructure/llm/ToolDefinitions.kt` — add `export_data` tool (no parameters)
- `infrastructure/llm/ActionParser.kt` — map `export_data` tool call to `ParsedAction.ExportData`
- `application/MessageRouter.kt` — add `handleExportData`: calls `ExportGdprData`, sends summary text, then CSV, then each PDF, then profile JSON as documents

**Important:** `PdfPort.generateInvoice` requires `User`, `Client`, and `plainIban`. Load client per invoice; pass decrypted IBAN (it's the user's own data export). Skip PDFs for invoices whose client was deleted.

**Acceptance criteria:**
- User says "exporte mes données" and receives: CSV + all invoice PDFs + all credit note PDFs + profile JSON
- Profile JSON contains all profile fields + IBAN presence indicator
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

**Spec:** §6 ("Ce mois : 3 200€ encaissés sur 4 factures.")

**What:** Fix `handleGetRevenue` in `MessageRouter` to use human-readable French period labels instead of always showing the year number.

**Layer:** `application/MessageRouter.kt` only (no domain changes)

**Target behavior:**
- `periodType == "month"` + current month → "Ce mois"
- `periodType == "month"` + past month → "Mars 2026" (month name + year, `Locale.FRENCH`)
- `periodType == "quarter"` + current quarter → "Ce trimestre"
- `periodType == "quarter"` + past quarter → "T1 2026"
- `periodType == "year"` + current year → "Cette année"
- `periodType == "year"` + past year → just the year number

**Implementation:** Add a private `formatPeriodLabel(periodType, year, month?, quarter?)` function in MessageRouter using `java.time.LocalDate.now()` for "current" comparisons and `java.time.format.TextStyle.FULL` with `Locale.FRENCH` for month names.

**Acceptance criteria:**
- Monthly revenue query for current month shows "Ce mois : ..."
- Monthly query for a past month shows "Février 2026 : ..."
- Quarterly query for current quarter shows "Ce trimestre : ..."
- Yearly query shows "Cette année : ..." or just the year

**Tests:** MessageRouter tests for each periodType variant (current + past)

**Validation:** `./gradlew build && ./gradlew ktlintCheck`

---

### 16.2 Revenue Comparison to Previous Period

**Spec:** §6 "Comparison to previous period" + example "Tu es à 68% de ton CA du mois dernier 👍"

**What:** When showing monthly or quarterly revenue, compute the previous period's total and show a percentage comparison.

**Layers:**
- `application/revenue/GetRevenue.kt` — add `previousBreakdown: RevenueBreakdown?` to `GetRevenueResult`. Add `periodType: String` to `GetRevenueCommand`. In `execute()`, compute the previous period (month−1 or quarter−1 via `period.start.minusMonths(1)` / `minusMonths(3)`) only for month/quarter periods. Run `RevenueCalculation.compute()` for the previous period.
- `application/MessageRouter.kt` — if `result.previousBreakdown` is non-null and its total > 0, append: "Tu es à X% de ton CA [du mois dernier / du trimestre dernier] [emoji]". Emoji: ≥100% → 🚀, ≥60% → 👍, <60% → 💪. If previous total is zero, omit the comparison line.

**Acceptance criteria:**
- Monthly revenue query includes comparison to previous month when previous month has data
- Quarterly revenue query includes comparison to previous quarter
- Yearly query has NO comparison line
- No error when previous period has zero revenue (line simply omitted)
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
