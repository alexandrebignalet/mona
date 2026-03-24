# Build Prompt — Mona v1

This prompt governs implementation. Every "MUST" is a hard gate.

**SESSION SCOPE: You MUST pick exactly ONE task from IMPLEMENTATION_PLAN.md, complete it fully, commit, and terminate. Do not start a second task. Do not ask the user what to work on. You decide, you execute, you finish, you stop.**

---

## Headless Output Discipline

You are running in a headless automated loop. Do not explain changes. Do not
summarize what you did. Do not restate the task. Output only:
- Tool calls (file reads, edits, writes, bash commands)
- Commit messages
- A single JSON status line after each major action:
  {"action": "edit", "file": "path/to/file.kt", "reason": "brief reason"}
- Error diagnostics when something fails

Runaway loop prevention (soft guidance — enforced programmatically):
- Run `./gradlew ktlintFormat` before `./gradlew ktlintCheck` to avoid
  lint-fix-lint cycles.
- If you cannot resolve an issue within 3 attempts, commit the partial work
  and document the blocker in the commit message.

---

## Phase 0 — Context Loading

**Do not write any code until Phase 0 is complete.**

### 0a. Read IMPLEMENTATION_PLAN.md

CLAUDE.md is already injected into system context — do not re-read it.
Read only IMPLEMENTATION_PLAN.md directly via the Read tool.

### 0b. Read relevant specs only

Based on the plan item you will implement, read the spec file most relevant to your task:
- `specs/mvp-spec.md` — product features, UX flows, conversation design
- `specs/tech-spec.md` — architecture, data model, integrations

Do not read both unless your task spans product and technical concerns.

### 0c. Verify source layout

```bash
ls src/main/kotlin/mona/ 2>/dev/null || echo "No source yet"
```

If layout contradicts CLAUDE.md, resolve before proceeding.

---

## Phase 1 — Select Exactly One Task

**Ultrathink** to select the single highest-priority task from IMPLEMENTATION_PLAN.md.

Selection criteria (in order):
1. The first incomplete item whose dependencies are all satisfied (all prior items marked done).
2. If multiple items at the same level have satisfied dependencies, pick the one listed first.

**You MUST select exactly one task.** State which task you selected and why. Do not combine tasks. Do not "also do" adjacent items. One task, one commit.

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
- Aggregate boundary questions (should X be inside aggregate Y or its own aggregate?)
- New domain event design (what data should the event carry?)
- DomainError hierarchy changes (adding new error types)

### Architecture layer enforcement (after writing any file)

Verify no architecture violations per CLAUDE.md rules:
- Domain files (`domain/`) must have zero framework imports (no Exposed, no TelegramBotAPI, no HTTP clients, no `java.sql`)
- Domain defines ports (interfaces); infrastructure implements them
- Application layer is thin orchestration only — no business logic
- Infrastructure adapters contain no invoice/payment/revenue logic

### DDD tactical pattern compliance (BLOCKING — after writing any domain/ or application/ file)

Run this checklist against every file you just wrote or modified:

| # | Rule | Violation Example | Fix |
|---|------|-------------------|-----|
| 1 | Domain functions return `DomainResult<T>`, never throw | `fun send(): Invoice { throw ... }` | Return `DomainResult.Err(...)` |
| 2 | No raw primitives for domain concepts | `fun find(id: String)` | Use `InvoiceId`, `UserId`, etc. |
| 3 | State transitions via aggregate methods only | `invoice.copy(status = Sent)` in use case | Call `invoice.send()` |
| 4 | New aggregates use factory method | `Invoice(... status = Draft ...)` in use case | Call `Invoice.create(...)` |
| 5 | Events returned from transitions, not published inline | `messagingPort.send()` inside `Invoice.send()` | Return event in `TransitionResult` |
| 6 | One repository per aggregate root | `CreditNoteRepository` interface | CreditNote is inside Invoice aggregate |
| 7 | Application layer: persist → dispatch → return | Dispatch before persist | Reorder |
| 8 | Read-model snapshots for revenue/URSSAF queries | Passing `List<Invoice>` to `RevenueCalculation` | Use `PaidInvoiceSnapshot` |
| 9 | `Cents` arithmetic uses operator overloads | `Cents(a.value + b.value)` | Use `a + b` (overflow-safe) |

If any violation is found, fix it before proceeding. Do not defer fixes to a later task.

### Prevention rules check (before writing code)

Scan the **Prevention Rules** table in IMPLEMENTATION_PLAN.md (already loaded from Phase 0). Apply any relevant rules to the code you are about to write.

### Implementation rules

- Implement completely. No placeholders, stubs, or TODOs.
- No `Any` types, no unchecked casts unless justified.
- Use value classes (`Cents`, `Siren`, `InvoiceNumber`, `Email`, `InvoiceId`, `ClientId`, `UserId`, etc.) — never raw primitives for domain concepts.
- Use sealed classes for state machines — enforce exhaustive `when` matching.
- All money arithmetic via `Cents` operator overloads (`+`, `-`, `*`). Never `Cents(a.value + b.value)` — use `a + b` for overflow safety.
- Domain functions return `DomainResult<T>`. Never throw from domain layer (except `Cents` arithmetic overflow).
- State transitions only via aggregate methods: `invoice.send()`, `.markPaid()`, `.markOverdue()`, `.cancel()`. Never `invoice.copy(status = ...)` from outside the aggregate.
- New Invoice creation via `Invoice.create()` factory. Data class constructor is for DB reconstitution only.
- Application use cases follow: persist aggregate → dispatch events → return result. Max ~30 lines.
- Events are returned from aggregate transitions in `TransitionResult`, dispatched by the application layer.
- Revenue/URSSAF calculations use read-model snapshots (`PaidInvoiceSnapshot`), not full aggregates.
- Use Read/Grep/Glob directly for all file reads and searches.
- Use an Explore subagent only for open-ended multi-file exploration where target files are unknown.
- Use 1 subagent for build/test (Phase 3).

### Golden test coverage check

**Applies when any changed file matches these paths:**

- `infrastructure/llm/` (prompt or tool definition changes)
- `application/` (new or modified use cases)
- `domain/service/` (new domain logic that LLM tools interact with)

Before proceeding to Phase 3, answer:

| Question | Action if "yes" |
|----------|----------------|
| Does this change add a new Claude tool definition? | Add golden test cases in `test/golden/` covering typical inputs and edge cases. |
| Does this change modify how an existing tool's parameters are parsed? | Update existing golden tests to match new parameter schema. |
| Does this change add a new user-facing action type? | Add ≥3 golden test cases: happy path, edge case, French slang variant. |

If all answers are "no", skip — no golden test changes needed.

---

## Phase 3 — Test (BLOCKING — before any commit)

**Delegate to an Explore subagent:**

> 1. Write unit tests for new domain services/functions.
> 2. Run: `./gradlew build && ./gradlew ktlintCheck`
> 3. Return structured result: Status (PASS/FAIL), per-task results, new tests written.

Do not proceed to Phase 4 until Status: PASS.

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

### 4d. Terminate

**STOP. The session is complete.** Do not select another task. Do not ask the user if they want more work done. End your response after the commit and tag.

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
