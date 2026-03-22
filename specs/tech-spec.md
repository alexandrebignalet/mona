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

## 2. DDD Tactical Patterns

The domain layer uses DDD tactical patterns to encode business invariants in types and make illegal states unrepresentable. This section is the authoritative reference for domain implementation.

### 2.1 Domain Result Type

Domain operations never throw exceptions. All fallible operations return `DomainResult<T>`:

```kotlin
// domain/model/DomainResult.kt
sealed class DomainResult<out T> {
    data class Ok<T>(val value: T) : DomainResult<T>()
    data class Err(val error: DomainError) : DomainResult<Nothing>()
}

inline fun <T, R> DomainResult<T>.map(f: (T) -> R): DomainResult<R> = when (this) {
    is DomainResult.Ok -> DomainResult.Ok(f(value))
    is DomainResult.Err -> this
}

inline fun <T, R> DomainResult<T>.flatMap(f: (T) -> DomainResult<R>): DomainResult<R> = when (this) {
    is DomainResult.Ok -> f(value)
    is DomainResult.Err -> this
}
```

**`DomainError` sealed hierarchy** — each error is a typed, exhaustively matchable case:

```kotlin
// domain/model/DomainError.kt
sealed class DomainError(val message: String) {
    class InvalidTransition(val from: InvoiceStatus, val to: String)
        : DomainError("Cannot transition from ${from::class.simpleName} to $to")
    class InvoiceNumberGap(val expected: String, val got: String)
        : DomainError("Expected $expected, got $got")
    class EmptyLineItems
        : DomainError("Invoice must have at least one line item")
    class NegativeAmount(val cents: Long)
        : DomainError("Amount cannot be negative: $cents")
    class ClientNotFound(val id: String)
        : DomainError("Client $id not found")
    class InvoiceNotCancellable(val number: InvoiceNumber)
        : DomainError("Invoice ${number.value} cannot be cancelled without credit note")
    class CreditNoteAmountMismatch(val invoiceAmount: Cents, val creditAmount: Cents)
        : DomainError("Credit note amount must match invoice")
    class SirenRequired
        : DomainError("SIREN is required to finalize invoice")
    class ProfileIncomplete(val missing: List<String>)
        : DomainError("Missing: ${missing.joinToString()}")
}
```

**Rule:** Domain functions return `DomainResult`, never throw. Application layer pattern-matches on `Ok`/`Err`. Infrastructure may throw (IO); application catches and wraps.

### 2.2 Value Objects

Kotlin inline value classes encode domain concepts with validation at construction. **Never use raw `String`, `Long`, or `Int` for domain concepts.**

```kotlin
// domain/model/values.kt

@JvmInline value class Cents(val value: Long) {
    operator fun plus(other: Cents) = Cents(Math.addExact(value, other.value))
    operator fun minus(other: Cents) = Cents(Math.subtractExact(value, other.value))
    operator fun times(multiplier: Long) = Cents(Math.multiplyExact(value, multiplier))
    fun isNegative() = value < 0
    fun isZero() = value == 0L
    companion object {
        val ZERO = Cents(0)
    }
}

@JvmInline value class Siren(val value: String) {
    init { require(value.length == 9 && value.all { it.isDigit() }) }
}

@JvmInline value class Siret(val value: String) {
    init { require(value.length == 14 && value.all { it.isDigit() }) }
    val siren: Siren get() = Siren(value.substring(0, 9))
}

@JvmInline value class InvoiceNumber(val value: String)
@JvmInline value class CreditNoteNumber(val value: String)

@JvmInline value class Email(val value: String) {
    init { require(value.contains("@") && value.length >= 5) }
}

@JvmInline value class PaymentDelayDays(val value: Int) {
    init { require(value in 1..60) { "Payment delay must be 1-60 days" } }
}

@JvmInline value class InvoiceId(val value: String)
@JvmInline value class ClientId(val value: String)
@JvmInline value class UserId(val value: String)

data class PostalAddress(
    val street: String,
    val postalCode: String,
    val city: String,
    val country: String = "France",
) {
    fun formatted(): String = "$street\n$postalCode $city\n$country"
}

data class DeclarationPeriod(
    val start: LocalDate,
    val endInclusive: LocalDate,
) {
    init { require(!endInclusive.isBefore(start)) }
    companion object {
        fun monthly(year: Int, month: Int): DeclarationPeriod {
            val s = LocalDate.of(year, month, 1)
            return DeclarationPeriod(s, s.withDayOfMonth(s.lengthOfMonth()))
        }
        fun quarterly(year: Int, quarter: Int): DeclarationPeriod {
            require(quarter in 1..4)
            val startMonth = (quarter - 1) * 3 + 1
            val s = LocalDate.of(year, startMonth, 1)
            val e = s.plusMonths(2).let { it.withDayOfMonth(it.lengthOfMonth()) }
            return DeclarationPeriod(s, e)
        }
    }
}
```

