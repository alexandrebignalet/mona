# Mona — SIRENE API Authentication (API Key)

The INSEE SIRENE API v3.11 uses a static API key passed via a custom header. No OAuth2 token lifecycle management is needed.

---

## 1. Current State

- `SireneApiClient` uses OAuth2 client credentials flow via `SireneTokenProvider`
- Stores `SIRENE_CLIENT_ID` and `SIRENE_CLIENT_SECRET` env vars
- Token is fetched, cached, and refreshed automatically
- `RealSireneHttpExecutor` receives a `SireneTokenProvider` and handles 401 retry with token invalidation

## 2. Target State

- Mona stores a single **API key** (long-lived credential from the INSEE API dashboard)
- The key is passed on every request via the `X-INSEE-Api-Key-Integration` header
- No token lifecycle, no caching, no refresh logic
- `SireneTokenProvider` is removed entirely

## 3. Environment Variables

| Variable | Current | New |
|----------|---------|-----|
| `SIRENE_CLIENT_ID` | OAuth2 client ID | **Remove** |
| `SIRENE_CLIENT_SECRET` | OAuth2 client secret | **Remove** |
| `SIRENE_API_KEY` | — | Static API key from INSEE dashboard |

## 4. API Base URL

```
https://api.insee.fr/api-sirene/3.11/
```

## 5. Design

### 5.1 Value Class for API Key

New inline value class in `infrastructure/sirene/`:

```kotlin
@JvmInline
internal value class SireneApiKey(val value: String)
```

### 5.2 Changes to RealSireneHttpExecutor

Replace `SireneTokenProvider` with `SireneApiKey`:

```kotlin
internal class RealSireneHttpExecutor(
    private val sireneApiKey: SireneApiKey,
) : SireneHttpExecutor {
    private val client: HttpClient = HttpClient.newHttpClient()

    override suspend fun get(url: String): SireneHttpResponse {
        return withContext(Dispatchers.IO) {
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-INSEE-Api-Key-Integration", sireneApiKey.value)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            val response = client.send(request, BodyHandlers.ofString())
            SireneHttpResponse(response.statusCode(), response.body())
        }
    }
}
```

Key changes:
- No token refresh, no 401 retry logic
- Auth header changed from `Authorization: Bearer $token` to `X-INSEE-Api-Key-Integration: $apiKey`
- Single straightforward HTTP call per request

### 5.3 Changes to SireneApiClient

- `fromEnv()` reads `SIRENE_API_KEY` instead of `SIRENE_CLIENT_ID` / `SIRENE_CLIENT_SECRET`
- Constructs `SireneApiKey` and passes it to `RealSireneHttpExecutor`

```kotlin
fun fromEnv(): SireneApiClient {
    val sireneApiKey = SireneApiKey(
        System.getenv("SIRENE_API_KEY") ?: error("SIRENE_API_KEY environment variable is not set")
    )
    return SireneApiClient(httpExecutor = RealSireneHttpExecutor(sireneApiKey))
}
```

### 5.4 Remove SireneTokenProvider

Delete `SireneTokenProvider` and `SireneTokenRefreshException` entirely. Remove all `catch (e: SireneTokenRefreshException)` blocks from `SireneApiClient` — these are no longer possible.

### 5.5 Test Seam

The existing `SireneHttpExecutor` fun interface remains the test seam. Tests inject a fake executor directly and never touch `SireneApiKey`. No token provider tests needed.

## 6. Error Handling

| Scenario | Behavior |
|----------|----------|
| API call returns 401 (invalid key) | Return `DomainError.SireneLookupFailed("HTTP 401: ...")` — no retry |
| API call returns 403 | Return `DomainError.SireneLookupFailed("HTTP 403: ...")` |
| Missing env var at startup | `error()` — fail fast |

## 7. Files to Change

| File | Change |
|------|--------|
| `infrastructure/sirene/SireneApiClient.kt` | Remove token provider usage, use `SireneApiKey`, update `fromEnv()`, remove `SireneTokenRefreshException` catches |
| `infrastructure/sirene/SireneTokenProvider.kt` | **Delete** |
| `App.kt` | No change (already calls `SireneApiClient.fromEnv()`) |
| `fly.toml` / Fly secrets | Replace `SIRENE_CLIENT_ID` + `SIRENE_CLIENT_SECRET` with `SIRENE_API_KEY` |
| Tests for SireneApiClient | Remove token-refresh-related tests |

## 8. Deployment

1. Set Fly secret: `fly secrets set SIRENE_API_KEY=xxx`
2. Deploy new code
3. Remove old secrets: `fly secrets unset SIRENE_CLIENT_ID SIRENE_CLIENT_SECRET`
