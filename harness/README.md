# Harness (step 1): emulator + AndroidWorld + local models

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

# planner on another machine: just change the endpoint
~/android_world/.venv/bin/python harness/run_task.py --base_url http://100.x.y.z:11434/v1 --model qwen3:4b --task ...
```

`run_task.py` prints each step and writes a JSON record to `~/ballistic_runs/`. `--agent t3a` is
text-only (UI tree), `m3a` sends the screenshot too (needs a vision model such as `gemma3:4b`).

Ollama is configured by `setup_emulator.sh`'s systemd override with `OLLAMA_CONTEXT_LENGTH=8192`
(AndroidWorld prompts exceed the 4k default), `OLLAMA_KEEP_ALIVE=1h`, and listening on all
interfaces so another machine can use it over Tailscale.

## Known rough edges (first run, 5 Nov 2026)

- A 2-core 2017 laptop CPU is the bottleneck: software GPU rendering + model inference push
  load to 6–8. App first-run dialogs sometimes miss AndroidWorld's 5 s `am start -W` timeout
  ("Failed to automatically setup app …"); re-running `--perform_emulator_setup` or dismissing
  the dialog by hand fixes it.
- First model call after idle takes ~20 s (model load); later calls are faster.