**Arithmetic safety:** `Cents` uses `Math.addExact` / `Math.subtractExact` / `Math.multiplyExact` — overflow throws `ArithmeticException` instead of silent wraparound. Non-negotiable for a financial domain.

### 2.3 Enums & Status FSM

```kotlin
enum class PaymentMethod { VIREMENT, CHEQUE, ESPECES, CARTE, AUTRE }
enum class ActivityType { BIC_VENTE, BIC_SERVICE, BNC }
enum class DeclarationPeriodicity { MONTHLY, QUARTERLY }

sealed class InvoiceStatus {
    data object Draft : InvoiceStatus()
    data object Sent : InvoiceStatus()
    data class Paid(val date: LocalDate, val method: PaymentMethod) : InvoiceStatus()
    data object Overdue : InvoiceStatus()
    data object Cancelled : InvoiceStatus()
}
```

Status transitions are enforced **inside the Invoice aggregate root** (see §2.5), not in an external function.

### 2.4 Aggregate Boundaries

| Aggregate Root | Contains | Referenced By ID |
|----------------|----------|------------------|
| **User** | Settings, encrypted IBAN | — |
| **Client** | Name, email, address, SIRET | `UserId` (owner) |
| **Invoice** | LineItems, CreditNote, status FSM | `UserId`, `ClientId` |

**Key decisions:**
- **CreditNote lives inside the Invoice aggregate.** A credit note has no independent lifecycle — it always references "the invoice being cancelled." This prevents orphan credit notes and ensures the cancellation invariant (Sent/Paid → Cancelled requires a credit note) is enforced within the aggregate boundary.
- **LineItem is an entity within Invoice**, not a separate aggregate. It has no meaning outside its invoice.
- **Client is a separate aggregate root.** Clients have their own lifecycle (created once, referenced by many invoices). Invoices reference clients by `ClientId`, not by embedding.
- **ConversationMessage is not a DDD aggregate** — it's a simple persistence concern for LLM context.

### 2.5 Aggregate Root Behavior & Domain Events

The Invoice aggregate encapsulates all state transitions as methods. Each transition returns `DomainResult<TransitionResult>` — the new aggregate state plus any domain events emitted.

```kotlin
// domain/model/DomainEvent.kt
sealed class DomainEvent {
    abstract val occurredAt: Instant

    data class InvoiceSent(
        val invoiceId: InvoiceId, val invoiceNumber: InvoiceNumber,
        val clientId: ClientId, val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()

    data class InvoicePaid(
        val invoiceId: InvoiceId, val invoiceNumber: InvoiceNumber,
        val amount: Cents, val paidDate: LocalDate,
        val method: PaymentMethod, val activityType: ActivityType,
        val userId: UserId, override val occurredAt: Instant,
    ) : DomainEvent()

    data class InvoiceOverdue(
        val invoiceId: InvoiceId, val invoiceNumber: InvoiceNumber,
        val dueDate: LocalDate, val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()

    data class InvoiceCancelled(
        val invoiceId: InvoiceId, val invoiceNumber: InvoiceNumber,
        val creditNote: CreditNote?, val userId: UserId,
        override val occurredAt: Instant,
    ) : DomainEvent()

    data class DraftDeleted(
        val invoiceId: InvoiceId, val invoiceNumber: InvoiceNumber,
        val userId: UserId, override val occurredAt: Instant,
    ) : DomainEvent()
}

data class TransitionResult(
    val invoice: Invoice,
    val events: List<DomainEvent>,
)
```

