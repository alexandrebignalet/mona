#!/bin/bash
# Usage: ./loop.sh [plan] [max_iterations]
# Examples:
#   ./loop.sh              # Build mode, unlimited iterations
#   ./loop.sh 20           # Build mode, max 20 iterations
#   ./loop.sh plan         # Plan mode, unlimited iterations
#   ./loop.sh plan 5       # Plan mode, max 5 iterations

set -euo pipefail

# ──────────────────────────────────────────────────────
# Parse arguments
# ──────────────────────────────────────────────────────
if [ "$1" = "plan" ] 2>/dev/null; then
    MODE="plan"
    PROMPT_FILE="PROMPT_plan.md"
    MODEL_OVERRIDE="opus"
    MAX_ITERATIONS=${2:-0}
elif [[ "${1:-}" =~ ^[0-9]+$ ]]; then
    MODE="build"
    PROMPT_FILE="PROMPT_build.md"
    MODEL_OVERRIDE=""
    MAX_ITERATIONS=$1
else
    MODE="build"
    PROMPT_FILE="PROMPT_build.md"
    MODEL_OVERRIDE=""
    MAX_ITERATIONS=0
fi

ITERATION=0
RETRY_COUNT=0
CURRENT_BRANCH=$(git branch --show-current)
METRICS_FILE="loop_metrics.csv"
ROUTING_METRICS_FILE="routing_metrics.csv"
OUTPUT_FILE=$(mktemp /tmp/loop-output-XXXXXX.json)

trap 'rm -f "$OUTPUT_FILE"' EXIT

# ──────────────────────────────────────────────────────
# Telemetry: initialize CSV headers if files don't exist
# ──────────────────────────────────────────────────────
if [ ! -f "$METRICS_FILE" ]; then
    echo "timestamp,phase,duration_sec,exit_code,outcome,retry_count,routed_model,input_tokens,output_tokens,cache_read_tokens,cache_write_tokens,tool_calls,actual_model,haiku_prepass" > "$METRICS_FILE"
fi
if [ ! -f "$ROUTING_METRICS_FILE" ]; then
    echo "timestamp,phase,routed_model,exit_code,retry_count" > "$ROUTING_METRICS_FILE"
fi

# ──────────────────────────────────────────────────────
# Change 2: Heuristic model routing (deterministic, zero cost)
# ──────────────────────────────────────────────────────
classify_task() {
    local TASK_DESC="${1:-}"
    local ACCEPTANCE="${2:-}"
    local COMBINED="$TASK_DESC $ACCEPTANCE"

    # Architectural signals → Opus
    if echo "$COMBINED" | grep -qiE \
        'aggregate|domain event|new port|new entity|migration|FSM|state machine|event dispatch'; then
        echo "opus"
        return
    fi

    # Multi-layer signals → Opus
    LAYER_COUNT=0
    echo "$COMBINED" | grep -qiE 'domain/' && LAYER_COUNT=$((LAYER_COUNT + 1))
    echo "$COMBINED" | grep -qiE 'infrastructure/' && LAYER_COUNT=$((LAYER_COUNT + 1))
    echo "$COMBINED" | grep -qiE 'application/' && LAYER_COUNT=$((LAYER_COUNT + 1))
    if [ "$LAYER_COUNT" -ge 3 ]; then
        echo "opus"
        return
    fi

    # Ambiguity markers → Haiku tiebreaker (Step 2)
    if echo "$COMBINED" | grep -qiE 'TBD|decide between|refactor|redesign'; then
        # Haiku escalation for ambiguous cases
        local HAIKU_RESPONSE
        HAIKU_RESPONSE=$(echo "Classify this task as 'opus' (architectural, novel, multi-layer) or 'sonnet' (mechanical, single-layer, follows patterns). Reply with ONLY the word 'opus' or 'sonnet'.\n\nTask: $TASK_DESC\nAcceptance: $ACCEPTANCE" | claude -p --model haiku --max-turns 1 2>/dev/null || echo "sonnet")
        if echo "$HAIKU_RESPONSE" | grep -qi "opus"; then
            echo "opus"
        else
            echo "sonnet"
        fi
        return
    fi

    # Everything else → Sonnet
    echo "sonnet"
}

