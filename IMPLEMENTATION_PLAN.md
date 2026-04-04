# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1.1–17.3 done. See git log for details.

---

## Phase 18: SIRENE OAuth2 Token Management

**Spec:** `specs/sirene-oauth-spec.md`
**Layer:** `infrastructure/sirene/`
**Why:** INSEE SIRENE API uses OAuth2 client credentials with ~7-day token expiry. Current code uses a static Bearer token (`SIRENE_API_KEY`) that requires manual rotation. All SIRENE lookups fail with HTTP 401 until manually updated.

### 18.1 SireneTokenProvider + SireneApiClient refactor

**New: `infrastructure/sirene/SireneTokenProvider.kt`**
- `class SireneTokenProvider(clientId: String, clientSecret: String)`
- `suspend fun getToken(): String` — returns cached token if within expiry minus 60-second safety margin; otherwise fetches fresh token via:
  `POST https://api.insee.fr/token` with `grant_type=client_credentials&client_id=...&client_secret=...`
- Thread-safe via `kotlinx.coroutines.sync.Mutex` (only one coroutine refreshes at a time)
- On HTTP 401 from a SIRENE API call: invalidate cached token, retry once with fresh token; if still 401, return `DomainResult.Err(DomainError.SireneLookupFailed(...))`
- Token fetch failure (non-200 or network error) returns `DomainResult.Err(DomainError.SireneLookupFailed("Token refresh failed: ..."))`
- Uses `java.net.http.HttpClient` (no new dependencies)

**Changed: `infrastructure/sirene/SireneApiClient.kt`**
- Remove `apiKey` constructor parameter; replace with `SireneTokenProvider` (passed at construction, not per-call)
- `RealSireneHttpExecutor.get(url)` no longer takes `apiKey` — calls `tokenProvider.getToken()` internally
- `SireneApiClient.fromEnv()` reads `SIRENE_CLIENT_ID` + `SIRENE_CLIENT_SECRET` (both required, `error()` on missing)
- `SIRENE_API_KEY` env var no longer referenced anywhere

**Tests:**
- New `infrastructure/sirene/SireneTokenProviderTest.kt`: caching returns same token within expiry, refresh triggered at expiry minus 60s, concurrent calls don't double-refresh (Mutex), fetch failure propagates as `DomainResult.Err`
- Update `infrastructure/sirene/SireneApiClientTest.kt`: remove `apiKey` param from fake executor, inject `SireneTokenProvider` stub

**Golden tests:** None (no LLM tool or prompt changes)

**Acceptance criteria:**
- [ ] `SireneTokenProvider` caches token, refreshes automatically on expiry
- [ ] `SireneApiClient.fromEnv()` reads `SIRENE_CLIENT_ID` + `SIRENE_CLIENT_SECRET`
- [ ] String `SIRENE_API_KEY` appears in zero source files
- [ ] Existing SIRENE integration tests pass with fake executor
- [ ] `./gradlew build && ./gradlew ktlintCheck` passes

**Post-deploy (manual, after next deploy):**
```bash
fly secrets set SIRENE_CLIENT_ID=xxx SIRENE_CLIENT_SECRET=xxx -a mona-late-tree-7299
fly secrets unset SIRENE_API_KEY -a mona-late-tree-7299
```

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
