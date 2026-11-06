"""Run one AndroidWorld task with a local model behind an OpenAI-compatible endpoint.

Mirrors android_world/minimal_task_runner.py but swaps the model for anything that speaks the
OpenAI chat API (Ollama, llama.cpp server, vLLM, or a cloud endpoint). Prints every step and
writes a JSON record of the run.

Run with AndroidWorld's venv, emulator already booted with -grpc 8554:

  ~/android_world/.venv/bin/python harness/run_task.py --task ContactsAddContact
  ~/android_world/.venv/bin/python harness/run_task.py --task SimpleSmsSend --agent m3a --model gemma3:4b
  ~/android_world/.venv/bin/python harness/run_task.py --list

Flags:
  --base_url   OpenAI-compatible endpoint (default http://127.0.0.1:11434/v1, Ollama)
  --model      model name at that endpoint (default gemma3:4b). Cloud example (Gemini):
               --base_url https://generativelanguage.googleapis.com/v1beta/openai/ --model gemini-3.8-flash
  --agent      t3a (text: UI tree only) or m3a (multimodal: screenshot + UI tree)
  --task       AndroidWorld task name; random if omitted
  --goal       free-form instruction instead of a task ("open Chrome and search for weather in Berlin");
               no success check, just the agent loop. --max_steps caps it (default 15)
  --perform_emulator_setup   one-time install of AndroidWorld's apps on a fresh AVD
"""

from __future__ import annotations

import base64
import json
import os
import random
import sys
import time
from typing import Any, Optional

from absl import app, flags, logging
import numpy as np
import requests

from android_world import registry
from android_world.agents import infer, m3a, t3a
from android_world.env import env_launcher
from android_world.task_evals import task_eval

sys.stdout.reconfigure(line_buffering=True)  # nohup/log friendly
logging.set_verbosity(logging.WARNING)
os.environ["GRPC_VERBOSITY"] = "ERROR"
os.environ["GRPC_TRACE"] = "none"

FLAGS = flags.FLAGS
flags.DEFINE_string("base_url", os.environ.get("BALLISTIC_BASE_URL", "http://127.0.0.1:11434/v1"), "OpenAI-compatible endpoint")
flags.DEFINE_string("model", os.environ.get("BALLISTIC_MODEL", "gemma3:4b"), "Model name at the endpoint")
flags.DEFINE_string("api_key", os.environ.get("BALLISTIC_API_KEY", "ollama"), "Bearer token (ignored by Ollama)")
flags.DEFINE_enum("agent", "t3a", ["t3a", "m3a", "core"], "t3a = text only (UI tree), m3a = screenshot + UI tree, core = Ballistic's agent (Rust, over JSON lines)")
flags.DEFINE_string("core_bin", None, "Path to the ballistic-core binary (default: core/target/{release,debug})")
flags.DEFINE_enum("core_mode", "run", ["run", "observe"], "core: run the agent, or observe once (pipe smoke test)")
flags.DEFINE_enum("confirm", "approve", ["approve", "deny"], "core: answer to every confirmation request")
flags.DEFINE_string("task", None, "AndroidWorld task name; random if omitted")
flags.DEFINE_string("goal", None, "Free-form goal; skips AndroidWorld task setup and success check")
flags.DEFINE_integer("max_steps", 15, "Step budget for --goal runs")
flags.DEFINE_bool("list", False, "List task names and exit")
flags.DEFINE_bool("perform_emulator_setup", False, "One-time: install AndroidWorld apps on the AVD")
flags.DEFINE_integer("console_port", 5554, "Emulator console port")
flags.DEFINE_string("adb_path", os.path.expanduser("~/Android/Sdk/platform-tools/adb"), "adb binary")
flags.DEFINE_float("temperature", 0.0, "Sampling temperature")
flags.DEFINE_integer("max_tokens", 600, "Max completion tokens per step (T3A/M3A answers are short)")
flags.DEFINE_integer("timeout", 600, "Per-call HTTP timeout, seconds (local models are slow)")
flags.DEFINE_string("out_dir", os.path.expanduser("~/ballistic_runs"), "Where to write run JSON")
flags.DEFINE_bool("no_think", True, "Prefix prompts with /no_think (disables Qwen3 reasoning mode; harmless for other models)")