**Invoice aggregate behavior:**

```kotlin
// domain/model/Invoice.kt
data class Invoice(
    val id: InvoiceId,
    val userId: UserId,
    val clientId: ClientId,
    val number: InvoiceNumber,
    val status: InvoiceStatus,
    val issueDate: LocalDate,
    val dueDate: LocalDate,
    val activityType: ActivityType,
    val lineItems: List<LineItem>,
    val pdfPath: String?,
    val creditNote: CreditNote?,
    val createdAt: Instant,
) {
    val amountHt: Cents
        get() = Cents(lineItems.sumOf { it.totalHt.value })

    /** DRAFT → SENT */
    fun send(now: Instant): DomainResult<TransitionResult> {
        if (status !is InvoiceStatus.Draft) return invalidTransition("Sent")
        val updated = copy(status = InvoiceStatus.Sent)
        return DomainResult.Ok(TransitionResult(updated, listOf(
            DomainEvent.InvoiceSent(id, number, clientId, userId, now)
        )))
    }

    /** DRAFT|SENT|OVERDUE → PAID */
    fun markPaid(date: LocalDate, method: PaymentMethod, now: Instant): DomainResult<TransitionResult> {
        return when (status) {
            is InvoiceStatus.Draft, is InvoiceStatus.Sent, is InvoiceStatus.Overdue -> {
                val updated = copy(status = InvoiceStatus.Paid(date, method))
                DomainResult.Ok(TransitionResult(updated, listOf(
                    DomainEvent.InvoicePaid(id, number, amountHt, date, method, activityType, userId, now)
                )))
            }
            else -> invalidTransition("Paid")
        }
    }

    /** SENT → OVERDUE */
    fun markOverdue(now: Instant): DomainResult<TransitionResult> {
        if (status !is InvoiceStatus.Sent) return invalidTransition("Overdue")
        val updated = copy(status = InvoiceStatus.Overdue)
        return DomainResult.Ok(TransitionResult(updated, listOf(
            DomainEvent.InvoiceOverdue(id, number, dueDate, userId, now)
        )))
    }

    /** SENT|OVERDUE|PAID → CANCELLED (requires credit note for non-draft) */
    fun cancel(creditNote: CreditNote?, now: Instant): DomainResult<TransitionResult> = when {
        status is InvoiceStatus.Draft -> {
            val updated = copy(status = InvoiceStatus.Cancelled)
            DomainResult.Ok(TransitionResult(updated, listOf(
                DomainEvent.DraftDeleted(id, number, userId, now)
            )))
        }
        (status is InvoiceStatus.Sent || status is InvoiceStatus.Overdue
            || status is InvoiceStatus.Paid) && creditNote != null -> {
            val updated = copy(status = InvoiceStatus.Cancelled, creditNote = creditNote)
            DomainResult.Ok(TransitionResult(updated, listOf(
                DomainEvent.InvoiceCancelled(id, number, creditNote, userId, now)
            )))
        }
        else -> DomainResult.Err(DomainError.InvoiceNotCancellable(number))
    }

    private fun invalidTransition(to: String): DomainResult<TransitionResult> =
        DomainResult.Err(DomainError.InvalidTransition(status, to))

    companion object {
        /** Factory — validates invariants, returns DRAFT invoice */
        fun create(
            id: InvoiceId, userId: UserId, clientId: ClientId,
            number: InvoiceNumber, issueDate: LocalDate,
            paymentDelay: PaymentDelayDays, activityType: ActivityType,
            lineItems: List<LineItem>, now: Instant,
        ): DomainResult<Invoice> {
            if (lineItems.isEmpty()) return DomainResult.Err(DomainError.EmptyLineItems)
            val total = lineItems.fold(Cents.ZERO) { acc, it -> acc + it.totalHt }
            if (total.isZero() || total.isNegative())
                return DomainResult.Err(DomainError.NegativeAmount(total.value))
            if (!InvoiceNumbering.validate(number))
                return DomainResult.Err(DomainError.InvoiceNumberGap("valid F-YYYY-MM-NNN", number.value))
            return DomainResult.Ok(Invoice(
                id, userId, clientId, number, InvoiceStatus.Draft,
                issueDate, issueDate.plusDays(paymentDelay.value.toLong()),
                activityType, lineItems, null, null, now,
            ))
        }
    }
}

data class LineItem(
    val description: String,
    val quantity: BigDecimal,
    val unitPriceHt: Cents,
) {
    val totalHt: Cents
        get() = Cents((quantity * BigDecimal(unitPriceHt.value))
            .setScale(0, RoundingMode.HALF_UP).toLong())
}

data class CreditNote(
    val number: CreditNoteNumber,
    val amountHt: Cents,
    val reason: String,
    val issueDate: LocalDate,
    val replacementInvoiceId: InvoiceId?,
    val pdfPath: String?,
)
```

