# Harness (step 1): emulator + AndroidWorld + local models

Plain-language walkthrough (interactive prompt, tasks, suite, baseline): `docs/guide/emulator.html`.

Runs on any Ubuntu machine with KVM (a 32 GB laptop works). Everything is
installed by one idempotent script; re-running it is always safe.

```bash
git clone https://github.com/mohammed7s/ballistic.git ~/ballistic
bash ~/ballistic/harness/setup_emulator.sh --check   # preflight only
bash ~/ballistic/harness/setup_emulator.sh           # install everything (~20 GB download)
```

What it sets up:

| Piece | Where | Notes |
|---|---|---|
| Android SDK: cmdline-tools, platform-tools, emulator, API 33 google_apis x86_64 image | `~/Android/Sdk` | AndroidWorld's documented target (Pixel 6 / API 33). An Android 17 AVD for AppFunctions / Computer Control experiments comes later. |
| AVD `AndroidWorldAvd` | `~/.android/avd/` | 4 GB RAM, 8 GB data, software GPU, headless-friendly |
| AndroidWorld + venv | `~/android_world` | `google-research/android_world`, `pip install -e .` |
| Models via Ollama | Ollama store | `gemma3:4b` (vision + text), `qwen3:4b` (text). OpenAI-compatible API at `http://127.0.0.1:11434/v1` |

Model placement for the split later: the planner URL in the harness config can point at another
machine (e.g. a faster box over Tailscale); nothing else changes.

## Run

```bash
# 1. emulator (headless, waits for boot)
bash harness/run_emulator.sh start        # status | stop

# 2. one task with a local model (Ollama on :11434). First time on a fresh AVD add
#    --perform_emulator_setup (installs AndroidWorld's ~20 apps, takes ~10 min).
~/android_world/.venv/bin/python harness/run_task.py --task ContactsAddContact --agent t3a --model gemma3:4b
~/android_world/.venv/bin/python harness/run_task.py --task SimpleSmsSend   --agent m3a --model gemma3:4b
~/android_world/.venv/bin/python harness/run_task.py --list

# your own instruction, no benchmark task (watch it with WINDOW=1 below)
~/android_world/.venv/bin/python harness/run_task.py --goal "open Chrome and search for the weather in Berlin"

# a cloud model for fast iteration (2-3 s per call). The key is read from BALLISTIC_API_KEY in the
# environment (see "API key" below); set -a exports it to the python child.
set -a; source ~/.ballistic_env; set +a
~/android_world/.venv/bin/python harness/run_task.py --base_url https://generativelanguage.googleapis.com/v1beta/openai/ \
    --model gemini-flash-lite-latest --max_tokens 2000 --task ContactsAddContact

# interactive: type instructions one at a time and watch the emulator (Gemini; chat.sh sources
# ~/.ballistic_env itself, extra flags pass through to chat.py)
bash harness/chat.sh                  # T3A
bash harness/chat.sh --agent core     # Ballistic's agent
bash harness/chat.sh --agent core --chat   # a conversation: replies, questions and tasks

# the 20-task suite, pass table in ~/ballistic_runs/suite_<agent>_<time>.tsv
bash harness/run_suite.sh                         # T3A, Gemini flash-lite
bash harness/run_suite.sh --agent core            # Ballistic's agent, confirmations approved
bash harness/run_suite.sh --agent core qwen3:4b http://127.0.0.1:11434/v1

# planner on another machine: just change the endpoint
~/android_world/.venv/bin/python harness/run_task.py --base_url http://100.x.y.z:11434/v1 --model qwen3:4b --task ...
```

### API key

The key lives in `~/.ballistic_env` (`BALLISTIC_API_KEY=...`), never in the repo and never on a
command line (a command line shows in `ps` and in shell history). `chat.sh` and `run_suite.sh`
source the file with `set -a` so the variable reaches Python; `run_task.py`, `chat.py` and
`record_screens.py` read it from the environment when the model client is built. `--api_key`
still exists as an override for one-off use; no script passes it. No harness file prints the key
or writes it to a run file, and `ballistic-core` is started without it in its environment (model
calls are made by the harness, not by core).

