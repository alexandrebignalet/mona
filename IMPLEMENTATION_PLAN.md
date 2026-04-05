# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1.1–22, 21 done. See git log for details.

> **Pending operations (not code — run manually):**
>
> 1. **SIRENE secrets rotation:**
>    ```bash
>    fly secrets set SIRENE_CLIENT_ID=xxx SIRENE_CLIENT_SECRET=xxx -a mona-late-tree-7299
>    fly secrets unset SIRENE_API_KEY -a mona-late-tree-7299
>    ```
>
> 2. **Phase 19.6 — Deploy and verify:**
>    - `fly secrets set TELEGRAM_WEBHOOK_URL=https://mona-late-tree-7299.fly.dev/webhook/telegram TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 32) -a mona-late-tree-7299`
>    - `fly deploy`
>    - Smoke test: message → response, inline button → no 30s spinner, `/start` → onboarding, `/health` → 200, `/webhook/resend` → still works.

---

---

## Phase 23 — Integration Test Suite

**What:** Create the `mona.integration` test package with 26 end-to-end use-case tests. Tests exercise real infrastructure (in-memory SQLite, PDFBox, AES crypto) but bypass Telegram and Claude. External HTTP APIs (Sirene, Resend) use canned JSON fixtures. Each test invokes a use case directly with structured inputs, then asserts DB state, collected messages, dispatched events, and generated PDF bytes.

**Layer:** Test (`src/test/kotlin/mona/integration/`). No production code changes expected (unless HTTP executor interfaces need extracting for testability).

**Spec ref:** `specs/integration-tests-spec.md` (entire document).

### 23.1 — Shared Fixtures and Test Base

Create `src/test/kotlin/mona/integration/` with shared infrastructure:

- **`IntegrationTestBase.kt`** — Abstract base class providing:
  - `TestDb`: in-memory SQLite via Exposed, fresh schema per test class, torn down after
  - All Exposed-backed repository implementations wired to `TestDb`
  - `FakeMessagingPort`: in-memory `MessagingPort` collector with `lastMessage()`, `lastDocument()`, `allMessages()` assertion helpers
  - `FakeSireneHttpExecutor`: returns canned JSON from `src/test/resources/fixtures/sirene/`, configurable per test (success, not found, ceased, multiple, malformed, IOException)
  - `FakeResendHttpExecutor`: returns canned JSON from `src/test/resources/fixtures/resend/`, configurable (success, bounce, rate limit, invalid email)
  - `TestCryptoPort`: real `IbanCryptoAdapter` with hardcoded test AES key
  - `TestEventCollector`: captures dispatched domain events for assertion
  - Real `PdfGenerator` (PDFBox) — no mock
  - `SireneApiClient` wired with `FakeSireneHttpExecutor`
  - `ResendEmailAdapter` wired with `FakeResendHttpExecutor`
  - Helper methods: `createTestUser()`, `createTestClient()`, `createTestInvoice(status)`
  - Pre-built domain objects: `TestUser` (fully onboarded with SIREN, SIRET, email, IBAN, activityType=BIC_SERVICE), `TestUserMinimal` (only telegramId), `TestClient` (linked to TestUser, has email, address, SIRET)

- **Fixture files** in `src/test/resources/fixtures/`:
  - `sirene/`: `lookup_success.json`, `lookup_not_found.json`, `lookup_ceased.json`, `search_single_match.json`, `search_multiple_matches.json`, `search_no_match.json`, `search_malformed.json`
  - `resend/`: `send_success.json`, `send_rate_limited.json`, `send_invalid_email.json`

**Acceptance criteria:**
- `IntegrationTestBase` compiles and can be extended by test classes
- Fixture JSON files are valid and match the real API response shapes
- `FakeMessagingPort`, `FakeSireneHttpExecutor`, `FakeResendHttpExecutor`, `TestEventCollector` are reusable across all test classes
- If `SireneApiClient` or `ResendEmailAdapter` do not already accept an injectable HTTP executor, extract the interface at the adapter boundary (infrastructure layer only)

### 23.2 — Command Scenario Tests (15 test classes)

