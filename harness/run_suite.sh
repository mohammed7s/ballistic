#!/usr/bin/env bash
# Run a fixed set of AndroidWorld tasks one after another and print a pass table.
# Usage: harness/run_suite.sh [model] [base_url]   (API key from ~/.ballistic_env as BALLISTIC_API_KEY)
# Env: TASKS="A B C" to override the default 20.
set -uo pipefail
cd "$(dirname "$0")/.."
[ -f ~/.ballistic_env ] && source ~/.ballistic_env
MODEL="${1:-gemini-flash-lite-latest}"
BASE_URL="${2:-https://generativelanguage.googleapis.com/v1beta/openai/}"
PY=~/android_world/.venv/bin/python
TASKS="${TASKS:-ContactsAddContact ClockTimerEntry ClockStopWatchRunning OpenAppTaskEval SystemWifiTurnOff SystemBluetoothTurnOn SystemBrightnessMax TurnOnWifiAndOpenApp SimpleSmsSend SimpleSmsReplyMostRecent SimpleCalendarAddOneEvent SimpleCalendarEventsOnDate MarkorCreateNote MarkorDeleteNote MarkorCreateFolder ExpenseAddSingle ExpenseDeleteSingle RecipeAddSingleRecipe FilesDeleteFile CameraTakePhoto}"
OUT=~/ballistic_runs/suite_$(date +%Y%m%d-%H%M%S).tsv
printf "task\tresult\tsteps\tseconds\n" > "$OUT"
for t in $TASKS; do
  line=$($PY harness/run_task.py --task "$t" --model "$MODEL" --base_url "$BASE_URL" \
           --api_key "${BALLISTIC_API_KEY:-none}" --max_tokens 2000 --timeout 120 2>&1 \
         | grep -E "^(PASS|FAIL) " | tail -1)
  res=$(echo "$line" | awk '{print $1}'); res=${res:-ERROR}
  steps=$(echo "$line" | sed -n 's/.*steps=\([0-9]*\).*/\1/p')
  secs=$(echo "$line" | sed -n 's/.*time=\([0-9.]*\)s.*/\1/p')
  printf "%s\t%s\t%s\t%s\n" "$t" "$res" "$steps" "$secs" | tee -a "$OUT"
done
echo "passed $(grep -c $'\tPASS\t' "$OUT") / $(echo $TASKS | wc -w)   ($OUT)"
