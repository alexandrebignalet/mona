# Spec: Minimal Structured Logging

## Status: Draft
## Date: 2026-04-05

---

## 1. Context & Motivation

The codebase has **zero logging**. All errors are converted to user-facing French messages via `DomainResult<T>` / `formatDomainError()` and sent to Telegram. The operator sees nothing.

Concrete pain points:

| Symptom | Root cause |
|---------|-----------|
| Bot replies "Je suis momentanément indisponible" — no idea why | `ClaudeApiClient` returns `LlmUnavailable(reason)` but the reason string is discarded at `MessageRouter.kt:255` |
| Scheduled jobs silently fail | `App.kt:169,183,…` — empty `catch (_: Exception)` blocks |
| Webhook parse errors invisible | `TelegramBotAdapter.kt:169` — exception swallowed, 200 returned |
| Telegram API send failures invisible | `TgResult.Err` is never logged anywhere |
| Email delivery failures invisible | `EmailDeliveryFailed` formatted for user, underlying HTTP status/body lost |

**Goal:** add just enough logging to answer "what went wrong?" from `fly logs` without introducing a framework that fights the existing architecture.

---

## 2. Decision: SLF4J + slf4j-simple

| Option | Verdict | Reason |
|--------|---------|--------|
| `println` / `System.err` | ❌ | No levels, no timestamps, no structured context |
| SLF4J + Logback | ❌ | XML config, 3 JARs, overkill for a single-instance bot |
| SLF4J + slf4j-simple | ✅ | One dependency, zero config files, outputs to stderr (which Fly captures), level filtering via system property |
| kotlin-logging | ❌ | Extra dependency on top of SLF4J for syntactic sugar we don't need |

### Why SLF4J

- Standard JVM logging facade — every library already speaks it (Exposed, PDFBox emit debug logs through it).
- `slf4j-simple` prints to stderr with timestamp, level, logger name, message. No config file needed.
- If we ever need structured JSON logs (e.g., for Datadog), we swap `slf4j-simple` for `logback-classic` — zero code changes.

### Dependency

```kotlin
// build.gradle.kts
implementation("org.slf4j:slf4j-api:2.0.16")
implementation("org.slf4j:slf4j-simple:2.0.16")
```

### Configuration

Default level set via system property in the shadow JAR main class or `JAVA_TOOL_OPTIONS`:

```
-Dorg.slf4j.simpleLogger.defaultLogLevel=info
-Dorg.slf4j.simpleLogger.showDateTime=true
-Dorg.slf4j.simpleLogger.dateTimeFormat=yyyy-MM-dd'T'HH:mm:ss.SSSZ
```

This keeps Exposed/PDFBox noise at INFO while our loggers emit WARN/ERROR/INFO as needed.

---

## 3. What to Log (and What Not To)

### 3.1 Log Points

Each log point is categorized by level and location. The table is exhaustive — **do not add log lines beyond this list** without updating the spec.

| # | Location | Level | What to log | Why |
|---|----------|-------|-------------|-----|
| L1 | `ClaudeApiClient` — non-200 response | WARN | `"LLM error: HTTP {status} body={truncated body}"` | The exact error behind "momentanément indisponible" |
| L2 | `ClaudeApiClient` — rate limit retry | WARN | `"LLM rate-limited ({status}), retry {n}/{max} in {delay}ms"` | Understand if we're consistently hitting limits |
| L3 | `ClaudeApiClient` — all retries exhausted | ERROR | `"LLM unavailable after {max} retries: HTTP {status}"` | Distinguish transient from persistent failure |
| L4 | `ClaudeApiClient` — request exception | ERROR | `"LLM request failed: {exception message}"` | Network errors, DNS failures, timeouts |
| L5 | `ClaudeApiClient` — parse failure | ERROR | `"LLM response parse error: {detail}"` | Malformed JSON, missing fields, unexpected structure |
| L6 | `MessageRouter` — domain error returned to user | WARN | `"Domain error for user {userId}: {error class} — {message}"` | Track which errors users actually hit |
| L7 | `TelegramBotAdapter` — webhook exception | ERROR | `"Webhook processing failed: {exception message}"` | Currently completely invisible |
| L8 | `TelegramBotAdapter` — send/document failures | WARN | `"Telegram send failed: {TgResult.Err code} {description}"` | Know when messages aren't reaching users |
| L9 | `ResendEmailAdapter` — delivery failure | WARN | `"Email delivery failed: HTTP {status} body={body}"` | Diagnose bounces, API key issues |
| L10 | `SireneApiClient` — lookup failure | WARN | `"SIRENE lookup failed for {siren}: {reason}"` | INSEE API issues |
| L11 | `App.kt` — scheduled job exception | ERROR | `"Job {name} failed: {exception message}"` | Replace the 4 empty catch blocks |
| L12 | `App.kt` — startup complete | INFO | `"Mona started — webhook={url}, db={path}"` | Confirm the bot is alive after deploy |
| L13 | `App.kt` — shutdown | INFO | `"Mona shutting down"` | Distinguish intentional stop from crash |

### 3.2 What NOT to Log

- **Message content.** Never log user messages, invoice data, client names, IBANs, SIRENs, or any PII. Log user IDs (opaque `UserId`) only.
- **Successful operations.** No `INFO: Invoice created`, `INFO: Message sent`. The happy path is silent.
- **LLM prompts or responses.** Contains user data. The reason string in `LlmUnavailable` is safe (HTTP status codes, parse errors) — log that, not the payload.
- **Full HTTP response bodies.** Truncate to 500 chars max to avoid flooding logs with HTML error pages.

