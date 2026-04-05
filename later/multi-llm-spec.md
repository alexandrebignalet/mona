# Mona — Multi-LLM Provider Specification

Add an OpenAI-compatible LLM adapter alongside the existing Claude adapter. Selection is config-driven. The first additional provider is Mistral NeMo 12B (free tier), but the adapter supports any OpenAI-compatible API (Groq, DeepSeek, etc.).

---

## 1. Motivation

Claude Sonnet is the primary LLM and the quality baseline. However:

- **Cost** — every user message costs API tokens. Mistral NeMo 12B offers 1 billion tokens/month free via La Plateforme.
- **Resilience** — a second provider means Mona can keep working during Anthropic outages.
- **Flexibility** — an OpenAI-compatible adapter unlocks Groq (Llama 3.3 70B), DeepSeek V4, Cerebras, and others with zero additional code.

This is **not a replacement** for Claude. It is an additional implementation of `LlmPort` selected at startup via configuration.

---

## 2. Architecture

### 2.1 Current State

```
LlmPort (interface)
  └── ClaudeApiClient (only implementation)
```

`App.kt` creates `ClaudeApiClient.fromEnv()` and injects it into `MessageRouter`.

### 2.2 Target State

```
LlmPort (interface)
  ├── ClaudeApiClient          — Anthropic API (existing, unchanged)
  └── OpenAiCompatibleClient   — OpenAI-compatible API (new)
```

`App.kt` reads `LLM_PROVIDER` env var and instantiates the corresponding implementation.

### 2.3 What Does NOT Change

| Component | Why |
|-----------|-----|
| `LlmPort` interface | Already provider-agnostic |
| `LlmToolDefinition` | Contains `name`, `description`, `inputSchemaJson` — generic enough |
| `LlmResponse` | `Text` / `ToolUse` sealed class — universal |
| `ActionParser` | Parses tool name + JSON input — provider-independent |
| `ToolDefinitions` | JSON Schema definitions — same schema, different wire format per adapter |
| `PromptBuilder` | Same prompt for all providers (see §5) |
| `MessageRouter` | Receives an `LlmPort`, doesn't care which |
| Domain layer | Zero LLM awareness |

---

## 3. Configuration

### 3.1 Environment Variables

| Variable | Required | Default | Description |
|----------|----------|---------|-------------|
| `LLM_PROVIDER` | No | `claude` | Provider selection: `claude`, `mistral`, `groq`, `deepseek`, or any OpenAI-compatible key |
| `ANTHROPIC_API_KEY` | If `claude` | — | Existing. Unchanged. |
| `OPENAI_COMPAT_API_KEY` | If not `claude` | — | API key for the selected OpenAI-compatible provider |
| `OPENAI_COMPAT_BASE_URL` | If not `claude` | — | Base URL (e.g., `https://api.mistral.ai/v1`) |
| `OPENAI_COMPAT_MODEL` | If not `claude` | — | Model ID (e.g., `open-mistral-nemo`, `llama-3.3-70b-versatile`) |

### 3.2 Provider Presets

For convenience, `LLM_PROVIDER` values `mistral`, `groq`, and `deepseek` auto-populate base URL and default model if not explicitly overridden:

| `LLM_PROVIDER` | Default Base URL | Default Model |
|-----------------|-----------------|---------------|
| `mistral` | `https://api.mistral.ai/v1` | `open-mistral-nemo` |
| `groq` | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| `deepseek` | `https://api.deepseek.com/v1` | `deepseek-chat` |
| `openai-compat` | Must set `OPENAI_COMPAT_BASE_URL` | Must set `OPENAI_COMPAT_MODEL` |

### 3.3 Wiring in App.kt

```kotlin
val llmPort: LlmPort = when (System.getenv("LLM_PROVIDER") ?: "claude") {
    "claude" -> ClaudeApiClient.fromEnv()
    else -> OpenAiCompatibleClient.fromEnv()
}
```

---

## 4. OpenAI-Compatible Adapter

### 4.1 File Location

`src/main/kotlin/mona/infrastructure/llm/OpenAiCompatibleClient.kt`

### 4.2 Wire Format Translation

The adapter translates between Mona's `LlmToolDefinition` and the OpenAI function-calling wire format:

**Mona's LlmToolDefinition:**
```json
{ "name": "create_invoice", "description": "...", "inputSchemaJson": "{...}" }
```

**OpenAI wire format (used by Mistral, Groq, DeepSeek):**
```json
{
  "type": "function",
  "function": {
    "name": "create_invoice",
    "description": "...",
    "parameters": { ... }
  }
}
```

**Message format translation:**

| Mona | OpenAI-compatible |
|------|-------------------|
| `system` prompt | `{ "role": "system", "content": "..." }` |
| `ConversationMessage(role=USER)` | `{ "role": "user", "content": "..." }` |
| `ConversationMessage(role=ASSISTANT)` | `{ "role": "assistant", "content": "..." }` |

**Response parsing:**

The adapter detects tool calls in the OpenAI response format:

```json
{
  "choices": [{
    "message": {
      "tool_calls": [{
        "id": "call_abc",
        "type": "function",
        "function": { "name": "create_invoice", "arguments": "{...}" }
      }]
    }
  }]
}
```

Maps to `LlmResponse.ToolUse(toolName, toolUseId, inputJson)`. If no tool calls, maps `content` to `LlmResponse.Text`.

### 4.3 Retry & Validation

The adapter implements two layers of retry:

