# Text-only model transport — offline change, 7 November 2026

## Scope and evidence

Implemented on `f6a3a47`, separately from the frozen emulator benchmark `7a9238e`.
The parent subsequently reported owner-excerpt integration `0751a68` (206 Rust / 88 Python /
45 JVM); those changes and counts are not part of this checkout. Its new reader summary JSON
shares the Reader role with plain element reads, reinforcing that a blanket reader JSON
response format would be wrong.

The parent supplied one new Core5 OpenAppTaskEval observation: HTTP 200,
`response_shape=missing_content`, `finish_reason=tool_calls`, `refusal_present=false`,
`tool_calls_present=true`, `function_call_present=false`. That is evidence of a native tool
response for this call, not proof of a server root cause. Historical missing-content `other`
cannot be retrospectively attributed to it. No live records were accessed here.

Python sends model/messages/temperature/max_tokens and no tools. Android sends
model/messages/max_tokens and no tools. Both ports supply text to core, whose existing JSON,
reader and confirmation boundaries remain authoritative. Native provider calls have no
Ballistic action authority.

## Primary-source assessment

Sources consulted on 7 November 2026:

- [Gemini OpenAI compatibility](https://ai.google.dev/gemini-api/docs/openai?linkId=13271420)
  demonstrates `tool_choice="auto"` together with function declarations at
  `generativelanguage.googleapis.com/v1beta/openai/`. It describes compatibility as beta.
  The retrieved material does **not** establish `tool_choice="none"` with `tools` omitted.
  Direct web open failed (redirect/access and markdown errors); the official page's indexed
  content was available through web search. This is not an endpoint acceptance test.
- [Google Cloud OpenAI migration overview](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/migrate/openai/overview)
  explicitly lists `none`, `auto` and `required`. This is a different hosted endpoint; its
  supported-parameter list does not prove acceptance at the Gemini Developer API endpoint.
- [Google native API mode reference](https://ai.google.dev/api/caching?authuser=01&hl=en)
  describes `FunctionCallingConfig.NONE` as disabling function prediction, with behavior
  equivalent to omitting declarations. This supports the intended semantics, not an assertion
  about the OpenAI compatibility adapter's validation or behavior.

No forum workaround claims are used as evidence. Endpoint acceptance without `tools` remains
uncertain, so forcing the parameter onto every existing compatible endpoint is unwarranted.
There is no claim that the optional field prevents the observed provider response or improves
task completion. Either requires a separately authorized, controlled live comparison.

## Implementation

- Python: boolean `disable_native_tools=False`, exposed as `--core_disable_native_tools` for
  core harness calls. Android: boolean `disable_native_tools=false` per configured endpoint,
  inherited through the existing endpoint role fallbacks. Opt-in adds only
  `tool_choice: "none"`; default requests omit it. No arbitrary extra-body configuration.
- No `tools` declaration, provider URL special case, model change, temperature/thinking
  adjustment, `response_format`, schema, token/step/attempt-budget change, automatic probe or
  compatibility fallback. HTTP 400 remains terminal. Existing transport retry policy remains.
- Both hosts reject native tool/function/refusal payloads and the standard `tool_calls`,
  `function_call`, `content_filter` finish signals even alongside nonempty text. This closes
  the prior mixed-response gap: accompanying action JSON cannot be accepted independently of
  the envelope. Python retains missing/null/empty response-shape diagnostics; mixed text
  errors use `non_text_completion`, with safe boolean presence flags and allowlisted finish
  metadata. Tool/refusal/filter responses are not regenerated or dispatched.
- Unknown fields never become provider error prose. Android HTTP errors now report status
  without raw body excerpts (previously only the exact key was scrubbed). Native arguments,
  refusal text and arbitrary finish reasons remain outside diagnostics. Existing key storage,
  Stop policy, validator, reader resolution and operation confirmation are unchanged.

## Offline verification

Existing base suite sizes are 188 Rust with UniFFI, 86 Python, 44 JVM; these are separate from
the parent's newer owner-excerpt counts. Initial Python baseline: 86 discovered, 85 passed,
one core-binary integration test skipped. Initial Rust run without UniFFI: 185 passed.
Initial JVM baseline build succeeded before the fresh host-library path existed; native skip
counts were not captured, so it is not claimed as a complete native baseline.

Focused regressions were added before implementation. Python's initial five new methods
reported 30 failing subcases and six errors (including the absent opt-in constructor argument).
The existing mixed verifier response could let a run exit successfully. JVM's initial nine
ModelsHttp tests had five failures, covering unsupported new config, accepted mixed responses
and provider-body leakage. Additional positive cases and JVM/core integration followed.

Final results on this checkout:

- **188 Rust passed**, `cargo test --offline --features uniffi`.
- **92 Python passed, zero skips**, including all six new transport methods, with
  `BALLISTIC_TEST_CORE_BIN` pointing at this worktree's freshly built debug binary.
- **50 JVM passed, zero skips**, including all ten ModelsHttp tests and real-core binding,
  Stop and confirmation coverage, using this worktree's fresh UniFFI host library.

Python inspects actual `requests.PreparedRequest` JSON with a fake Session transport;
JVM uses a loopback-only HTTP fixture. All four roles are checked for unchanged model,
messages and token budget, opt-in `none`, omission by default and absence of extra format/tools
fields. HTTP 400 has no fallback. Native, refusal and filter signals are terminal with or
without text; unknown metadata and native payloads are excluded from diagnostics/errors.
Both hosts run the real Rust core with synthetic screens to assert zero Hands dispatch on
an initial native response, or exactly one approved dispatch and no redispatch when the
verifier returns a native/mixed response after a guarded action.

Reproduction from this checkout (tool paths are local prerequisites):

```bash
CARGO_TARGET_DIR="$PWD/core/target" /home/wakeel/.cargo/bin/cargo test --offline --manifest-path core/Cargo.toml --features uniffi
CARGO_TARGET_DIR="$PWD/core/target" /home/wakeel/.cargo/bin/cargo build --offline --manifest-path core/Cargo.toml --features uniffi
BALLISTIC_TEST_CORE_BIN="$PWD/core/target/debug/ballistic-core" python3 -m unittest discover -s harness -v
cd apps/assistant
JAVA_HOME=/home/wakeel/ballistic-sign/prebuilts/jdk/jdk21/linux-x86 BALLISTIC_HOST_CORE="$PWD/../../core/target/debug/libballistic_core.so" ./gradlew --offline :core-api:test --max-workers=2 --rerun-tasks
```

No SSH, ADB, emulator/device, live model, key, OS repository or buildserver access; no push or
merge. Public primary-doc retrieval was the only external investigation. The frozen benchmark
and its run parameters remain unchanged. Known compatibility risk: an opted-in endpoint may
reject or ignore `tool_choice: "none"` without declarations. No live uplift is claimed.