To watch the emulator on the machine's own screen, start it with `WINDOW=1 bash harness/run_emulator.sh start`
from a terminal on that desktop (stop the headless one first). The agent runs identically; you just see it.

`run_task.py` prints each step and writes a JSON record to `~/ballistic_runs/`. `--agent t3a` is
text-only (UI tree), `m3a` sends the screenshot too (needs a vision model such as `gemma3:4b`).

### The core agent (`--agent core`)

`--agent core` runs Ballistic's own agent, the Rust process `ballistic-core` (`core/`, build it with
`cargo build --release` there; the harness finds it under cargo's target dir, or pass `--core_bin`).
Core asks and the harness answers, one JSON line each way (`core/src/bridge.rs`):
`harness/core_bridge.py` serves observe (screen from the accessibility tree), act (adb), complete
(the model endpoint) and confirm (a fixed policy), and decides nothing.

| Flag | Meaning |
|---|---|
| `--confirm approve\|deny` | answer to every confirmation core asks for (benchmark: approve; injection tests: deny) |
| `--reader_model`, `--judge_model`, `--verifier_model` | model name for that role on the same `--base_url`; default `--model` (the planner's) |
| `--core_bin` | path to `ballistic-core`; default `$BALLISTIC_CORE_BIN`, else the cargo target dirs |
| `--core_mode observe` | observe once and report counts (pipe smoke test) |
| `--core_model_min_interval N` | minimum seconds between core HTTP attempts across roles/retries, 0–60; default 0 disables pacing |
| `--core_disable_native_tools` | opt in to `tool_choice: "none"` for all core model roles; default false; endpoint must accept it without `tools` |
| `--provider openai\|anthropic` | `openai` (default): every role on the OpenAI-compatible `--base_url`, unchanged. `anthropic`: the Anthropic Messages API through the official `anthropic` SDK; `--base_url` is ignored; core only |
| `--effort low\|medium\|high` | provider `anthropic` only: `output_config.effort`; default `low` |
| `--thinking_headroom N` | provider `anthropic` only: tokens added to core's per-role `max_tokens`, since thinking counts toward it; 0–32000, default 2048 |

A core run's JSON has the same fields as before plus `core_args`, `roles`, `confirm`,
`finished` (core's last event), `confirmations`, `exit_code`, `stderr`, `provider` (and `effort`
for anthropic), and `lines`: every bridge line in both directions, in order.

### Anthropic provider (`--provider anthropic`)

Needs the SDK in the AndroidWorld venv (`~/android_world/.venv/bin/pip install anthropic`; 2.6.0
on the ThinkPad, 8 November 2026) and `ANTHROPIC_API_KEY` in the environment. `run_suite.sh`
reads only that variable from `~/.ballistic_env` when it is not already set; `chat.sh` sources the
file. The key is never on a command line, never logged and never in a run file, and core is
started without any `ANTHROPIC_*` variable.

```bash
bash harness/run_suite.sh --agent core --provider anthropic --model claude-haiku-5-5 --effort low
~/android_world/.venv/bin/python harness/run_task.py --agent core --provider anthropic \
    --model claude-haiku-5-5 --effort low --task ContactsAddContact
```

Model IDs exactly as published, no date suffix: `claude-haiku-5-5`, `claude-sonnet-5-5`,
`claude-opus-5-5`. Each call is `messages.create(model, max_tokens=<core's role cap>, system,
messages, output_config={"effort": ...})`: core's leading system message becomes `system`, the
rest are user/assistant turns with consecutive same-role messages merged. No `temperature`,
`top_p`, `top_k` or `thinking` is sent (current models reject sampling parameters; thinking is
left to the model's default, which effort controls). Only `text` blocks are returned to core;
thinking blocks are counted, never read. Thinking counts toward `max_tokens`, and
`claude-haiku-5-5` thinks by default: with core's caps alone (reader 64, verifier 400, planner
800) the first Haiku suite (8 November 2026, `docs/BENCHMARK_RUNS.md`) had 22 calls end at
`max_tokens` (verifier ones after 304–400 of 400 tokens of thinking) and 10 of 20 tasks end on a
reply with no text, four of them after the checker's state had passed. So the client sends core's cap plus `--thinking_headroom` (default 2048; `THINKING_HEADROOM`
for `run_suite.sh`; 0 sends core's cap unchanged). Each call records `max_tokens` (core's),
`max_tokens_sent` and `text_over_core_cap` (visible text tokens above core's cap; the text is
returned unchanged).

Outcomes, all recorded with code-owned names only (no provider message, body, prompt or key):

| Reply or error | Handling |
|---|---|
| `stop_reason: refusal` | terminal `ModelResponseError: refusal`; `error_category: refusal`, `refusal_category` (allowlisted, else `other`) |
| `tool_use` block, `tool_use` or `pause_turn` | terminal `non_text_completion` |
| no text, `end_turn` | one regeneration within the attempt budget, as on the OpenAI path |
| no text, `max_tokens` | terminal (same as an empty reply with `finish_reason: length`) |
| `OverloadedError` (529), other 5xx, `APIConnectionError` | transient: retried within the same budget (waits 2, 4, 8 s) |
| `APITimeoutError` | terminal (read timeouts are not retried on either path) |
| `RateLimitError` (429) with `retry-after` ≤ 60 s | retried after that delay within the budget; the retry keeps `http_status: 429`, `quota_category`, `retry_after_seconds` |
| `RateLimitError` without a usable delay | terminal; the suite stops (exit 75) as for any call that ends on 429 |
| credit balance too low (400), 402 `billing_error`, spend limit reached | terminal `error_category: billing`; `run_task.py` prints a billing line and `run_suite.sh` stops (exit 76) |
| any other 4xx | terminal, category `bad_request`, `authentication`, `permission`, `not_found` or `http_error` |

The SDK's own retries are off (`max_retries=0`), so pacing, the attempt budget and telemetry are
this client's. Usage: `prompt_tokens` = `input_tokens` + cache read + cache creation tokens,
`completion_tokens` = `output_tokens` (thinking included); `thinking_tokens`, `text_blocks`,
`thinking_blocks` and `stop_reason` are recorded per call. `suite_row.py` now stops the suite
only for a call that *ended* on 429; on the OpenAI path a 429 is never retried, so its behaviour
is unchanged. Offline coverage: `test_model_anthropic.py` (fake SDK always; the installed SDK
over a mocked transport when `anthropic` is importable).

### Prompt box (`chat.sh`)

One emulator connection for the session; each line typed is a fresh run starting from the
current screen. With `--agent core` each line starts
`ballistic-core run --request <line> --max-steps <budget>`; one line per step is printed from
core's log events, then core's finished report. Every run is written to
`~/ballistic_runs/<time>_Chat.json` (core runs: the same fields as `run_task.py --agent core`,
`lines` included).

With `--agent core --chat` each line is one conversation turn instead:
`ballistic-core converse --message <line> --history <JSON> --max-steps <budget>`. The chat
model (role `chat`, routed to `--model` like the planner) answers, asks a question, or turns the
line into a self-contained task that runs the agent loop on the same pipe. The harness keeps the
last `--chat_history_turns` turns (default 12) exactly as core returned them and passes them back;
`/clear` forgets them (`core/README.md` §7, Chat). `--core_judge` turns on core's deny-only judge
for these turns (off by default, as for benchmark runs). Offline coverage: `test_core_chat.py`.

| At the prompt | |
|---|---|
| any text | instruction |
| `/home` | go to the home screen |
| `/steps N` | step budget per instruction (default `--max_steps`, 15) |
| `/confirm approve\|deny` | core's confirmation policy from the next instruction on |
| `/agent core\|t3a\|m3a` | switch agents live |
| `/clear` | forget the conversation (`--chat`) |
| `/quit`, Ctrl+D | leave |
| Ctrl+C while a run is going | stop it (core: the process is killed), back to the prompt |

### Replay a core run without the emulator (`replay_core.py`)

```bash
python3 harness/replay_core.py ~/ballistic_runs/<time>_Chat.json   # any core run JSON
```
Starts `ballistic-core` with the recorded arguments and answers each ask from the recorded
`lines` (observe -> recorded screen, complete -> recorded completion, act -> recorded result,
confirm -> recorded verdict). No emulator, no model, standard library only, works on either
laptop. It reports the first ask where core differs from the recording (index, the differing
field, both lines) and exits 1, or reports a match and exits 0. Timing keys in log events
(`seconds`, ...) are left out of the comparison; `--ignore_keys` changes the list. This is
core/README.md §15 rule 4, on the host side.

### Dream pass over a suite (`dream.py`, offline)

```bash
python3 harness/dream.py ballistic_runs/suite_X/ --compare ballistic_runs/suite_OLD/ \
    --group "practice:ContactsAddContact,..." --price_in 0.10 --price_out 0.50 --out dream.md
```
Clusters non-PASS runs by failure category, final package and activity (observation headers
only), last action type, readback word and outcome kind, and lists state-passed-but-not-credited
runs, false done, per-task one-liners, token totals and cost. It reads only core- and
harness-written fields (fixed report phrases, step outcomes, readback words); planner prompts,
reader prompts, screen text and answer text never enter the report. No model call unless
`--summarize` (one Anthropic call over the cluster table only, labelled as unverified
proposals). Proposal C of `docs/research/OPENCLAW_DREAMING.md`; it proposes, it changes nothing.

### Answers to AndroidWorld's answer checkers (harness only)

The 25 information-retrieval tasks score `env.interaction_cache`. After a core run, `run_task.py`
(`--deliver_answers`, default on; never passed to core) writes the raw planner `answer.text` of
a sourced answer (`evidence_basis: answer_values_sourced`), or the excerpts of an available owner
summary joined with `, `, through AndroidWorld's own ANSWER action (`env.execute_action`, as T3A
does), before the checker runs. The display string with `(from <source>)` tags is never sent.
Nothing goes back into a model prompt. The run JSON records `answer_delivery` (delivered, source,
character count) and the benchmark fields `answer_delivered`, `answer_scored_by_checker` and
`agent_complete_basis`. The combined PASS counts a delivered answer as agent-complete only for a
task whose checker scores the answer (`InformationRetrieval`), and the checker still decides: a
wrong answer is FAIL (`failure_category: answer`). `agent_reported_done` stays core's own flag.

