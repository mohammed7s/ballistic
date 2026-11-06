#!/usr/bin/env bash
# Interactive prompt for the emulator agent. Usage: bash harness/chat.sh [extra flags]
#   bash harness/chat.sh                 T3A (default)
#   bash harness/chat.sh --agent core    Ballistic's agent (ballistic-core; build it first in core/)
# Defaults to Gemini flash-lite. The key is read by chat.py from BALLISTIC_API_KEY in the
# environment (~/.ballistic_env is sourced and exported here); it is never put on a command line.
set -uo pipefail
cd "$(dirname "$0")"
if [ -f ~/.ballistic_env ]; then set -a; source ~/.ballistic_env; set +a; fi
exec ~/android_world/.venv/bin/python chat.py \
  --model "${BALLISTIC_MODEL:-gemini-flash-lite-latest}" \
  --base_url "${BALLISTIC_BASE_URL:-https://generativelanguage.googleapis.com/v1beta/openai/}" \
  --max_tokens 2000 --timeout 120 "$@"