class OpenAICompatWrapper(infer.LlmWrapper, infer.MultimodalLlmWrapper):
    """AndroidWorld LLM wrapper for any OpenAI-compatible /chat/completions endpoint."""

    def __init__(self, base_url: str, model: str, api_key: str, temperature: float, max_tokens: int, timeout: int, no_think: bool = True):
        self.url = base_url.rstrip("/") + "/chat/completions"
        self.model = model
        self.headers = {"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"}
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.timeout = timeout
        self.calls: list[dict[str, Any]] = []
        self.no_think = no_think

    @staticmethod
    def _b64(image: np.ndarray) -> str:
        return base64.b64encode(infer.array_to_jpeg_bytes(image)).decode("utf-8")

    def predict(self, text_prompt: str) -> tuple[str, Optional[bool], Any]:
        return self.predict_mm(text_prompt, [])

    def predict_mm(self, text_prompt: str, images: list[np.ndarray]) -> tuple[str, Optional[bool], Any]:
        if self.no_think and "qwen3" in self.model.lower():
            text_prompt = "/no_think\n" + text_prompt
        content: list[dict[str, Any]] = [{"type": "text", "text": text_prompt}]
        for img in images:
            content.append({"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{self._b64(img)}"}})
        payload = {
            "model": self.model,
            "temperature": self.temperature,
            "max_tokens": self.max_tokens,
            "messages": [{"role": "user", "content": content}],
        }
        t0 = time.time()
        last_err = None
        for attempt in range(3):
            try:
                r = requests.post(self.url, headers=self.headers, json=payload, timeout=self.timeout)
                data = r.json()
                if isinstance(data, list):  # Gemini's OpenAI-compat endpoint wraps errors in a list
                    data = data[0] if data else {}
                if r.ok and "choices" in data:
                    text = data["choices"][0]["message"]["content"] or ""
                    usage = data.get("usage", {})
                    self.calls.append({"seconds": round(time.time() - t0, 1), "images": len(images), **usage})
                    print(f"  model: {usage.get('prompt_tokens', '?')} prompt tok, {usage.get('completion_tokens', '?')} out tok, {time.time() - t0:.0f}s", flush=True)
                    return text, None, data
                last_err = data.get("error", data)
            except Exception as e:  # noqa: BLE001
                last_err = repr(e)
            print(f"  model call failed (attempt {attempt + 1}): {last_err}")
            time.sleep(2)
        return infer.ERROR_CALLING_LLM, None, last_err


def _main() -> None:
    task_registry = registry.TaskRegistry()
    aw = task_registry.get_registry(task_registry.ANDROID_WORLD_FAMILY)
    if FLAGS.list:
        for name in sorted(aw):
            print(name)
        return

    env = env_launcher.load_and_setup_env(
        console_port=FLAGS.console_port,
        emulator_setup=FLAGS.perform_emulator_setup,
        adb_path=FLAGS.adb_path,
    )
    env.reset(go_home=True)

    task = None
    if FLAGS.goal:
        name, goal, budget = "FreePrompt", FLAGS.goal, FLAGS.max_steps
    else:
        if FLAGS.task:
            if FLAGS.task not in aw:
                raise ValueError(f"Task {FLAGS.task} not found; use --list")
            task_type: type[task_eval.TaskEval] = aw[FLAGS.task]
        else:
            task_type = random.choice(list(aw.values()))
        task = task_type(task_type.generate_random_params())
        task.initialize_task(env)
        name, goal, budget = task_type.__name__, task.goal, int(task.complexity * 10)

    print(f"\nTask:  {name}\nGoal:  {goal}\nAgent: {FLAGS.agent}  Model: {FLAGS.model} @ {FLAGS.base_url}\nStep budget: {budget}\n")
    if FLAGS.agent == "core":
        _run_core(env, task, name, goal, budget)
        env.close()
        return

    llm = OpenAICompatWrapper(FLAGS.base_url, FLAGS.model, FLAGS.api_key, FLAGS.temperature, FLAGS.max_tokens, FLAGS.timeout, FLAGS.no_think)
    agent = m3a.M3A(env, llm) if FLAGS.agent == "m3a" else t3a.T3A(env, llm)

    steps: list[dict[str, Any]] = []
    done = False
    t_start = time.time()
    for i in range(budget):
        t0 = time.time()
        resp = agent.step(goal)
        d = resp.data or {}
        action = d.get("action_output") or d.get("action") or ""
        summary = d.get("summary") or ""
        print(f"step {i + 1:>2}  {time.time() - t0:5.1f}s  {str(action)[:110]}")
        if summary:
            print(f"         {str(summary)[:110]}")
        steps.append({"step": i + 1, "seconds": round(time.time() - t0, 1), "action": str(action), "summary": str(summary)})
        if resp.done:
            done = True
            break

    success = bool(done and task.is_successful(env) == 1) if task else None
    total = round(time.time() - t_start, 1)
    verdict = "DONE" if task is None else ("PASS" if success else "FAIL")
    print(f"\n{verdict}  {name}  steps={len(steps)}  time={total}s  done_flag={done}")

    os.makedirs(FLAGS.out_dir, exist_ok=True)
    out = os.path.join(FLAGS.out_dir, f"{time.strftime('%Y%m%d-%H%M%S')}_{name}.json")
    with open(out, "w") as f:
        json.dump({
            "task": name, "goal": goal, "agent": FLAGS.agent, "model": FLAGS.model,
            "base_url": FLAGS.base_url, "success": success, "done": done, "steps": steps,
            "model_calls": llm.calls, "total_seconds": total,
        }, f, indent=2)
    print(f"written {out}")
    env.close()


