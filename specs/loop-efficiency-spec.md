# Loop Efficiency Spec

## Problem

As the codebase grew (119 files, 16K+ lines) and implementation progressed past Phase 13, each `loop.sh` iteration consumes significantly more tokens. What used to allow 10+ iterations per $20/day now caps at ~2.

### Root Causes

| # | Cause | Impact |
|---|-------|--------|
| 1 | No prompt caching — static context (CLAUDE.md, plan, specs) re-processed at full cost every call | Paying 10x for tokens that could be cached |
| 2 | Opus used for entire iteration regardless of task complexity | 5x cost on mechanical tasks that Sonnet handles fine |
| 3 | Verbose output (explanations, summaries) in a headless loop nobody reads | Wasted output tokens every iteration |
| 4 | IMPLEMENTATION_PLAN.md carries ~120 lines of verbose completed-phase descriptions | ~3-4K wasted input tokens per iteration |
| 5 | Full spec files read each iteration despite content already internalized in code | ~800 lines x 2 specs = wasted reads |
| 6 | No turn cap — runaway read-fix-read loops with no root-cause analysis | Unbounded token spend per iteration |
| 7 | CLAUDE.md repeats DDD patterns already enforced in code | ~50 lines of redundant system context |
| 8 | No per-iteration telemetry — no way to measure what's actually expensive | Optimizing blind; can't validate savings |
| 9 | Extended thinking runs at full budget even on trivial mechanical tasks | Wasted thinking tokens on boilerplate |

---

## Changes

### 1. Enable prompt caching (HIGH IMPACT)

The Anthropic API supports prompt caching — cached input tokens cost **90% less**. The static prefix (CLAUDE.md, PROMPT_build.md, IMPLEMENTATION_PLAN.md) is identical across iterations and should hit cache on every call after the first.

**Requirements:**
- Structure the API call so the static system prompt (CLAUDE.md + PROMPT_build.md) is in a cacheable prefix block.
- Keep the static prefix **stable between iterations** — avoid per-iteration mutations (e.g., updating a one-liner range after each phase) that would bust the cache.
- The task-specific content (current phase, acceptance criteria) goes after the cached prefix.

**⚠ Cache TTL constraint:** Anthropic's prompt cache expires after **5 minutes of inactivity**. If a loop iteration takes 6+ minutes (common with Opus on architectural tasks), the next iteration gets a full cache miss.

**⚠ Cache is per-model.** A Haiku request with the same prefix does NOT refresh an Opus or Sonnet cache. The keepalive ping must use the same model as the main iteration.

**Mitigation strategies:**
- **Same-model keepalive ping:** Between iterations, if elapsed time > 4 minutes, send a no-op request using the **same model** as the upcoming iteration (with `max_tokens: 1`) to refresh the cache TTL. Cost: ~$0.003-0.01 per ping depending on model.
- **Minimize iteration wall-clock time:** Solving runaway loops (Change 4) before enabling caching reduces TTL misses at the source. This is a prerequisite — implement Change 4 before Change 1.
- **Realistic modeling:** Do not assume 90% cache hit rate in cost projections. Model at **60-70%** effective hit rate to account for TTL misses.

**Implication for other changes:** Context trimming (Changes 5, 7) is still worth doing but becomes lower priority. Do not sacrifice cache stability to save a few hundred tokens — a cache hit on 5K tokens saves more than trimming 1K tokens that busts the cache.

```bash
# In loop.sh or the API wrapper, use cache_control breakpoints
# to mark the end of the static prefix.
```

### 2. Dynamic model routing via heuristic + Haiku fallback (HIGH IMPACT)

**Problem with static labels:** Complexity is a human guess made days/weeks before execution. Static labels require manual maintenance and can't adapt.

**Problem with pure-LLM classification:** Using an LLM to decide whether to use an LLM is over-engineered. Task complexity is largely predictable from static signals in the acceptance criteria — no LLM needed for 90% of cases.

**Approach: deterministic heuristic with Haiku escalation.**

**Step 1 — Bash heuristic (zero cost, deterministic):**

