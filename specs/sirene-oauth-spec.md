# Mona — SIRENE API OAuth2 Token Management

The INSEE SIRENE API uses OAuth2 client credentials flow. Tokens expire (~7 days). Mona must manage token lifecycle automatically instead of requiring a manually generated Bearer token.

---

## 1. Current State

- `SireneApiClient` takes a static `apiKey` (Bearer token) via `SIRENE_API_KEY` env var
- Token is passed as `Authorization: Bearer $apiKey` on every request
- When the token expires, all SIRENE lookups fail with HTTP 401 until the env var is manually updated
- `RealSireneHttpExecutor` creates a plain `HttpClient` with no token management

## 2. Target State

- Mona stores **client ID + client secret** (long-lived credentials from the INSEE API dashboard)
- On first request (or after expiry), Mona exchanges credentials for a short-lived access token via the INSEE `/token` endpoint
- Tokens are cached in memory and refreshed automatically before or upon expiry
- No manual token rotation needed

## 3. Environment Variables

| Variable | Current | New |
|----------|---------|-----|
| `SIRENE_API_KEY` | Static Bearer token | **Remove** |
| `SIRENE_CLIENT_ID` | — | OAuth2 client ID from INSEE dashboard |
| `SIRENE_CLIENT_SECRET` | — | OAuth2 client secret from INSEE dashboard |

## 4. OAuth2 Token Endpoint

```
POST https://api.insee.fr/token
Content-Type: application/x-www-form-urlencoded

grant_type=client_credentials&client_id={SIRENE_CLIENT_ID}&client_secret={SIRENE_CLIENT_SECRET}
```

**Response (200):**
```json
{
  "access_token": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "scope": "am_application_scope default",
  "token_type": "Bearer",
  "expires_in": 604800
}
```

## 5. Design

### 5.1 Token Provider

New internal class `SireneTokenProvider` in `infrastructure/sirene/`:

```kotlin
internal class SireneTokenProvider(
    private val clientId: String,
    private val clientSecret: String,
    private val tokenUrl: String = "https://api.insee.fr/token",
) {
    // Cached token + expiry timestamp
    // Thread-safe via Mutex (coroutine-friendly)

    suspend fun getToken(): String
    // Returns cached token if still valid (with safety margin, e.g. 60s before expiry)
    // Otherwise fetches a new token via client_credentials grant
}
```

**Rules:**
- Uses `java.net.http.HttpClient` for the token POST (same as existing code — no new dependencies)
- Caches `access_token` and `expires_in` in memory
- Refreshes when token is within **60 seconds** of expiry (safety margin)
- Uses `kotlinx.coroutines.sync.Mutex` for thread safety — only one coroutine refreshes at a time
- On token fetch failure, returns `DomainResult.Err` (no exceptions)

### 5.2 Changes to SireneHttpExecutor

`RealSireneHttpExecutor` currently receives the API key per-call. Change it to receive a `SireneTokenProvider` at construction:

```kotlin
internal class RealSireneHttpExecutor(
    private val tokenProvider: SireneTokenProvider,
) : SireneHttpExecutor {
    // get() no longer takes apiKey parameter
    // Instead: val token = tokenProvider.getToken()
}
```

The `SireneHttpExecutor` fun interface changes from:
```kotlin
suspend fun get(url: String, apiKey: String): SireneHttpResponse
```
to:
```kotlin
suspend fun get(url: String): SireneHttpResponse
```

### 5.3 Changes to SireneApiClient

- Remove `apiKey` constructor parameter
- `fromEnv()` reads `SIRENE_CLIENT_ID` and `SIRENE_CLIENT_SECRET` instead of `SIRENE_API_KEY`
- Constructs `SireneTokenProvider` and passes it to `RealSireneHttpExecutor`

### 5.4 Test Seam

The existing `SireneHttpExecutor` fun interface remains the test seam. Tests inject a fake executor directly and never touch `SireneTokenProvider`. The token provider itself can be tested in isolation with a mock HTTP response.

## 6. Error Handling

| Scenario | Behavior |
|----------|----------|
| Token fetch returns non-200 | Return `DomainError.SireneLookupFailed("Token refresh failed: HTTP {status}")` |
| Token fetch network error | Return `DomainError.SireneLookupFailed("Token refresh failed: {message}")` |
| API call returns 401 | Invalidate cached token, retry **once** with a fresh token. If still 401, return error. |
| Missing env vars at startup | `error()` — fail fast, same as current behavior |

## 7. Files to Change

| File | Change |
|------|--------|
| `infrastructure/sirene/SireneTokenProvider.kt` | **New** — OAuth2 token cache + refresh |
| `infrastructure/sirene/SireneApiClient.kt` | Remove `apiKey`, use token provider, update `fromEnv()` |
| `App.kt` | No change (already calls `SireneApiClient.fromEnv()`) |
| `fly.toml` / Fly secrets | Replace `SIRENE_API_KEY` with `SIRENE_CLIENT_ID` + `SIRENE_CLIENT_SECRET` |
| Tests for SireneApiClient | Update fake executor signature (remove `apiKey` param) |
| `infrastructure/sirene/SireneTokenProviderTest.kt` | **New** — unit test for token caching, refresh, expiry margin |

## 8. Deployment

1. Set Fly secrets: `fly secrets set SIRENE_CLIENT_ID=xxx SIRENE_CLIENT_SECRET=xxx`
2. Deploy new code
3. Remove old secret: `fly secrets unset SIRENE_API_KEY`
