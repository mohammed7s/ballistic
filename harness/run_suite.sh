#!/usr/bin/env bash
# Run a fixed set of AndroidWorld tasks one after another and print a pass table.
# Usage: harness/run_suite.sh [--agent t3a|m3a|core] [--provider openai|anthropic] [--effort low|medium|high]
#                             [--model NAME] [model] [base_url]
#   --agent defaults to t3a; core needs ballistic-core built (core/). Confirmations are approved.
#   --provider anthropic (core only) calls the Anthropic Messages API through the official SDK;
#   base_url is not used, the model defaults to claude-haiku-5-5 and --effort to low.
# The API key is read by run_task.py from the environment: BALLISTIC_API_KEY (openai) or
# ANTHROPIC_API_KEY (anthropic); it is never put on a command line. ~/.ballistic_env is sourced
# only when BALLISTIC_API_KEY is not already set, so a caller can pick another key
# (BALLISTIC_API_KEY="$BALLISTIC_API_KEY_2" bash harness/run_suite.sh ...); for anthropic, only
# ANTHROPIC_API_KEY is read from the file when it is not already set.
# Env: TASKS="A B C" to override the default 20. THINKING_HEADROOM (anthropic; default 2048):
# tokens added to core's per-role max_tokens, since thinking counts toward it.
set -uo pipefail
cd "$(dirname "$0")/.."
if [ -z "${BALLISTIC_API_KEY:-}" ] && [ -f ~/.ballistic_env ]; then set -a; source ~/.ballistic_env; set +a; fi
export BALLISTIC_API_KEY="${BALLISTIC_API_KEY:-}"
AGENT=t3a
PROVIDER=openai
EFFORT=low
MODEL_FLAG=
POS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --agent) AGENT="${2:?--agent needs t3a, m3a or core}"; shift 2 ;;
    --agent=*) AGENT="${1#--agent=}"; shift ;;
    --provider) PROVIDER="${2:?--provider needs openai or anthropic}"; shift 2 ;;
    --provider=*) PROVIDER="${1#--provider=}"; shift ;;
    --effort) EFFORT="${2:?--effort needs low, medium or high}"; shift 2 ;;
    --effort=*) EFFORT="${1#--effort=}"; shift ;;
    --model) MODEL_FLAG="${2:?--model needs a name}"; shift 2 ;;
    --model=*) MODEL_FLAG="${1#--model=}"; shift ;;
    *) POS+=("$1"); shift ;;
  esac
done
case "$PROVIDER" in
  openai)
    MODEL="${MODEL_FLAG:-${POS[0]:-gemini-flash-lite-latest}}"
    BASE_URL="${POS[1]:-https://generativelanguage.googleapis.com/v1beta/openai/}"
    PROVIDER_ARGS=(--base_url "$BASE_URL")
    ;;
  anthropic)
    [ "$AGENT" = core ] || { echo "--provider anthropic needs --agent core"; exit 2; }
    MODEL="${MODEL_FLAG:-${POS[0]:-claude-haiku-5-5}}"
    BASE_URL=anthropic
    if [ -z "${ANTHROPIC_API_KEY:-}" ] && [ -f ~/.ballistic_env ]; then
      ANTHROPIC_API_KEY="$(set -a; source ~/.ballistic_env >/dev/null 2>&1; printf %s "${ANTHROPIC_API_KEY:-}")"
    fi
    [ -n "${ANTHROPIC_API_KEY:-}" ] || { echo "--provider anthropic needs ANTHROPIC_API_KEY (environment or ~/.ballistic_env)"; exit 2; }
    export ANTHROPIC_API_KEY
    PROVIDER_ARGS=(--provider anthropic --effort "$EFFORT" --thinking_headroom "${THINKING_HEADROOM:-2048}")
    ;;
  *) echo "--provider must be openai or anthropic"; exit 2 ;;
esac
PY=~/android_world/.venv/bin/python
TASKS="${TASKS:-ContactsAddContact ClockTimerEntry ClockStopWatchRunning OpenAppTaskEval SystemWifiTurnOff SystemBluetoothTurnOn SystemBrightnessMax TurnOnWifiAndOpenApp SimpleSmsSend SimpleSmsReplyMostRecent SimpleCalendarAddOneEvent SimpleCalendarEventsOnDate MarkorCreateNote MarkorDeleteNote MarkorCreateFolder ExpenseAddSingle ExpenseDeleteSingle RecipeAddSingleRecipe FilesDeleteFile CameraTakePhoto}"
OUT=~/ballistic_runs/suite_${AGENT}_$(date +%Y%m%d-%H%M%S).tsv
mkdir -p ~/ballistic_runs
printf "# agent=%s model=%s base_url=%s seed=%s commit=%s core_model_min_interval=%s%s\n" "$AGENT" "$MODEL" "$BASE_URL" "${SEED:-0}" "$(git rev-parse --short HEAD 2>/dev/null || echo "${BALLISTIC_COMMIT:-unknown}")" "${CORE_MODEL_MIN_INTERVAL:-0}" "$([ "$PROVIDER" = anthropic ] && echo " provider=anthropic effort=$EFFORT thinking_headroom=${THINKING_HEADROOM:-2048}")" > "$OUT"
"$PY" -c 'from harness.suite_row import COLUMNS; print("\t".join(COLUMNS))' >> "$OUT"
attempted=0
stop_code=0
for t in $TASKS; do
  attempted=$((attempted + 1))
  log="${OUT%.tsv}_${t}.log"
  timeout --signal=INT --kill-after=15s "${TASK_TIMEOUT:-900}" "$PY" -u harness/run_task.py \
      --task "$t" --agent "$AGENT" --model "$MODEL" "${PROVIDER_ARGS[@]}" \
      --core_model_min_interval "${CORE_MODEL_MIN_INTERVAL:-0}" \
      --seed "${SEED:-0}" --confirm approve --max_tokens 2000 --timeout 120 > "$log" 2>&1
  process_exit=$?
  "$PY" harness/suite_row.py --task "$t" --log "$log" --exit "$process_exit" --stop-on-quota | tee -a "$OUT"
  row_exit=${PIPESTATUS[0]}
  if [ "$row_exit" -eq 75 ]; then
    echo "HTTP 429: suite stopped; remaining tasks were not run. Inspect quota telemetry before resuming."
    stop_code=75
    break
  fi
  if [ "$row_exit" -eq 76 ]; then
    echo "Provider billing error (e.g. credit balance too low): suite stopped; remaining tasks were not run. Add credit before resuming."
    stop_code=76
    break
  fi
done
echo "$AGENT: combined passed $(awk -F '\t' '$2 == "PASS" {n++} END {print n+0}' "$OUT") / $attempted attempted ($(echo $TASKS | wc -w) requested)   ($OUT)"
echo "benchmark state passed $(awk -F '\t' '$6 == "passed" {n++} END {print n+0}' "$OUT") / $attempted attempted"
exit "$stop_code"