```bash
classify_task() {
  local TASK_DESC="$1"
  local ACCEPTANCE="$2"

  # Architectural signals → Opus
  if echo "$ACCEPTANCE" | grep -qiE \
    'aggregate|domain event|new port|new entity|migration|FSM|state machine|event dispatch'; then
    echo "opus"
    return
  fi

  # Multi-layer signals → Opus
  LAYER_COUNT=0
  echo "$ACCEPTANCE" | grep -qiE 'domain/' && LAYER_COUNT=$((LAYER_COUNT + 1))
  echo "$ACCEPTANCE" | grep -qiE 'infrastructure/' && LAYER_COUNT=$((LAYER_COUNT + 1))
  echo "$ACCEPTANCE" | grep -qiE 'application/' && LAYER_COUNT=$((LAYER_COUNT + 1))
  if [ "$LAYER_COUNT" -ge 3 ]; then
    echo "opus"
    return
  fi

  # Everything else → Sonnet
  echo "sonnet"
}
```

**Step 2 — Haiku escalation (rare, for ambiguous cases):**

If the heuristic returns `sonnet` but the task description contains uncertainty markers (`TBD`, `decide between`, `refactor`, `redesign`), escalate to a Haiku classification call as a tiebreaker. This applies to <10% of iterations.

**Feedback loop (critical — validate the router):**

Log every routing decision and its outcome in telemetry:

```bash
echo "$TIMESTAMP,$PHASE,$ROUTED_MODEL,$EXIT_CODE,$RETRY_COUNT" >> routing_metrics.csv
```

Track `routed=sonnet, outcome=fail, retried_on=opus` events. If Sonnet failure rate exceeds 20% for a task pattern, update the heuristic keywords. Without this feedback loop, the router optimizes blind.