def _run_core(env, task, name: str, goal: str, budget: int) -> None:
    """Serve ballistic-core over the bridge, then judge the task as for the other agents."""
    import core_bridge  # local module, same directory

    route = (FLAGS.base_url, FLAGS.model, FLAGS.api_key)
    models = core_bridge.ModelClient({"planner": route}, FLAGS.temperature, FLAGS.timeout)

    def on_event(ev: dict[str, Any]) -> None:
        if "step" in ev:
            print(f"step {ev.get('step'):>2}  {ev.get('seconds', 0):5.1f}s  {str(ev.get('action'))[:110]}")
        elif ev.get("finished"):
            print("finished:", json.dumps(ev.get("report") or ev.get("error"))[:300])

    host = core_bridge.CoreHost(env, models, FLAGS.confirm, on_event)
    core_bin = FLAGS.core_bin or core_bridge.default_core_bin()
    args = ["observe"] if FLAGS.core_mode == "observe" else ["run", "--request", goal, "--max-steps", str(budget)]
    t_start = time.time()
    proc = core_bridge.start_core(core_bin, args)
    finished = host.serve(proc)
    proc.stdin.close()
    stderr = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""
    code = proc.wait(timeout=30)
    total = round(time.time() - t_start, 1)
    done = bool(finished.get("report", {}).get("done")) if isinstance(finished.get("report"), dict) else False

    success = None
    if task is not None and FLAGS.core_mode == "run":
        success = bool(done and task.is_successful(env) == 1)
    verdict = "DONE" if task is None or FLAGS.core_mode == "observe" else ("PASS" if success else "FAIL")
    steps = [e for e in host.events if "step" in e]
    print(f"\n{verdict}  {name}  steps={len(steps)}  time={total}s  exit={code}  model_calls={len(models.calls)}")
    if stderr.strip():
        print("core stderr:", stderr.strip()[:2000])

    os.makedirs(FLAGS.out_dir, exist_ok=True)
    out = os.path.join(FLAGS.out_dir, f"{time.strftime('%Y%m%d-%H%M%S')}_{name}.json")
    with open(out, "w") as f:
        json.dump({
            "task": name, "goal": goal, "agent": "core", "core_mode": FLAGS.core_mode, "model": FLAGS.model,
            "base_url": FLAGS.base_url, "success": success, "done": done, "steps": steps,
            "finished": finished, "model_calls": models.calls, "confirmations": host.confirmations,
            "total_seconds": total, "exit_code": code, "stderr": stderr[-4000:],
            "lines": host.lines,
        }, f, indent=1)
    print(f"written {out}")


def main(argv) -> None:
    del argv
    _main()


if __name__ == "__main__":
    app.run(main)