**Layer 1 — HTTP retry (same as Claude adapter):**
- Retry on 429 (rate limit) and 5xx (server error)
- 4 attempts, exponential backoff: 2s → 4s → 8s

**Layer 2 — Output validation retry (new, specific to weaker models):**
- After a successful HTTP response, validate the tool call output:
  1. Response contains a `tool_calls` entry (not just free-form text when a tool was expected)
  2. Tool name is one of the known tools in `ToolDefinitions.all`
  3. `arguments` field is valid JSON (parseable by `kotlinx.serialization`)
- On validation failure: retry the LLM request (up to 2 additional attempts)
- After exhausting retries: return `DomainResult.Err(DomainError.LlmOutputInvalid)`
- Log every validation retry with the raw response for debugging

**Total worst case:** 4 HTTP retries × 3 validation attempts = 12 API calls per user message. In practice this should be rare — if it isn't, the model is not viable.

### 4.4 Companion Factory

```kotlin
companion object {
    fun fromEnv(): OpenAiCompatibleClient {
        val provider = System.getenv("LLM_PROVIDER") ?: "openai-compat"
        val preset = PRESETS[provider]
        val baseUrl = System.getenv("OPENAI_COMPAT_BASE_URL")
            ?: preset?.baseUrl
            ?: error("OPENAI_COMPAT_BASE_URL required for provider '$provider'")
        val model = System.getenv("OPENAI_COMPAT_MODEL")
            ?: preset?.model
            ?: error("OPENAI_COMPAT_MODEL required for provider '$provider'")
        val apiKey = System.getenv("OPENAI_COMPAT_API_KEY")
            ?: error("OPENAI_COMPAT_API_KEY required")
        return OpenAiCompatibleClient(baseUrl, model, apiKey)
    }
}
```

---

## 5. Prompt Strategy

**Same prompt for all providers.** `PromptBuilder` is not provider-aware.

Rationale:
- Keeps complexity low — one prompt to maintain, one behavior to test
- The prompt is already concise and explicit (tool names, onboarding rules, output expectations)
- If a provider can't handle the current prompt, it's likely not viable for Mona's use case

If golden tests reveal that a specific provider needs prompt adjustments, revisit this decision at that point. The adapter architecture does not preclude provider-specific prompts later.

---

## 6. Domain Error

Add one new error variant:

```kotlin
// In DomainError.kt
data object LlmOutputInvalid : DomainError
```

Used when the adapter exhausts validation retries — the model returned a response but it wasn't a valid tool call or parseable JSON.

---

## 7. Golden Tests

### 7.1 Requirement

Golden tests must run against any configured provider, not just Claude. The test infrastructure should be provider-agnostic.

### 7.2 Approach

- Golden tests already call `LlmPort.complete()` — they test through the port interface
- Test configuration reads `LLM_PROVIDER` (and associated env vars) to instantiate the correct adapter
- The same test cases, same assertions, same pass/fail criteria apply regardless of provider
- CI workflow `golden-tests.yml` gains optional `llm_provider` input parameter (defaults to `claude`)

### 7.3 Running Golden Tests by Provider

```bash
# Against Claude (default, existing behavior)
LLM_PROVIDER=claude ANTHROPIC_API_KEY=sk-... ./gradlew test --tests "mona.golden.*"

# Against Mistral NeMo
LLM_PROVIDER=mistral OPENAI_COMPAT_API_KEY=... ./gradlew test --tests "mona.golden.*"

# Against Groq
LLM_PROVIDER=groq OPENAI_COMPAT_API_KEY=... ./gradlew test --tests "mona.golden.*"
```

### 7.4 CI Workflow Update

Add `llm_provider` as an optional `workflow_dispatch` input in `golden-tests.yml`:

```yaml
inputs:
  llm_provider:
    description: 'LLM provider to test against'
    required: false
    default: 'claude'
    type: choice
    options:
      - claude
      - mistral
      - groq
      - deepseek
```

The workflow sets `LLM_PROVIDER` and the corresponding API key secret before running tests.

---

## 8. Implementation Plan

### Phase 1 — Adapter & wiring

1. Add `LlmOutputInvalid` to `DomainError`
2. Create `OpenAiCompatibleClient` implementing `LlmPort`
   - Wire format translation (tools, messages, response)
   - HTTP retry (429/5xx)
   - Output validation retry (malformed JSON, unknown tool name)
3. Update `App.kt` to select provider via `LLM_PROVIDER` env var
4. Unit test the adapter with mock HTTP responses

### Phase 2 — Golden test infrastructure

5. Make golden test setup read `LLM_PROVIDER` to instantiate the correct adapter
6. Update `golden-tests.yml` with `llm_provider` input
7. Add Mistral API key as GitHub Actions secret

### Phase 3 — Validation

8. Run golden tests against Mistral NeMo — measure pass rate
9. Run golden tests against Groq (Llama 3.3 70B) — compare
10. Document results and decide on production readiness

---

## 9. Known Risks

| Risk | Mitigation |
|------|-----------|
| NeMo 12B too weak for 20-tool orchestration | Golden tests will surface this. Fallback: Groq Llama 3.3 70B (larger model, also free) |
| Mistral free tier rate limit (1 RPS) | Sufficient for current user base. Monitor and alert if approaching |
| Prompt needs provider-specific tuning | Start with same prompt. Only diverge if golden tests fail significantly |
| OpenAI-compatible API subtleties between providers | Test each provider independently. Adapter handles response parsing defensively |
| Free tier disappears or terms change | Config-driven — switch provider with one env var change, no code change |
