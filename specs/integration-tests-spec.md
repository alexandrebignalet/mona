# Integration Tests Spec

**Goal:** Fast local feedback loop for every command and query the system supports. Tests exercise real infrastructure (SQLite, HTTP clients, PDF generation) but bypass Telegram and Claude. Each test starts from the application layer with hardcoded structured inputs (as if Claude already parsed the user message).

## Principles

1. **No LLM calls.** Tests invoke use cases directly with typed `ParsedAction`-equivalent inputs. Claude is not part of the feedback loop.
2. **No Telegram.** `MessagingPort` is stubbed (in-memory collector of sent messages/documents).
3. **Real SQLite.** In-memory SQLite via Exposed. Fresh DB per test class.
4. **Real PDF generation.** PdfPort (PDFBox) runs for real. Tests assert PDF bytes are non-empty and valid.
5. **Recorded HTTP responses for external APIs.** Sirene and Resend use canned JSON responses (files in `test/resources/fixtures/`). A `FakeSireneHttpExecutor` and `FakeResendHttpExecutor` replay them.
6. **One optional live smoke test per external API.** Gated behind env var `LIVE_API_TESTS=true`. Skipped by default.
7. **Real crypto.** `IbanCryptoAdapter` runs with a test key.
8. **Tests are fast.** Full suite < 10s. No network calls unless `LIVE_API_TESTS=true`.

## Test Infrastructure

### Shared Test Fixtures

| Fixture | Description |
|---------|-------------|
| `TestDb` | Creates in-memory SQLite, runs schema creation, provides Exposed `Database` instance. Torn down after each test class. |
| `TestUser` | Pre-created `User` with SIREN, SIRET, email, IBAN, activity type = BIC_SERVICE. Covers the "fully onboarded" case. |
| `TestUserMinimal` | Pre-created `User` with only telegramId. Covers "fresh user" onboarding scenarios. |
| `TestClient` | Pre-created `Client` linked to `TestUser`. Has email, address, SIRET. |
| `FakeMessagingPort` | In-memory `MessagingPort`. Collects all `sendMessage`, `sendDocument`, `sendButtons` calls. Provides `lastMessage()`, `lastDocument()`, `allMessages()` for assertions. |
| `FakeSireneHttpExecutor` | Returns canned JSON from `test/resources/fixtures/sirene/`. Configurable per test: success, not found, multiple results, malformed response. |
| `FakeResendHttpExecutor` | Returns canned JSON from `test/resources/fixtures/resend/`. Configurable: success, bounce, rate limit. |
| `TestCryptoPort` | Real `IbanCryptoAdapter` with hardcoded test AES key. |
| `TestEventCollector` | Captures dispatched domain events for assertion. |

### Fixture Files (test/resources/fixtures/)

```
fixtures/
├── sirene/
│   ├── lookup_success.json          # Valid SIREN lookup response
│   ├── lookup_not_found.json        # SIREN not in registry
│   ├── lookup_ceased.json           # Company ceased activity
│   ├── search_single_match.json     # Name+city search → 1 result
│   ├── search_multiple_matches.json # Name+city search → N results
│   ├── search_no_match.json         # Name+city search → 0 results
│   └── search_malformed.json        # Unexpected API response shape
├── resend/
│   ├── send_success.json            # 200 OK with message ID
│   ├── send_rate_limited.json       # 429 response
│   └── send_invalid_email.json      # 400 bad recipient
└── golden-pdfs/
    ├── invoice_simple.pdf           # Reference PDF for visual regression (optional)
    └── credit_note_simple.pdf
```

### Test Location

```
test/kotlin/mona/integration/
├── IntegrationTestBase.kt           # Shared setup: TestDb, repos, fixtures, wiring
├── onboarding/
│   ├── SearchSirenTest.kt
│   ├── LookupSirenTest.kt
│   ├── SetupProfileTest.kt
│   └── FinalizeInvoiceTest.kt
├── invoicing/
│   ├── CreateInvoiceTest.kt
│   ├── SendInvoiceTest.kt
│   ├── UpdateDraftTest.kt
│   ├── DeleteDraftTest.kt
│   ├── CancelInvoiceTest.kt
│   └── CorrectInvoiceTest.kt
├── payment/
│   ├── MarkPaidTest.kt
│   ├── PaymentCheckInJobTest.kt
│   └── OverdueTransitionJobTest.kt
├── revenue/
│   ├── GetRevenueTest.kt
│   ├── GetUnpaidTest.kt
│   └── ExportCsvTest.kt
├── client/
│   ├── ListClientsTest.kt
│   ├── UpdateClientTest.kt
│   └── ClientHistoryTest.kt
├── urssaf/
│   ├── CheckVatThresholdTest.kt
│   └── UrssafReminderJobTest.kt
├── settings/
│   └── ConfigureSettingTest.kt
├── gdpr/
│   ├── ExportDataTest.kt
│   └── DeleteAccountTest.kt
└── sirene/
    └── SireneApiLiveTest.kt         # Gated behind LIVE_API_TESTS=true
```

