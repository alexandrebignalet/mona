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

## Phase 25 — SIRENE API Key Migration ✓ COMPLETE

Implemented: deleted `SireneTokenProvider.kt` and `SireneTokenProviderTest.kt`, rewrote
`RealSireneHttpExecutor` to use `SireneApiKey` + `X-INSEE-Api-Key-Integration` header,
updated `fromEnv()` to read `SIRENE_API_KEY`, removed all OAuth2/token-refresh code.
Build and ktlintCheck pass.
