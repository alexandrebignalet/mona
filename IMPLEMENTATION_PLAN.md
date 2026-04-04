# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1.1–14.4 done. See git log for details.

---

## Phase 15: GDPR Compliance

Implemented: ports (UserRepository.delete, ClientRepository.deleteByUser, ConversationRepository.deleteByUser, InvoiceRepository.anonymizeByUser), all Exposed impls, DeleteAccount use case, delete_account tool, ActionParser mapping, MessageRouter confirm/cancel flow with pendingDeletionSet. Tests: confirm path, cancel path, confirmation prompt. Golden tests added.

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

## Phase 17: CI/CD Pipelines

### 17.1 CI Workflow

**Spec:** `specs/ci-cd-spec.md` §2

**What:** Create `.github/workflows/ci.yml` — the main build-test-lint pipeline that runs on every push to `main` and every PR targeting `main`. This workflow gates merges. Golden tests skip gracefully via `Assumptions.assumeTrue` when `ANTHROPIC_API_KEY` is absent (reported as skipped, not failed). No secrets required.

**Layer:** infrastructure/CI (YAML only, no Kotlin changes)

**File:** `.github/workflows/ci.yml`

**Contents:**
- `name: CI` (exact name — deploy workflow references this via `workflow_run.workflows: [CI]`)
- Trigger: `on: push: branches: [main]` and `pull_request: branches: [main]`
- Runner: `ubuntu-latest`
- Job: `build-and-test`
- Steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` with `distribution: temurin`, `java-version: '21'`
  3. `actions/cache@v4` with paths `~/.gradle/caches` and `~/.gradle/wrapper`, key based on `hashFiles('**/*.gradle.kts')`
  4. `./gradlew build --no-daemon`
  5. `./gradlew ktlintCheck --no-daemon`
  6. `actions/upload-artifact@v4` uploading `build/reports/tests/` with `if: always()`

**Acceptance criteria:**
- Workflow triggers on push to main and on PRs to main
- `name:` field is exactly `CI` (must match deploy workflow's `workflow_run.workflows` reference)
- JDK is Temurin 21
- Gradle caches are restored and saved between runs
- Build and lint steps use `--no-daemon`
- Test reports uploaded even on failure (`if: always()`)
- Golden tests appear as skipped (not failed) when no API key is present
- No secrets required

**Validation:** Push to GitHub and verify workflow runs. Confirm golden tests show as skipped, not failed.

---

### 17.2 Golden Tests Workflow

**Spec:** `specs/ci-cd-spec.md` §3

**What:** Create `.github/workflows/golden-tests.yml` — a manual-only workflow for running LLM golden tests against the real Claude API. Supports optional category filtering. Requires `ANTHROPIC_API_KEY` repository secret.

**Layer:** infrastructure/CI (YAML only, no Kotlin changes)

**File:** `.github/workflows/golden-tests.yml`

**Contents:**
- `name: Golden Tests`
- Trigger: `workflow_dispatch` with optional `categories` input (string, default `""`, description: "Comma-separated golden test categories (blank = all)")
- Runner: `ubuntu-latest`
- Job: `golden-tests`
- Environment variable: `ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}`
- Steps:
  1. `actions/checkout@v4`
  2. `actions/setup-java@v4` with `distribution: temurin`, `java-version: '21'`
  3. `actions/cache@v4` (same Gradle cache config as CI)
  4. Run golden tests: `./gradlew test --tests "*.GoldenParsingTest" --tests "*.GoldenContextTest" --no-daemon` — when `inputs.categories` is non-empty, append `-Dgolden.categories=${{ inputs.categories }}`
  5. `actions/upload-artifact@v4` uploading `build/reports/tests/` with `if: always()`

**Acceptance criteria:**
- Workflow only triggers manually (no push/PR trigger)
- Optional `categories` input filters test execution when provided
- `ANTHROPIC_API_KEY` secret is passed as environment variable
- Test reports uploaded even on failure
- All Gradle invocations use `--no-daemon`

**Validation:** Trigger manually from GitHub Actions UI. Confirm golden tests execute against real API. Confirm category filtering works when input is provided.

---

### 17.3 Deploy Workflow

**Spec:** `specs/ci-cd-spec.md` §4

**What:** Create `.github/workflows/deploy.yml` — deploys to Fly.io production. Two trigger paths: automatic (on CI workflow success on `main`) and manual (with `confirm: 'deploy'` guard). Builds Docker image on Fly.io's remote builders — no Docker setup needed on the runner. Requires `FLY_API_TOKEN` repository secret.

**Layer:** infrastructure/CI (YAML only, no Kotlin changes)

**File:** `.github/workflows/deploy.yml`

**Contents:**
- `name: Deploy`
- Triggers:
  - `workflow_run: workflows: [CI]`, `branches: [main]`, `types: [completed]`
  - `workflow_dispatch` with required input `confirm` (description: "Type 'deploy' to confirm production deployment")
- Job: `deploy`
- Job-level `if` condition:
  ```
  (github.event_name == 'workflow_run' && github.event.workflow_run.conclusion == 'success')
  || (github.event_name == 'workflow_dispatch' && github.event.inputs.confirm == 'deploy')
  ```
- Environment variable: `FLY_API_TOKEN: ${{ secrets.FLY_API_TOKEN }}`
- Steps:
  1. `actions/checkout@v4`
  2. `superfly/flyctl-actions/setup-flyctl@master`
  3. `flyctl deploy --remote-only`
  4. `flyctl status --app mona-late-tree-7299` (health check — verify machine reaches `started` state)

**Acceptance criteria:**
- Automatic trigger: runs only when CI workflow succeeds on `main` (skips on failure/cancellation)
- Manual trigger: requires typing `deploy` as confirmation — prevents accidental dispatch
- `workflow_run.workflows` references `[CI]` which matches the exact `name: CI` in `ci.yml`
- Uses Fly.io remote builders (`--remote-only`) — no Docker installed on runner
- Health check verifies app status after deploy
- `FLY_API_TOKEN` secret is passed as environment variable
- No Gradle or JDK setup needed (no local build — Docker image built remotely)

**Validation:** Push to GitHub and verify workflow runs. Confirm automatic deploy triggers after CI success on main. Confirm manual deploy rejects when confirmation input is not `deploy`.

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
