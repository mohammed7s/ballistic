"""Interactive prompt: type an instruction, watch the agent do it on the emulator, type the next.

The emulator connection and the model client are set up once; each line you type is a fresh
agent run (history cleared) that starts from whatever screen is showing, so instructions can
build on each other ("open the Clock app", then "add an alarm for 7:00").

  bash harness/chat.sh            # Gemini defaults, key from ~/.ballistic_env
  ~/android_world/.venv/bin/python harness/chat.py --model ... --base_url ... --api_key ...

At the prompt:  any text = instruction;  /home = go to the home screen;  /steps N = step budget;
                /quit (or Ctrl+D) = leave.  Ctrl+C while the agent is working stops that run.
Every run is written to ~/ballistic_runs/<time>_Chat.json, same shape as run_task.py's.
"""

from __future__ import annotations

import functools
import json
import os
import readline  # noqa: F401  (line editing and history for input())
import subprocess
import time
from typing import Any

from absl import app, flags

# AndroidWorld's adb calls inherit our stdin and swallow typed lines; give children an empty stdin.
_popen_init = subprocess.Popen.__init__


@functools.wraps(_popen_init)
def _popen_no_stdin(self, *args, **kwargs):
    if kwargs.get("stdin") is None:
        kwargs["stdin"] = subprocess.DEVNULL
    _popen_init(self, *args, **kwargs)


subprocess.Popen.__init__ = _popen_no_stdin

import run_task  # noqa: F401  (defines the shared flags and the OpenAI-compatible wrapper)
from android_world.agents import m3a, t3a
from android_world.env import env_launcher

FLAGS = flags.FLAGS


def run_one(env, llm, agent, goal: str, budget: int) -> None:
    agent.reset(False)
    llm.calls = []
    steps: list[dict[str, Any]] = []
    done = False
    t_start = time.time()
    try:
        for i in range(budget):
            t0 = time.time()
            resp = agent.step(goal)
            d = resp.data or {}
            action = d.get("action_output") or d.get("action") or ""
            summary = d.get("summary") or ""
            print(f"step {i + 1:>2}  {time.time() - t0:5.1f}s  {str(action).split('Action:')[-1].strip()[:110]}")
            if summary:
                print(f"         {str(summary)[:110]}")
            steps.append({"step": i + 1, "seconds": round(time.time() - t0, 1), "action": str(action), "summary": str(summary)})
            if resp.done:
                done = True
                break
    except KeyboardInterrupt:
        print("\n(stopped)")
    total = round(time.time() - t_start, 1)
    print(f"{'done' if done else 'not finished'}: {len(steps)} steps, {total}s\n")
    os.makedirs(FLAGS.out_dir, exist_ok=True)
    out = os.path.join(FLAGS.out_dir, f"{time.strftime('%Y%m%d-%H%M%S')}_Chat.json")
    with open(out, "w") as f:
        json.dump({
            "task": "Chat", "goal": goal, "agent": FLAGS.agent, "model": FLAGS.model,
            "base_url": FLAGS.base_url, "success": None, "done": done, "steps": steps,
            "model_calls": llm.calls, "total_seconds": total,
        }, f, indent=2)


def main(argv) -> None:
    del argv
    print("Connecting to the emulator...")
    env = env_launcher.load_and_setup_env(console_port=FLAGS.console_port, emulator_setup=False, adb_path=FLAGS.adb_path)
    llm = run_task.OpenAICompatWrapper(FLAGS.base_url, FLAGS.model, FLAGS.api_key, FLAGS.temperature, FLAGS.max_tokens, FLAGS.timeout, FLAGS.no_think)
    agent = m3a.M3A(env, llm) if FLAGS.agent == "m3a" else t3a.T3A(env, llm)
    budget = FLAGS.max_steps
    print(f"Ready. Model {FLAGS.model}, agent {FLAGS.agent}, {budget} steps per instruction.")
    print("Type an instruction, or /home, /steps N, /quit.\n")
    try:
        while True:
            try:
                line = input("ballistic> ").strip()
            except KeyboardInterrupt:
                print()
                continue
            if not line:
                continue
            if line in ("/quit", "/exit", "/q"):
                break
            if line == "/home":
                env.reset(go_home=True)
                continue
            if line.startswith("/steps"):
                try:
                    budget = int(line.split()[1])
                    print(f"step budget {budget}")
                except (IndexError, ValueError):
                    print("usage: /steps 20")
                continue
            run_one(env, llm, agent, line, budget)
    except EOFError:
        print()
    env.close()


if __name__ == "__main__":
    app.run(main)
