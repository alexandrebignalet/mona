# Loop Monitoring & Optimization Guide

This document describes the automated `loop.sh` build loop, its cost problems, and the optimization strategy to get from ~2 iterations/day back to 10-15 on a $20/day budget.

---

## How the Loop Works

`loop.sh` runs Claude in headless mode (`-p`) to autonomously implement one task per iteration from `IMPLEMENTATION_PLAN.md`. Each iteration:

1. Reads `PROMPT_build.md` (static build instructions)
2. Loads `CLAUDE.md` + `IMPLEMENTATION_PLAN.md` as context
3. Selects the next task, implements it, tests, commits
4. Loop restarts for the next task

```
┌─────────────────────────────────────────────────────┐
│                    loop.sh                          │
│                                                     │
│  ┌───────────┐    ┌───────────┐    ┌────────────┐  │
│  │  Context   │───▶│  Claude   │───▶│  Validate  │  │
│  │  Loading   │    │  (task)   │    │  & Commit  │  │
│  └───────────┘    └───────────┘    └────────────┘  │
│       ▲                                    │        │
│       └────────────────────────────────────┘        │
│                 next iteration                      │
└─────────────────────────────────────────────────────┘
```

**Current state:** `./loop.sh` defaults to Sonnet for build, Opus for plan. No telemetry, no guardrails, no caching.

---

## The Cost Problem

As the codebase grew past 16K lines, each iteration became expensive:

| Cause | Impact |
|-------|--------|
| No prompt caching — CLAUDE.md/plan/specs re-processed every call | Paying 10x for cacheable tokens |
| Single model for all tasks regardless of complexity | 5x overspend on mechanical tasks |
| Verbose output nobody reads in headless mode | Wasted output tokens |
| IMPLEMENTATION_PLAN.md carries verbose completed-phase descriptions | ~3-4K wasted input tokens |
| Full spec files read each iteration | ~800 lines x 2 wasted reads |
| No turn cap — runaway read-fix-read loops | Unbounded token spend |
| Extended thinking at full budget on trivial tasks | Wasted thinking tokens |

---

## Optimization Strategy

### Implementation Order

Changes are ordered in three phases: measurement first, then high-impact cost cuts, then stacking gains.

### Phase A — Foundation (prerequisites)

#### 1. Per-Iteration Telemetry

Add `--output-format json` to the Claude CLI call and extract metrics after each iteration:

```
timestamp, phase, duration_sec, exit_code, outcome, retry_count,
routed_model, input_tokens, output_tokens, cache_read_tokens,
cache_write_tokens, tool_calls, actual_model
```

Written to `loop_metrics.csv`. Key derived metrics (compute weekly):

| Metric | Target | Formula |
|--------|--------|---------|
| Cost per successful phase | Minimize | total cost of all attempts / phases completed |
| Sonnet success rate | >80% | sonnet successes / sonnet attempts |
| Cache hit rate | >60% | cache_read_tokens / (cache_read_tokens + input_tokens) |
| Runaway rate | <5% | iterations hitting cap / total iterations |

#### 2. Structured Output Discipline

Add to `PROMPT_build.md` preamble — no prose, only tool calls, commit messages, JSON status lines, and error diagnostics.

#### 3. Trim IMPLEMENTATION_PLAN.md

Collapse completed phases to one line:

```markdown
## Completed Phases
Phases 1.1-14.1 done. See git log for details.
```

Keep stable across iterations to preserve cache hits.

#### 4. Programmatic Guardrails

Add `--max-turns 40` and parse JSON output for:

| Guard | Trigger | Action |
|-------|---------|--------|
| Read thrashing | unique/total read ratio < 0.50 (with 10+ reads) | Force partial commit |
| Build failures | 3+ consecutive `gradlew` failures | Commit partial, move on |
| Token ceiling | >500K tokens in one iteration | Log warning |

### Phase B — High-Impact Cost Reduction

#### 5. Heuristic Model Routing

A zero-cost bash function classifies tasks without an LLM call:

| Signal | Routes to |
|--------|-----------|
| Keywords: aggregate, domain event, new port, FSM, migration, event dispatch | Opus |
| Task touches 3+ layers (domain + infrastructure + application) | Opus |
| Everything else | Sonnet |

For ambiguous cases (contains "TBD", "refactor", "redesign"), a Haiku tiebreaker call decides. Routing decisions are logged to `routing_metrics.csv` for feedback:

```
timestamp, phase, routed_model, exit_code, retry_count
```

Track `routed=sonnet, outcome=fail` — if Sonnet failure rate exceeds 20% for a pattern, update the heuristic keywords.

#### 6. Prompt Caching

Structure API calls so static context (CLAUDE.md + PROMPT_build.md) is in a cacheable prefix block. Cached input tokens cost 90% less.

**Cache TTL caveat:** Anthropic's cache expires after 5 minutes of inactivity. If an iteration takes 6+ minutes, insert a same-model keepalive ping (`max_tokens: 1`) between iterations when elapsed > 4 minutes. Cost: ~$0.003-0.01 per ping.

Model at 60-70% effective hit rate (not 90%) to account for TTL misses.

#### 7. Thinking Budget Control

Tie extended thinking budget to task complexity:

| Route | `budget_tokens` |
|-------|-----------------|
| Sonnet (mechanical) | 2,048 |
| Opus (moderate) | 8,192 |
| Opus (architectural — 3+ layers or novel) | 32,768 |

### Phase C — Stacking Optimizations

#### 8. Haiku Pre-Pass for Context Selection

Before the main model call, Haiku reads a lightweight codebase index + spec TOC and returns a JSON manifest of relevant files/sections. The main model starts with targeted context instead of reading everything.

**Interim fallback:** Grep for the specific spec section referenced in the task's acceptance criteria instead of reading full spec files.

#### 9. CLAUDE.md Compaction

Only if caching proves unreliable. The DDD rules are already enforced by the compiler and tests — removing them saves ~1K tokens but loses pre-compilation guidance. If caching works, keep them (cached tokens are cheap).

---

## Expected Impact

| Scenario | Cost Reduction | Iterations/day ($20) |
|----------|---------------|---------------------|
| Caching + routing + thinking control | ~85-90% | 10-15 |
| Without caching (trimming + routing only) | ~50-70% | 6-9 |

Projections assume Sonnet success rate stays above 80%. Telemetry validates within the first week.

---

## Design Principles

1. **Instrument before optimizing.** Telemetry first. Every other change is a hypothesis until measured.
2. **Enforce programmatically, not via prompt.** The model is least reliable at self-regulation in exactly the failure scenarios where regulation matters most.
3. **Deterministic over probabilistic.** Bash heuristic > LLM call when static signals suffice.
4. **Measure cost per success, not cost per attempt.** A cheaper iteration that fails more often isn't cheaper.
5. **Cache-friendly by default.** Prefer approaches that preserve prompt cache stability.
6. **Close the feedback loop.** Every automated decision logs its outcome for accuracy tracking.
7. **Graceful degradation.** Every optimization has a fallback — no single point of failure.

---

## Monitoring Checklist

**Weekly review of `loop_metrics.csv`:**

- [ ] Cost per successful phase trending down?
- [ ] Sonnet success rate >80%?
- [ ] Cache hit rate >60%?
- [ ] Runaway rate <5%?
- [ ] Any new Sonnet failure patterns that should update the routing heuristic?

**After deploying any optimization:**

- [ ] Compare 1-week before/after on cost per successful phase
- [ ] Check cache hit rate didn't regress (e.g., after editing CLAUDE.md)
- [ ] Verify Sonnet failure rate didn't spike (after changing routing keywords)
