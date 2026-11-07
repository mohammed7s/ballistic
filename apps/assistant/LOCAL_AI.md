# Local AI in Ballistic

7 November 2026. Implementation and validation status are recorded in the delivery log; an
installable debug APK does not establish model quality or production phone-control readiness.

## Where it sits

The native Ask screen submits a request to the same Rust core used by the development harness.
The core owns planning, filtered observations, action validation, verification and approval requests.
When it needs text from a model, its Models port calls the selected backend:

- **On device:** Kotlin routes the call through JNI to embedded llama.cpp and an imported GGUF.
- **Connected model:** the existing OpenAI-compatible HTTP adapter uses the owner's endpoint.

The runtime is packaged in the APK; model weights are a separate verified download or managed import. There is no
local server or Ollama process in this path. Android hosts Screen, Hands and Confirm separately;
adding local inference does not grant screen access or additional action permissions.

Planner, reader and verifier are distinct calls with core-owned inputs and output checks. The
local backend shares one loaded model across roles, serializes inference and clears the context
between calls. Separate calls are not process isolation or a claim of injection immunity. The
existing core safety limitations still apply. The judge role is not a completed deny-only judge.

## Using the app

1. Unlock Ballistic, then open **Local AI → Download & set up**.
2. Review the starter, download size, storage requirement and replacement notice. Confirm
   **Download & use on device**. No transfer begins just by opening the screen.
3. Ballistic downloads the pinned file, checks its byte count and SHA-256, selects local inference
   and loads it. If loading fails, the verified file remains available to load later.
4. Optionally run the short local speed check, then return to Ask.

The first catalogue entry is **Qwen3 0.6B Q8**, a 639,446,688-byte official Qwen artifact,
licensed Apache-2.0. It is a suggested starter **preview**, not a measured phone-agent winner.
All phone profiles currently offer the same small starter; a device-specific quality/performance
recommendation still requires target-phone testing.

Downloads contact Hugging Face and its HTTPS CDN, not a model inference service. This transfers
model files without sending prompts or API keys. The catalogue is shipped with the app and pins
an immutable source revision and digest. Keep Ballistic open while downloading: this version has
no background download service or resumable transfers. Stop discards the partial file; process
restart cleans abandoned partials. Retry starts from byte zero. Existing managed weights survive
network, size or digest failures. A completed verified replacement may remain installed if Stop
arrives during loading.

Manual **Import GGUF** remains available. Ballistic copies a compatible chat/instruct file into
private storage and leaves the source untouched. Imported files are validated by native load;
only catalogue downloads receive the expected publisher-artifact digest check.

The initial local configuration may need a larger context for an agent request than for the
short speed check. A context overflow fails explicitly; the runtime does not silently truncate
the request or send it to a server. A tiny model that passes the speed check is not necessarily
capable of producing valid agent actions.

Select a connected model explicitly to use an endpoint configured in Settings. Existing HTTP
configuration and encrypted API keys are retained. A missing, invalid or failed local model
never triggers a cloud fallback. Backend selection stays fixed throughout an Ask run; finish or
stop the run before changing it.

Model copies live under Android's no-backup app directory. Unload releases model resources;
remove deletes only the managed copy. After a process restart, load the model again. Stop
requests cancellation of local work and prevents subsequent core dispatch; it cannot undo an
action already submitted. Connected-model HTTP cancellation retains the existing limitations.

## What the overview measures

The Local AI screen reports load duration, first-token latency and model-call timing from this
process. Detailed metrics include prompt and output counts and processing/generation rates.
Unavailable values say **Not measured**. The short speed check measures model execution only,
not task success, battery life or sustained thermal performance.

Phone profiles are suggested CPU/context settings and candidate-model guidance. They are not
validated performance ratings. Downloads require the owner to confirm setup. Pixel results require measurements on a
Pixel; laptop and emulator results must be labeled separately.

## Current capability boundary

The app can launch supported apps through Android intents. Its notification adapter cannot
observe another app's complete screen, and general tap/type/scroll control still needs a platform
adapter. Local model support and the Local AI UI do not complete that integration. Production
biometric approval also remains subject to physical-device validation.

## Building this slice

Use the existing Rust/UniFFI build script, plus Android SDK platform 37, NDK `29.0.14206865`
and CMake `3.31.6` for the embedded runtime. The Android build includes `arm64-v8a` and `x86_64`.

```bash
cd apps/assistant
scripts/build_core.sh
./gradlew :core-api:test :app:assembleDebug --max-workers=2
```

The native CMake configuration pins the llama.cpp source revision and archive digest. For an
offline build, `-PllamaArchive=/absolute/path/to/the/pinned/archive.tar.gz` supplies the same
hash-verified source archive. Never substitute an unverified archive or bundle test weights.

Optional host JNI tests require a host CMake build of `app/src/main/cpp` and
`BALLISTIC_LLAMA_LIBRARY_DIR` pointing at its library directory. For the real inference check,
also set `BALLISTIC_LLAMA_TEST_MODEL` to a compatible local test file and
`BALLISTIC_LLAMA_TEST_MODEL_SHA256` to its independently verified digest. Without these inputs,
optional native/model tests are skipped; a generic Gradle success is not proof of real inference.