**Rule:** State transitions happen only through aggregate methods. No external code may construct an `Invoice` with `status = Paid` — new invoices go through `Invoice.create()` (returns Draft), existing invoices are reconstituted from the database via the data class constructor (already validated data).

### 2.6 Invoice Numbering (Pure Domain Logic)

Encapsulated as a pure object — no IO, independently testable:

```kotlin
// domain/model/InvoiceNumbering.kt
object InvoiceNumbering {
    fun next(yearMonth: YearMonth, lastInMonth: InvoiceNumber?): DomainResult<InvoiceNumber> {
        val prefix = "F-${yearMonth.year}-${"%02d".format(yearMonth.monthValue)}-"
        if (lastInMonth == null) return DomainResult.Ok(InvoiceNumber("${prefix}001"))
        val seq = parseSequence(lastInMonth)
            ?: return DomainResult.Err(DomainError.InvoiceNumberGap("valid F-YYYY-MM-NNN", lastInMonth.value))
        if (seq + 1 > 999) return DomainResult.Err(DomainError.InvoiceNumberGap("sequence overflow", lastInMonth.value))
        return DomainResult.Ok(InvoiceNumber("$prefix${"%03d".format(seq + 1)}"))
    }

    fun validate(number: InvoiceNumber): Boolean =
        number.value.matches(Regex("^F-\\d{4}-\\d{2}-\\d{3}$"))

    fun isContiguous(last: InvoiceNumber, next: InvoiceNumber): Boolean {
        val a = parseSequence(last) ?: return false
        val b = parseSequence(next) ?: return false
        return b == a + 1
    }

    private fun parseSequence(n: InvoiceNumber): Int? =
        n.value.split("-").takeIf { it.size == 4 }?.get(3)?.toIntOrNull()
}

object CreditNoteNumbering {
    fun next(yearMonth: YearMonth, lastInMonth: CreditNoteNumber?): DomainResult<CreditNoteNumber> {
        val prefix = "A-${yearMonth.year}-${"%02d".format(yearMonth.monthValue)}-"
        if (lastInMonth == null) return DomainResult.Ok(CreditNoteNumber("${prefix}001"))
        val seq = lastInMonth.value.split("-").getOrNull(3)?.toIntOrNull()
            ?: return DomainResult.Err(DomainError.InvoiceNumberGap("valid A-YYYY-MM-NNN", lastInMonth.value))
        return DomainResult.Ok(CreditNoteNumber("$prefix${"%03d".format(seq + 1)}"))
    }
}
```

### 2.7 URSSAF Thresholds (Specification Pattern)

