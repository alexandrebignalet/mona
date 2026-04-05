# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1.1–18.1 done. See git log for details.

> **Note (post-deploy, pending):** After next deploy, run SIRENE OAuth2 secrets rotation:
> ```bash
> fly secrets set SIRENE_CLIENT_ID=xxx SIRENE_CLIENT_SECRET=xxx -a mona-late-tree-7299
> fly secrets unset SIRENE_API_KEY -a mona-late-tree-7299
> ```

---

## Phase 19 — Telegram Direct API Migration

Replace `dev.inmo:tgbotapi` long-polling with direct HTTP calls to `api.telegram.org` using webhooks. Spec: `specs/telegram-direct-api-spec.md`.

### 19.1 — TelegramModels.kt ✅ DONE

Created `TelegramModels.kt` with six `@Serializable` DTOs (`TgUpdate`, `TgMessage`, `TgChat`, `TgCallbackQuery`, `TgUser`, `TgResponse<T>`). Added `kotlin("plugin.serialization")` to `build.gradle.kts` (was missing — the library was present but the compiler plugin was not). Tests: round-trip message update, round-trip callback_query update, unknown-field ignoring, null-text message, null-data callback. All pass.

---

### 19.2 — TelegramApiClient.kt

**What:** Create `src/main/kotlin/mona/infrastructure/telegram/TelegramApiClient.kt`. Raw HTTP client for Telegram Bot API. Methods: `sendMessage`, `sendDocument`, `answerCallbackQuery`, `setWebhook`, `deleteWebhook`. Internal `fun interface TelegramHttpExecutor` for test seam (same pattern as `ClaudeHttpExecutor`, `SireneHttpExecutor`). `TgResult<T>` sealed class (`Ok`/`Err`) for Telegram's `{ ok, result }` envelope. `sendDocument` uses multipart/form-data; all others use JSON POST. Pass `parse_mode = "Markdown"` on `sendMessage`. All HTTP calls in `withContext(Dispatchers.IO)`.

**Layer:** Infrastructure (telegram).

**Spec ref:** telegram-direct-api-spec.md §6.

**Acceptance criteria:**
- Unit tests with fake `TelegramHttpExecutor`:
  - `sendMessage` builds correct JSON payload (chat_id, text, parse_mode, reply_markup).
  - `sendDocument` builds correct multipart body with file bytes + caption.
  - `answerCallbackQuery` sends callback_query_id and optional text.
  - `setWebhook` sends url, secret_token, allowed_updates.
  - Success responses parse to `TgResult.Ok`.
  - Error responses (ok=false) parse to `TgResult.Err` with code and description.
- `./gradlew build && ./gradlew ktlintCheck` passes.

---

### 19.3 — MessagingPort callback support

**What:** Add to `src/main/kotlin/mona/domain/port/MessagingPort.kt`:
- `data class IncomingCallback(telegramId: Long, callbackQueryId: String, data: String, userId: UserId?)`
- `suspend fun onCallback(handler: suspend (IncomingCallback) -> Unit)` on `MessagingPort`
- `suspend fun answerCallback(callbackQueryId: String, text: String? = null)` on `MessagingPort`

**Layer:** Domain (port).

**Spec ref:** telegram-direct-api-spec.md §5.

**Acceptance criteria:**
- `IncomingCallback` is a plain data class with no infrastructure imports.
- `MessagingPort` interface has the 3 new members.
- No existing code breaks — all `MessagingPort` implementors updated in same step (done together with 19.4).
- `./gradlew build && ./gradlew ktlintCheck` passes.

---

### 19.4 — TelegramBotAdapter.kt rewrite

**What:** Rewrite `src/main/kotlin/mona/infrastructure/telegram/TelegramBotAdapter.kt`. Remove all `dev.inmo:tgbotapi` imports. Constructor takes `TelegramApiClient`, `UserRepository`, `webhookUrl: String`, `webhookSecret: String`. Implements updated `MessagingPort` including `onCallback` and `answerCallback`. Webhook handler method `handleWebhook(HttpExchange)`: verify `X-Telegram-Bot-Api-Secret-Token` header, parse JSON to `TgUpdate`, route to message/callback handlers, respond 200 immediately (before processing). `start()` calls `setWebhook` with `allowed_updates = ["message", "callback_query"]`. `stop()` calls `deleteWebhook`. Retain existing `chatIdCache` and `persistentKeyboards` patterns. `sendButtons` builds inline keyboard JSON manually.

**Layer:** Infrastructure (telegram).

**Spec ref:** telegram-direct-api-spec.md §§3, 7.

**Acceptance criteria:**
- Zero imports from `dev.inmo` package.
- Unit tests for webhook handler:
  - Valid message update dispatches `IncomingMessage` to registered handler.
  - Valid callback_query update dispatches `IncomingCallback` to registered handler.
  - Missing/wrong secret token returns 401, does not dispatch.
  - Malformed JSON returns 200 (avoids Telegram retries), does not dispatch.
  - Non-POST returns 405.