Ollama is configured by `setup_emulator.sh`'s systemd override with `OLLAMA_CONTEXT_LENGTH=8192`
(AndroidWorld prompts exceed the 4k default), `OLLAMA_KEEP_ALIVE=1h`, and listening on all
interfaces so another machine can use it over Tailscale.

## Known rough edges (first run, 5 Nov 2026)

- A 2-core 2017 laptop CPU is the bottleneck: software GPU rendering + model inference push
  load to 6–8. App first-run dialogs sometimes miss AndroidWorld's 5 s `am start -W` timeout
  ("Failed to automatically setup app …"); re-running `--perform_emulator_setup` or dismissing
  the dialog by hand fixes it.
- First model call after idle takes ~20 s (model load); later calls are faster.

### Independent outcomes and capability contracts (7 November 2026)

Benchmark runs now record `agent_reported_done`, `benchmark_success`, `benchmark_status`,
`benchmark_error`, `checker_seconds` and `combined_success` separately. The existing `success`
field and PASS score remain the combined result for comparison with the original baseline.
The state checker runs even when core gives up or exhausts its budget; checker failures are
unknown/error, not model failures. Free prompts have no benchmark checker. Core's own
`verification.evidence_basis=model_assessment` is weaker than AndroidWorld's state check.

The suite TSV adds independent state outcome, agent completion, failure category, call/token
counts and the JSON path. Token totals include recorded retries and count only known numeric
usage; `unknown_prompt_attempts` / `unknown_completion_attempts` report missing usage rather
than treating it as measured zero. `model_calls` counts logical completions; `http_attempts`
counts client request attempts, including retries and connection failures, not proof that the
provider received each request. `retry_attempts`, completion tokens, total pacing and checker
seconds are separate columns. Older records without retry telemetry cannot recover that cost.
Full task logs are preserved. `SEED=0` is the new default; the original
baseline was unseeded, so comparisons with it are descriptive rather than matched-input trials.
`TASK_TIMEOUT` bounds a task process (default 900 seconds). HTTP/checker/process errors are
reported separately. The harness advertises only its actual input operations and test app scopes;
these emulator grants do not establish production Android permissions.

