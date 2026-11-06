#!/usr/bin/env bash
# Start (or report) the AndroidWorld emulator, headless, with the gRPC port AndroidWorld needs.
# Usage: harness/run_emulator.sh [start|stop|status]
#   WINDOW=1  show the emulator window on the local desktop (DISPLAY, default :1) with host GPU
#             instead of headless software rendering; lighter on the CPU and you can watch it.
set -euo pipefail
AVD_NAME="${AVD_NAME:-AndroidWorldAvd}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}" ANDROID_SDK_ROOT="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
LOG="$HOME/emulator.log"

running() { pgrep -f "qemu-system-x86_64.*-avd $AVD_NAME" >/dev/null 2>&1; }

case "${1:-start}" in
  start)
    if running; then echo "emulator already running"; else
      if [ "${WINDOW:-0}" = "1" ]; then
        export DISPLAY="${DISPLAY:-:1}"
        nohup emulator -avd "$AVD_NAME" -no-snapshot -grpc 8554 -gpu host -no-audio -no-boot-anim >"$LOG" 2>&1 </dev/null &
      else
        nohup emulator -avd "$AVD_NAME" -no-snapshot -grpc 8554 -no-window -gpu swiftshader_indirect -no-audio -no-boot-anim >"$LOG" 2>&1 </dev/null &
      fi
      echo "started, log: $LOG"
    fi
    echo -n "waiting for boot"
    for _ in $(seq 1 120); do
      if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then echo " ok"; adb devices; exit 0; fi
      echo -n "."; sleep 5
    done
    echo " timed out; see $LOG"; exit 1 ;;
  stop)   adb emu kill >/dev/null 2>&1 || pkill -f "qemu-system-x86_64.*-avd $AVD_NAME" || true; echo stopped ;;
  status) running && { echo running; adb devices; adb shell getprop sys.boot_completed; } || echo "not running" ;;
  *) echo "usage: $0 [start|stop|status]"; exit 2 ;;
esac
