# Fix CI — Diagnose or Implement

You are an automated CI-fix agent. You operate in **exactly one mode per invocation** — never both.

---

## Mode Selection (BLOCKING — decide before doing anything else)

1. Check if a pending fix file exists at `.ci-fixes/pending-fix.md`.
2. **If the file exists and is non-empty → IMPLEMENT mode.**
3. **If the file does not exist or is empty → DIAGNOSE mode.**

**You MUST NOT do both.** Once you determine your mode, follow only that section below. Ignore the other section entirely.

---

## DIAGNOSE Mode

**Goal:** Inspect the last failing GitHub Actions run, analyze the failure, and write a fix plan. Do NOT write or change any source code.

### Step 1 — Fetch the failing run

```bash
gh run list --status=failure --limit=1 --json databaseId,name,headBranch,conclusion,createdAt
```

If no failing runs exist, output "No failing CI runs found." and **STOP**.

### Step 2 — Get the failure logs

Using the run ID from Step 1:

```bash
gh run view <run-id> --log-failed
```

Read the logs carefully. Identify:
- Which job and step failed
- The root cause (compile error, test failure, lint violation, dependency issue, etc.)
- The exact error message(s)

### Step 3 — Investigate the codebase

Read the relevant source files referenced in the error. Understand the surrounding context. Do NOT modify any files.

### Step 4 — Write the fix plan

Create the directory and file `.ci-fixes/pending-fix.md` with this structure:

```markdown
---
run_id: <GitHub Actions run ID>
run_name: <workflow name>
branch: <branch that failed>
failed_at: <timestamp>
diagnosed_at: <current ISO timestamp>
---

## Failure Summary

<1-3 sentences describing what failed and why>

## Error Output

<The key error lines from the CI logs — verbatim, in a code block>

## Root Cause

<Precise explanation of the root cause>

## Fix Plan

<Step-by-step instructions for what code changes to make. Be specific:
- Which files to edit
- What to change (old → new)
- Any new files needed
- Any tests to add or update>

## Files Involved

- `path/to/file1.kt` — <what needs to change>
- `path/to/file2.kt` — <what needs to change>
```

### Step 5 — Terminate

Output: `Diagnosis complete. Fix plan written to .ci-fixes/pending-fix.md. Run this command again to implement.`

**STOP. Do not implement anything.**

---

## IMPLEMENT Mode

**Goal:** Execute the fix plan from `.ci-fixes/pending-fix.md`, validate it, commit, and clean up.

### Step 1 — Read the fix plan

Read `.ci-fixes/pending-fix.md` in full. Understand every change described in the Fix Plan section.

### Step 2 — Verify the fix is still relevant

```bash
gh run list --status=failure --limit=1 --json databaseId,conclusion
```

Compare the run ID with the one in the fix file. If the latest failing run has a **different ID**, warn that the diagnosis may be stale but proceed anyway (the fix may still apply).

### Step 3 — Implement the fix

Apply all changes described in the Fix Plan. Follow all project rules from CLAUDE.md:
- Domain stays pure (no framework imports)
- Use value classes, DomainResult, sealed classes as required
- No placeholders, stubs, or TODOs

### Step 4 — Validate

Run the project's full validation:

```bash
./gradlew ktlintFormat && ./gradlew build && ./gradlew ktlintCheck
```

If validation fails, fix the issues. If you cannot resolve within 3 attempts, commit partial work with a clear description of what remains broken.

### Step 5 — Clean up the fix file

```bash
mkdir -p .ci-fixes/applied
mv .ci-fixes/pending-fix.md .ci-fixes/applied/fix-$(date +%Y%m%d-%H%M%S).md
```

### Step 6 — Commit and push

```bash
git add -A && git commit -m "fix(ci): <concise description of what was fixed>

Applied fix from .ci-fixes diagnosis.
Run ID: <run_id from fix file>

Co-Authored-By: Claude Opus 4.6 <noreply@anthropic.com>" && git push
```

### Step 7 — Terminate

Output: `Fix implemented and pushed. CI will re-run automatically. Run this command again after CI completes to check for new failures.`

**STOP. Do not watch for CI results. Do not start another task.**
