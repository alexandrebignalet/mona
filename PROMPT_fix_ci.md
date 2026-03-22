# Fix CI Prompt — v1

This prompt diagnoses the latest CI failure and writes a prioritized fix task into IMPLEMENTATION_PLAN.md. **It does not fix code.**

---

## Phase 0 — Fetch Failure

**Use a haiku subagent** for all GitHub API calls. It must:

1. Run `gh run list --limit 1 --status failure --json databaseId,headBranch,name,createdAt` to get the latest failed run.
2. Run `gh run view <id> --json jobs` to identify which job and step failed.
3. Run `gh api repos/{owner}/{repo}/actions/jobs/{job_id}/logs` to fetch the raw logs.
4. Return a **structured summary only** (not raw logs):

```
Run: <id> | Branch: <branch> | Date: <date>
Failed step: <step name>
Failed tests (if any): <test file> — <test name> (repeat per failure)
Error signatures: <1-line per distinct error, e.g. "TimeoutError: locator.waitFor 5000ms exceeded">
Key context: <any repeated DOM state, blocking element, or pattern visible in logs>
```

**Budget:** The haiku agent should read at most the last 300 lines of logs. If the failure is in E2E tests, also extract the Playwright call log for each failing test (the indented lines showing retry attempts).

Do not proceed until this summary is in hand.

---

## Phase 1 — Diagnose Root Cause

### 1a. Form hypothesis

Read the haiku summary. Before reading any source files, state a hypothesis about what is failing and why. Be specific — name a component, state variable, event, or timing issue.

### 1b. Targeted investigation

Read **at most 5 files** to confirm or reject the hypothesis. Prioritize:

1. The failing test file(s) — only the relevant test function(s), not the whole file. Use `offset`/`limit`.
2. The app code directly referenced by the error (e.g., the component rendering the blocking element, the hook managing the state).
3. The event/state flow connecting them (e.g., socket handler → state update → re-render).

Use Grep for symbol lookups. Use Read with offset/limit for targeted sections. Do **not** read entire files when you only need 30 lines.

### 1c. Confirm root cause

State the root cause in one sentence. It must explain **why** the failure happens, not just what fails. Example:

- Bad: "Test 5.3 times out waiting for `game-coins` to be visible"
- Good: "`selectedCard` state in `game.tsx` is never cleared after turn resolution, so `ActionOverlay` (z-50 fixed overlay) persists and blocks pointer events on all underlying elements including `game-coins`"

If you cannot confirm after 5 files, use an **Explore subagent** to widen the search — but it must return a summary, not raw file contents.

### 1d. Bombadil-specific diagnosis

When the failing CI step is **"Bombadil property tests"**:

1. Identify which property (P1–P15+) violated from the trace output.
2. Read the property definition in `bombadil/spec.ts` and the relevant extractor(s).
3. Determine whether the violation indicates:
   - **App bug** — the UI broke an invariant → fix the app code.
   - **Stale property** — the property's assumption no longer holds after a valid app change → update the property and document why.
   - **Flaky extractor** — the DOM query returns stale/missing data due to timing → fix the extractor or add a wait condition.
4. Root cause must name the specific property (e.g., "P8: cancel-trade-preserves-coins") and the extractor or action that surfaced it.

---

## Phase 2 — Assess Fix Strategy

**Ultrathink** about the fix. Consider:

1. **Fix the app vs fix the tests** — Prefer fixing the app when the app behavior is genuinely wrong (e.g., overlay should auto-dismiss). Prefer fixing the tests only when the app behavior is correct but the test assumptions are wrong. State which you chose and why.
2. **Blast radius** — How many files need to change? If more than 2, reconsider.
3. **Regression risk** — Could this fix break other behavior? Name what to watch.
4. **Minimal change** — What is the smallest code change that fixes the root cause?

---

## Phase 3 — Write Task to IMPLEMENTATION_PLAN.md

Insert a new section **immediately after the `---` following the "Known Issues" heading** (bottom of the file), using this exact format:

```markdown
## CI Fix — <short title>

> **Priority: TOP** — CI is broken. Fix before any new work.
>
> **Failing run:** <gh run URL or ID> | <date>

### Diagnosis

<Root cause in 2-3 sentences. Include specific file paths, line numbers, state variables, and event names. This must be self-contained — a developer reading only this section should understand the problem without re-reading CI logs.>

### Failing tests

| Test file | Test name | Error |
|-----------|-----------|-------|
| `<path>` | `<test name>` | `<1-line error>` |

### Suggested approach

<The fix strategy from Phase 2. Include:>
- Which file(s) to modify and what to change
- Why this approach over alternatives
- What to verify (specific tests to run, edge cases to check)

### Constraints

- Max 2 files changed
- Must pass: `npm run typecheck && npm run lint && npm run test`
- E2E tests that were failing must pass after fix

### Prevention rule

Append one row to the **Prevention Rules** table at the top of IMPLEMENTATION_PLAN.md. Format: `cause | rule`. The rule must be a one-liner that prevents this class of bug in future builds. Distill from the root cause — do not repeat the fix steps.

**Bombadil violations MUST produce a prevention rule**, same as any other CI failure. Example rules:

| Cause | Rule |
|-------|------|
| P2 violated: coins went negative after trade because commerce discount was double-applied | Trade cost calculation must clamp to zero; add unit test for double-discount edge case |
| P6 violated: overlay blocked all actions because modal dismiss handler was not wired | Every modal component must call `onClose` on backdrop click and Escape key |
| Bombadil timeout (P13): game never reached scoring because bot engine threw on empty hand | Bot engine must handle edge case of zero playable cards by discarding |
```

---

## Phase 4 — Commit and Push

```bash
git add IMPLEMENTATION_PLAN.md && git commit -m "ci: diagnose <failure type> and add fix task" && git push
```

---

## Rules

- **No code changes.** This prompt produces a diagnosis and plan, not a fix.
- **Token discipline.** Use haiku for API calls, Explore subagents for wide searches. Keep raw content out of the main context.
- **Hypothesis-first.** Always state what you think is wrong before reading more files.
- **File budget.** At most 5 files read directly in main context during diagnosis. Use subagents for anything beyond that.
- **Self-contained output.** The IMPLEMENTATION_PLAN.md entry must be understandable without access to CI logs or this conversation.