---

## Commands

### 1. search_siren

**Use case:** `SetupProfile` with `SearchSiren` command
**Infra touched:** SirenePort (HTTP), UserRepository (DB)
**Entry point:** `SetupProfile.execute(userId, SetupProfile.Command.SearchSiren(name, city))`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Single match | name="Dupont", city="Paris" | `SirenMatches` with 1 result containing SIREN, SIRET, legal name, address, activity type | Sirene returns `search_single_match.json` |
| 2 | Multiple matches | name="Martin", city="Lyon" | `SirenMatches` with N results | Sirene returns `search_multiple_matches.json` |
| 3 | No match | name="Zzzznotfound", city="Nowhere" | `DomainResult.Err` with appropriate error | Sirene returns `search_no_match.json` |
| 4 | Malformed API response | name="Test", city="Test" | `DomainResult.Err` — graceful handling, no crash | Sirene returns `search_malformed.json` |
| 5 | API timeout/error | name="Test", city="Test" | `DomainResult.Err` with network error | Sirene executor throws IOException |

### 2. lookup_siren (update_profile with SIREN)

**Use case:** `SetupProfile` with `LookupSiren` command
**Infra touched:** SirenePort (HTTP), UserRepository (DB)
**Entry point:** `SetupProfile.execute(userId, SetupProfile.Command.LookupSiren(siren))`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Valid SIREN | siren=Siren("123456789") | `SirenFound(user)` with siren, siret, name, address, activityType populated | Sirene returns `lookup_success.json` |
| 2 | SIREN not found | siren=Siren("000000000") | `DomainResult.Err` | Sirene returns `lookup_not_found.json` |
| 3 | Ceased company | siren=Siren("999999999") | `DomainResult.Err` or appropriate handling | Sirene returns `lookup_ceased.json` |
| 4 | User already has SIREN | siren=Siren("123456789") on TestUser | Updates/replaces existing SIREN | Sirene returns `lookup_success.json` |

### 3. update_profile (non-SIREN fields)

**Use case:** `SetupProfile` with `UpdateFields` or `SetIban` command
**Infra touched:** UserRepository (DB), CryptoPort (for IBAN)
**Entry point:** `SetupProfile.execute(userId, SetupProfile.Command.UpdateFields(...))`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Set email | email="test@example.com" | User updated with email | DB write |
| 2 | Set address | address=PostalAddress("1 rue X, 75001 Paris") | User updated | DB write |
| 3 | Set IBAN | iban="FR7630001007941234567890185" | User updated with encrypted IBAN | CryptoPort.encrypt called, ciphertext stored |
| 4 | Set activity type | activityType=BNC | User updated | DB write |
| 5 | Set declaration periodicity | periodicity=MONTHLY | User updated | DB write |
| 6 | Set payment delay | paymentDelayDays=PaymentDelayDays(45) | User updated | DB write |
| 7 | Set name | name="Jean Dupont" | User updated | DB write |
| 8 | Update multiple fields at once | name + email + address | All fields updated in single operation | DB write |

### 4. create_invoice

