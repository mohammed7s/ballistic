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

A core run's JSON has the same fields as before plus `core_args`, `roles`, `confirm`,
`finished` (core's last event), `confirmations`, `exit_code`, `stderr`, and `lines`: every bridge
line in both directions, in order.

### Prompt box (`chat.sh`)

One emulator connection for the session; each line typed is a fresh run starting from the
current screen. With `--agent core` each line starts
`ballistic-core run --request <line> --max-steps <budget>`; one line per step is printed from
core's log events, then core's finished report. Every run is written to
`~/ballistic_runs/<time>_Chat.json` (core runs: the same fields as `run_task.py --agent core`,
`lines` included).

| At the prompt | |
|---|---|
| any text | instruction |
| `/home` | go to the home screen |
| `/steps N` | step budget per instruction (default `--max_steps`, 15) |
| `/confirm approve\|deny` | core's confirmation policy from the next instruction on |
| `/agent core\|t3a\|m3a` | switch agents live |
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
instead of a missing-content `KeyError`. Empty replies also record boolean refusal/tool-call/
function-call presence and fixed documented Gemini finish enums; unknown values remain `other`.
Provider text and tool arguments are never copied into diagnostics. These additional enums do
not broaden retries: only an eligible empty reply with existing `stop`/missing finish reason can
receive one recovery attempt within the same total ceiling. Historical `other` cannot be decoded
retrospectively. Observation settles on two consecutive samples with the same foreground and size whose
trees match, or differ only slightly (same skeleton, at most a tenth of the nodes changed); the
later sample is returned. Unsettled samples are spaced 0.3 s, doubling; six acquisitions and a
30-second soft deadline bound the wait.
