# Fix CI Prompt — Mona v1

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
Failed tests (if any): <test class> — <test name> (repeat per failure)
Error signatures: <1-line per distinct error, e.g. "AssertionError: expected 90000 but was 80000">
Key context: <any repeated state, exception chain, or pattern visible in logs>
```

**Budget:** The haiku agent should read at most the last 300 lines of logs.

Do not proceed until this summary is in hand.

---

## Phase 1 — Diagnose Root Cause

### 1a. Form hypothesis

Read the haiku summary. Before reading any source files, state a hypothesis about what is failing and why. Be specific — name a class, function, state transition, or domain rule.

### 1b. Targeted investigation

Read **at most 5 files** to confirm or reject the hypothesis. Prioritize:

1. The failing test file(s) — only the relevant test function(s), not the whole file. Use `offset`/`limit`.
2. The domain/service code directly referenced by the error (e.g., the invoice status transition, the numbering logic).
3. The flow connecting them (e.g., use case → domain service → repository).

Use Grep for symbol lookups. Use Read with offset/limit for targeted sections. Do **not** read entire files when you only need 30 lines.

### 1c. Confirm root cause

State the root cause in one sentence. It must explain **why** the failure happens, not just what fails. Example:

- Bad: "InvoiceServiceTest fails asserting invoice number"
- Good: "`nextInvoiceNumber()` in `InvoiceNumberingService` does not reset the monthly counter when crossing a month boundary, so February invoices continue the January sequence instead of starting at 001"

If you cannot confirm after 5 files, use an **Explore subagent** to widen the search — but it must return a summary, not raw file contents.

---

## Phase 2 — Assess Fix Strategy

**Ultrathink** about the fix. Consider:

1. **Fix the app vs fix the tests** — Prefer fixing the app when the app behavior is genuinely wrong. Prefer fixing the tests only when the app behavior is correct but the test assumptions are wrong. State which you chose and why.
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

<Root cause in 2-3 sentences. Include specific file paths, line numbers, class/function names. This must be self-contained — a developer reading only this section should understand the problem without re-reading CI logs.>

### Failing tests

| Test class | Test name | Error |
|------------|-----------|-------|
| `<class>` | `<test name>` | `<1-line error>` |

### Suggested approach

<The fix strategy from Phase 2. Include:>
- Which file(s) to modify and what to change
- Why this approach over alternatives
- What to verify (specific tests to run, edge cases to check)

### Constraints

- Max 2 files changed
- Must pass: `./gradlew build && ./gradlew ktlintCheck`
- Tests that were failing must pass after fix

### Prevention rule

Append one row to the **Prevention Rules** table at the top of IMPLEMENTATION_PLAN.md. Format: `cause | rule`. The rule must be a one-liner that prevents this class of bug in future builds. Distill from the root cause — do not repeat the fix steps.
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
