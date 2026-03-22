# Build Prompt — v3 (Token-Optimized)

This prompt governs implementation. Every "MUST" is a hard gate.

---

## Phase 0 — Context Loading

**Do not write any code until Phase 0 is complete.**

### 0a. Read IMPLEMENTATION_PLAN.md

CLAUDE.md is already injected into system context — do not re-read it.
Read only IMPLEMENTATION_PLAN.md directly via the Read tool.

### 0b. Read specs/README.md, then relevant specs only

Read `specs/README.md` — it contains summaries and topic lists for every spec file. Based on the plan item you will implement, use the README to identify which spec files are relevant, then read **only those**. Do not read all specs.

### 0c. Verify source layout

```bash
ls src/
```

If layout contradicts CLAUDE.md, resolve before proceeding.

---

## Phase 1 — Select and Plan Work

**Ultrathink** before selecting which IMPLEMENTATION_PLAN.md item to work on.

Verify current state using parallel Read tool calls (with offset/limit for large files).
Use Grep for symbol/pattern searches across unknown files.
Use an Explore subagent only when you need to search across many files without knowing their paths in advance.

**Critical:** Do NOT use subagents to read files you will later edit — the Edit tool requires a
prior Read in the same conversation; a subagent's read does not satisfy this, causing a double-read.

---

## Phase 2 — Implement

### Backpressure checklist (BLOCKING — before creating or modifying any file)

Answer the 6 questions from CLAUDE.md. Only write them out when an answer is "no" or unclear. If all clear, proceed.

### Opus subagent triggers (MANDATORY)

Delegate to an **Opus subagent with ultrathink** for:

- Layer assignment ambiguity
- New file structure or directory patterns
- New dependency not in Tech Stack
- Cross-cutting infrastructure decisions
- Debugging after > 1 failed attempt
- Spec inconsistency

### UI Stability rule (same weight as DDD — applies to all game components)

**Game components must not receive raw socket state as props. They consume stable slices from the adapter hook (`useStableGameState`).** See `specs/13-ui-stability.md`.

### DDD layer enforcement (after writing any file)

Verify no DDD violations per CLAUDE.md rules. Domain files must have zero framework imports.

### Prevention rules check (before writing code)

Scan the **Prevention Rules** table in IMPLEMENTATION_PLAN.md (already loaded from Phase 0). Apply any relevant rules to the code you are about to write.

### Implementation rules

- Implement completely. No placeholders, stubs, or TODOs.
- No `any` types, no `as` casts unless justified.
- Use Read/Grep/Glob directly for all file reads and searches.
- Use an Explore subagent only for open-ended multi-file exploration where target files are unknown.
- Use 1 subagent for build/test (Phase 3).

### Bombadil coverage check

**Applies when any changed file matches these paths:**

- `src/components/`
- `src/infrastructure/ws/`
- `src/routes/`
- `src/application/game/`
- `bombadil/`

Before proceeding to Phase 3, answer:

| Question | Action if "yes" |
|----------|----------------|
| Does this change add a new user-visible UI element or interaction? | Add or extend a Bombadil action generator in `bombadil/lib/actions.ts` to exercise it. |
| Does this change add a new piece of game state visible in the DOM? | Add or extend an extractor in `bombadil/lib/extractors.ts` to capture it. |
| Does this change introduce a new invariant the UI must uphold? | Add a new property in `bombadil/spec.ts` (next P-number). |
| Does this change modify an existing UI element's `data-testid` or structure? | Update the corresponding selector in `bombadil/lib/selectors.ts` and verify affected properties still compile. |

If all answers are "no", skip — no Bombadil changes needed.

---

## Phase 3 — Test (BLOCKING — before any commit)

**Delegate to an Explore subagent:**

> 1. Write unit tests for new domain services/functions/modules.
> 2. Run: `npm run typecheck && npm run lint && npm run test`
> 3. Return structured result: Status (PASS/FAIL), per-command results, new tests written.

Do not proceed to Phase 4 until Status: PASS.

### Bombadil compilation gate (always, not conditional)

After unit tests pass, run:

```bash
npx tsc --noEmit --project bombadil/tsconfig.json 2>/dev/null || npx tsc --noEmit
```

If the Bombadil spec files have TypeScript errors, fix them before proceeding to Phase 4. This catches broken extractors/selectors from refactored UI without requiring a full Bombadil run locally.

> **Note:** A full `npm run test:bombadil` run is NOT required locally — it requires the Bombadil CLI binary and a running server. The compilation check is the local gate; the full run happens in CI.

### Phase 3b — Conditional Bombadil compilation gate

Run `git diff --name-only HEAD~1` to list changed files. If **any** path matches these prefixes, the Bombadil compilation gate above is especially critical:

- `src/components/`
- `src/infrastructure/ws/`
- `src/routes/`
- `src/application/game/`
- `bombadil/`

If only `src/domain/`, `src/infrastructure/db/`, `src/data/`, or non-source files changed, the unconditional gate above still runs but failures are less likely.

Do not proceed to Phase 4 until the Bombadil compilation gate passes.

---

## Phase 4 — Document and Commit

### 4a. Update IMPLEMENTATION_PLAN.md (SYNCHRONOUS)

Update with: what was implemented, issues found, remaining work. **Clean out completed items** to keep the file lean.

### 4b. Commit and push

```bash
git add -A && git commit -m "<description>" && git push
```

### 4c. Tag if clean

If zero build/test errors, create a git tag. Determine the next tag by running:

```bash
git tag --sort=version:refname | tail -1
```

Increment the patch number from the output (e.g. `0.0.12` → `0.0.13`). If no tags exist, start at `0.0.1`. Use no `v` prefix.

```bash
git tag <next-tag> && git push origin <next-tag>
```

---

## Issue Discovery Protocol

1. Document in IMPLEMENTATION_PLAN.md (blocking).
2. Fix it.
3. Mark resolved in IMPLEMENTATION_PLAN.md.

---

## Spec Inconsistency Protocol

1. STOP implementation.
2. Opus subagent with ultrathink to resolve.
3. Apply fix, then resume.

---

## Ultrathink Checkpoints

1. Before selecting work item (Phase 1).
2. Inside every Opus subagent invocation.
3. Before resolving any spec inconsistency.
