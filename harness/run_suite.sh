#!/usr/bin/env bash
# Run a fixed set of AndroidWorld tasks one after another and print a pass table.
# Usage: harness/run_suite.sh [--agent t3a|m3a|core] [model] [base_url]
#   --agent defaults to t3a; core needs ballistic-core built (core/). Confirmations are approved.
# The API key is read by run_task.py from BALLISTIC_API_KEY in the environment; it is never put on
# a command line. ~/.ballistic_env is sourced only when BALLISTIC_API_KEY is not already set, so a
# caller can pick another key (BALLISTIC_API_KEY="$BALLISTIC_API_KEY_2" bash harness/run_suite.sh ...).
# Env: TASKS="A B C" to override the default 20.
set -uo pipefail
cd "$(dirname "$0")/.."
if [ -z "${BALLISTIC_API_KEY:-}" ] && [ -f ~/.ballistic_env ]; then set -a; source ~/.ballistic_env; set +a; fi
export BALLISTIC_API_KEY="${BALLISTIC_API_KEY:-}"
AGENT=t3a
POS=()
while [ $# -gt 0 ]; do
  case "$1" in
    --agent) AGENT="${2:?--agent needs t3a, m3a or core}"; shift 2 ;;
    --agent=*) AGENT="${1#--agent=}"; shift ;;
    *) POS+=("$1"); shift ;;
  esac
done
MODEL="${POS[0]:-gemini-flash-lite-latest}"
BASE_URL="${POS[1]:-https://generativelanguage.googleapis.com/v1beta/openai/}"
PY=~/android_world/.venv/bin/python
TASKS="${TASKS:-ContactsAddContact ClockTimerEntry ClockStopWatchRunning OpenAppTaskEval SystemWifiTurnOff SystemBluetoothTurnOn SystemBrightnessMax TurnOnWifiAndOpenApp SimpleSmsSend SimpleSmsReplyMostRecent SimpleCalendarAddOneEvent SimpleCalendarEventsOnDate MarkorCreateNote MarkorDeleteNote MarkorCreateFolder ExpenseAddSingle ExpenseDeleteSingle RecipeAddSingleRecipe FilesDeleteFile CameraTakePhoto}"
OUT=~/ballistic_runs/suite_${AGENT}_$(date +%Y%m%d-%H%M%S).tsv
mkdir -p ~/ballistic_runs
printf "# agent=%s model=%s base_url=%s seed=%s commit=%s\n" "$AGENT" "$MODEL" "$BASE_URL" "${SEED:-0}" "$(git rev-parse --short HEAD 2>/dev/null || echo "${BALLISTIC_COMMIT:-unknown}")" > "$OUT"
printf "task\tresult\tsteps\tseconds\texit\tbenchmark\tagent_done\tfailure_category\tmodel_calls\tprompt_tokens\trun_json\n" >> "$OUT"
for t in $TASKS; do
  log="${OUT%.tsv}_${t}.log"
  timeout --signal=INT --kill-after=15s "${TASK_TIMEOUT:-900}" "$PY" -u harness/run_task.py \
      --task "$t" --agent "$AGENT" --model "$MODEL" --base_url "$BASE_URL" \
      --seed "${SEED:-0}" --confirm approve --max_tokens 2000 --timeout 120 > "$log" 2>&1
  process_exit=$?
  "$PY" harness/suite_row.py --task "$t" --log "$log" --exit "$process_exit" | tee -a "$OUT"
done
echo "$AGENT: combined passed $(awk -F '\t' '$2 == "PASS" {n++} END {print n+0}' "$OUT") / $(echo $TASKS | wc -w)   ($OUT)"
echo "benchmark state passed $(awk -F '\t' '$6 == "passed" {n++} END {print n+0}' "$OUT") / $(echo $TASKS | wc -w)"
