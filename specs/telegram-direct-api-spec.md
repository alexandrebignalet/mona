# Spec: Replace TelegramBotAPI Library with Direct Telegram HTTP API

## Status: Draft
## Date: 2026-04-05

---

## 1. Context & Motivation

The current Telegram integration uses `dev.inmo:tgbotapi` (InsanusMokrassar), a low-adoption Kotlin wrapper with limited community support. The library is unreliable — features break silently and updates lag behind the Telegram Bot API.

The codebase already uses `java.net.http.HttpClient` + `kotlinx-serialization-json` for three other integrations (Claude API, Resend, SIRENE). The `MessagingPort` abstraction keeps the blast radius small: only `TelegramBotAdapter.kt` and one Gradle dependency line need to change.

**Decision: call the Telegram Bot API over HTTP directly. No replacement library.**

### Why not `rubenlagus/TelegramBots`?

- Java-first, blocking thread model — fights the coroutine architecture.
- Heavyweight: pulls in OkHttp, Jackson, and other transitive deps we don't want.
- The integration surface is small (6–7 HTTP endpoints). A library adds coupling without proportional value.

### Why not raw HTTP without a thin client wrapper?

We *will* use raw HTTP — but wrapped in a small `TelegramApiClient` class (same pattern as `ClaudeApiClient`, `SireneApiClient`, `ResendEmailAdapter`). This keeps JSON serialization, error handling, and retry logic in one place.

---

## 2. Scope — One-Shot Replacement

| In scope | Out of scope |
|----------|-------------|
| Remove `dev.inmo:tgbotapi` dependency | Receiving files/photos from users |
| New `TelegramApiClient` (raw HTTP) | Group chat support |
| Switch from long polling to webhook | Multi-bot support |
| Rewrite `TelegramBotAdapter` | Changes to `MessagingPort` interface (beyond adding callback queries) |
| Add callback query handling (fix existing gap) | Horizontal scaling / multiple instances |
| `setWebhook` / `deleteWebhook` lifecycle | |

---

## 3. Long Polling → Webhook

**Decision: switch to webhook mode.**

### Rationale

| Concern | Long Polling | Webhook |
|---------|-------------|---------|
| Infrastructure fit | Requires persistent outbound connection; reconnection logic on restart | Fly.io already exposes port 8080 publicly; `HttpServer` already running |
| Complexity | Must manage polling loop, backoff, connection drops | Single HTTP endpoint, Telegram pushes updates |
| Shutdown | Must drain in-flight `getUpdates` call | Just stop accepting requests |
| Latency | Up to polling interval delay | Near-instant push |
| Existing pattern | N/A | Identical to Resend webhook handler already in codebase |

### Webhook Setup

- **Endpoint:** `POST /webhook/telegram` on the existing `com.sun.net.httpserver.HttpServer` (port 8080).
- **Registration:** on startup, call `setWebhook` with `url = https://<fly-app>.fly.dev/webhook/telegram` and a `secret_token` for verification.
- **Verification:** Telegram sends the secret token in the `X-Telegram-Bot-Api-Secret-Token` header. The handler validates it before processing.
- **On shutdown:** call `deleteWebhook` to stop Telegram from queuing updates to an unreachable URL. Fly.io health check will catch restarts.
- **Allowed updates:** `["message", "callback_query"]` — only subscribe to what we handle.

### Webhook URL Configuration

The webhook URL must be provided via env var `TELEGRAM_WEBHOOK_URL`. This avoids hardcoding the Fly.io app name and supports local development with tunnels (e.g., ngrok).

A second env var `TELEGRAM_WEBHOOK_SECRET` holds the secret token for `X-Telegram-Bot-Api-Secret-Token` verification. Generated once, stored in Fly secrets.

---

## 4. Architecture

### New Files

```
src/main/kotlin/mona/infrastructure/telegram/
├── TelegramApiClient.kt      # Raw HTTP calls to api.telegram.org
├── TelegramBotAdapter.kt     # Rewritten — implements MessagingPort, webhook handler
└── TelegramModels.kt         # Kotlinx-serializable data classes for Telegram JSON
```

### Deleted

- `dev.inmo:tgbotapi` dependency from `build.gradle.kts`

### Unchanged

- `MessagingPort` interface (except adding callback query support — see §5)
- `App.kt` wiring (adapter creation + registration on HttpServer)
- All domain and application layers

---

## 5. MessagingPort Changes

### Add: Callback Query Support

The current `MessagingPort` sends inline keyboard buttons (`sendButtons`) but never receives the user's button press. This is a known gap.