# ──────────────────────────────────────────────────────
# Change 9: Thinking budget control
# ──────────────────────────────────────────────────────
get_thinking_budget() {
    local MODEL="$1"
    local ACCEPTANCE="${2:-}"

    case "$MODEL" in
        sonnet)
            echo "2048"
            ;;
        opus)
            # Escalate thinking for high-complexity signals
            if echo "$ACCEPTANCE" | grep -qiE 'aggregate|FSM|migration|event dispatch|state machine'; then
                echo "32768"
            else
                echo "8192"
            fi
            ;;
        *)
            echo "8192"
            ;;
    esac
}

# ──────────────────────────────────────────────────────
# Change 6: Prompt cache keepalive
# ──────────────────────────────────────────────────────
maybe_keepalive() {
    local ELAPSED=$1
    local MODEL=$2

    if [ "$ELAPSED" -gt 240 ]; then
        echo "KEEPALIVE: Refreshing prompt cache for $MODEL (elapsed=${ELAPSED}s > 240s)"
        echo "ping" | claude -p --model "$MODEL" --max-turns 1 > /dev/null 2>&1 || true
    fi
}

# ──────────────────────────────────────────────────────
# Change 8: Haiku pre-pass for context selection (Phase C)
# ──────────────────────────────────────────────────────
generate_codebase_index() {
    # Generate lightweight index: path + first comment/class declaration
    find src -name '*.kt' -exec head -5 {} + > .codebase-index.txt 2>/dev/null || true
    grep '^#' specs/mvp-spec.md specs/tech-spec.md > .spec-toc.txt 2>/dev/null || true
}