**Use case:** `CreateInvoice`
**Infra touched:** UserRepository, ClientRepository, InvoiceRepository (DB), PdfPort (PDFBox)
**Entry point:** `CreateInvoice.execute(userId, clientName, lineItems, issueDate, activityType, paymentDelay)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path — new client | clientName="ACME", items=[("Dev", 1, 50000)], date=today | Draft invoice created, client auto-created, PDF bytes returned, invoice number = F-YYYY-MM-001 | DB: client + invoice saved. PDF generated. |
| 2 | Existing client | clientName matching TestClient | Invoice linked to existing client (no duplicate) | DB: only invoice saved |
| 3 | Multiple line items | 3 line items with different quantities/prices | Total = sum of (qty * unitPrice) per item | Amounts in Cents, no floating point |
| 4 | Duplicate warning | Same client + same amount + recent date | `DuplicateWarning` result with existing invoice ref | DB: checks `findByClientAndAmountSince` |
| 5 | Sequential numbering | Create 2 invoices in same month | Numbers = F-YYYY-MM-001, F-YYYY-MM-002 | DB: `findLastNumberInMonth` returns previous |
| 6 | Cross-month numbering | Invoice in Jan then Feb | Numbers restart: F-2026-01-001, F-2026-02-001 | DB query scoped to month |
| 7 | Custom payment delay | paymentDelay=PaymentDelayDays(60) | dueDate = issueDate + 60 days | Stored on invoice |
| 8 | Activity type override | activityType=BIC_VENTE (user default is BIC_SERVICE) | Invoice uses BIC_VENTE | Per-invoice override |

### 5. send_invoice

**Use case:** `SendInvoice`
**Infra touched:** UserRepository, ClientRepository, InvoiceRepository (DB), PdfPort, EmailPort (Resend), EventDispatcher
**Entry point:** `SendInvoice.execute(userId, invoiceId, plainIban)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path | Draft invoice with client email | Invoice transitions to Sent, email sent with PDF, `InvoiceSent` event dispatched | Email: `send_success.json`. DB: status=Sent, dueDate set |
| 2 | Client has no email | Invoice for client without email | `DomainResult.Err` — cannot send | No email call |
| 3 | Email fails | Valid invoice | `DomainResult.Err` — invoice stays Draft | Email: returns error. DB: no status change |
| 4 | Invoice not Draft | Sent invoice | `DomainResult.Err` — wrong status transition | No email call |
| 5 | PDF includes IBAN | plainIban provided | PDF contains IBAN section | PdfPort called with IBAN |
| 6 | PDF without IBAN | no plainIban | PDF generated without IBAN section | PdfPort called with null IBAN |

### 6. mark_paid

**Use case:** `MarkInvoicePaid`
**Infra touched:** InvoiceRepository (DB), EventDispatcher
**Entry point:** `MarkInvoicePaid.execute(userId, invoiceId, paymentDate, paymentMethod)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path — Sent invoice | Sent invoice, date=today, method=VIREMENT | Invoice transitions to Paid, `InvoicePaid` event dispatched | DB: status=Paid, paidAt, paymentMethod |
| 2 | Overdue invoice | Overdue invoice | Transitions to Paid (valid transition) | Same as above |
| 3 | Draft invoice | Draft invoice | `DomainResult.Err` — cannot mark Draft as paid | No DB change |
| 4 | Already paid | Paid invoice | `DomainResult.Err` — already in terminal state | No DB change |
| 5 | All payment methods | VIREMENT, CHEQUE, ESPECES, CARTE, AUTRE | Each accepted | DB stores correct method |

### 7. update_draft

**Use case:** `UpdateDraft`
**Infra touched:** UserRepository, ClientRepository, InvoiceRepository (DB), PdfPort, EventDispatcher
**Entry point:** `UpdateDraft.execute(userId, invoiceId, clientName?, lineItems?, issueDate?, paymentDelay?, activityType?)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Update line items | New line items | Draft updated, new PDF generated | DB: invoice updated. PDF regenerated. |
| 2 | Change client | New client name | Client changed (or created), invoice re-linked | DB: new client if needed |
| 3 | Update issue date | New date | Date updated, due date recalculated | DB update |
| 4 | Not a Draft | Sent invoice | `DomainResult.Err` | No change |
| 5 | Partial update | Only activityType changed | Other fields unchanged | DB: only activity type updated |

### 8. delete_draft