Pure domain rules, isolated for annual threshold updates:

```kotlin
// domain/service/UrssafThresholds.kt
data class ThresholdAlert(
    val currentRevenue: Cents,
    val threshold: Cents,
    val activityType: ActivityType,
    val percentReached: Int,
)

object UrssafThresholds {
    private val TVA_THRESHOLDS = mapOf(
        ActivityType.BIC_VENTE to Cents(91_900_00),
        ActivityType.BIC_SERVICE to Cents(36_800_00),
        ActivityType.BNC to Cents(36_800_00),
    )

    fun checkTvaThreshold(revenue: Cents, activityType: ActivityType): ThresholdAlert? {
        val threshold = TVA_THRESHOLDS[activityType] ?: return null
        val pct = if (threshold.value > 0) ((revenue.value * 100) / threshold.value).toInt() else 0
        return if (pct >= 80) ThresholdAlert(revenue, threshold, activityType, pct) else null
    }

    fun nextDeclarationDeadline(periodicity: DeclarationPeriodicity, ref: LocalDate): LocalDate =
        when (periodicity) {
            DeclarationPeriodicity.MONTHLY -> {
                val m = ref.plusMonths(1); m.withDayOfMonth(m.lengthOfMonth())
            }
            DeclarationPeriodicity.QUARTERLY -> {
                val qEnd = when (ref.monthValue) {
                    in 1..3 -> LocalDate.of(ref.year, 3, 31)
                    in 4..6 -> LocalDate.of(ref.year, 6, 30)
                    in 7..9 -> LocalDate.of(ref.year, 9, 30)
                    else -> LocalDate.of(ref.year, 12, 31)
                }
                val d = qEnd.plusMonths(1); d.withDayOfMonth(d.lengthOfMonth())
            }
        }
}
```

### 2.8 Revenue Calculation (Read-Model Snapshots)

Revenue queries use lightweight projections — no full aggregate hydration:

```kotlin
// domain/model/Snapshots.kt
data class PaidInvoiceSnapshot(
    val invoiceId: InvoiceId,
    val amountHt: Cents,
    val paidDate: LocalDate,
    val activityType: ActivityType,
)

data class CreditNoteSnapshot(
    val creditNoteNumber: CreditNoteNumber,
    val amountHt: Cents,  // negative
    val issueDate: LocalDate,
    val activityType: ActivityType,
)

// domain/service/RevenueCalculation.kt
data class RevenueBreakdown(
    val total: Cents,
    val byActivity: Map<ActivityType, Cents>,
)

object RevenueCalculation {
    fun compute(
        paidInvoices: List<PaidInvoiceSnapshot>,
        creditNotes: List<CreditNoteSnapshot>,
    ): RevenueBreakdown {
        val allActivities = (paidInvoices.map { it.activityType } +
            creditNotes.map { it.activityType }).toSet()
        val byActivity = allActivities.associateWith { activity ->
            val revenue = Cents(paidInvoices.filter { it.activityType == activity }.sumOf { it.amountHt.value })
            val offset = Cents(creditNotes.filter { it.activityType == activity }.sumOf { it.amountHt.value })
            revenue + offset
        }
        return RevenueBreakdown(Cents(byActivity.values.sumOf { it.value }), byActivity)
    }
}
```

### 2.9 Event Dispatcher (Application Layer)

Simple in-process dispatch — no library, no bus. Application layer dispatches after persisting.

```kotlin
// application/EventDispatcher.kt
class EventDispatcher {
    private val handlers = mutableListOf<suspend (DomainEvent) -> Unit>()
    fun register(handler: suspend (DomainEvent) -> Unit) { handlers.add(handler) }
    suspend fun dispatch(events: List<DomainEvent>) {
        for (event in events) { for (handler in handlers) { handler(event) } }
    }
}
```

**Use case pattern:**