```kotlin
// New data class in MessagingPort.kt
data class IncomingCallback(
    val telegramId: Long,
    val callbackQueryId: String,
    val data: String,
    val userId: UserId?,
)

// New method on MessagingPort
suspend fun onCallback(handler: suspend (IncomingCallback) -> Unit)

// Required by Telegram API — must answer within 30s or button shows loading spinner
suspend fun answerCallback(callbackQueryId: String, text: String? = null)
```

The `MessageRouter` (or a new `CallbackRouter`) will register a handler via `onCallback` to dispatch button presses to the appropriate use case.

---

## 6. TelegramApiClient — HTTP Client

Follows the same pattern as `ClaudeApiClient` and `SireneApiClient`:

```kotlin
class TelegramApiClient(private val token: String) {
    private val client = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl = "https://api.telegram.org/bot$token"

    // -- Outbound methods (called by TelegramBotAdapter) --

    suspend fun sendMessage(chatId: Long, text: String, replyMarkup: JsonElement? = null): TgResult<TgMessage>
    suspend fun sendDocument(chatId: Long, fileBytes: ByteArray, fileName: String, caption: String?): TgResult<TgMessage>
    suspend fun answerCallbackQuery(callbackQueryId: String, text: String? = null): TgResult<Boolean>
    suspend fun setWebhook(url: String, secretToken: String, allowedUpdates: List<String>): TgResult<Boolean>
    suspend fun deleteWebhook(): TgResult<Boolean>

    // -- Internal --

    private suspend fun <T> post(method: String, body: JsonElement, deserialize: (JsonElement) -> T): TgResult<T>
    private suspend fun <T> postMultipart(method: String, parts: Map<String, Any>, deserialize: (JsonElement) -> T): TgResult<T>
}
```

### Key Details

- **Coroutine integration:** all HTTP calls wrapped in `withContext(Dispatchers.IO)` (same as existing clients).
- **`sendDocument`** uses `multipart/form-data` (Telegram requires it for file uploads). All other methods use `application/json`.
- **Result type:** `TgResult<T>` is a simple sealed class (`Ok(value)` / `Err(code, description)`) for Telegram's `{ ok: bool, result: ... }` envelope. Not a domain type — lives in infrastructure.
- **Retry:** no retry logic in V1. Telegram webhooks have built-in retry on non-2xx responses. For outbound sends, failures surface through existing error handling.
- **Markdown parsing:** pass `parse_mode = "Markdown"` on `sendMessage` to preserve existing formatting in bot responses.

---

## 7. TelegramBotAdapter — Rewrite

The adapter becomes thinner — no more library abstractions to navigate.

```kotlin
class TelegramBotAdapter(
    private val apiClient: TelegramApiClient,
    private val userRepository: UserRepository,
    private val webhookUrl: String,
    private val webhookSecret: String,
) : MessagingPort {

    // -- MessagingPort implementation (outbound) --
    // sendMessage, sendDocument, sendButtons, setPersistentMenu, answerCallback
    // Each delegates to apiClient with chatId resolution (same cache pattern as today)

    // -- Webhook handler (inbound) --
    fun handleWebhook(exchange: HttpExchange) { ... }

    // -- Lifecycle --
    suspend fun start()   // calls apiClient.setWebhook(...)
    suspend fun stop()    // calls apiClient.deleteWebhook()
}
```

### Webhook Handler Flow

1. Verify `X-Telegram-Bot-Api-Secret-Token` header matches `webhookSecret`.
2. Read body, parse JSON into `TgUpdate`.
3. Route based on update type:
   - `update.message` → build `IncomingMessage`, dispatch to registered handlers.
   - `update.callback_query` → build `IncomingCallback`, dispatch to registered handlers.
4. Respond `200 OK` immediately (Telegram retries on non-2xx).

This follows the exact same pattern as `ResendWebhookHandler`: verify signature → parse → respond 200 → process async.

---

## 8. TelegramModels — Serializable DTOs

Minimal set of Telegram API types needed. Only model fields we actually use.

```kotlin
@Serializable data class TgUpdate(
    @SerialName("update_id") val updateId: Long,
    val message: TgMessage? = null,
    @SerialName("callback_query") val callbackQuery: TgCallbackQuery? = null,
)

@Serializable data class TgMessage(
    @SerialName("message_id") val messageId: Long,
    val chat: TgChat,
    val text: String? = null,
)

@Serializable data class TgChat(val id: Long)

@Serializable data class TgCallbackQuery(
    val id: String,
    val from: TgUser,
    val data: String? = null,
)

@Serializable data class TgUser(val id: Long)

@Serializable data class TgResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
)
```

These are infrastructure DTOs — they do not leak into domain. The adapter converts them to `IncomingMessage` / `IncomingCallback` at the boundary.