**Use case:** `DeleteDraft`
**Infra touched:** InvoiceRepository (DB), EventDispatcher
**Entry point:** `DeleteDraft.execute(userId, invoiceId)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path | Draft invoice | Invoice deleted, `DraftDeleted` event dispatched | DB: invoice removed |
| 2 | Not a Draft | Sent invoice | `DomainResult.Err` | No deletion |
| 3 | Number gap handling | Delete F-2026-04-002, create new | New invoice gets next available number (verify no gap violation) | DB: numbering query |

### 9. cancel_invoice

**Use case:** `CancelInvoice`
**Infra touched:** UserRepository, ClientRepository, InvoiceRepository (DB), PdfPort, EventDispatcher
**Entry point:** `CancelInvoice.execute(userId, invoiceId, reason, issueDate)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path | Sent invoice, reason="Erreur de facturation" | Invoice → Cancelled, credit note created (A-YYYY-MM-001), credit note PDF generated, `InvoiceCancelled` event | DB: invoice status=Cancelled, credit note stored. PDF: credit note generated. |
| 2 | Credit note numbering | Cancel 2 invoices same month | A-YYYY-MM-001, A-YYYY-MM-002 | Sequential credit note numbers |
| 3 | Cancel Draft | Draft invoice | `DomainResult.Err` — use delete_draft instead | No change |
| 4 | Cancel already cancelled | Cancelled invoice | `DomainResult.Err` | No change |
| 5 | Credit note amount matches | Invoice of 50000 cents | Credit note amount = 50000 cents | Amounts match exactly |

### 10. correct_invoice

**Use case:** `CorrectInvoice`
**Infra touched:** UserRepository, ClientRepository, InvoiceRepository (DB), PdfPort, EventDispatcher
**Entry point:** `CorrectInvoice.execute(userId, invoiceId, lineItems, activityType?, paymentDelay?, reason, issueDate)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path | Sent invoice + corrected line items | Original → Cancelled, credit note created, new corrected invoice created (Draft), both PDFs generated | DB: 3 records modified/created. PDF: 2 generated. |
| 2 | Different amount | Original 50000, corrected 60000 | Credit note = 50000, new invoice = 60000 | Amounts independent |
| 3 | Different client | New client name | New invoice linked to different client | Client created if needed |
| 4 | Numbering | Correct F-2026-04-001 | Credit note A-2026-04-001, new invoice F-2026-04-002 | Both numbering sequences advance |

### 11. update_client

**Use case:** `UpdateClient`
**Infra touched:** ClientRepository (DB)
**Entry point:** `UpdateClient.execute(userId, clientName, newName?, email?, address?, companyName?, siret?)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Update email | clientName="ACME", email="new@acme.com" | Client updated | DB write |
| 2 | Rename client | clientName="ACME", newName="ACME Corp" | Client renamed | DB write |
| 3 | Client not found | clientName="Unknown" | `DomainResult.Err` or empty result | No change |
| 4 | Ambiguous name | clientName="Ma" matching "Martin" and "Marchand" | `Ambiguous(matches)` result | DB: fuzzy/partial match query |
| 5 | Update multiple fields | email + address + siret | All updated | Single DB write |

### 12. configure_setting

**Use case:** `ConfigureSetting`
**Infra touched:** UserRepository (DB)
**Entry point:** `ConfigureSetting.execute(userId, setting, value)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Set confirm_before_create=true | setting="confirm_before_create", value=true | User.confirmBeforeCreate = true | DB write |
| 2 | Set confirm_before_create=false | setting="confirm_before_create", value=false | User.confirmBeforeCreate = false | DB write |
| 3 | Set default_payment_delay | setting="default_payment_delay_days", value=45 | User.defaultPaymentDelayDays = 45 | DB write |

### 13. delete_account

**Use case:** `DeleteAccount`
**Infra touched:** UserRepository, ClientRepository, ConversationRepository, InvoiceRepository (DB)
**Entry point:** `DeleteAccount.execute(userId)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Full account deletion | User with invoices, clients, conversations | Invoices anonymized, clients deleted, conversations deleted, user deleted | DB: 4 tables affected |
| 2 | User with no data | Minimal user, no invoices | Clean deletion, no errors | DB: only user deleted |
| 3 | Anonymized invoices preserved | User with Sent/Paid invoices | Invoice records remain (for legal) but anonymized | DB: invoices updated, not deleted |

### 14. export_data

**Use case:** `ExportGdprData`
**Infra touched:** UserRepository, InvoiceRepository, ClientRepository (DB), PdfPort, CryptoPort
**Entry point:** `ExportGdprData.execute(userId)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Full export | User with invoices + clients | CSV bytes, invoice PDFs, credit note PDFs, profile JSON | DB reads + PDF generation + IBAN decryption |
| 2 | Empty account | User with no invoices | CSV (headers only), empty PDF lists, profile JSON | Minimal DB reads |
| 3 | IBAN decryption | User with encrypted IBAN | Profile JSON contains plaintext IBAN | CryptoPort.decrypt called |

### 15. finalize_invoice (onboarding)

**Use case:** `FinalizeInvoice`
**Infra touched:** UserRepository, ClientRepository, InvoiceRepository (DB), PdfPort
**Entry point:** `FinalizeInvoice.execute(userId, invoiceId, plainIban?)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Finalize with IBAN | Draft invoice + IBAN | PDF regenerated with IBAN | PdfPort called with IBAN |
| 2 | Finalize without IBAN | Draft invoice, no IBAN | PDF regenerated without IBAN | PdfPort called with null |
| 3 | Not a draft | Sent invoice | `DomainResult.Err` | No change |

