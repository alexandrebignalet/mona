# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

The strategy is: scaffolding -> domain (pure, testable) -> infrastructure adapters -> application use cases -> LLM integration -> end-to-end wiring -> scheduled jobs -> deployment -> polish.

---

## Completed Phases

Phases 1.1–14.1 done. See git log for details.

---

## Phase 14: Polish and Hardening

### 14.2 VAT Threshold Alerts — DONE
- Implemented `CheckVatThreshold` use case wired into `InvoicePaid` event handler
- `VatAlertRecord` domain model, `VatAlertRepository` port, `ExposedVatAlertRepository` adapter, `VatAlertsTable` DB table
- Alerts at 80% and 95% with no-duplicate guard per year+activity key
- 8 unit tests covering all acceptance criteria; French locale number formatting (`\u202F` normalized in assertions)

### 14.3 Duplicate Detection Integration — DONE
- `pendingDuplicateMap` added to `MessageRouter`; `PendingDuplicate` and `DOUBLON_TOKENS` added
- When `CreateInvoiceResult.DuplicateWarning` is returned, stores pending and shows warning with existing number
- "doublon" / CANCEL_TOKENS → deletes draft, responds "OK, je ne crée rien ✓"
- CONFIRM_TOKENS → keeps invoice, responds with creation confirmation
- 4 new MessageRouter tests; `findByClientAndAmountSince` in test repo now does real matching
- All builds and ktlintCheck pass

### 14.4 Error Messages and Edge Cases
- **Layer:** all
- **Spec:** mvp-spec S10
- **What:** Review and implement all user-facing error messages from mvp-spec S10. LLM unavailable, SIRENE down (manual fallback), email failure, PDF failure. Non-EUR currency rejection. Ensure all errors are in French, conversational, and actionable.
- **Acceptance criteria:**
  - [ ] LLM unavailable: "Je suis momentanement indisponible..."
  - [ ] SIRENE down: falls back to manual data collection
  - [ ] Email failure: "L'envoi a echoue, je reessaie automatiquement."
  - [ ] PDF failure: "J'ai un souci pour generer le PDF..."
  - [ ] Non-EUR currency: "Pour l'instant je ne gere que l'euro..."
  - [ ] All error messages match mvp-spec S10 table
  - [ ] `./gradlew build && ./gradlew ktlintCheck` passes

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
