#!/usr/bin/env bash
# Step 1 harness setup: Android emulator + AndroidWorld + local models (Ollama).
# Idempotent: safe to re-run; every stage checks whether it is already done.
# Target: any Ubuntu 24.04 x86_64 machine with KVM (laptop, cloud box, CI runner).
#
# Usage:  bash harness/setup_emulator.sh            # everything
#         bash harness/setup_emulator.sh --check    # only the preflight checks
#
# Layout it creates (nothing inside the repo):
#   ~/Android/Sdk            Android command-line tools, platform-tools, emulator, system image
#   ~/android_world          google-research/android_world checkout + Python venv
#   Ollama models            gemma3:4b (vision + text), qwen3:4b (text planner)
set -euo pipefail

SDK="$HOME/Android/Sdk"
AW="$HOME/android_world"
AVD_NAME="AndroidWorldAvd"
# AndroidWorld's documented target: Pixel 6 profile, API 33 (Tiramisu) google_apis x86_64.
SYS_IMAGE="system-images;android-33;google_apis;x86_64"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
MODELS=("gemma3:4b" "qwen3:4b")

log()  { printf '\n\033[1;32m==> %s\033[0m\n' "$*"; }
warn() { printf '\n\033[1;33m!!  %s\033[0m\n' "$*"; }
die()  { printf '\n\033[1;31mXX  %s\033[0m\n' "$*"; exit 1; }

preflight() {
  log "Preflight"
  # Power: the emulator image download + model pulls take a while; refuse on battery.
  if [ "$(cat /sys/class/power_supply/AC*/online 2>/dev/null | head -1)" != "1" ]; then
    die "Not on AC power (battery $(cat /sys/class/power_supply/BAT*/capacity 2>/dev/null | head -1)%). Plug in and re-run."
  fi
  # Disk: need ~25 GB for SDK (~10), AndroidWorld (~2), models (~6), AVD (~8).
  local free_gb; free_gb=$(df -BG --output=avail "$HOME" | tail -1 | tr -dc '0-9')
  [ "$free_gb" -ge 40 ] || die "Only ${free_gb} GB free in \$HOME; need 40+."
  echo "disk: ${free_gb} GB free"
  # KVM
  [ -r /dev/kvm ] && [ -w /dev/kvm ] || die "/dev/kvm not accessible; add user to kvm group or fix ACL."
  echo "kvm: ok"
  # RAM
  local mem_gb; mem_gb=$(free -g | awk '/^Mem:/{print $2}')
  [ "$mem_gb" -ge 16 ] || warn "Only ${mem_gb} GB RAM; emulator + models will be tight."
  echo "ram: ${mem_gb} GB"
  # Java 17+ for sdkmanager
  java -version 2>&1 | head -1 || die "Java not found (apt install openjdk-21-jdk-headless)."
  command -v python3 >/dev/null || die "python3 missing"
  python3 - <<'EOF' || die "Python 3.11+ required"
import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)
EOF
  echo "python: $(python3 --version)"
}

apt_deps() {
  log "apt packages"
  sudo apt-get install -y -qq unzip git python3-venv python3-pip libpulse0 libgl1 libnss3 libxcomposite1 libxcursor1 libxi6 libxtst6 libasound2t64 >/dev/null
}

sdk() {
  log "Android SDK -> $SDK"
  mkdir -p "$SDK/cmdline-tools"
  if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    tmp=$(mktemp -d); curl -fsSL "$CMDLINE_TOOLS_URL" -o "$tmp/ct.zip"
    unzip -q "$tmp/ct.zip" -d "$tmp"; rm -rf "$SDK/cmdline-tools/latest"
    mv "$tmp/cmdline-tools" "$SDK/cmdline-tools/latest"; rm -rf "$tmp"
  fi
  export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
  export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
  yes | sdkmanager --licenses >/dev/null 2>&1 || true
  sdkmanager --install "platform-tools" "emulator" "platforms;android-33" "$SYS_IMAGE" >/dev/null
  echo "sdk packages: ok"
  if ! avdmanager list avd 2>/dev/null | grep -q "Name: $AVD_NAME"; then
    echo no | avdmanager create avd -n "$AVD_NAME" -k "$SYS_IMAGE" -d "pixel_6" >/dev/null
  fi
  # AVD config: 4 GB RAM, 8 GB disk, hardware GPU off (headless-friendly), no boot animation.
  cfg="$HOME/.android/avd/$AVD_NAME.avd/config.ini"
  set_cfg() { grep -q "^$1=" "$cfg" && sed -i "s|^$1=.*|$1=$2|" "$cfg" || echo "$1=$2" >> "$cfg"; }
  set_cfg hw.ramSize 4096
  set_cfg disk.dataPartition.size 8G
  set_cfg hw.gpu.enabled yes
  set_cfg hw.gpu.mode swiftshader_indirect
  set_cfg hw.keyboard yes
  echo "avd $AVD_NAME: ok"
  # Shell env for future logins
  if ! grep -q 'ANDROID_HOME' "$HOME/.bashrc"; then
    cat >> "$HOME/.bashrc" <<EOF

# Android SDK (ballistic harness)
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/emulator:\$PATH"
EOF
  fi
}

android_world() {
  log "AndroidWorld -> $AW"
  if [ ! -d "$AW/.git" ]; then
    git clone -q --depth 1 https://github.com/google-research/android_world.git "$AW"
  else
    git -C "$AW" pull -q --ff-only || true
  fi
  if [ ! -x "$AW/.venv/bin/python" ]; then python3 -m venv "$AW/.venv"; fi
  "$AW/.venv/bin/pip" install -q --upgrade pip
  "$AW/.venv/bin/pip" install -q -r "$AW/requirements.txt"   # setup.py does not pull these in
  "$AW/.venv/bin/pip" install -q -e "$AW"
  "$AW/.venv/bin/pip" install -q openai   # for our OpenAI-compatible wrapper
  echo "android_world: ok ($("$AW/.venv/bin/python" -c 'import android_world, sys; print(sys.version.split()[0])'))"
}

models() {
  log "Ollama models"
  command -v ollama >/dev/null || die "ollama not installed (https://ollama.com/download)"
  systemctl is-active --quiet ollama || sudo systemctl start ollama
  for m in "${MODELS[@]}"; do
    if ollama list | awk '{print $1}' | grep -qx "$m"; then echo "$m: present"; else ollama pull "$m"; fi
  done
  # Ollama's OpenAI-compatible endpoint; keep models loaded 1h between calls.
  curl -fsS http://127.0.0.1:11434/v1/models >/dev/null && echo "ollama api: ok"
}

summary() {
  log "Done"
  cat <<EOF
Start the emulator (first boot takes a few minutes):
  \$ANDROID_HOME/emulator/emulator -avd $AVD_NAME -no-snapshot -grpc 8554 -no-window -no-audio &
  adb wait-for-device

Or: bash harness/run_emulator.sh start
First task (installs AndroidWorld's apps on a fresh AVD the first time):
  $AW/.venv/bin/python harness/run_task.py --perform_emulator_setup --task ContactsAddContact

Models served at http://127.0.0.1:11434/v1  (${MODELS[*]})
EOF
}

main() {
  preflight
  [ "${1:-}" = "--check" ] && exit 0
  apt_deps; sdk; android_world; models; summary
}
main "$@"
