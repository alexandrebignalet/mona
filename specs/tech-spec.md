# Mona MVP — Technical Specification

This document covers architecture, data model, and implementation decisions for Mona V1. The product specification lives in `mvp-spec.md`.

---

## 1. Tech Stack

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Runtime | **Kotlin** (JVM 21, coroutines) | Type safety for financial domain (sealed classes for FSM, value classes for Cents/Siren), null safety at runtime, structured concurrency |
| Build | **Gradle** (Kotlin DSL) | Standard Kotlin build tool |
| Bot Framework | **TelegramBotAPI** (InsanusMokrassar) | Kotlin-first, coroutine-native, behind `MessagingPort` abstraction |
| LLM | **Claude Sonnet** (tool use / function calling) | NL parsing → structured actions via native tool use — more reliable than free-form JSON |
| PDF Generation | **Apache PDFBox** | Pure JVM, no native deps, battle-tested |
| Database | **SQLite** (sqlite-jdbc + Exposed ORM) | Zero config, single-file, sufficient for V1. Exposed gives type-safe queries in Kotlin |
| Database Backup | **Litestream** → S3-compatible storage | Continuous WAL streaming, point-in-time recovery, RPO < 1 minute |
| Email | **Resend** (HTTP API) | Simple API, free tier (100 emails/day), webhook for bounce detection |
| Scheduling | **kotlinx-coroutines** (ticker / delay) | URSSAF reminders + payment check-ins. No extra dependency — coroutine-based scheduling |
| Hosting | **Fly.io** | Persistent volumes for SQLite, simple deployment, health check auto-restart |

### Why Kotlin over Node.js/TypeScript

This is a financial domain — invoice numbering with legal no-gap guarantees, money in cents, invoice status state machines, GDPR constraints. Kotlin's type system pays for itself:

