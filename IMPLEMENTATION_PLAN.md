# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1–24 complete. See git log for details.

> **Pending operations (not code — run manually):**
>
> 1. **Phase 19.6 — Deploy and verify:**
>    - `fly secrets set TELEGRAM_WEBHOOK_URL=https://mona-late-tree-7299.fly.dev/webhook/telegram TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 32) -a mona-late-tree-7299`
>    - `fly deploy`
>    - Smoke test: message → response, inline button → no 30s spinner, `/start` → onboarding, `/health` → 200, `/webhook/resend` → still works.
>
> 2. **SIRENE API key rotation** (after Phase 25 is deployed):
>    ```bash
>    fly secrets set SIRENE_API_KEY=xxx -a mona-late-tree-7299
>    fly secrets unset SIRENE_CLIENT_ID SIRENE_CLIENT_SECRET -a mona-late-tree-7299
>    ```

---

## Phase 25 — SIRENE API Key Migration

**Spec:** `specs/sirene-oauth-spec.md`
**Layer:** infrastructure only

### What to implement

1. **Delete** `src/main/kotlin/mona/infrastructure/sirene/SireneTokenProvider.kt`
2. **Delete** `src/test/kotlin/mona/infrastructure/SireneTokenProviderTest.kt`
3. **Add** `@JvmInline value class SireneApiKey(val value: String)` in `src/main/kotlin/mona/infrastructure/sirene/SireneApiClient.kt` (top of file, internal)
4. **Rewrite** `RealSireneHttpExecutor` in `SireneApiClient.kt`:
   - Constructor takes `SireneApiKey` instead of `SireneTokenProvider`
   - Auth header: `X-INSEE-Api-Key-Integration: <key>` (no Bearer token)
   - Remove 401 retry logic entirely
5. **Update** `SireneApiClient.fromEnv()`: read `SIRENE_API_KEY` env var; fail fast if missing
6. **Remove** all `catch (e: SireneTokenRefreshException)` blocks from `lookupBySiren` and `searchByNameAndCity`
7. Verify no remaining references to `SireneTokenProvider` or `SireneTokenRefreshException` in the codebase

### Acceptance criteria

1. `SireneTokenProvider.kt` deleted
2. `SireneTokenProviderTest.kt` deleted
3. `RealSireneHttpExecutor` uses `SireneApiKey` + `X-INSEE-Api-Key-Integration` header
4. `SireneApiClient.fromEnv()` reads `SIRENE_API_KEY` env var
5. No `SireneTokenRefreshException` catches remain anywhere
6. `./gradlew build && ./gradlew ktlintCheck` passes
7. No golden test updates needed (no LLM integration change)
