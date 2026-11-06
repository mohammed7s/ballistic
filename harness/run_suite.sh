#!/usr/bin/env bash
# Run a fixed set of AndroidWorld tasks one after another and print a pass table.
# Usage: harness/run_suite.sh [--agent t3a|m3a|core] [model] [base_url]
#   --agent defaults to t3a; core needs ballistic-core built (core/). Confirmations are approved.
# The API key is read by run_task.py from BALLISTIC_API_KEY in the environment (~/.ballistic_env is
# sourced and exported here); it is never put on a command line.
# Env: TASKS="A B C" to override the default 20.
set -uo pipefail
cd "$(dirname "$0")/.."
if [ -f ~/.ballistic_env ]; then set -a; source ~/.ballistic_env; set +a; fi
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
printf "# agent=%s model=%s base_url=%s\n" "$AGENT" "$MODEL" "$BASE_URL" > "$OUT"
printf "task\tresult\tsteps\tseconds\texit\n" >> "$OUT"
for t in $TASKS; do
  # run_task.py's verdict line, for every agent:  PASS|FAIL  <task>  steps=N  time=Ts  [exit=N  model_calls=N]
  # exit= is core's exit code (empty for t3a/m3a); a FAIL with a non-zero exit is core erroring, not the task.
  line=$($PY harness/run_task.py --task "$t" --agent "$AGENT" --model "$MODEL" --base_url "$BASE_URL" \
           --confirm approve --max_tokens 2000 --timeout 120 2>&1 \
         | grep -E "^(PASS|FAIL) " | tail -1)
  res=$(echo "$line" | awk '{print $1}'); res=${res:-ERROR}
  steps=$(echo "$line" | sed -n 's/.*steps=\([0-9]*\).*/\1/p')
  secs=$(echo "$line" | sed -n 's/.*time=\([0-9.]*\)s.*/\1/p')
  code=$(echo "$line" | sed -n 's/.*exit=\(-\{0,1\}[0-9]*\).*/\1/p')
  printf "%s\t%s\t%s\t%s\t%s\n" "$t" "$res" "$steps" "$secs" "$code" | tee -a "$OUT"
done
echo "$AGENT: passed $(grep -c $'\tPASS\t' "$OUT") / $(echo $TASKS | wc -w)   ($OUT)"