| # | Test class | Package | Scenarios | Key assertions |
|---|-----------|---------|-----------|----------------|
| 1 | `SearchSirenTest` | `onboarding/` | 5: single match, multiple matches, no match, malformed response, API error | Sirene fixture switching, `DomainResult.Ok`/`Err` |
| 2 | `LookupSirenTest` | `onboarding/` | 4: valid SIREN, not found, ceased, user already has SIREN | User fields populated from Sirene, DB state |
| 3 | `SetupProfileTest` | `onboarding/` | 8: email, address, IBAN, activity type, periodicity, payment delay, name, multi-field | IBAN encrypted via `TestCryptoPort`, all fields persisted |
| 4 | `FinalizeInvoiceTest` | `onboarding/` | 3: with IBAN, without IBAN, not a draft | PDF bytes non-empty, IBAN presence in PDF text |
| 5 | `CreateInvoiceTest` | `invoicing/` | 8: happy path, existing client, multi-line, duplicate warning, sequential numbering, cross-month, custom delay, activity override | `F-YYYY-MM-NNN` format, `Cents` arithmetic, client auto-creation |
| 6 | `SendInvoiceTest` | `invoicing/` | 6: happy path, no client email, email fails, not Draft, with IBAN, without IBAN | Status=Sent, `InvoiceSent` event, email via `FakeResendHttpExecutor` |
| 7 | `UpdateDraftTest` | `invoicing/` | 5: update items, change client, update date, not a draft, partial update | PDF regenerated, DB fields match |
| 8 | `DeleteDraftTest` | `invoicing/` | 3: happy path, not a draft, number gap handling | `DraftDeleted` event, invoice removed from DB |
| 9 | `CancelInvoiceTest` | `invoicing/` | 5: happy path, credit note numbering, cancel draft, cancel cancelled, amount match | Credit note `A-YYYY-MM-NNN`, `InvoiceCancelled` event |
| 10 | `CorrectInvoiceTest` | `invoicing/` | 4: happy path, different amount, different client, numbering | Original cancelled + credit note + new draft, 2 PDFs |
| 11 | `MarkPaidTest` | `payment/` | 5: sent, overdue, draft, already paid, all payment methods | Status=Paid, `InvoicePaid` event, `paidAt`+`paymentMethod` stored |
| 12 | `UpdateClientTest` | `client/` | 5: update email, rename, not found, ambiguous, multi-field | DB state, `Ambiguous(matches)` result |
| 13 | `ConfigureSettingTest` | `settings/` | 3: confirm_before_create true/false, default_payment_delay | User settings persisted |
| 14 | `DeleteAccountTest` | `gdpr/` | 3: full deletion, no data, anonymized invoices preserved | FK-aware deletion order, invoices anonymized not deleted |
| 15 | `ExportDataTest` | `gdpr/` | 3: full export, empty account, IBAN decryption | CSV bytes, PDF bytes, profile JSON with plaintext IBAN |

### 23.3 — Query Scenario Tests (5 test classes)

| # | Test class | Package | Scenarios | Key assertions |
|---|-----------|---------|-----------|----------------|
| 1 | `GetRevenueTest` | `revenue/` | 8: monthly, quarterly, yearly, empty, mixed activity, credit note deduction, previous period comparison, pending count | `PaidInvoiceSnapshot` read-model, cash basis (paidDate) |
| 2 | `GetUnpaidTest` | `revenue/` | 3: has unpaid, all paid, mixed statuses | Only Sent+Overdue returned |
| 3 | `ExportCsvTest` | `revenue/` | 3: happy path, no invoices, CSV column verification | CSV bytes, header row, correct columns |
| 4 | `ListClientsTest` | `client/` | 3: has clients, no clients, client with no invoices | invoiceCount + totalAmount aggregation |
| 5 | `ClientHistoryTest` | `client/` | 4: happy path, not found, ambiguous, many invoices | Ordered by date, `Ambiguous(matches)` |

### 23.4 — Background Job Scenario Tests (6 test classes)

| # | Test class | Package | Scenarios | Key assertions |
|---|-----------|---------|-----------|----------------|
| 1 | `PaymentCheckInJobTest` | `payment/` | 3: invoice due yesterday, none due, multiple due | Messages sent via `FakeMessagingPort`, DB unchanged |
| 2 | `OverdueTransitionJobTest` | `payment/` | 4: 3+ days overdue, 2 days (no transition), already overdue, multiple | Status=Overdue, `InvoiceOverdue` event, threshold = 3 days |
| 3 | `CheckVatThresholdTest` | `urssaf/` | 4: 80% threshold, 95% threshold, below, already alerted | Alert recorded in DB, no duplicate alerts per year |
| 4 | `UrssafReminderJobTest` | `urssaf/` | 5: 7 days before, 1 day before, not near, already reminded, quarterly user | Reminder messages, periodicity-aware deadline calculation |
| 5 | `OnboardingRecoveryJobTest` | (root or `onboarding/`) | 4: day-1 reminder, day-3 reminder, has SIREN (skip), already reminded | Reminder recorded, no duplicates |
| 6 | `HandleBouncedEmailTest` | (root or `email/`) | 2: bounce on sent invoice, invoice not found | Invoice reverts to Draft, user notified |

### 23.5 — HTTP Contract and PDF Tests

- **`SireneApiContractTest`** (`sirene/`): 9 scenarios testing `SireneApiClient` directly with fixture files — field mapping, address assembly, NAF-to-ActivityType, 404 handling, malformed JSON
- **`SireneApiLiveTest`** (`sirene/`): 2 scenarios gated behind `LIVE_API_TESTS=true` env var — real lookup + real search against INSEE API
- **`ResendApiContractTest`** (`email/`): 3 scenarios testing `ResendEmailAdapter` directly — success, invalid recipient, rate limit
- **`PdfGenerationTest`** (`pdf/`): 7 scenarios with real PDFBox — valid PDF header (`%PDF`), key text extraction (invoice number, client, amounts), draft watermark, IBAN presence/absence, credit note ("Avoir"), multi-line-item

**Acceptance criteria (all of Phase 23):**
- All 26 test classes exist under `src/test/kotlin/mona/integration/` in the package structure matching the spec
- `./gradlew test --tests "mona.integration.*"` runs all integration tests and passes
- Full suite completes in < 10 seconds (no network calls unless `LIVE_API_TESTS=true`)
- `SireneApiLiveTest` is skipped by default (gated behind `LIVE_API_TESTS=true`)
- `./gradlew build && ./gradlew ktlintCheck` pass with all new test code included

---

## Prevention Rules

1. **No unapproved libraries.** Check CLAUDE.md tech stack before adding any dependency.
2. **Golden tests gate prompt changes.** Never deploy a prompt change without running the golden suite.
3. **URSSAF = cash basis.** Revenue computed from `paidDate`, never `issueDate`.
4. **Last 3 messages only.** No separate "last action" pointer. LLM resolves from conversation history.
