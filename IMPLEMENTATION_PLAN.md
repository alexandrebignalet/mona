# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1.1–17.3 done. See git log for details.

---

## Completed Phases (continued)

Phase 18.1 done — SIRENE OAuth2 token management:
- `SireneTokenProvider` with injectable `TokenFetcher` + `clock`, Mutex-based cache, 60s safety margin, `invalidate()` for 401 retry
- `SireneApiClient` refactored: `apiKey` removed, `RealSireneHttpExecutor` handles 401 retry via token invalidation
- `fromEnv()` reads `SIRENE_CLIENT_ID` + `SIRENE_CLIENT_SECRET`; `SIRENE_API_KEY` fully removed
- New `SireneTokenProviderTest` (caching, expiry margin, Mutex concurrency, failure propagation)
- Updated `SireneApiClientTest` (executor lambda signature, added token failure test)

**Post-deploy (manual, after next deploy):**
```bash
fly secrets set SIRENE_CLIENT_ID=xxx SIRENE_CLIENT_SECRET=xxx -a mona-late-tree-7299
fly secrets unset SIRENE_API_KEY -a mona-late-tree-7299
```

---

## No Remaining Phases

All planned v1 phases complete. See git log for full history.

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