```kotlin
// application/invoicing/MarkInvoicePaid.kt
class MarkInvoicePaid(
    private val invoiceRepo: InvoiceRepository,
    private val dispatcher: EventDispatcher,
) {
    suspend fun execute(
        invoiceId: InvoiceId, paidDate: LocalDate,
        method: PaymentMethod, now: Instant,
    ): DomainResult<Invoice> {
        val invoice = invoiceRepo.findById(invoiceId)
            ?: return DomainResult.Err(DomainError.ClientNotFound(invoiceId.value))
        return invoice.markPaid(paidDate, method, now).map { result ->
            invoiceRepo.save(result.invoice)
            dispatcher.dispatch(result.events)
            result.invoice
        }
    }
}
```

Event handlers are registered at startup in `App.kt`. Side effects (messaging, scheduling) are wired as handlers — the domain never knows about them.

### 2.10 Repository Ports

One repository per aggregate root. Query projections for read-model snapshots.

```kotlin
// domain/port/InvoiceRepository.kt
interface InvoiceRepository {
    suspend fun findById(id: InvoiceId): Invoice?
    suspend fun save(invoice: Invoice)
    suspend fun delete(id: InvoiceId)
    suspend fun findLastNumberInMonth(userId: UserId, yearMonth: YearMonth): InvoiceNumber?
    suspend fun findByUser(userId: UserId): List<Invoice>
    suspend fun findPaidInPeriod(userId: UserId, period: DeclarationPeriod): List<PaidInvoiceSnapshot>
    suspend fun findCreditNotesInPeriod(userId: UserId, period: DeclarationPeriod): List<CreditNoteSnapshot>
    suspend fun findSentOverdue(cutoffDate: LocalDate): List<Invoice>
}

// domain/port/ClientRepository.kt
interface ClientRepository {
    suspend fun findById(id: ClientId): Client?
    suspend fun save(client: Client)
    suspend fun findByUserAndName(userId: UserId, name: String): List<Client>
}

// domain/port/UserRepository.kt
interface UserRepository {
    suspend fun findById(id: UserId): User?
    suspend fun findByTelegramId(telegramId: Long): User?
    suspend fun save(user: User)
}
```

**Rule:** Repositories operate on aggregate roots only. No `LineItemRepository`, no `CreditNoteRepository` (CreditNote is inside Invoice). Infrastructure adapters handle relational storage/joins internally.

---

## 3. Data Model

The data model below shows **storage tables**. The domain model (§2) is authoritative for aggregate boundaries and behavior. Infrastructure adapters translate between these tables and domain aggregates.

### User (Aggregate Root)

| Field | Type | Required | Domain VO | Notes |
|-------|------|----------|-----------|-------|
| `id` | UUID (PK) | Yes | `UserId` | |
| `telegram_id` | Long (unique) | Yes | — | From Telegram API |
| `email` | String (unique) | No | `Email` | For sending invoices |
| `name` | String | No | — | Auto-filled from SIRENE |
| `siren` | String(9) | No | `Siren` | Validated at construction |
| `siret` | String(14) | No | `Siret` | Auto-filled from SIRENE |
| `address_street` | String | No | `PostalAddress` | Structured for PDF |
| `address_postal_code` | String | No | `PostalAddress` | |
| `address_city` | String | No | `PostalAddress` | |
| `iban_encrypted` | ByteArray | No | — | AES-256-GCM at app level |
| `activity_type` | Enum | No | `ActivityType` | |
| `declaration_periodicity` | Enum | No | `DeclarationPeriodicity` | |
| `confirm_before_create` | Boolean | Yes | — | Default `true` |
| `default_payment_delay_days` | Int | Yes | `PaymentDelayDays` | Default `30` |
| `created_at` | Instant | Yes | — | |

### Client (Aggregate Root)

| Field | Type | Required | Domain VO | Notes |
|-------|------|----------|-----------|-------|
| `id` | UUID (PK) | Yes | `ClientId` | |
| `user_id` | FK → User | Yes | `UserId` | Owner |
| `name` | String | Yes | — | Extracted from invoice request |
| `email` | String | No | `Email` | Collected when sending |
| `address_street` | String | No | `PostalAddress` | |
| `address_postal_code` | String | No | `PostalAddress` | |
| `address_city` | String | No | `PostalAddress` | |
| `company_name` | String | No | — | |
| `siret` | String(14) | No | `Siret` | |
| `created_at` | Instant | Yes | — | |