The suite stops after the first recorded HTTP 429 and exits 75. That task is an infrastructure
ERROR even if the independent state check passed; subsequent tasks are not run. Summary
denominators show attempted and requested tasks separately. Inspect the quota telemetry before
starting another run; the runner does not rotate keys, sleep until reset or retry a 429.

For a paced core suite, set `CORE_MODEL_MIN_INTERVAL=5` when invoking `run_suite.sh`, or pass
`--core_model_min_interval 5` to `run_task.py`/`chat.sh`. This is a configurable precaution,
not a claimed provider limit. One serial model client shares the interval across all roles and
retry attempts; separate processes do not share a limiter. The interval is recorded in the suite
header and core run JSON, and each call records `pacing_seconds` when enabled. Total elapsed time
includes these waits, so compare timing only with pacing accounted for. Model, token caps and
attempt budgets are unchanged. The option has no effect on T3A/M3A.

Core binds guarded approvals to one exact resolved operation and app. A host error after dispatch
is ambiguous: core stops instead of retrying a possible send/delete. `not_executed` is reserved
for cases where the host can guarantee no input was dispatched. Do not classify a timeout as
`not_executed` merely because an acknowledgment was not received.

### Model retries and observation settling (7 November 2026)

`ModelClient` retries HTTP 502/503/504 and connection failures up to three times (waits 2, 4,
8 s); a success after retries is one call with a `retried` list. Read timeouts and 4xx are not
retried. The run JSON records HTTP status and a quota classification derived from the full
response (daily, rate, mixed or unknown), without storing the raw error body. When supplied, it
also retains bounded numeric retry guidance (`retry_after_seconds`) and allowlisted quota limit
types/values (`quota_limits`), excluding provider messages, project IDs and quota dimensions.
These fields report provider guidance, not an automatic retry decision. Malformed successful
responses produce `ModelResponseError` with response-shape and allowlisted finish-reason fields,
instead of a missing-content `KeyError`. Replies also record boolean refusal/tool-call/
function-call presence and fixed documented Gemini finish enums; unknown values remain `other`.
Provider text and tool arguments are never copied into diagnostics. These additional enums do
not broaden retries: only an eligible empty reply with existing `stop`/missing finish reason can
receive one recovery attempt within the same total ceiling. Historical `other` cannot be decoded
retrospectively. Observation settles on two consecutive samples with the same foreground and size whose
trees match, or differ only in text on at most a tenth of the nodes. Tolerated text nodes must be
noninteractive and outside interactive ancestry; all other fields, including flags, bounds and
labels, must match. Unknown or incomplete ancestry prevents tolerance. The later sample is
returned. Unsettled samples are spaced 0.3 s, doubling; six acquisitions and a 30-second soft
deadline bound the wait (blocking transport calls still depend on their own timeouts).
Formerly tolerated control animations may now require extra samples or time out. This cannot
identify repeated stale/incomplete trees or establish that changing noninteractive text is harmless.
Offline checks, baseline-specific reproduction commands and limits are in
[the settling report](../docs/log/2026-11-07-observation-settling.md); live impact is unknown.

### Text-only model transport

Every core model role returns text, which still passes through the existing core validators.
Native `tool_calls`, legacy `function_call`, refusal payloads and their tool/filter finish
signals are terminal even when accompanied by nonempty text. They are never translated into
Hands actions or retried. Diagnostics retain only presence flags and allowlisted finish
reasons, including for mixed responses; unknown metadata and native arguments are excluded.

`--core_disable_native_tools` (`ModelClient(..., disable_native_tools=True)` for direct callers)
adds only `tool_choice: "none"`. It is a boolean opt-in, default false, recorded in ordinary
core run JSON as `core_disable_native_tools`. There are no tools declarations, format/schema
changes, probes, fallback requests or new retries. Reader element responses remain plain
`eN`/`none`; this does not constrain future reader summary text to an element response.
Models, temperature, thinking and token/step/attempt budgets are unchanged.

Endpoint compatibility and effectiveness are unmeasured. Google documents tool control, but
the reviewed Gemini `v1beta/openai` material does not establish acceptance of `none` without
`tools`; a rejection such as HTTP 400 stays terminal. See the primary-source assessment and
offline test evidence in [the transport report](../docs/log/2026-11-07-model-text-transport.md).