- Unit tests for outbound methods:
  - `sendMessage` delegates to `apiClient.sendMessage` with resolved chatId.
  - `sendButtons` builds inline keyboard JSON with button text and callbackData.
  - `sendDocument` delegates to `apiClient.sendDocument`.
  - `answerCallback` delegates to `apiClient.answerCallbackQuery`.
- `start()` calls `setWebhook` with correct URL and secret.
- `stop()` calls `deleteWebhook`.
- `./gradlew build && ./gradlew ktlintCheck` passes.

---

### 19.5 — App.kt wiring and dependency removal

**What:** Update `src/main/kotlin/mona/App.kt`:
- Create `TelegramApiClient(telegramToken)`, pass to `TelegramBotAdapter`.
- Read `TELEGRAM_WEBHOOK_URL` and `TELEGRAM_WEBHOOK_SECRET` from env (`error()` if missing).
- Pass `webhookUrl` and `webhookSecret` to adapter constructor.
- Register `POST /webhook/telegram` on the existing `HttpServer` (same pattern as `/webhook/resend`).
- Register `onCallback` handler (no-op stub, ready for future use case wiring).
- Replace `botJob` lifecycle: `start()` registers webhook and returns; no `botJob.join()`. Block main thread with `Thread.currentThread().join()`.
- Shutdown hook: call `telegramAdapter.stop()` (deleteWebhook), then stop HttpServer, then cancel scope.

Update `build.gradle.kts`:
- Remove `dev.inmo:tgbotapi` dependency entirely.

Update `CLAUDE.md` Bot Framework row: change to "Direct Telegram Bot API (HTTP + kotlinx-serialization, webhook)".

**Layer:** Infrastructure (wiring), build config.

**Spec ref:** telegram-direct-api-spec.md §§9, 10, 12 items 6–8.

**Acceptance criteria:**
- `dev.inmo:tgbotapi` no longer in dependency tree (`./gradlew dependencies | grep inmo` returns nothing).
- App fails fast with clear error if `TELEGRAM_WEBHOOK_URL` or `TELEGRAM_WEBHOOK_SECRET` are missing.
- `/webhook/telegram` endpoint registered on HttpServer.
- Shutdown hook calls `deleteWebhook`.
- `./gradlew build && ./gradlew ktlintCheck` passes.
- No golden test regressions (LLM layer unchanged).

---

### 19.6 — Deploy and verify

**What:** Deploy to Fly.io, set new env vars, verify end-to-end.

**Layer:** Operations.

**Spec ref:** telegram-direct-api-spec.md §§10, 12 items 7–10.

**Steps:**
1. `fly secrets set TELEGRAM_WEBHOOK_URL=https://mona-late-tree-7299.fly.dev/webhook/telegram TELEGRAM_WEBHOOK_SECRET=$(openssl rand -hex 32) -a mona-late-tree-7299`
2. Deploy via `fly deploy`.
3. Also run the pending SIRENE secrets rotation (see Note above).
4. Manual smoke test: send message → confirm response; press inline keyboard button → confirm no 30s spinner; send `/start` → confirm onboarding flow works.

**Acceptance criteria:**
- Bot responds to messages via webhook (no long-polling connection in logs).
- Inline keyboard button presses are acknowledged (answerCallbackQuery called).
- `/health` returns 200.
- `/webhook/resend` still handles bounce events.

---

## Prevention Rules

1. **No floating point for money.** All amounts in `Cents` (Long).
2. **Domain purity.** Any import of Exposed, Telegram, or HTTP client in `domain/` is a build failure.
3. **No raw primitives for domain concepts.** Use `InvoiceId`, `UserId`, `Cents`, `Siren`, etc.
4. **State transitions via aggregate only.** Never `invoice.copy(status = ...)` outside `Invoice.kt`.
5. **Factory for creation.** Never construct `Invoice(...)` directly for new invoices — use `Invoice.create()`.
6. **Events returned, not published.** Aggregate methods return `TransitionResult`. Application layer dispatches.
7. **URSSAF = cash basis.** Revenue computed from `paidDate`, never `issueDate`.
8. **Application use cases: max ~30 lines.** Longer means business logic in the wrong layer.
9. **Last 3 messages only.** No separate "last action" pointer. LLM resolves from conversation history.
10. **No unapproved libraries.** Check CLAUDE.md tech stack before adding any dependency.
11. **Golden tests gate prompt changes.** Never deploy a prompt change without running the golden suite.
12. **FK-aware deletion order.** Follow the sequence in `DeleteAccount.execute()` when changing GDPR deletion.
13. **Infrastructure DTOs stay in infrastructure.** Telegram/Resend/SIRENE models never leak into domain or application layers. Convert at the adapter boundary.
