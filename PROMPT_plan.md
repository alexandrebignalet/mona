# Plan Prompt — Mona v1

This prompt governs planning. Every "MUST" is a hard gate.

---

## Phase 0 — Context Loading

**Do not produce any plan items until Phase 0 is complete.**

### 0a. Skip CLAUDE.md

CLAUDE.md is already injected into system context — do not re-read it.

### 0b. Read IMPLEMENTATION_PLAN.md

Read it to understand the plan so far. It may be outdated — verify against code.

### 0c. Read relevant specs

Based on IMPLEMENTATION_PLAN.md's next priorities, read the spec most relevant:
- `specs/mvp-spec.md` — product features, UX flows, conversation design
- `specs/tech-spec.md` — architecture, data model, integrations

Read both only if the plan spans product and technical concerns.

### 0d. Verify source layout

```bash
ls src/main/kotlin/mona/ 2>/dev/null || echo "No source yet"
```

If layout contradicts CLAUDE.md, document as the first plan item.

### 0e. Verify validation toolchain (skip if recently confirmed passing)

Only run if there is reason to suspect the toolchain is broken:

```bash
./gradlew build && ./gradlew ktlintCheck
```

If any command is missing or fails, add a plan item to fix it first.

---

## Phase 1 — Research Existing Code

**Ultrathink** before beginning research.

Use parallel Read/Grep/Glob calls to study existing source code relevant to incomplete plan items.
Use an Explore subagent only when the scope is genuinely open-ended (e.g. "find all places that touch invoice status") and you cannot name the target files up front.

For each area studied, verify:

1. **Architecture layer compliance** — code in the correct layer per CLAUDE.md?
2. **Tech stack compliance** — only approved dependencies?
3. **Kotlin strictness** — no `Any`, no unchecked casts, value classes used for domain concepts?
4. **Domain purity** — domain/ has zero framework imports?
5. **Completeness** — TODOs, placeholders, skipped tests, stubs?

---

## Phase 2 — Produce the Plan

Use an **Opus subagent with ultrathink** to analyze findings and update @IMPLEMENTATION_PLAN.md.

### Compliance gate (BLOCKING)

Before writing any plan item, verify:

- [ ] **Layer assignment is explicit.** Every item specifies which architecture layer (domain/application/infrastructure).
- [ ] **No unapproved tech.** Check CLAUDE.md Tech Stack table.
- [ ] **Domain purity maintained.** No invoice logic in Telegram handlers or Exposed repositories.
- [ ] **Application layer is thin.** Orchestration only, max ~30 lines per use case.
- [ ] **Validation included.** Every item includes `./gradlew build && ./gradlew ktlintCheck`.
- [ ] **Structural violations addressed first.**

### Plan item format

Each item must include: what to implement, architecture layer(s), spec reference (section number), acceptance criteria.

### Golden test audit

For each plan item that touches LLM integration or adds a new user action, include a sub-item:

```
- [ ] Golden tests: <what to add/update> (parsing cases / context resolution / edge cases)
```

This ensures LLM test coverage is planned alongside the feature, not forgotten.

### If a new spec is needed

Search first to confirm it doesn't exist, then author at `specs/FILENAME.md`. Document the plan in @IMPLEMENTATION_PLAN.md.

---

## Phase 3 — Commit & Push

After updating IMPLEMENTATION_PLAN.md (and any new/updated specs):

```bash
git add -A && git commit -m "<description>" && git push
```

---

## Rules

- **Plan only.** Do NOT implement or modify source code.
- **Do not assume functionality is missing.** Verify with code search.
- **CLAUDE.md is authoritative.** Plan fixes for violations.
- **File paths must match the architecture structure.**
- **Clean out completed items** from IMPLEMENTATION_PLAN.md to keep it lean.
- **Prune prevention rules** that are now encoded in code or tests (e.g., a test guards against the failure, or the code structure prevents it). Rules should trend toward empty, not accumulate.

---

## Ultrathink Checkpoints

1. Before beginning code research (Phase 1).
2. Inside the Opus subagent that produces the plan (Phase 2).
3. Before resolving any discrepancy between CLAUDE.md and the codebase.
