# Plan Prompt — Mona v1

This prompt governs planning. Every "MUST" is a hard gate.

**Core job: compare specs against code, identify every gap, and produce a prioritized implementation plan from that delta.**

---

## Phase 0 — Context Loading

**Do not produce any plan items until Phase 0 is complete.**

### 0a. Skip CLAUDE.md

CLAUDE.md is already injected into system context — do not re-read it.

### 0b. Read IMPLEMENTATION_PLAN.md

Read it for context on what has already been completed. **Do not treat it as the source of truth for what remains** — the spec-vs-code diff is authoritative. The plan may be stale or incomplete.

### 0c. Read specs (parallel)

List `specs/*` and read all relevant specs using parallel subagents. When in doubt, read it — reading too much is better than missing context.

### 0d. Study source code (parallel)

Use parallel subagents to study existing source across all architecture layers (domain, application, infrastructure) and tests. Let the subagents discover the file structure — do not hardcode paths.

### 0e. Verify source layout

```bash
ls src/main/kotlin/mona/ 2>/dev/null || echo "No source yet"
```

If layout contradicts CLAUDE.md, document as the first plan item.

### 0f. Verify validation toolchain (skip if recently confirmed passing)

Only run if there is reason to suspect the toolchain is broken:

```bash
./gradlew build && ./gradlew ktlintCheck
```

If any command is missing or fails, add a plan item to fix it first.

---

## Phase 1 — Spec-vs-Code Diff

**Ultrathink** before beginning research.

This is the core planning activity. For each spec, systematically compare what the spec requires against what the code implements. **The gap is the plan.**

Use parallel subagents to search the codebase. Use Explore subagents only when scope is genuinely open-ended and you cannot name the target files up front.

### 1a. Find unimplemented features

For each feature, flow, or rule described in specs:

- Does corresponding code exist? (use case, domain logic, handler, adapter)
- Is it complete or only partially stubbed?
- Are there spec-described flows with no corresponding use case or handler?
- Are there domain rules in the spec not enforced in code?

**Do not assume functionality is missing — confirm with code search.** Equally, do not assume it exists just because a file is named correctly — read the implementation.

### 1b. Find flagged gaps

Search for work the developers already marked:

- TODO / FIXME / HACK comments
- Placeholder or minimal implementations
- Skipped or flaky tests
- Inconsistent patterns across modules
- Dead code or unused stubs

### 1c. Compliance audit

For each area of existing code, verify:

1. **Architecture layer compliance** — code in the correct layer per CLAUDE.md?
2. **Tech stack compliance** — only approved dependencies?
3. **Kotlin strictness** — no `Any`, no unchecked casts, value classes used for domain concepts?
4. **Domain purity** — domain/ has zero framework imports?
5. **Duplicated logic** — same concern implemented in multiple places instead of consolidated?

---

## Phase 2 — Produce the Plan

Use an **Opus subagent with ultrathink** to synthesize all findings and update @IMPLEMENTATION_PLAN.md.

The plan is rebuilt from evidence (Phase 1 findings), not incrementally patched from the previous plan. Completed items are removed. New gaps are added.

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
- **Specs are authoritative.** The spec-vs-code delta defines the work.
- **CLAUDE.md is authoritative for architecture.** Plan fixes for violations.
- **Do not assume.** Verify presence AND completeness with code search.
- **File paths must match the architecture structure.**
- **Clean out completed items** from IMPLEMENTATION_PLAN.md to keep it lean.
- **Prune prevention rules** that are now encoded in code or tests.

---

## Subagent Strategy

| Task | Agent | Why |
|------|-------|-----|
| Reading specs (Phase 0c) | Parallel Sonnet/Explore | Fast, parallelizable reads |
| Studying source (Phase 0d) | Parallel Sonnet/Explore | Fast, parallelizable reads |
| Searching for gaps (Phase 1) | Parallel Sonnet/Explore | Independent per-spec searches |
| Synthesizing plan (Phase 2) | Single Opus with ultrathink | Requires judgment and prioritization |

---

## Ultrathink Checkpoints

1. Before beginning code research (Phase 1).
2. Inside the Opus subagent that produces the plan (Phase 2).
3. Before resolving any discrepancy between spec and codebase.
