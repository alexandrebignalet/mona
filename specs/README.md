# Specs Index

Use this file to resolve which spec to read for a given implementation task. Read a spec only when the task requires details not already in `IMPLEMENTATION_PLAN.md`.

| File | Scope | Plan Phases |
|------|-------|-------------|
| `mvp-spec.md` | Product features, UX flows, conversation design, onboarding, invoicing, payments, URSSAF | 1–13 |
| `tech-spec.md` | Architecture, data model, DDD patterns, integrations, encryption, scheduling | 1–13 |
| `ci-cd-spec.md` | GitHub Actions pipelines, build validation, golden tests, deployment | 14 |
| `sirene-oauth-spec.md` | INSEE SIRENE API OAuth2 client credentials flow, token lifecycle | 15 |
| `loop-efficiency-spec.md` | Automated build loop token efficiency, context reduction strategies | 16 |
| `telegram-direct-api-spec.md` | Replace TelegramBotAPI library with direct HTTP + webhook | 19 |
| `logging-spec.md` | Minimal structured logging (SLF4J/Logback) | 20+ |
| `cqrs-spec.md` | Generic CQRS framework module — command/query buses, middleware chains, event dispatching | 20+ |
| `integration-tests-spec.md` | Integration test suite — every command, query, and background job tested with real infra, no LLM | — |
