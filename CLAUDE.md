# Mona — Conversational Invoice Bot for French Auto-Entrepreneurs

## Tech Stack (Authoritative)

| Layer | Technology | Notes |
|-------|-----------|-------|
| Runtime | Kotlin (JVM 21, coroutines) | Sealed classes for FSM, value classes for Cents/Siren |
| Build | Gradle (Kotlin DSL) | Shadow plugin for fat JAR |
| Bot Framework | Direct Telegram Bot API (HTTP + kotlinx-serialization, webhook) | Behind `MessagingPort` abstraction |
| LLM | Claude Sonnet (tool use / function calling) | NL parsing → structured actions |
| PDF | Apache PDFBox | Pure JVM, no native deps |
| Database | SQLite (sqlite-jdbc + Exposed ORM) | Single-file, type-safe queries |
| Backup | Litestream → S3-compatible storage | Continuous WAL streaming, RPO < 1 min |
| Email | Resend (HTTP API) | Webhook for bounce detection |
| Scheduling | kotlinx-coroutines (ticker / delay) | URSSAF reminders, payment check-ins |
| Hosting | Fly.io | Persistent volume for SQLite, health check auto-restart |

**Do not introduce unapproved libraries.** No ORMs beyond Exposed, no Spring/Ktor frameworks, no additional DI containers.

## Build & Validate

```bash
./gradlew build          # Compile + tests
./gradlew test           # Unit + integration tests
./gradlew shadowJar      # Fat JAR for deployment
./gradlew ktlintCheck    # Lint
./gradlew build && ./gradlew ktlintCheck  # Full validation
```

## Project Structure

```
src/
├── main/kotlin/mona/
│   ├── domain/          # Pure Kotlin — ZERO framework imports (no Telegram, no Exposed, no HTTP)
│   │   ├── model/       # Entities & value classes (Invoice, Client, User, Cents, Siren, InvoiceNumber)
│   │   ├── service/     # InvoiceService, RevenueService, UrssafService, CreditNoteService
│   │   └── port/        # Interfaces (InvoiceRepository, ClientRepository, UserRepository, MessagingPort)
│   ├── infrastructure/  # Framework & IO adapters
│   │   ├── db/          # Exposed tables, repository implementations
│   │   ├── telegram/    # TelegramBotAPI adapter implementing MessagingPort
│   │   ├── llm/         # Claude API client, tool definitions, prompt management
│   │   ├── pdf/         # PDFBox invoice/credit note generation
│   │   ├── email/       # Resend adapter
│   │   ├── sirene/      # INSEE SIRENE API client
│   │   └── crypto/      # AES-256-GCM encryption for IBAN
│   ├── application/     # Thin orchestration (use cases)
│   │   ├── onboarding/  # Progressive onboarding flow
│   │   ├── invoicing/   # Create, send, cancel, correct invoice
│   │   ├── payment/     # Mark paid, overdue transitions
│   │   ├── revenue/     # Revenue queries, CSV export
│   │   └── urssaf/      # Reminder scheduling, threshold alerts
│   └── App.kt           # Entry point, wiring
├── test/kotlin/mona/
│   ├── domain/          # Unit tests (pure, no infra)
│   ├── infrastructure/  # Integration tests
│   └── golden/          # LLM parsing golden test suite
└── resources/
    └── ...              # Config, templates
```

### Architecture Rules

1. **Domain is pure.** `domain/` has zero framework imports. No Exposed, no TelegramBotAPI, no HTTP clients.
2. **Domain owns invariants.** Invoice status FSM, numbering validation, amount calculations — all in domain.
3. **Infrastructure adapts.** Translates external systems ↔ domain types. No business logic.
4. **Application orchestrates.** Thin wiring of domain services + infrastructure ports. Max ~30 lines per use case.
5. **Ports define boundaries.** Domain exposes interfaces (ports); infrastructure provides implementations (adapters).
6. **Test domain in isolation.** No database, no Telegram, no HTTP needed for domain tests.

### Backpressure Checklist (answer before writing code)

1. Right layer? 2. Approved tech only? 3. Domain stays pure? 4. Not duplicating? 5. Testable in isolation? 6. Matches spec? 7. DDD patterns respected? (see §DDD Tactical Patterns above)

## DDD Tactical Patterns (Enforcement Rules)

The domain layer uses DDD tactical patterns. The full reference with code is in `specs/tech-spec.md` §2. These rules are **hard gates** for any code touching `domain/` or `application/`.

### Pattern Inventory