---

## Queries

### 16. get_revenue

**Use case:** `GetRevenue`
**Infra touched:** InvoiceRepository (DB)
**Entry point:** `GetRevenue.execute(userId, periodType, year, month?, quarter?)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Monthly revenue | periodType=month, year=2026, month=4 | Breakdown by activity type, total, paid count | DB: `findPaidInPeriod` + `findCreditNotesInPeriod` |
| 2 | Quarterly revenue | periodType=quarter, year=2026, quarter=1 | Jan+Feb+Mar aggregated | DB: period spans 3 months |
| 3 | Yearly revenue | periodType=year, year=2026 | Full year | DB: period spans 12 months |
| 4 | No invoices in period | Empty month | All zeros, counts = 0 | DB returns empty lists |
| 5 | Mixed activity types | BIC_SERVICE + BNC invoices | Breakdown shows both separately | Revenue computed per activity |
| 6 | Credit notes deducted | Paid invoice + credit note in same period | Credit note amount subtracted from revenue | Both snapshots used |
| 7 | Previous period comparison | Current month query | Returns current AND previous period breakdown | Two DB queries |
| 8 | Pending invoices counted | Sent but unpaid invoices | pendingCount and pendingAmount populated | DB: `findByUserAndStatus(Sent)` |

### 17. get_unpaid

**Use case:** `GetUnpaidInvoices`
**Infra touched:** InvoiceRepository, ClientRepository (DB)
**Entry point:** `GetUnpaidInvoices.execute(userId)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Has unpaid | User with Sent + Overdue invoices | List of (invoice, clientName) pairs | DB: finds Sent + Overdue |
| 2 | All paid | No Sent/Overdue invoices | Empty list | DB: empty result |
| 3 | Mix of statuses | Draft + Sent + Paid + Overdue | Only Sent + Overdue returned | Draft and Paid excluded |

### 18. export_invoices (CSV)

**Use case:** `ExportInvoicesCsv`
**Infra touched:** InvoiceRepository, ClientRepository (DB)
**Entry point:** `ExportInvoicesCsv.execute(userId, exportDate)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path | User with invoices | CSV bytes with all invoices, correct filename pattern | DB: all invoices + clients |
| 2 | No invoices | Empty user | CSV with headers only, count = 0 | DB: empty result |
| 3 | CSV content | Multiple invoices | Columns: number, date, client, amount, status, paid_date, payment_method | Verify CSV structure |

### 19. list_clients

**Use case:** `ListClients`
**Infra touched:** ClientRepository, InvoiceRepository (DB)
**Entry point:** `ListClients.execute(userId)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Has clients | User with clients + invoices | Clients with invoiceCount + totalAmount | DB: clients + invoice aggregation |
| 2 | No clients | New user | Empty list | DB: empty result |
| 3 | Client with no invoices | Client created but no invoices yet | Client listed, count=0, total=0 | DB: client found, no invoices |

### 20. client_history

**Use case:** `GetClientHistory`
**Infra touched:** ClientRepository, InvoiceRepository (DB)
**Entry point:** `GetClientHistory.execute(userId, clientName)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Happy path | Known client name | `Found(client, invoices)` | DB: client + invoices |
| 2 | Client not found | Unknown name | Error or empty | DB: no match |
| 3 | Ambiguous name | Partial match | `Ambiguous(matches)` | DB: multiple matches |
| 4 | Client with many invoices | Client with 10+ invoices | All invoices returned, ordered by date | DB: ordered query |

---

## Background Jobs

### 21. PaymentCheckInJob

**Use case:** `PaymentCheckInJob`
**Infra touched:** InvoiceRepository, ClientRepository (DB), MessagingPort
**Entry point:** `PaymentCheckInJob.run(today)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Invoice due yesterday | today = dueDate + 1 | Message sent to user asking about payment | DB: `findSentDueOn(yesterday)`. Messaging: message sent. |
| 2 | No invoices due | No invoices due yesterday | Nothing happens | DB: empty result. No messages. |
| 3 | Multiple invoices due | 3 invoices due yesterday | Message for each | 3 messages sent |

