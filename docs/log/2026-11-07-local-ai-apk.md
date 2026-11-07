# Embedded Local AI app delivery — 7 November 2026

The owner paused harness reliability work and requested focus on embedded AI, native UX and an
updated APK. No benchmark suite was run for this delivery. The existing delegated app work was
resumed after interruption; the harness task remained stopped.

## Implementation

The Models port now supports an in-process llama.cpp CPU backend through Kotlin/JNI. All local
roles share loaded weights with serialized ownership and cleared inference context per call.
Core action validation and confirmation remain in Rust. The connected HTTP backend is retained;
selection is frozen for each request and local failures never fall back to a server.

The native Local AI destination provides managed GGUF import, load/unload/remove, local versus
connected-model selection, phone-profile suggestions and measured-only performance. Ask links
new users to model setup and describes the ordinary app's limited action capabilities. Runtime
and weights are separate: the APK contains native libraries, not a bundled model.

llama.cpp is pinned to `80d2c0c2c56e4a1bbf74be98f64b99927bf230bc`, source-archive SHA-256
`e9bf2b83a13e14b5577dcdb65bb18ff8c049143caab22106547d37ef8c54fbf3`. Its license/component
notices ship as app assets. Upstream source is fetched only at build time.

## Real inference fixture

For a bounded runtime check, the parent downloaded the 105,454,432-byte
[SmolLM2-135M-Instruct Q4_K_M artifact](https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/blob/09816acd5d99df7be770d85ea30822623dab342c/SmolLM2-135M-Instruct-Q4_K_M.gguf).
Hugging Face artifact metadata declared Apache-2.0 and SHA-256
`2e8040ceae7815abe0dcb3540b9995eaa1fa0d2ca9e797d0a635ae4433c68c2d`; the downloaded bytes matched.
It is an ignored test fixture, not a shipped weight file or a recommended phone agent model.

The host native smoke produced 18 tokens. JNI tests separately produced nonempty text through
all four role labels, each recording 20 prompt / 8 generated tokens, and exercised cancellation
and reuse. These are laptop runtime checks with synthetic prompts, not task-quality, role safety,
phone speed, sustained thermal or battery evidence. A callable judge role does not complete the
core's planned deny-only judge.

## Device boundary

The ThinkPad SSH preflight timed out. The preceding harness worker reported its lock and drivers
released; this delivery did not acquire emulator ownership or change its state. No physical phone
was used. The current app still lacks general other-app observation and GUI input; local inference
does not supply those permissions. Production biometric approval remains unvalidated on a device.

The source checkout contained an unrelated documentation reorganization. Integration preserves
those edits and commits only the app feature plus its own documentation additions.

## Final validation and artifact

- Full host JVM suite: **62 passed, zero failures/errors/skips**, including real Rust/UniFFI and
  local JNI generation/cancellation checks.
- After the final context-default/control change: **9 affected tests passed**, zero failures,
  errors or skips. Final Compose compilation and `:app:assembleDebug` succeeded.
- Final APK: `ballistic_runs/local_ai_delivery_20261107/Ballistic-local-ai-debug.apk`,
  **53,829,429 bytes**, SHA-256
  `8807c540b992f0c8add862bd5227800f3f7ae9e0dc0a0de62a3dfd4a5f479857`.
- Parent independently checked both ABI inventories (Rust core, llama.cpp, C++ runtime, JNA),
  absence of GGUF weights, matching artifact digest and APK v2 signature verification.
- Source implementation originated at `67f43c8`; it was applied as a scoped patch onto parent
  `57faba5`. Core/app implementation at the delegate's `ed52b84` base and parent `57faba5` is
  identical before the app change. Existing parent edits in app/core files are documentation
  comments; they are preserved outside this feature commit.
- Evidence, reports and XML are copied into ignored
  `ballistic_runs/local_ai_delivery_20261107/`. No new device installation or runtime validation
  is claimed; the ThinkPad was unreachable. This is a debug prototype APK.

Fresh configuration uses a 4,096-token context. Local AI exposes 2K/4K/8K controls that unload the
model when changed. Saved settings are preserved. Models can have smaller native limits, and
larger context does not guarantee that a request or model fits memory. All phone profiles remain
unmeasured suggestions.