- **Sealed classes** model the invoice status FSM — illegal transitions become compile errors
- **Value classes** (`Cents`, `Siren`, `InvoiceNumber`) prevent accidental type mixing at zero runtime cost
- **Null safety** is enforced at runtime, not just compile-time (unlike TypeScript's `strictNullChecks`)
- **Coroutines** handle async bot loop, API calls, and scheduled jobs without callback hell
- SQLite's single-writer model + Kotlin's structured concurrency make sequential invoice numbering straightforward

The JVM adds deployment weight (~200MB image) but this is a long-running bot process on Fly.io — cold start is irrelevant.

---

## 2. Domain Types

Kotlin's type system should encode business rules. Key types:

```kotlin
@JvmInline value class Cents(val value: Long)
@JvmInline value class Siren(val value: String) {
    init { require(value.length == 9 && value.all { it.isDigit() }) }
}
@JvmInline value class InvoiceNumber(val value: String)
@JvmInline value class CreditNoteNumber(val value: String)

sealed class InvoiceStatus {
    data object Draft : InvoiceStatus()
    data object Sent : InvoiceStatus()
    data class Paid(val date: LocalDate, val method: PaymentMethod) : InvoiceStatus()
    data object Overdue : InvoiceStatus()
    data object Cancelled : InvoiceStatus()
}

enum class PaymentMethod { VIREMENT, CHEQUE, ESPECES, CARTE, AUTRE }
enum class ActivityType { BIC_VENTE, BIC_SERVICE, BNC }
enum class DeclarationPeriodicity { MONTHLY, QUARTERLY }
```

Status transitions are enforced in a `transition()` function that returns `Result<InvoiceStatus>` — invalid transitions fail explicitly.

---

## 3. Data Model

### User

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID (PK) | Yes | |
| `telegram_id` | Long (unique) | Yes | From Telegram API |
| `email` | String (unique) | No | For sending invoices, future account recovery |
| `name` | String | No | Auto-filled from SIRENE, confirmed at onboarding |
| `siren` | String(9) | No | Collected just-in-time before first PDF |
| `siret` | String(14) | No | Auto-filled from SIRENE if available |
| `address` | String | No | Auto-filled from SIRENE, confirmed at onboarding |
| `iban_encrypted` | ByteArray | No | AES-256-GCM encrypted at application level |
| `activity_type` | Enum | No | `BIC_VENTE`, `BIC_SERVICE`, `BNC` |
| `declaration_periodicity` | Enum | No | `MONTHLY`, `QUARTERLY` |
| `confirm_before_create` | Boolean | Yes | Default `true` |
| `default_payment_delay_days` | Int | Yes | Default `30` |
| `created_at` | Instant | Yes | |

### Client

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID (PK) | Yes | |
| `user_id` | FK → User | Yes | |
| `name` | String | Yes | Extracted from invoice request |
| `email` | String | No | Collected when sending email |
| `address` | String | No | |
| `company_name` | String | No | |
| `siret` | String(14) | No | |
| `created_at` | Instant | Yes | |

### Invoice

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID (PK) | Yes | |
| `user_id` | FK → User | Yes | |
| `client_id` | FK → Client | Yes | |
| `invoice_number` | String | Yes | `F-YYYY-MM-NNN`, per-user sequential |
| `status` | Enum | Yes | `DRAFT`, `SENT`, `PAID`, `OVERDUE`, `CANCELLED` |
| `issue_date` | LocalDate | Yes | |
| `due_date` | LocalDate | Yes | issue_date + user's payment delay |
| `paid_date` | LocalDate | No | |
| `payment_method` | Enum | No | `VIREMENT`, `CHEQUE`, `ESPECES`, `CARTE`, `AUTRE` |
| `activity_type` | Enum | Yes | Defaults to user's, overridable per invoice |
| `pdf_path` | String | No | |
| `created_at` | Instant | Yes | |

**V1 constraints:** currency is always EUR, vat_rate is always 0 (franchise en base). No need to store these as fields — they're constants. Add them when V2 introduces TVA or multi-currency.

### InvoiceLineItem

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID (PK) | Yes | |
| `invoice_id` | FK → Invoice | Yes | |
| `description` | String | Yes | |
| `quantity` | BigDecimal | Yes | Default `1` |
| `unit_price_ht` | Long | Yes | In cents |
| `created_at` | Instant | Yes | |

**Computed:** `line_total_ht = quantity × unit_price_ht`, invoice `amount_ht = sum(line_total_ht)`, `amount_ttc = amount_ht` (V1, franchise en base).

### CreditNote

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID (PK) | Yes | |
| `user_id` | FK → User | Yes | |
| `original_invoice_id` | FK → Invoice | Yes | |
| `replacement_invoice_id` | FK → Invoice | No | |
| `credit_note_number` | String | Yes | `A-YYYY-MM-NNN`, separate sequence |
| `amount_ht` | Long | Yes | In cents (negative) |
| `reason` | String | No | |
| `issue_date` | LocalDate | Yes | |
| `pdf_path` | String | No | |
| `created_at` | Instant | Yes | |

### ConversationMessage

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `id` | UUID (PK) | Yes | |
| `user_id` | FK → User | Yes | |
| `role` | Enum | Yes | `USER`, `ASSISTANT` |
| `content` | String | Yes | |
| `created_at` | Instant | Yes | |

Only the last 3 messages per user are retained — older messages are pruned on each insert.

### Why no Declaration table

URSSAF reminders are computed from invoice data (`paid_date`, `amount_ht`, `activity_type`) + user's `declaration_periodicity`. No need to persist declaration state until Phase 3 (URSSAF API integration). The reminder schedule (D-7, D-1) and acknowledgment state can be tracked with a lightweight in-memory or single-row-per-user approach.

---

## 4. LLM Integration: Claude Tool Use

The spec's structured actions are implemented as **Claude tool definitions** (function calling), not free-form JSON extraction. This is more reliable and gives structured error handling.

Each action from the product spec maps to a tool definition. Claude selects and parameterizes the right tool based on the user's message.

**What is sent to Claude on each request:**
- System prompt (~300 tokens): Mona's personality, French-only, concise responses
- User context (structured JSON, ~100 tokens): name, SIREN status
- Last 2-3 conversation messages (~200 tokens) — used as the sole mechanism for context resolution (no separate last-action pointer)
- Tool definitions (cached by the API, not counted per-request)

**Target per-request:** ~500-800 tokens in, ~200 tokens out → ~€0.15/user/month at 50 messages.

**No fallback on Claude outage.** If the API is unavailable, Mona responds with a user-friendly error message and the user retries manually. No message queue — premature for V1 with one user.

---

## 5. Messaging Abstraction

All messaging goes through a `MessagingPort` interface. Telegram (via TelegramBotAPI) is the first adapter.

```kotlin
interface MessagingPort {
    suspend fun sendMessage(userId: String, text: String)
    suspend fun sendDocument(userId: String, file: ByteArray, filename: String)
    suspend fun sendButtons(userId: String, text: String, buttons: List<Button>)
    suspend fun setPersistentMenu(userId: String, items: List<MenuItem>)
    fun onMessage(handler: suspend (IncomingMessage) -> Unit)
}

data class IncomingMessage(
    val userId: String,
    val text: String,
    val timestamp: Instant,
)

data class Button(val label: String, val callbackData: String)
data class MenuItem(val label: String, val text: String)
```

Buttons are a UX enhancement, never a requirement. Text replies always work. If a channel adapter doesn't support buttons, it falls back to a text prompt.

Conversation state is stored independent of channel chat IDs — the `userId` is mapped per-adapter.

---

## 6. PDF Generation

Apache PDFBox generates text-based invoice PDFs programmatically. No HTML→PDF rendering (no Puppeteer/Chromium dependency).

**Layout:** Professional, clean, text-only (no logo in V1). Line items table, totals, mandatory French legal mentions. All fields from product spec section 2.8.

PDFs are stored on the Fly.io persistent volume alongside the SQLite database. Path stored in `Invoice.pdf_path`.

---

## 7. Data Safety

### Storage

SQLite on a **Fly.io persistent volume**. Data survives deploys and restarts.

### Continuous Backup

**Litestream** streams WAL changes to S3-compatible storage (Tigris on Fly.io or Cloudflare R2 — both free tier). Point-in-time recovery. Target RPO: < 1 minute.

If the volume is lost, the database is restored from Litestream replica. Documented runbook for manual recovery.

### Encryption

- **Volume-level:** Fly.io volume encryption satisfies GDPR Art. 32 for data at rest
- **Application-level:** IBAN is encrypted with AES-256-GCM before storage (dedicated `iban_encrypted` column). Encryption key in environment variable, not in code
- **No SQLCipher.** Volume encryption + app-level IBAN encryption is sufficient. SQLCipher adds JVM complexity without meaningful security gain for this threat model

### Legal Retention

Auto-entrepreneurs must keep invoices for **10 years**. Data integrity is non-negotiable.

---

## 8. Deployment & Availability

Single Fly.io machine. Single point of failure — acceptable for V1.

- **Health check:** `/health` endpoint returns 200 when bot + database + Litestream are operational. Fly.io auto-restarts on failure
- **Recovery:** Fly.io provisions new machine → Litestream restores database (RPO < 1 min)
- **Target uptime:** 99.5% (~3.6 hours/month downtime acceptable for validation)
- **Monitoring:** Basic uptime alerting (Fly.io built-in or UptimeRobot)

### Docker Image

JVM 21 (Eclipse Temurin) base image. Fat JAR via Gradle Shadow plugin. Expected image size ~200MB. No native dependencies.

---

## 9. Scheduled Jobs

Implemented with Kotlin coroutines (no extra dependency):

| Job | Schedule | Logic |
|-----|----------|-------|
| **Payment check-in** | Daily | Query invoices where `due_date = yesterday` and `status = SENT`. Send batched message per user |
| **Overdue transition** | Daily | Query invoices where `due_date < today - 3 days` and `status = SENT`. Transition to `OVERDUE`, notify user once |
| **URSSAF D-7 reminder** | Daily | Compute next deadline per user. If deadline is in 7 days and no reminder sent, send reminder |
| **URSSAF D-1 reminder** | Daily | If deadline is tomorrow and D-7 not acknowledged, send last-call reminder |
| **Onboarding recovery** | Daily | Query users with draft invoices, no SIREN. Send Reminder 1 at 24h (basic nudge), Reminder 2 at 72h (proactive name+city search offer). Skip Reminder 2 if user responded to Reminder 1 |

All jobs are idempotent — safe to re-run after a crash.

---

## 10. External Integrations

### SIRENE API (INSEE)

Free, public API. Used during onboarding to validate SIREN and auto-fill business details.

- **Endpoint:** `https://api.insee.fr/entreprises/sirene/V3.11/`
- **Auth:** API key (free registration on api.insee.fr)
- **Fallback:** If API is unavailable, manual collection (user provides name, address, activity type). Manually entered data is trusted in V1. Future improvement: retry verification in background and flag discrepancies
- **Search:** Supports full-text search by name + city for users who don't know their SIREN

### Resend (Email)

- **Sending:** HTTP API call with PDF attachment
- **Bounce detection:** Webhook endpoint receives delivery status events. On bounce, invoice stays `DRAFT` and user is notified
- **Sender address:** `factures@mona-app.fr` (configurable)

### Claude Sonnet API

- **Integration:** Tool use / function calling for structured action extraction
- **On failure:** User-friendly error message, manual retry. No queue
- **Cost:** ~€0.15/user/month at 50 messages

---

## 11. Testing Strategy

### Golden Test Suite (LLM Parsing)

A suite of French invoice requests ensures parsing quality across prompt iterations:

- **Minimum 50 parsing test cases:** simple invoices, multi-line, slang ("800 balles"), ambiguous clients, corrections, edge cases
- **Minimum 15 context resolution test cases:** anaphora ("envoie-la à Jean"), corrections ("en fait c'est 900€"), ambiguity detection, multi-step mutations
- **Expected structured output** for each test case
- **CI gate:** Parsing accuracy must stay above **95%** before any prompt change is deployed
- **Growing suite:** New failure modes discovered in production are added as test cases

### Unit Tests

- Invoice status FSM transitions (sealed class exhaustive matching)
- Invoice numbering (sequential, no gaps, month rollover)
- Revenue calculations (cents arithmetic, period aggregation, mixed activity breakdown)
- Credit note generation
- URSSAF deadline computation

### Integration Tests

- Full message → action → database → response flow
- PDF generation with all mandatory legal mentions
- Email sending + bounce handling (mocked Resend)
- SIRENE API lookup + fallback

---

## 12. Usage Monitoring (V1)

**Rate limit: 200 messages per user per day** (counter resets at midnight UTC). Per-user metrics tracked for observability and V2 planning:

| Metric | Purpose |
|--------|---------|
| Messages per day per user | V2 rate limit thresholds |
| API token consumption per user | Cost model validation |
| Action type distribution | Feature usage / V2 paywall boundaries |
| Error rate by dependency | Reliability tracking |

Exposed via logs + the `/health` endpoint (basic stats). Full admin dashboard is V2.