**Fallback:** If heuristic + Haiku both fail (shouldn't happen), default to Sonnet. Sonnet handles ~80% of tasks correctly; the occasional architectural miss will surface as a build failure and retry on Opus.

| Task type | Model | Signals |
|-----------|-------|---------|
| Mechanical | Sonnet | Single layer, CRUD pattern, follows existing code, new use case/endpoint |
| Architectural | Opus | New aggregate/event/port, 3+ layers, ambiguous spec, novel infrastructure |

Subagent tiers still apply within an iteration:

| Tier | Model | Trigger |
|------|-------|---------|
| Must-be-Opus | opus | New aggregate boundary, new domain event design, spec inconsistency, debugging after > 2 failed attempts |
| Can-be-Sonnet | sonnet | Layer assignment, file structure, testing, DomainError additions |

### 3. Enforce structured output discipline (FREE)

In a headless `loop.sh` run, nobody reads prose. But complete silence makes debugging failed iterations impossible.

**Add to PROMPT_build.md preamble:**

```
You are running in a headless automated loop. Do not explain changes. Do not
summarize what you did. Do not restate the task. Output only:
- Tool calls (file reads, edits, writes, bash commands)
- Commit messages
- A single JSON status line after each major action:
  {"action": "edit", "file": "path/to/file.kt", "reason": "brief reason"}
- Error diagnostics when something fails
```

Structured status lines are both cheaper than prose AND more useful for post-mortem debugging and telemetry enrichment.

### 4. Programmatic guardrails (replaces prompt-based runaway prevention)

A hard `--max-turns 40` cap prevents blowups but doesn't address *why* loops run away.

**⚠ Problem with prompt-based guardrails:** Rules like "do not re-read files" and "stop after 3 attempts" are instructions the model will sometimes ignore, especially under complex failure conditions. The model is least likely to self-regulate in exactly the scenarios where regulation matters most.

**Add `--max-turns 40` to loop.sh:**
```bash
cat "$PROMPT_FILE" | claude -p \
    --dangerously-skip-permissions \
    --model $MODEL \
    --max-turns 40 \
    --output-format json \
    --verbose
```

**Enforce guardrails programmatically in `loop.sh`** by parsing `--output-format json` output:

```bash
# After each iteration, parse the JSON output to detect runaway patterns:

# 1. Read thrashing: low unique-to-total file read ratio signals looping
TOTAL_READS=$(jq '[.tool_calls[] | select(.name=="Read")] | length' "$OUTPUT_FILE")
UNIQUE_READS=$(jq '[.tool_calls[] | select(.name=="Read") | .input.file_path] | unique | length' "$OUTPUT_FILE")
if [ "$TOTAL_READS" -gt 10 ]; then
  READ_RATIO=$(echo "scale=2; $UNIQUE_READS / $TOTAL_READS" | bc)
  if (( $(echo "$READ_RATIO < 0.50" | bc -l) )); then
    echo "WARN: Read thrashing detected (ratio=$READ_RATIO). Forcing partial commit."
    FORCE_PARTIAL=true
  fi
fi

# 2. Consecutive build failures: 3 in a row → commit partial, move on
CONSECUTIVE_FAILURES=$(jq '[.tool_calls[] | select(.name=="Bash" and (.input.command | test("gradlew"))) | .exit_code] |
  [foreach .[] as $x (0; if $x != 0 then . + 1 else 0 end)] | max' "$OUTPUT_FILE")
if [ "$CONSECUTIVE_FAILURES" -ge 3 ]; then
  echo "WARN: 3+ consecutive build failures. Committing partial work."
  FORCE_PARTIAL=true
fi

# 3. Token budget per iteration (catches expensive turns even under turn cap)
TOTAL_TOKENS=$(jq '.usage.input_tokens + .usage.output_tokens' "$OUTPUT_FILE")
TOKEN_CEILING=500000
if [ "$TOTAL_TOKENS" -gt "$TOKEN_CEILING" ]; then
  echo "WARN: Token ceiling exceeded ($TOTAL_TOKENS > $TOKEN_CEILING)."
fi
```

**Keep in PROMPT_build.md (soft guidance, not relied upon):**

```
Runaway loop prevention (soft guidance — enforced programmatically):
- Run `./gradlew ktlintFormat` before `./gradlew ktlintCheck` to avoid
  lint-fix-lint cycles.
- If you cannot resolve an issue within 3 attempts, commit the partial work
  and document the blocker in the commit message.
```

### 5. Trim IMPLEMENTATION_PLAN.md

**Before:** Each completed phase has a multi-line description (who, what, how, test count).

**After:** Completed phases collapsed to a single summary line referencing the commit range.

```markdown
## Completed Phases
Phases 1.1-14.1 done. See git log for details.
```

Keep only:
- The "Completed Phases" one-liner above
- The next incomplete phase with full acceptance criteria
- 2-3 upcoming phases (brief, no acceptance criteria yet)
- Prevention Rules (unchanged)

**Important:** Keep the one-liner stable across iterations (don't update it after every phase) to preserve prompt cache hits. Update it only when doing a batch cleanup.

### 6. Haiku pre-pass for context selection (SCOPED — context only, not routing)

Model routing is handled by the deterministic heuristic (Change 2). This change uses Haiku only for what it's actually good at: selecting relevant context from a large codebase.

**Input to Haiku:**
- Task description + acceptance criteria
- Codebase file index (generated once per batch, cached):
  ```bash
  # Generate lightweight index: path + first comment/class declaration
  find src -name '*.kt' -exec head -5 {} + > .codebase-index.txt
  ```
- Spec file table of contents (heading lines only):
  ```bash
  grep '^#' specs/mvp-spec.md specs/tech-spec.md > .spec-toc.txt
  ```

**Output (JSON manifest):**
```json
{
  "context": {
    "spec_sections": ["## S7 — URSSAF Reminders", "### S7.2 — Threshold Alerts"],
    "source_files": ["src/main/kotlin/mona/domain/service/UrssafThresholds.kt",
                     "src/main/kotlin/mona/application/urssaf/"],
    "domain_types": ["DeclarationPeriod", "Cents", "PaidInvoiceSnapshot"]
  }
}
```

**`loop.sh` then:**
1. Reads only the spec sections and source files from the manifest.
2. Injects them into the main model's prompt after the cached prefix.

**The main model is NOT hard-constrained to the manifest.** It can read additional files if needed — the manifest is a starting point that eliminates ~80% of exploratory reads.

**Fallback (simpler alternative):** If the Haiku pre-pass is not yet implemented, use the manual targeted read rule as an interim:

> For Phase 14+: do not read full spec files. Grep for the specific section reference from the task's acceptance criteria (e.g., `mvp-spec S7` -> grep for `## S7` or `### S7`). Read only the matched section with offset/limit.

### 7. Compact CLAUDE.md

The DDD rules in CLAUDE.md are already enforced by the type system, compiler, and tests. If the model violates them, `./gradlew build` fails and it self-corrects.

**Decision: keep or remove, don't half-extract.** Two options:

- **Option A (recommended if caching works):** Keep the DDD rules in CLAUDE.md. Cached tokens are cheap (~10x cheaper). The rules provide useful pre-compilation guidance that prevents wasted build cycles. Cache stability > token trimming.
- **Option B (if caching doesn't work):** Remove the DDD sections entirely. Rely on build failures as the enforcement mechanism. Do NOT extract to a separate `docs/ddd-reference.md` — a pointer to another file is the worst of both worlds: the model may not read it when it should, and when it does, it pays full uncached cost.

### 8. Per-iteration telemetry (prerequisite for all optimization)

Without token-level metrics, every savings estimate in this spec is a guess. Telemetry is the prerequisite for validating all other changes.

**Implementation:** Append a metrics row after each `loop.sh` iteration:

```bash
# Extract from --output-format json or --verbose output
METRICS=$(jq -r '[
  .usage.input_tokens,
  .usage.output_tokens,
  .usage.cache_read_input_tokens // 0,
  .usage.cache_creation_input_tokens // 0,
  (.tool_calls | length),
  .model
] | @csv' "$OUTPUT_FILE")

EXIT_CODE=$?
OUTCOME="success"
[ "$EXIT_CODE" -ne 0 ] && OUTCOME="fail"

echo "$(date -Iseconds),$PHASE,$DURATION,$EXIT_CODE,$OUTCOME,$RETRY_COUNT,$ROUTED_MODEL,$METRICS" >> loop_metrics.csv
```

**CSV schema:**
```
timestamp, phase, duration_sec, exit_code, outcome, retry_count, routed_model, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, tool_calls, actual_model
```

**Key addition vs. original: outcome and retry tracking.** The metric that matters is **cost per successful phase**, not cost per iteration. A cheaper iteration that fails more often isn't actually cheaper.

**Derived metrics to compute weekly:**
- `cost_per_successful_phase` = total cost of all attempts / phases completed
- `sonnet_success_rate` = sonnet successes / sonnet attempts (target: >80%)
- `cache_hit_rate` = cache_read_tokens / (cache_read_tokens + input_tokens) (target: >60%)
- `runaway_rate` = iterations hitting turn cap or token ceiling / total iterations (target: <5%)

**Usage:**
- Review weekly to identify which phases are expensive and why.
- Compare before/after when deploying changes from this spec.
- Detect regressions (e.g., cache hit rate dropping after a CLAUDE.md edit).
- Validate model routing accuracy (Sonnet fail rate by task pattern).

### 9. Thinking budget control

Extended thinking is valuable for architectural decisions but wasteful on mechanical tasks. Control it based on task complexity from the routing heuristic (Change 2).

| Routed model | `budget_tokens` | Rationale |
|-------------|----------------|-----------|
| Sonnet (mechanical) | 2048 | Enough for basic planning, not more |
| Opus (moderate, default) | 8192 | Standard reasoning |
| Opus (architectural, 3+ layers or novel) | 32768 | Full deliberation for novel problems |

**Implementation:**
```bash
case $MODEL in
  sonnet) BUDGET=2048 ;;
  opus)
    # Escalate thinking for high-complexity signals
    if echo "$ACCEPTANCE" | grep -qiE 'aggregate|FSM|migration|event dispatch'; then
      BUDGET=32768
    else
      BUDGET=8192
    fi
    ;;
  *) BUDGET=8192 ;;
esac

# Pass to claude CLI or API call
```

This is orthogonal to model selection and stacks with it — a Sonnet call with low thinking budget is the cheapest possible iteration.

---

## Expected Impact

| Change | Savings estimate | Effort |
|--------|-----------------|--------|
| Telemetry + outcome tracking | Enables measurement and validates all other changes | Low — bash script addition |
| Prompt caching (with same-model keepalive) | ~60-70% effective reduction on static context tokens | Medium — API call restructuring + keepalive |
| Heuristic model routing (+ Haiku escalation) | ~5x cost reduction on mechanical iterations (~70% of tasks) | Low — bash function + loop.sh |
| Structured output discipline | ~30-50% reduction in output tokens, better debuggability | Trivial — one prompt paragraph |
| Programmatic guardrails (ratio + budget + failures) | Prevents outlier 2-3x overspend iterations | Low — JSON parsing in loop.sh |
| Trim IMPLEMENTATION_PLAN.md | ~3,000-4,000 input tokens | Trivial — text edit |
| Haiku pre-pass context selection | ~2,000-3,000 input tokens per iteration | Medium — Haiku call + manifest parsing |
| Compact CLAUDE.md | ~1,000-1,500 input tokens (only if caching unavailable) | Trivial — conditional |
| Thinking budget control | ~20-40% reduction in thinking tokens on mechanical tasks | Low — parameter plumbing |
**Composite projection:**

| Scenario | Cost reduction | Iterations/day ($20) |
|----------|---------------|---------------------|
| Caching + heuristic routing + thinking control | ~85-90% | 10-15 |
| Without caching (trimming + routing only) | ~50-70% | 6-9 |

**⚠ These projections assume current Sonnet success rate holds above 80%.** If Sonnet failures cause frequent Opus retries, effective savings drop to ~60-75%. Telemetry will validate within the first week.

---

## Implementation Order

**Phase A — Foundation (do first, prerequisite for everything):**
1. Per-iteration telemetry with outcome/retry tracking in loop.sh
2. Structured output discipline in PROMPT_build.md
3. Trim IMPLEMENTATION_PLAN.md
4. Add `--max-turns 40` + `--output-format json` + programmatic guardrails (ratio, budget, consecutive failures)

**Phase B — High-impact cost reduction:**
5. Heuristic model routing with routing_metrics.csv feedback loop
6. Enable prompt caching with same-model keepalive strategy
7. Thinking budget control tied to routing heuristic

**Phase C — Stacking optimizations:**
8. Haiku pre-pass for context selection (scoped to context, not routing)
9. CLAUDE.md compaction — only if caching proves unreliable

**Ordering rationale:** Phase A establishes the measurement baseline. Guardrails (A4) reduce iteration wall-clock time, which improves cache hit rates in Phase B. Routing (B5) comes before caching (B6) because it's simpler and its feedback loop informs whether cache keepalive is worth the complexity. Phase C stacks marginal gains.

---

## Design Principles

These principles guided the changes above and should guide future loop optimizations:

1. **Instrument before optimizing.** Telemetry comes first. Every other change is a hypothesis until measured.
2. **Enforce programmatically, not via prompt.** The model is least reliable at self-regulation in exactly the failure scenarios where regulation matters most. Use `loop.sh` as the control plane.
3. **Deterministic over probabilistic.** Prefer a bash heuristic over an LLM call when static signals suffice. Save LLM classification for genuinely ambiguous cases.
4. **Measure cost per success, not cost per attempt.** A cheaper iteration that fails more often isn't actually cheaper. Every optimization must track its impact on success rate.
5. **Cache-friendly by default.** When choosing between two approaches, prefer the one that preserves prompt cache stability — a cache hit on 5K tokens dwarfs trimming 500 tokens.
6. **Close the feedback loop.** Every automated decision (model routing, context selection) must log its outcome so accuracy can be measured and the decision logic improved.
7. **Graceful degradation.** Every optimization has a fallback (heuristic ambiguous → Haiku tiebreak, Haiku down → default Sonnet, cache miss → full-price but still works). No single point of failure.