### Invoice (Aggregate Root — includes LineItems and CreditNote)

| Field | Type | Required | Domain VO | Notes |
|-------|------|----------|-----------|-------|
| `id` | UUID (PK) | Yes | `InvoiceId` | |
| `user_id` | FK → User | Yes | `UserId` | |
| `client_id` | FK → Client | Yes | `ClientId` | |
| `invoice_number` | String | Yes | `InvoiceNumber` | `F-YYYY-MM-NNN` |
| `status` | Enum | Yes | `InvoiceStatus` | Sealed class |
| `issue_date` | LocalDate | Yes | — | |
| `due_date` | LocalDate | Yes | — | Computed: issue_date + payment delay |
| `paid_date` | LocalDate | No | — | Set on Paid transition |
| `payment_method` | Enum | No | `PaymentMethod` | Set on Paid transition |
| `activity_type` | Enum | Yes | `ActivityType` | Overridable per invoice |
| `pdf_path` | String | No | — | |
| `created_at` | Instant | Yes | — | |

**V1 constraints:** currency = EUR, vat_rate = 0 (franchise en base). Constants, not stored.

### InvoiceLineItem (Entity within Invoice aggregate)

| Field | Type | Required | Domain VO | Notes |
|-------|------|----------|-----------|-------|
| `id` | UUID (PK) | Yes | — | |
| `invoice_id` | FK → Invoice | Yes | `InvoiceId` | |
| `description` | String | Yes | — | |
| `quantity` | BigDecimal | Yes | — | Default `1` |
| `unit_price_ht` | Long | Yes | `Cents` | In cents |

**Computed:** `totalHt = quantity × unitPriceHt` (in domain), `amountHt = sum(lineItems.totalHt)`.

### CreditNote (Entity within Invoice aggregate)

Stored in a separate table but loaded/saved as part of the Invoice aggregate. **No `CreditNoteRepository`** — the `InvoiceRepository` handles persistence.

| Field | Type | Required | Domain VO | Notes |
|-------|------|----------|-----------|-------|
| `id` | UUID (PK) | Yes | — | |
| `invoice_id` | FK → Invoice | Yes | `InvoiceId` | The cancelled invoice |
| `replacement_invoice_id` | FK → Invoice | No | `InvoiceId` | If correction issued |
| `credit_note_number` | String | Yes | `CreditNoteNumber` | `A-YYYY-MM-NNN` |
| `amount_ht` | Long | Yes | `Cents` | Negative |
| `reason` | String | No | — | |
| `issue_date` | LocalDate | Yes | — | |
| `pdf_path` | String | No | — | |

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

### Unit Tests (Pure Domain — No Infra)

- **Aggregate behavior:** `Invoice.send()`, `.markPaid()`, `.markOverdue()`, `.cancel()` — valid + invalid transitions, correct events emitted
- **Factory validation:** `Invoice.create()` rejects empty line items, negative amounts, invalid numbers
- **Invoice numbering:** `InvoiceNumbering.next()` — sequential, no gaps, month rollover, `isContiguous()`
- **Value object invariants:** `Cents` arithmetic with overflow, `Siren`/`Siret` validation, `Email` validation, `PaymentDelayDays` bounds
- **Revenue calculation:** `RevenueCalculation.compute()` — cents arithmetic, period aggregation, credit note offsets, mixed activity breakdown
- **URSSAF thresholds:** `UrssafThresholds.checkTvaThreshold()` — 80%/95% alerts per activity type
- **URSSAF deadlines:** `UrssafThresholds.nextDeclarationDeadline()` — monthly/quarterly computation
- **DomainResult chaining:** `map`/`flatMap` propagation of errors

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
