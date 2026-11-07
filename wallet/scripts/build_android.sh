#!/usr/bin/env bash
# Cross-compile the wallet adapter with Tor for Android (wallet/README.md, "Android"):
#   <out>/jniLibs/<abi>/libballistic_wallet.so   C ABI for the app (src/ffi.rs)
#   <out>/bin/<abi>/ballistic-wallet             CLI, for adb runs on a device or emulator
#
#   wallet/scripts/build_android.sh [--abi arm64-v8a|x86_64]... [--api 33]
#                                   [--target-dir DIR] [--out DIR] [--features LIST]
#
# Needs: the Android NDK (ANDROID_NDK_HOME, else the newest under $ANDROID_HOME/ndk or
# ~/Android/Sdk/ndk), rustup, and cargo-ndk (`cargo install cargo-ndk`). No OpenSSL.
# Cargo defaults to two build jobs; override with CARGO_BUILD_JOBS.
set -euo pipefail

wallet="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
abis=()
api=33
target_dir="$wallet/target/android"
out="$wallet/target/android/dist"
features="https,tor"

while [ $# -gt 0 ]; do
  case "$1" in
    --abi) abis+=("$2"); shift 2 ;;
    --api) api="$2"; shift 2 ;;
    --target-dir) target_dir="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    --features) features="$2"; shift 2 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "unknown argument $1" >&2; exit 2 ;;
  esac
done
[ ${#abis[@]} -gt 0 ] || abis=(arm64-v8a x86_64)

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
  ANDROID_NDK_HOME="$(ls -d "$sdk"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
  ANDROID_NDK_HOME="${ANDROID_NDK_HOME%/}"
fi
[ -f "${ANDROID_NDK_HOME:-}/source.properties" ] || { echo "no Android NDK found; set ANDROID_NDK_HOME" >&2; exit 1; }
export ANDROID_NDK_HOME
echo "NDK: $ANDROID_NDK_HOME ($(sed -n 's/^Pkg.Revision = //p' "$ANDROID_NDK_HOME/source.properties"))"

if command -v rustup >/dev/null; then cargo=(rustup run stable cargo); else cargo=(cargo); fi
command -v cargo-ndk >/dev/null || { echo "cargo-ndk not found (cargo install cargo-ndk)" >&2; exit 1; }

triple() {
  case "$1" in
    arm64-v8a) echo aarch64-linux-android ;;
    x86_64) echo x86_64-linux-android ;;
    *) echo "unsupported ABI $1 (arm64-v8a, x86_64)" >&2; exit 2 ;;
  esac
}
for abi in "${abis[@]}"; do
  t="$(triple "$abi")"
  rustup target add --toolchain stable "$t"
done

cd "$wallet"
targets=()
for abi in "${abis[@]}"; do targets+=(-t "$abi"); done
# One cargo-ndk run per invocation builds the lib (cdylib, copied to jniLibs by -o) and the CLI.
"${cargo[@]}" ndk "${targets[@]}" -P "$api" -o "$out/jniLibs" \
  build --release --locked --jobs "${CARGO_BUILD_JOBS:-2}" \
  --no-default-features --features "$features" --target-dir "$target_dir"

for abi in "${abis[@]}"; do
  t="$(triple "$abi")"
  mkdir -p "$out/bin/$abi"
  cp "$target_dir/$t/release/ballistic-wallet" "$out/bin/$abi/ballistic-wallet"
  printf '%-10s %10s bytes  %s\n' "$abi" "$(stat -c %s "$out/jniLibs/$abi/libballistic_wallet.so")" "jniLibs/$abi/libballistic_wallet.so"
  printf '%-10s %10s bytes  %s\n' "$abi" "$(stat -c %s "$out/bin/$abi/ballistic-wallet")" "bin/$abi/ballistic-wallet"
done
echo "out: $out"
