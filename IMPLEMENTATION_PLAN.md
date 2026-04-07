# Mona v1 — Implementation Plan

This plan is ordered by dependency: each item builds on what came before. Items are sized for meaningful commits. Every item ends with `./gradlew build && ./gradlew ktlintCheck` passing.

---

## Completed Phases

Phases 1–24 complete. See git log for details.

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

## Prevention Rules

1. **No unapproved libraries.** Check CLAUDE.md tech stack before adding any dependency.
2. **Golden tests gate prompt changes.** Never deploy a prompt change without running the golden suite.
3. **URSSAF = cash basis.** Revenue computed from `paidDate`, never `issueDate`.
4. **Last 3 messages only.** No separate "last action" pointer. LLM resolves from conversation history.