# Calls Haiku with codebase index + spec TOC + task info.
# Returns compact JSON manifest or empty string on failure.
haiku_context_prepass() {
    local PHASE="$1"
    local TASK_SECTION="${2:-}"

    # Requires jq for validation
    if ! command -v jq &>/dev/null; then
        echo ""
        return
    fi

    local INDEX_FILE=".codebase-index.txt"
    local TOC_FILE=".spec-toc.txt"
    [ ! -f "$INDEX_FILE" ] && { echo ""; return; }
    [ ! -f "$TOC_FILE" ] && { echo ""; return; }

    local INDEX_SNIPPET TOC_SNIPPET
    INDEX_SNIPPET=$(head -200 "$INDEX_FILE" 2>/dev/null || echo "")
    TOC_SNIPPET=$(cat "$TOC_FILE" 2>/dev/null || echo "")

    local HAIKU_PROMPT
    HAIKU_PROMPT="Select the minimum relevant context for this Kotlin task. Return ONLY valid JSON — no explanation, no markdown.

TASK: $PHASE
DETAILS: $TASK_SECTION

CODEBASE INDEX (path + first lines of each file):
$INDEX_SNIPPET

SPEC TOC (heading lines only):
$TOC_SNIPPET

Return JSON in this exact format:
{\"context\":{\"spec_sections\":[\"## Exact Heading\"],\"source_files\":[\"src/path/File.kt\"]}}"

    local RAW
    RAW=$(echo "$HAIKU_PROMPT" | claude -p --model haiku --max-turns 1 2>/dev/null || echo "")

    # Try to parse as-is first, then strip possible markdown fences
    local MANIFEST
    MANIFEST=$(echo "$RAW" | jq -c '.' 2>/dev/null || echo "")
    if [ -z "$MANIFEST" ]; then
        MANIFEST=$(echo "$RAW" | sed -n '/^```/,/^```/p' | grep -v '^```' | jq -c '.' 2>/dev/null || echo "")
    fi

    # Validate structure: must have at least source_files array
    if [ -z "$MANIFEST" ] || ! echo "$MANIFEST" | jq -e '.context.source_files | arrays' &>/dev/null; then
        echo ""
        return
    fi

    echo "$MANIFEST"
}

# Builds an enriched prompt file from the base prompt + Haiku-selected context.
# Prints the path to a temp file (caller must delete it when done).
# Falls back to a copy of BASE_PROMPT if MANIFEST is empty or any step fails.
build_enriched_prompt() {
    local MANIFEST="$1"
    local BASE_PROMPT="$2"
    local TEMP_FILE
    TEMP_FILE=$(mktemp /tmp/loop-prompt-XXXXXX.md)

    cat "$BASE_PROMPT" > "$TEMP_FILE"

    if [ -z "$MANIFEST" ]; then
        echo "$TEMP_FILE"
        return
    fi

    printf '\n\n---\n\n## Pre-loaded Context (Haiku-selected)\n\n' >> "$TEMP_FILE"

    # Inject spec sections
    local SPEC_SECTIONS
    SPEC_SECTIONS=$(echo "$MANIFEST" | jq -r '.context.spec_sections // [] | .[]' 2>/dev/null || true)
    if [ -n "$SPEC_SECTIONS" ]; then
        while IFS= read -r HEADING; do
            [ -z "$HEADING" ] && continue
            local SPEC_FILE LINE_NUM
            SPEC_FILE=$(grep -rl "^${HEADING}" specs/ 2>/dev/null | head -1 || echo "")
            if [ -n "$SPEC_FILE" ]; then
                LINE_NUM=$(grep -n "^${HEADING}" "$SPEC_FILE" 2>/dev/null | head -1 | cut -d: -f1 || echo "")
                if [ -n "$LINE_NUM" ]; then
                    printf '### From %s\n\n' "$SPEC_FILE" >> "$TEMP_FILE"
                    sed -n "${LINE_NUM},$((LINE_NUM + 49))p" "$SPEC_FILE" >> "$TEMP_FILE" 2>/dev/null || true
                    printf '\n\n' >> "$TEMP_FILE"
                fi
            fi
        done <<< "$SPEC_SECTIONS"
    fi

    # Inject source files
    local SOURCE_FILES
    SOURCE_FILES=$(echo "$MANIFEST" | jq -r '.context.source_files // [] | .[]' 2>/dev/null || true)
    if [ -n "$SOURCE_FILES" ]; then
        while IFS= read -r FILE_PATH; do
            [ -z "$FILE_PATH" ] && continue
            if [ -f "$FILE_PATH" ]; then
                printf '### %s\n\n```kotlin\n' "$FILE_PATH" >> "$TEMP_FILE"
                cat "$FILE_PATH" >> "$TEMP_FILE"
                printf '\n```\n\n' >> "$TEMP_FILE"
            fi
        done <<< "$SOURCE_FILES"
    fi

    echo "$TEMP_FILE"
}

# ──────────────────────────────────────────────────────
# Change 4: Programmatic guardrails (parse JSON output)
# ──────────────────────────────────────────────────────
check_guardrails() {
    local OUTPUT="$1"
    local FORCE_PARTIAL=false

    # Requires jq; skip guardrail checks if unavailable
    if ! command -v jq &>/dev/null; then
        echo "false"
        return
    fi

    # Guard 1: Read thrashing — low unique-to-total file read ratio
    local TOTAL_READS UNIQUE_READS
    TOTAL_READS=$(jq '[.result.tool_calls // [] | .[] | select(.name=="Read")] | length' "$OUTPUT" 2>/dev/null || echo "0")
    UNIQUE_READS=$(jq '[.result.tool_calls // [] | .[] | select(.name=="Read") | .input.file_path] | unique | length' "$OUTPUT" 2>/dev/null || echo "0")

    if [ "$TOTAL_READS" -gt 10 ]; then
        local READ_RATIO
        READ_RATIO=$(echo "scale=2; $UNIQUE_READS / $TOTAL_READS" | bc 2>/dev/null || echo "1")
        if [ "$(echo "$READ_RATIO < 0.50" | bc -l 2>/dev/null || echo "0")" = "1" ]; then
            echo "WARN: Read thrashing detected (ratio=$READ_RATIO, $UNIQUE_READS unique / $TOTAL_READS total)." >&2
            FORCE_PARTIAL=true
        fi
    fi

    # Guard 2: Consecutive build failures — 3+ in a row
    local CONSECUTIVE_FAILURES
    CONSECUTIVE_FAILURES=$(jq '[.result.tool_calls // [] | .[] | select(.name=="Bash" and (.input.command | test("gradlew"))) | .exit_code // 0] |
        [foreach .[] as $x (0; if $x != 0 then . + 1 else 0 end)] | max // 0' "$OUTPUT" 2>/dev/null || echo "0")
    if [ "$CONSECUTIVE_FAILURES" -ge 3 ]; then
        echo "WARN: $CONSECUTIVE_FAILURES consecutive build failures detected." >&2
        FORCE_PARTIAL=true
    fi

    # Guard 3: Token ceiling
    local TOTAL_TOKENS
    TOTAL_TOKENS=$(jq '(.result.usage.input_tokens // 0) + (.result.usage.output_tokens // 0)' "$OUTPUT" 2>/dev/null || echo "0")
    local TOKEN_CEILING=500000
    if [ "$TOTAL_TOKENS" -gt "$TOKEN_CEILING" ]; then
        echo "WARN: Token ceiling exceeded ($TOTAL_TOKENS > $TOKEN_CEILING)." >&2
    fi

    echo "$FORCE_PARTIAL"
}

# ──────────────────────────────────────────────────────
# Change 8: Extract metrics from JSON output
# ──────────────────────────────────────────────────────
extract_metrics() {
    local OUTPUT="$1"

    if ! command -v jq &>/dev/null; then
        echo "0,0,0,0,0,unknown"
        return
    fi

    jq -r '[
        .result.usage.input_tokens // 0,
        .result.usage.output_tokens // 0,
        .result.usage.cache_read_input_tokens // 0,
        .result.usage.cache_creation_input_tokens // 0,
        (.result.tool_calls // [] | length),
        .result.model // "unknown"
    ] | @csv' "$OUTPUT" 2>/dev/null || echo "0,0,0,0,0,unknown"
}

# ──────────────────────────────────────────────────────
# Extract current phase from IMPLEMENTATION_PLAN.md
# ──────────────────────────────────────────────────────
extract_phase() {
    # Find the first unchecked acceptance criterion to identify the current phase
    grep -oP 'Phase \d+\.\d+' IMPLEMENTATION_PLAN.md 2>/dev/null | tail -1 || echo "unknown"
}

# ──────────────────────────────────────────────────────
# Main loop
# ──────────────────────────────────────────────────────
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Mode:   $MODE"
echo "Prompt: $PROMPT_FILE"
echo "Branch: $CURRENT_BRANCH"
[ "$MAX_ITERATIONS" -gt 0 ] && echo "Max:    $MAX_ITERATIONS iterations"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

# Verify prompt file exists
if [ ! -f "$PROMPT_FILE" ]; then
    echo "Error: $PROMPT_FILE not found"
    exit 1
fi

# Generate codebase index for Haiku pre-pass (once per loop start)
generate_codebase_index

while true; do
    if [ "$MAX_ITERATIONS" -gt 0 ] && [ "$ITERATION" -ge "$MAX_ITERATIONS" ]; then
        echo "Reached max iterations: $MAX_ITERATIONS"
        break
    fi

    ITER_START=$(date +%s)
    PHASE=$(extract_phase)

    # ── Model routing ──
    # Always populate TASK_SECTION (needed for pre-pass and thinking budget)
    TASK_SECTION=$(sed -n "/### ${PHASE##Phase }/,/^###/p" IMPLEMENTATION_PLAN.md 2>/dev/null | head -30 || echo "")
    if [ -n "$MODEL_OVERRIDE" ]; then
        MODEL="$MODEL_OVERRIDE"
    else
        MODEL=$(classify_task "$PHASE" "$TASK_SECTION")
    fi

    THINKING_BUDGET=$(get_thinking_budget "$MODEL" "$TASK_SECTION")

    # ── Haiku context pre-pass ──
    HAIKU_MANIFEST=$(haiku_context_prepass "$PHASE" "$TASK_SECTION")
    HAIKU_USED="false"
    [ -n "$HAIKU_MANIFEST" ] && HAIKU_USED="true"
    ENRICHED_PROMPT=$(build_enriched_prompt "$HAIKU_MANIFEST" "$PROMPT_FILE")

    echo "──────── Iteration $((ITERATION + 1)) | Phase: $PHASE | Model: $MODEL | Thinking: $THINKING_BUDGET | Haiku: $HAIKU_USED ────────"

    # ── Run Claude iteration ──
    # -p: Headless mode (non-interactive, reads from stdin)
    # --dangerously-skip-permissions: Auto-approve all tool calls
    # --output-format json: Structured output for telemetry + guardrails
    # --max-turns 40: Hard cap to prevent runaway loops
    # --verbose: Detailed execution logging
    cat "$ENRICHED_PROMPT" | claude -p \
        --dangerously-skip-permissions \
        --model "$MODEL" \
        --max-turns 40 \
        --output-format json \
        --verbose \
        > "$OUTPUT_FILE" 2>&1

    EXIT_CODE=$?
    ITER_END=$(date +%s)
    DURATION=$((ITER_END - ITER_START))

    # Clean up enriched prompt temp file
    rm -f "$ENRICHED_PROMPT"

    # ── Determine outcome ──
    OUTCOME="success"
    [ "$EXIT_CODE" -ne 0 ] && OUTCOME="fail"

    # ── Telemetry: extract and log metrics ──
    METRICS=$(extract_metrics "$OUTPUT_FILE")
    echo "$(date -Iseconds),$PHASE,$DURATION,$EXIT_CODE,$OUTCOME,$RETRY_COUNT,$MODEL,$METRICS,$HAIKU_USED" >> "$METRICS_FILE"

    # ── Routing feedback loop ──
    echo "$(date -Iseconds),$PHASE,$MODEL,$EXIT_CODE,$RETRY_COUNT" >> "$ROUTING_METRICS_FILE"

    # ── Programmatic guardrails ──
    FORCE_PARTIAL=$(check_guardrails "$OUTPUT_FILE")
    if [ "$FORCE_PARTIAL" = "true" ]; then
        echo "GUARDRAIL: Forcing partial commit due to detected issues."
        git add -A 2>/dev/null && git commit -m "WIP: partial work (guardrail triggered) for $PHASE" 2>/dev/null || true
    fi

    # ── Handle failure with retry escalation ──
    if [ "$OUTCOME" = "fail" ]; then
        RETRY_COUNT=$((RETRY_COUNT + 1))
        if [ "$RETRY_COUNT" -ge 2 ] && [ "$MODEL" = "sonnet" ]; then
            echo "ESCALATE: Sonnet failed $RETRY_COUNT times, escalating to Opus for next attempt."
            MODEL_OVERRIDE="opus"
        fi
        if [ "$RETRY_COUNT" -ge 4 ]; then
            echo "ABORT: Too many retries ($RETRY_COUNT) for $PHASE. Moving on."
            RETRY_COUNT=0
            MODEL_OVERRIDE=""
        fi
    else
        RETRY_COUNT=0
        MODEL_OVERRIDE="${MODEL_OVERRIDE:-}"
        # Only clear override if it was set by escalation (not by plan mode)
        if [ "$MODE" != "plan" ]; then
            MODEL_OVERRIDE=""
        fi
    fi

    ITERATION=$((ITERATION + 1))
    echo -e "\n======================== LOOP $ITERATION (${DURATION}s, $OUTCOME) ========================\n"

    # ── Cache keepalive between iterations ──
    # Determine what model the next iteration will use
    NEXT_MODEL="${MODEL_OVERRIDE:-sonnet}"
    if [ "$MODE" = "plan" ]; then
        NEXT_MODEL="opus"
    fi
    maybe_keepalive "$DURATION" "$NEXT_MODEL"
done