---

## 9. App.kt Wiring Changes

```kotlin
// Before
val telegramAdapter = TelegramBotAdapter(telegramToken, userRepository, scope)
// ...
val botJob = runBlocking {
    telegramAdapter.onMessage { ... }
    telegramAdapter.start()
}

// After
val telegramApiClient = TelegramApiClient(telegramToken)
val telegramAdapter = TelegramBotAdapter(
    apiClient = telegramApiClient,
    userRepository = userRepository,
    webhookUrl = System.getenv("TELEGRAM_WEBHOOK_URL")
        ?: error("TELEGRAM_WEBHOOK_URL is not set"),
    webhookSecret = System.getenv("TELEGRAM_WEBHOOK_SECRET")
        ?: error("TELEGRAM_WEBHOOK_SECRET is not set"),
)

// Register webhook endpoint on existing HttpServer (alongside /health and /webhook/resend)
healthServer.createContext("/webhook/telegram") { exchange ->
    if (exchange.requestMethod == "POST") {
        telegramAdapter.handleWebhook(exchange)
    } else {
        exchange.sendResponseHeaders(405, 0)
        exchange.responseBody.close()
    }
}

// Register handlers then start (setWebhook)
runBlocking {
    telegramAdapter.onMessage { message -> ... }
    telegramAdapter.onCallback { callback -> ... }
    telegramAdapter.start()
}

// Shutdown hook
Runtime.getRuntime().addShutdownHook(Thread {
    healthServer.stop(1)
    runBlocking { telegramAdapter.stop() } // deleteWebhook
    scope.cancel()
})
```

**Key change:** no more `botJob` to join. The webhook is served by `HttpServer` which already blocks the main thread. The `start()` method just registers the webhook with Telegram — it returns immediately.

---

## 10. Environment Variables

| Variable | Required | Example | Purpose |
|----------|----------|---------|---------|
| `TELEGRAM_BOT_TOKEN` | Yes (existing) | `123456:ABC-DEF...` | Bot authentication |
| `TELEGRAM_WEBHOOK_URL` | Yes (new) | `https://mona-late-tree-7299.fly.dev/webhook/telegram` | Webhook registration |
| `TELEGRAM_WEBHOOK_SECRET` | Yes (new) | Random 64-char hex string | Webhook verification |

Set on Fly.io via `fly secrets set`.

---

## 11. Testing Strategy

### Unit Tests

- **`TelegramApiClient`** — inject a fake HTTP executor (same `fun interface` pattern as `ClaudeHttpExecutor`). Verify JSON payloads sent, parse response fixtures.
- **`TelegramBotAdapter.handleWebhook`** — feed raw JSON strings, verify `IncomingMessage` / `IncomingCallback` dispatched correctly. Verify secret token rejection.
- **Multipart encoding** — verify `sendDocument` builds correct multipart body.

### Integration Test

- Existing golden tests and message routing tests are unaffected — they mock `MessagingPort`.
- Add one smoke test that calls `setWebhook` / `deleteWebhook` against Telegram API with the real token (gated behind env var, CI-only).

### Manual Verification

- Deploy to Fly.io staging, send messages, verify responses.
- Press inline keyboard buttons, verify callback is received and answered.
- Send `/start`, verify onboarding flow works end-to-end.

---

## 12. Migration Checklist

1. Create `TelegramModels.kt` with serializable DTOs.
2. Create `TelegramApiClient.kt` with HTTP methods.
3. Rewrite `TelegramBotAdapter.kt` — webhook handler + MessagingPort implementation.
4. Add `IncomingCallback`, `onCallback`, `answerCallback` to `MessagingPort`.
5. Update `App.kt` wiring — register `/webhook/telegram`, remove bot job.
6. Remove `dev.inmo:tgbotapi` from `build.gradle.kts`.
7. Add `TELEGRAM_WEBHOOK_URL` and `TELEGRAM_WEBHOOK_SECRET` to Fly secrets.
8. Update `fly.toml` if needed (no changes expected — port 8080 already exposed).
9. Write unit tests for `TelegramApiClient` and webhook handler.
10. Deploy and verify.

---

## 13. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Multipart form-data encoding with `java.net.http` is verbose | Dev effort | Isolated in one method (`sendDocument`); well-documented JDK API |
| Telegram retries on slow webhook response | Duplicate processing | Respond 200 before processing (async dispatch, same as Resend handler) |
| Webhook URL unreachable during deploy | Missed messages | Fly.io zero-downtime deploy (rolling); Telegram retries for up to 24h |
| Missing Telegram API fields in minimal DTOs | Future feature work blocked | `ignoreUnknownKeys = true` on JSON parser; add fields as needed |
