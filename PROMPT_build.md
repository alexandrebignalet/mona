# Build Prompt — Mona v1

This prompt governs the automated build loop. Code rules live in CLAUDE.md.

**SESSION SCOPE: Pick exactly ONE task from IMPLEMENTATION_PLAN.md, complete it fully, commit, and terminate. Do not start a second task. Do not ask the user what to work on.**

---

## Headless Output Discipline

You are running in a headless automated loop. Output only:
- Tool calls (file reads, edits, writes, bash commands)
- Commit messages
- A single JSON status line after each major action:
  {"action": "edit", "file": "path/to/file.kt", "reason": "brief reason"}
- Error diagnostics when something fails

Runaway loop prevention:
- Run `./gradlew ktlintFormat` before `./gradlew ktlintCheck` to avoid lint-fix-lint cycles.
- If you cannot resolve an issue within 3 attempts, commit partial work and document the blocker.

---

## Phase 0 — Context Loading

**Do not write any code until Phase 0 is complete.**

1. Read `IMPLEMENTATION_PLAN.md` (CLAUDE.md is already in system context).
2. Read a spec only when the plan item lacks detail. Use `specs/README.md` to find the right file.
3. Run `ls src/main/kotlin/mona/ 2>/dev/null || echo "No source yet"` — resolve if layout contradicts CLAUDE.md.

---

## Phase 1 — Select Exactly One Task

**Ultrathink** to select the first incomplete item whose dependencies are all satisfied.

One task, one commit. Do not combine tasks.

Verify current state using parallel Read tool calls. Use Grep for symbol searches.
Use an Explore subagent only for open-ended multi-file exploration.

**Critical:** Do NOT use subagents to read files you will later edit — the Edit tool requires a prior Read in the same conversation.

---

## Phase 2 — Implement

1. **Backpressure checklist** (CLAUDE.md) — answer before creating or modifying any file. Write out only if an answer is unclear.
2. **Prevention rules** — scan the table in IMPLEMENTATION_PLAN.md. Apply relevant rules.
3. **Implement completely.** No placeholders, stubs, or TODOs. No `Any` types.
4. **After writing any file**, verify Hard Rules and Architecture Rules from CLAUDE.md. Fix violations before proceeding.
5. **Golden tests** — if changed files match the paths in CLAUDE.md §Specs, read `specs/golden-tests.md` and follow it.

### Opus subagent triggers (MANDATORY)

Delegate to an **Opus subagent with ultrathink** for:
- Layer assignment ambiguity
- New file structure or directory patterns
- New dependency not in Tech Stack
- Cross-cutting infrastructure decisions
- Debugging after > 1 failed attempt
- Spec inconsistency
- Aggregate boundary questions
- New domain event design
- DomainError hierarchy changes

### Tooling rules

- Use Read/Grep/Glob directly for all file reads and searches.
- Use an Explore subagent only when target files are unknown.
- Use 1 subagent for build/test (Phase 3).

---

## Phase 3 — Test (BLOCKING — before any commit)

**Delegate to an Explore subagent:**

> 1. Write unit tests for new domain services/functions.
> 2. Run: `./gradlew build && ./gradlew ktlintCheck`
> 3. Return structured result: Status (PASS/FAIL), per-task results, new tests written.

Do not proceed to Phase 4 until Status: PASS.

---

## Phase 4 — Document and Commit

1. **Update IMPLEMENTATION_PLAN.md** — what was implemented, issues found, remaining work. Clean out completed items.
2. **Commit and push:** `git add -A && git commit -m "<description>" && git push`
3. **Tag if clean** — if zero errors, get next tag:
   ```bash
   git tag --sort=version:refname | tail -1
   ```
   Increment patch (e.g. `0.0.12` → `0.0.13`). No `v` prefix.
   ```bash
   git tag <next-tag> && git push origin <next-tag>
   ```
4. **STOP.** The session is complete.

---

## Protocols

**Issue discovery:** Document in IMPLEMENTATION_PLAN.md → fix → mark resolved.

**Spec inconsistency:** STOP → Opus subagent with ultrathink to resolve → apply fix → resume.

**Ultrathink checkpoints:** (1) Before selecting work item. (2) Inside every Opus subagent. (3) Before resolving spec inconsistency.