---

## 4. Logger Pattern

One logger per class, using SLF4J directly:

```kotlin
import org.slf4j.LoggerFactory

class ClaudeApiClient(/* ... */) {
    private val log = LoggerFactory.getLogger(ClaudeApiClient::class.java)

    // Usage:
    log.warn("LLM error: HTTP {} body={}", response.statusCode, response.body.take(500))
    log.error("LLM request failed: {}", e.message)
}
```

Rules:
- Use `LoggerFactory.getLogger(ClassName::class.java)` — no string names, no companion objects.
- Use SLF4J parameterized messages (`{}` placeholders) — no string concatenation.
- `val log` is a private instance field, not top-level or companion. Keeps it simple, avoids companion init ordering issues.
- Loggers live in **infrastructure** and **application** layers only. **Domain has no logging** (stays pure).

---

## 5. Implementation — File by File

### 5.1 `build.gradle.kts`

Add SLF4J dependencies:

```kotlin
implementation("org.slf4j:slf4j-api:2.0.16")
implementation("org.slf4j:slf4j-simple:2.0.16")
```

### 5.2 `ClaudeApiClient.kt` — L1, L2, L3, L4, L5

Replace the silent `DomainResult.Err` returns with log-then-return:

```kotlin
// L1: Non-200, non-rate-limit
log.warn("LLM error: HTTP {} body={}", response.statusCode, response.body.take(500))
return DomainResult.Err(DomainError.LlmUnavailable("HTTP ${response.statusCode}: ${response.body}"))

// L2: Rate limit retry
log.warn("LLM rate-limited ({}), retry {}/{} in {}ms", response.statusCode, attempt + 1, MAX_RETRIES, delayMs)

// L3: Retries exhausted
log.error("LLM unavailable after {} retries: HTTP {}", MAX_RETRIES, lastResponse!!.statusCode)

// L4: Request exception (in catch block)
log.error("LLM request failed: {}", e.message)

// L5: Parse failures (in parseResponse)
log.error("LLM response parse error: {}", detail)
```

### 5.3 `MessageRouter.kt` — L6

At the `DomainResult.Err` branch (line 255) and in `formatDomainError`:

```kotlin
is DomainResult.Err -> {
    log.warn("Domain error for user {}: {}", user.id, llmResult.error.message)
    Pair("Je suis momentanément indisponible — réessaie dans quelques minutes 🔄", emptyList())
}
```

Also log when `formatDomainError` is called from other error-handling sites in the router (the various `when (result)` branches that call `formatDomainError`). One log line per `Err` branch is sufficient — log at the `formatDomainError` call site, not inside the function itself.

### 5.4 `TelegramBotAdapter.kt` — L7, L8

```kotlin
// L7: Webhook exception (replace empty catch)
} catch (e: Exception) {
    log.error("Webhook processing failed: {}", e.message)
    runCatching { /* 200 response */ }
}

// L8: After sendMessage/sendDocument when TgResult is Err
// Add logging in the MessagingPort-implementing methods
```

### 5.5 `ResendEmailAdapter.kt` — L9

```kotlin
is ResendResult.Failure -> {
    log.warn("Email delivery failed: HTTP {} body={}", result.statusCode, result.body.take(500))
    DomainResult.Err(DomainError.EmailDeliveryFailed(result.statusCode, result.body))
}
```

### 5.6 `SireneApiClient.kt` — L10

```kotlin
else -> {
    log.warn("SIRENE lookup failed for {}: HTTP {}", siren.value, response.statusCode)
    DomainResult.Err(DomainError.SireneLookupFailed("HTTP ${response.statusCode}"))
}
```

### 5.7 `App.kt` — L11, L12, L13

```kotlin
// L12: After start()
log.info("Mona started — webhook={}, db={}", webhookUrl, dbPath)

// L11: Replace all 4 empty catch blocks
} catch (e: Exception) {
    log.error("Job {} failed: {}", "overdue-transition", e.message)
}

// L13: Shutdown hook
Runtime.getRuntime().addShutdownHook(Thread {
    log.info("Mona shutting down")
})
```

---

## 6. Fly.io Integration

No changes needed. `slf4j-simple` writes to stderr by default. Fly.io captures stderr via `fly logs`. To view:

```bash
fly logs              # Live tail
fly logs --app mona   # Explicit app name
```

Filter by level in the terminal:

```bash
fly logs | grep ERROR
fly logs | grep "LLM"
```

---

## 7. Out of Scope

| Topic | Why deferred |
|-------|-------------|
| Structured JSON logging | No log aggregator yet; human-readable stderr is enough for `fly logs` |
| Log file rotation | Fly.io manages log capture; no local files |
| Metrics / counters | Separate concern; would need Prometheus or similar |
| Request tracing / correlation IDs | Single-instance, single-user-at-a-time; not needed yet |
| Alerting | Fly.io has basic log alerting; configure separately if needed |
| DEBUG-level logging | Add when needed for specific investigations; not worth the noise by default |

---

## 8. Validation

After implementation:

1. `./gradlew build` — compiles, tests pass.
2. `./gradlew shadowJar` — fat JAR includes slf4j-api + slf4j-simple.
3. Deploy to Fly.io, send a message, verify `fly logs` shows `INFO ... Mona started`.
4. Temporarily break the Claude API key → verify `fly logs` shows the `ERROR ... LLM unavailable` line with the actual HTTP status.
5. Restore the key → verify the happy path produces **no log lines** (silent success).