| Pattern | Location | Key Types |
|---------|----------|-----------|
| Domain Result | `domain/model/DomainResult.kt` | `DomainResult<T>`, `DomainError` |
| Value Objects | `domain/model/values.kt` | `Cents`, `Siren`, `Siret`, `Email`, `PostalAddress`, `PaymentDelayDays`, `DeclarationPeriod`, `InvoiceId`, `ClientId`, `UserId` |
| Aggregate Root | `domain/model/Invoice.kt` | `Invoice` with `.send()`, `.markPaid()`, `.markOverdue()`, `.cancel()` |
| Factory | `Invoice.create()` companion | Validates invariants, returns `DomainResult<Invoice>` |
| Domain Events | `domain/model/DomainEvent.kt` | `InvoiceSent`, `InvoicePaid`, `InvoiceOverdue`, `InvoiceCancelled`, `DraftDeleted` |
| Transition Result | `domain/model/DomainEvent.kt` | `TransitionResult(invoice, events)` |
| Numbering | `domain/model/InvoiceNumbering.kt` | `InvoiceNumbering.next()`, `CreditNoteNumbering.next()` |
| Thresholds | `domain/service/UrssafThresholds.kt` | `UrssafThresholds.checkTvaThreshold()`, `.nextDeclarationDeadline()` |
| Revenue | `domain/service/RevenueCalculation.kt` | `RevenueCalculation.compute()` with read-model snapshots |
| Event Dispatcher | `application/EventDispatcher.kt` | Registered handlers, dispatched after persist |

### Hard Rules

1. **No exceptions in domain.** Domain functions return `DomainResult<T>`, never throw. The only exception is `ArithmeticException` from `Cents` overflow (intentional — silent wraparound is worse).
2. **No raw primitives for domain concepts.** Use `InvoiceId` not `String`, `Cents` not `Long`, `Email` not `String`, etc.
3. **State transitions via aggregate methods only.** Call `invoice.send()`, never construct `invoice.copy(status = Sent)` from outside the aggregate.
4. **Factory for creation.** New invoices go through `Invoice.create()`. The data class constructor is for reconstitution from persistence only.
5. **Events returned, not published.** Aggregate methods return `TransitionResult(invoice, events)`. Application layer persists, then dispatches events.
6. **One repository per aggregate root.** `InvoiceRepository` handles Invoice + LineItems + CreditNote. No `LineItemRepository`, no `CreditNoteRepository`.
7. **CreditNote lives inside Invoice aggregate.** Never create or query credit notes independently.
8. **Read-model snapshots for queries.** Revenue/URSSAF calculations use `PaidInvoiceSnapshot`, not full `Invoice` objects.
9. **Application layer pattern:** persist → dispatch events → return result. Max ~30 lines.

### Domain Types (Quick Reference)

```kotlin
// Value Objects (inline — zero runtime cost)
Cents, Siren, Siret, Email, PostalAddress, PaymentDelayDays, DeclarationPeriod
InvoiceId, ClientId, UserId, InvoiceNumber, CreditNoteNumber

// Enums
PaymentMethod { VIREMENT, CHEQUE, ESPECES, CARTE, AUTRE }
ActivityType { BIC_VENTE, BIC_SERVICE, BNC }
DeclarationPeriodicity { MONTHLY, QUARTERLY }

// Status FSM (sealed class — exhaustive matching)
InvoiceStatus { Draft, Sent, Paid(date, method), Overdue, Cancelled }

// Result type
DomainResult<T> { Ok(value), Err(error: DomainError) }
```

## Specs

Product spec: `specs/mvp-spec.md` — features, UX flows, conversation design.
Technical spec: `specs/tech-spec.md` — architecture, data model, integrations.
Brand brief: `docs/mona-brand-brief.md` — tone, personality, voice examples.
Project brief: `docs/mona-brief.md` — market context, competitive landscape, roadmap.

Read the spec most relevant to your task. Do not read all specs upfront.

## Key Constraints

- **Amounts in cents.** All money is `Long` (cents). Never use floating point for money.
- **EUR only in V1.** No multi-currency support.
- **TVA = 0 in V1.** Franchise en base — `amount_ht == amount_ttc`. No VAT fields stored.
- **Invoice numbers have no gaps.** `F-YYYY-MM-NNN` per-user sequential. French legal requirement.
- **URSSAF = cash basis.** Declare on `paid_date`, not `issue_date`.
- **IBAN encrypted at app level.** AES-256-GCM, key in env var.
- **Last 3 conversation messages** persisted for context. No separate last-action pointer.
- **Single Fly.io instance.** No horizontal scaling. SQLite single-writer is fine.

## Auto Memory

**Disabled for this project.** Do not read from or write to the auto memory system (`~/.claude/projects/*/memory/`). All persistent guidance lives in `CLAUDE.md`, `PROMPT_build.md`, and `IMPLEMENTATION_PLAN.md`.

## Updating This File

Update `CLAUDE.md` only when the session reveals a new operational fact not already captured here (e.g. a newly approved library, a confirmed architectural constraint, a permanent workflow rule). Do not update it for task-specific decisions or transient state.
