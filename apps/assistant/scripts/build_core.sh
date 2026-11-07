#!/usr/bin/env bash
# Builds libballistic_core for the app and regenerates its Kotlin binding (core/src/ffi.rs).
#
#   1. Android libraries, arm64-v8a (phone) and x86_64 (emulator), into
#      app/src/main/jniLibs/<abi>/libballistic_core.so (git-ignored; this script makes them).
#   2. A host build of the same library (for the binding generator and :core-api:test).
#   3. The Kotlin binding from that library, into
#      core-api/src/main/kotlin/app/ballistic/assistant/core/ffi/ballistic_core.kt (committed).
#
# Needs: Rust stable with targets aarch64-linux-android and x86_64-linux-android, cargo-ndk,
# and an NDK under $ANDROID_HOME/ndk (or ANDROID_NDK_HOME). Override cargo with CARGO=...
# Usage: apps/assistant/scripts/build_core.sh [--no-android]
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
app="$(cd "$here/.." && pwd)"
core="$(cd "$app/../../core" && pwd)"
cargo="${CARGO:-cargo}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

cd "$core"
target_dir="$($cargo metadata --format-version 1 --no-deps | python3 -c 'import json,sys; print(json.load(sys.stdin)["target_directory"])')"

if [[ "${1:-}" != "--no-android" ]]; then
    $cargo ndk -t arm64-v8a -t x86_64 -P 33 -o "$app/app/src/main/jniLibs" build --release --features uniffi --lib
fi

$cargo build --release --features uniffi --lib
host_lib="$target_dir/release/libballistic_core.so"

$cargo run --release --features uniffi-cli --bin uniffi-bindgen -- \
    generate --library "$host_lib" --language kotlin --no-format \
    --out-dir "$app/core-api/src/main/kotlin"

echo
ls -l "$app"/app/src/main/jniLibs/*/libballistic_core.so 2>/dev/null || true
echo "binding: $app/core-api/src/main/kotlin/app/ballistic/assistant/core/ffi/ballistic_core.kt"
echo "host library: $host_lib"
echo "  (:core-api:test finds it under core/target; elsewhere export BALLISTIC_HOST_CORE=$host_lib)"
