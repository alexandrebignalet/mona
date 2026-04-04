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