### 22. OverdueTransitionJob

**Use case:** `OverdueTransitionJob`
**Infra touched:** InvoiceRepository (DB), EventDispatcher
**Entry point:** `OverdueTransitionJob.run(today)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Invoice 3+ days overdue | Sent invoice, dueDate = today - 3 | Invoice → Overdue, `InvoiceOverdue` event | DB: status updated |
| 2 | Invoice 2 days overdue | Sent invoice, dueDate = today - 2 | No transition (not yet 3 days) | No change |
| 3 | Already Overdue | Overdue invoice | No duplicate transition | No change |
| 4 | Multiple overdue | 5 invoices past threshold | All transitioned | 5 DB updates, 5 events |

### 23. CheckVatThreshold

**Use case:** `CheckVatThreshold`
**Infra touched:** InvoiceRepository, VatAlertRepository (DB), MessagingPort
**Entry point:** `CheckVatThreshold.execute(event: InvoicePaid)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Revenue crosses 80% | InvoicePaid event, cumulative revenue = 80% of threshold | Warning message sent, alert recorded | DB: revenue query + alert saved. Message sent. |
| 2 | Revenue crosses 95% | Cumulative = 95% | Urgent alert sent | Same |
| 3 | Below threshold | Cumulative = 50% | No alert | DB: revenue query only. No message. |
| 4 | Alert already sent | 80% already alerted this year | No duplicate alert | DB: `findByUserAndYear` returns existing |

### 24. UrssafReminderJob

**Use case:** `UrssafReminderJob`
**Infra touched:** UserRepository, InvoiceRepository, UrssafReminderRepository (DB), MessagingPort
**Entry point:** `UrssafReminderJob.run(today)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | 7 days before deadline | today = deadline - 7, MONTHLY user | Reminder sent with revenue summary | DB reads + message sent |
| 2 | 1 day before deadline | today = deadline - 1 | Urgent reminder sent | DB reads + message sent |
| 3 | Not near deadline | today = mid-period | No reminder | DB: date check only |
| 4 | Already reminded | Reminder already sent for this period | No duplicate | DB: `findByUserAndPeriod` returns existing |
| 5 | QUARTERLY user | Quarterly periodicity | Different deadline calculation | `UrssafThresholds.nextDeclarationDeadline` with QUARTERLY |

### 25. OnboardingRecoveryJob

**Use case:** `OnboardingRecoveryJob`
**Infra touched:** UserRepository, InvoiceRepository, OnboardingReminderRepository, ConversationRepository (DB), MessagingPort
**Entry point:** `OnboardingRecoveryJob.run(today)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | User with draft + no SIREN (day 1) | User created yesterday, has draft, no SIREN | Day-1 reminder sent | DB reads + message sent + reminder recorded |
| 2 | User with draft + no SIREN (day 3) | User created 3 days ago | Day-3 reminder sent | Same |
| 3 | User has SIREN | User has SIREN | No reminder (already onboarded) | DB: filtered out |
| 4 | Already reminded | Day-1 reminder already sent | No duplicate | DB: `findByUser` returns existing |

### 26. HandleBouncedEmail

**Use case:** `HandleBouncedEmail`
**Infra touched:** InvoiceRepository (DB), MessagingPort
**Entry point:** `HandleBouncedEmail.execute(invoiceNumber, recipientEmail)`

| # | Scenario | Input | Expected | Infra behavior |
|---|----------|-------|----------|----------------|
| 1 | Bounce on Sent invoice | Valid invoice number + email | Invoice reverts to Draft, user notified | DB: status=Draft. Message sent. |
| 2 | Invoice not found | Unknown invoice number | No action or error | DB: lookup returns null |

---

## Sirene API Contract Tests

These tests validate the HTTP layer independently from use cases. They test `SireneApiClient` directly.

### Location: `test/kotlin/mona/integration/sirene/`

### SireneApiContractTest

