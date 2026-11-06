#!/usr/bin/env bash
# Interactive prompt for the emulator agent. Usage: bash harness/chat.sh [extra flags]
# Defaults to Gemini flash-lite; key from ~/.ballistic_env (BALLISTIC_API_KEY).
set -uo pipefail
cd "$(dirname "$0")"
[ -f ~/.ballistic_env ] && source ~/.ballistic_env
exec ~/android_world/.venv/bin/python chat.py \
  --model "${BALLISTIC_MODEL:-gemini-flash-lite-latest}" \
  --base_url "${BALLISTIC_BASE_URL:-https://generativelanguage.googleapis.com/v1beta/openai/}" \
  --api_key "${BALLISTIC_API_KEY:-none}" --max_tokens 2000 --timeout 120 "$@"
