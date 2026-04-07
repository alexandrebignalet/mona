# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1–23 complete. See git log for details.

> **Pending operations (not code — run manually):**
>
> 1. **SIRENE secrets rotation** (after Phase 24 is deployed):
>    ```bash
>    fly secrets set SIRENE_CLIENT_ID=xxx SIRENE_CLIENT_SECRET=xxx -a mona-late-tree-7299
>    fly secrets unset SIRENE_API_KEY -a mona-late-tree-7299
>    ```
>
> 2. **Phase 19.6 — Deploy and verify:**
>    - `fly secrets set TELEGRAM_WEBHOOK_URL=https://mona-late-tree-7299.fly.dev/webhook/telegram TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 32) -a mona-late-tree-7299`
>    - `fly deploy`
>    - Smoke test: message → response, inline button → no 30s spinner, `/start` → onboarding, `/health` → 200, `/webhook/resend` → still works.

---

## Phase 24 — SIRENE Lookup Fix + OAuth2 Wiring

**What:** Fix two gaps in `SireneApiClient.kt` that cause 4 failing integration tests. Both gaps are in the same file and are implemented together to avoid conflicts.

**Layer:** Infrastructure (`src/main/kotlin/mona/infrastructure/sirene/`)

**Spec refs:** `specs/sirene-lookup-fix.md`, `specs/sirene-oauth-spec.md`

---

### 24.1 — Lookup Fix: Replace `/siren/{siren}` with `/siret` Search

**Problem:** `lookupBySiren()` calls `GET /siren/{siren}`, which returns no establishment-level data. `parseSirenResponse()` always returns `address = null`. Four tests currently fail because they assert a populated address, a valid SIRET, and correct ActivityType mapping — none of which can pass with the old endpoint.

**Changes to `SireneApiClient.kt`:**

- Rewrite `lookupBySiren()` to call `GET $baseUrl/siret?q=siren:{siren}%20AND%20etablissementSiege:true&nombre=1` (URL-encode the `q` parameter value via `URLEncoder.encode(..., StandardCharsets.UTF_8)`).
- Remove the `response.statusCode == 404 -> SirenNotFound` branch. The `/siret` search never returns 404 for "not found" — it returns HTTP 200 with `etablissements: []`. The new "not found" check is inside `parseLookupResponse` when `etablissements` is null or empty.
- Add private method `parseLookupResponse(body: String, requestedSiren: Siren): DomainResult<SireneResult>` that reads `etablissements[0]`, extracts `siret`, `uniteLegale`, and `adresseEtablissement`, and delegates to existing `extractLegalName`, `parseAddress`, and `nafToActivityType` helpers. Empty array → `DomainResult.Err(DomainError.SirenNotFound(siren))`. Missing `siret` or `uniteLegale` keys → `DomainResult.Err(DomainError.SireneLookupFailed(...))`.
- Delete `parseSirenResponse()` entirely.

**Changes to test fixtures:**

- `src/test/resources/fixtures/sirene/lookup_success.json` — replace with `/siret` response format matching `search_single_match.json` structure (with `etablissements[]`, `uniteLegale`, `adresseEtablissement`, NAF code `62.01Z`, siret `12345678900012`).
- `src/test/resources/fixtures/sirene/lookup_not_found.json` — replace with HTTP-200 empty-list format: `{ "header": {"total":0,"debut":0,"nombre":0}, "etablissements": [] }`.
- `src/test/resources/fixtures/sirene/lookup_ceased.json` — replace with same HTTP-200 empty-list format (ceased treated as not found per spec).

**Changes to `IntegrationTestBase.kt`:**

- In `FakeSireneHttpExecutor`, change `SireneScenario.LookupNotFound` and `SireneScenario.LookupCeased` response status from `404` to `200`.

---

### 24.2 — OAuth2 Wiring: Replace API Key with Token Provider

**Problem:** `RealSireneHttpExecutor` uses a static `X-INSEE-Api-Key-Integration` header sourced from `SIRENE_API_KEY`. `SireneTokenProvider` was implemented but is not yet wired into the executor. `fromEnv()` still reads `SIRENE_API_KEY`.

**Changes to `SireneApiClient.kt`:**

- Remove the `SireneApiKey` value class.
- Rewrite `RealSireneHttpExecutor` to accept `SireneTokenProvider` instead of `SireneApiKey`. In `get(url)`, call `val token = tokenProvider.getToken()` and set `Authorization: Bearer $token` header.
- Add 401 retry: if the API response is HTTP 401, call `tokenProvider.invalidate()` and send once more with a fresh token. If the retry is also 401, return the error.
- Rewrite `fromEnv()` to read `SIRENE_CLIENT_ID` and `SIRENE_CLIENT_SECRET` env vars (both `error()` if absent), construct `SireneTokenProvider.create(clientId, clientSecret)`, and pass it to `RealSireneHttpExecutor`.
- The `SireneTokenRefreshException` catch blocks in `lookupBySiren` and `searchByNameAndCity` already exist and require no changes.

**What does NOT change:**

- `SireneHttpExecutor` fun interface — already has `suspend fun get(url: String): SireneHttpResponse` with no `apiKey` parameter.
- `SireneTokenProvider.kt` — already fully implemented; no changes needed.
- All test classes — fake executors implement `SireneHttpExecutor` directly and never touch `SireneTokenProvider`.

---

### Acceptance Criteria

The following 4 currently-failing tests must pass:

1. `SireneApiContractTest.lookupBySiren address is assembled correctly`
2. `SireneApiContractTest.lookupBySiren NAF code maps to ActivityType`
3. `SireneApiContractTest.lookupBySiren success SIRET is populated`
4. `LookupSirenTest.valid SIREN populates user profile from Sirene`

Additional checks:

- All existing passing tests continue to pass (no regressions in search, 500-error, malformed-JSON, or token-refresh-exception scenarios).
- `lookupBySiren` calls a URL containing `/siret?q=` and `siren:` in the query string.
- `./gradlew build && ./gradlew ktlintCheck` both pass with zero errors.

---

## Prevention Rules

1. **No unapproved libraries.** Check CLAUDE.md tech stack before adding any dependency.
2. **Golden tests gate prompt changes.** Never deploy a prompt change without running the golden suite.
3. **URSSAF = cash basis.** Revenue computed from `paidDate`, never `issueDate`.
4. **Last 3 messages only.** No separate "last action" pointer. LLM resolves from conversation history.
