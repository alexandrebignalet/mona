# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1.1–14.4 done. See git log for details.

---

## Phase 15: GDPR Compliance

Implemented: ports (UserRepository.delete, ClientRepository.deleteByUser, ConversationRepository.deleteByUser, InvoiceRepository.anonymizeByUser), all Exposed impls, DeleteAccount use case, delete_account tool, ActionParser mapping, MessageRouter confirm/cancel flow with pendingDeletionSet. Tests: confirm path, cancel path, confirmation prompt. Golden tests added.

Phase 15.3 done: ExportGdprData use case, export_data tool, ExportData ParsedAction, MessageRouter handleExportData, 4 unit tests, 2 MessageRouter tests, 3 golden test cases. Build + lint pass.

---

## Phase 16: Revenue Polish

Phase 16.1 done: added `periodType`/`period` to `GetRevenueCommand`/`GetRevenueResult`, added `formatPeriodLabel` to `MessageRouter` (Ce mois / Janvier 2020 / Ce trimestre / T3 2020 / Cette année / 2020). 6 MessageRouter tests added. Build + lint pass.

Phase 16.2 done: `previousBreakdown: RevenueBreakdown?` added to `GetRevenueResult`; computed for month/quarter, null for year. MessageRouter appends "Tu es à X% de ton CA du mois/trimestre dernier [emoji]" when previous total > 0. 4 GetRevenueTest cases + 4 MessageRouterTest cases added. Build + lint pass.

---

## Phase 17: CI/CD Pipelines

### 17.1 CI Workflow — DONE

`.github/workflows/ci.yml` created. Triggers on push/PR to main. Job `build-and-test` with Temurin 21, Gradle cache, build+lint+upload. Build+lint pass.

---

### 17.2 Golden Tests Workflow — DONE

`.github/workflows/golden-tests.yml` created. `workflow_dispatch` only with optional `categories` input. Passes `ANTHROPIC_API_KEY` secret. Runs `GoldenParsingTest` + `GoldenContextTest` with optional `-Dgolden.categories` filter. Uploads test reports on `always()`.

---

### 17.3 Deploy Workflow — DONE

`.github/workflows/deploy.yml` created. Triggers on CI workflow success on `main` (automatic) and `workflow_dispatch` with `confirm: 'deploy'` guard (manual). Uses `superfly/flyctl-actions/setup-flyctl@master`, deploys with `--remote-only`, health check via `flyctl status --app mona-late-tree-7299`. `FLY_API_TOKEN` passed as env var from secrets.

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