| # | Scenario | Fixture | Assertions |
|---|----------|---------|------------|
| 1 | Lookup — parse legal name, SIREN, SIRET | `lookup_success.json` | All fields correctly mapped to `SireneResult` |
| 2 | Lookup — parse address fields | `lookup_success.json` | PostalAddress correctly assembled from API fields |
| 3 | Lookup — NAF code → ActivityType mapping | `lookup_success.json` | NAF code correctly mapped to BIC_VENTE/BIC_SERVICE/BNC |
| 4 | Lookup — not found HTTP 404 | `lookup_not_found.json` | Returns `DomainResult.Err` |
| 5 | Lookup — ceased company | `lookup_ceased.json` | Appropriate error or filtered result |
| 6 | Search — multiple results parsed | `search_multiple_matches.json` | List of `SireneResult` with correct count |
| 7 | Search — zero results | `search_no_match.json` | Empty list or appropriate error |
| 8 | Search — query parameter encoding | Any | Verify URL-encoded name+city in request |
| 9 | Malformed JSON | `search_malformed.json` | Graceful error, no exception leak |

### SireneApiLiveTest (LIVE_API_TESTS=true only)

| # | Scenario | Input | Assertions |
|---|----------|-------|------------|
| 1 | Real lookup | Known SIREN | Response parses correctly, fields non-null |
| 2 | Real search | Known name + city | At least 1 result returned |

---

## Resend Email Contract Tests

### Location: `test/kotlin/mona/integration/email/`

### ResendApiContractTest

| # | Scenario | Fixture | Assertions |
|---|----------|---------|------------|
| 1 | Send success | `send_success.json` | Returns `DomainResult.Ok`, message ID extracted |
| 2 | Invalid recipient | `send_invalid_email.json` | Returns `DomainResult.Err` |
| 3 | Rate limited | `send_rate_limited.json` | Returns `DomainResult.Err` with appropriate error type |

---

## PDF Generation Tests

### Location: `test/kotlin/mona/integration/pdf/`

Tests use real `PdfGenerator` (PDFBox). No mocks.

### PdfGenerationTest

| # | Scenario | Assertions |
|---|----------|------------|
| 1 | Invoice PDF — happy path | Non-empty bytes, valid PDF header (`%PDF`), parseable by PDFBox |
| 2 | Invoice PDF — contains key text | Extract text: invoice number, client name, amounts, dates present |
| 3 | Invoice PDF — draft watermark | Draft invoice PDF contains watermark indicator |
| 4 | Invoice PDF — with IBAN | PDF text contains IBAN value |
| 5 | Invoice PDF — without IBAN | PDF text does not contain IBAN section |
| 6 | Credit note PDF | Non-empty, valid, contains "Avoir" and credit note number |
| 7 | Multi-line-item invoice | All line items present in PDF text |

---

## Implementation Notes for Agent

### Wiring

Each test class extends `IntegrationTestBase` which provides:
- `db`: in-memory SQLite Database instance
- All repository implementations (Exposed-based, using `db`)
- `messagingPort`: `FakeMessagingPort`
- `pdfPort`: real `PdfGenerator`
- `cryptoPort`: `TestCryptoPort` with hardcoded key
- `eventDispatcher`: wired with `TestEventCollector`
- `sirenePort`: `SireneApiClient` with `FakeSireneHttpExecutor`
- `emailPort`: `ResendEmailAdapter` with `FakeResendHttpExecutor`
- Helper methods: `createTestUser()`, `createTestClient()`, `createTestInvoice(status)`

### Fake HTTP Executors

The existing `SireneApiClient` and `ResendEmailAdapter` should accept an HTTP executor interface (or already do). The fake executor returns canned responses from fixture files based on configuration:

```
// Pseudocode — agent will adapt to actual class structure
class FakeSireneHttpExecutor : SireneHttpExecutor {
    var responseFile: String = "lookup_success.json"
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        return HttpResponse(200, loadFixture("sirene/$responseFile"))
    }
}
```

### Assertions Pattern

- Domain results: assert `is DomainResult.Ok` / `is DomainResult.Err`, check inner value
- Events: assert `eventCollector.events` contains expected event types
- Messages: assert `messagingPort.lastMessage()` contains expected text fragments
- PDF: assert bytes start with `%PDF`, optionally extract text with PDFBox and check content
- DB state: re-query via repository and assert fields match

### Running

```bash
./gradlew test                          # All tests (unit + integration, no live API)
LIVE_API_TESTS=true ./gradlew test      # Include live API smoke tests
./gradlew test --tests "mona.integration.*"  # Integration only
```
