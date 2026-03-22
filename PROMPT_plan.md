# Plan Prompt — v3 (Token-Optimized)

This prompt governs planning. Every "MUST" is a hard gate.

---

## Phase 0 — Context Loading

**Do not produce any plan items until Phase 0 is complete.**

### 0a. Skip CLAUDE.md

CLAUDE.md is already injected into system context — do not re-read it.

### 0b. Read IMPLEMENTATION_PLAN.md

Read it to understand the plan so far. It may be outdated — verify against code.

### 0c. Read specs/README.md, then relevant specs only

Read `specs/README.md` — it contains summaries and topic lists for every spec file. Based on IMPLEMENTATION_PLAN.md's next priorities, use the README to identify which spec files are relevant, then read **only those**. Do not read all specs.

### 0d. Verify source layout

```bash
ls src/
```

If layout contradicts CLAUDE.md, document as the first plan item.

### 0e. Verify validation toolchain (skip if recently confirmed passing)

Only run if there is reason to suspect the toolchain is broken:

```bash
npm run typecheck && npm run lint && npm run test
```

If any command is missing or fails, add a plan item to fix it first.

---

## Phase 1 — Research Existing Code

**Ultrathink** before beginning research.

Use parallel Read/Grep/Glob calls to study existing source code relevant to incomplete plan items.
Use an Explore subagent only when the scope is genuinely open-ended (e.g. "find all places that touch game state") and you cannot name the target files up front.

For each area studied, verify:

1. **DDD layer compliance** — code in the correct layer per CLAUDE.md?
2. **Tech stack compliance** — only approved dependencies?
3. **TypeScript strictness** — no `any`, no unjustified `as` casts?
4. **Completeness** — TODOs, placeholders, skipped tests, stubs?

---

## Phase 2 — Produce the Plan

Use an **Opus subagent with ultrathink** to analyze findings and update @IMPLEMENTATION_PLAN.md.

### Compliance gate (BLOCKING)

Before writing any plan item, verify:

- [ ] **Layer assignment is explicit.** Every item specifies which DDD layer.
- [ ] **No unapproved tech.** Check CLAUDE.md Tech Stack table.
- [ ] **Domain purity maintained.** No game logic in handlers.
- [ ] **Application layer is thin.** Orchestration only.
- [ ] **Validation included.** Every item includes `npm run typecheck && npm run lint && npm run test`.
- [ ] **Structural violations addressed first.**

### Plan item format

Each item must include: what to implement, DDD layer(s), spec reference, acceptance criteria.

### Bombadil coverage audit

For each plan item that touches UI or game flow, include a sub-item:

```
- [ ] Bombadil: <what to add/update> (action generator / extractor / property)
```

This ensures coverage work is planned alongside the feature, not forgotten.

### Property removal governance

If a plan item proposes removing or weakening a Bombadil property, it must include:

1. **Why** the property is no longer valid.
2. **What replaces it** (a new property, a unit test, or an explicit "this invariant no longer holds because X").
3. Approval note in the commit message: `bombadil: remove P<N> — <reason>`.

### If a new spec is needed

Search first to confirm it doesn't exist, then author at `specs/FILENAME.md`. **You MUST also add an entry to `specs/README.md`** following the existing format (scope, summary, topics). Document the plan in @IMPLEMENTATION_PLAN.md.

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
- **File paths must match the DDD structure.**
- **Clean out completed items** from IMPLEMENTATION_PLAN.md to keep it lean.
- **Prune prevention rules** that are now encoded in code or tests (e.g., a test guards against the failure, or the code structure prevents it). Rules should trend toward empty, not accumulate.

---

## Ultrathink Checkpoints

1. Before beginning code research (Phase 1).
2. Inside the Opus subagent that produces the plan (Phase 2).
3. Before resolving any discrepancy between CLAUDE.md and the codebase.
