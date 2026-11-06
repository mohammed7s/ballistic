"""Interactive prompt: type an instruction, watch the agent do it on the emulator, type the next.

The emulator connection is set up once; each line you type is a fresh agent run that starts from
whatever screen is showing, so instructions can build on each other ("open the Clock app", then
"add an alarm for 7:00").

  bash harness/chat.sh                    # Gemini defaults, key from ~/.ballistic_env
  bash harness/chat.sh --agent core       # Ballistic's agent (ballistic-core) instead of T3A
  ~/android_world/.venv/bin/python harness/chat.py --model ... --base_url ...   (key: $BALLISTIC_API_KEY)

Agents: t3a / m3a (AndroidWorld's, history cleared per line) or core (each line starts
`ballistic-core run --request <line> --max-steps <budget>`, served by core_bridge.CoreHost on the
same env; every role on --base_url, --reader_model / --judge_model / --verifier_model override the
model name per role).

At the prompt:  any text = instruction;  /home = go to the home screen;  /steps N = step budget;
                /confirm approve|deny = core's confirmation policy;  /agent core|t3a|m3a = switch;
                /quit (or Ctrl+D) = leave.  Ctrl+C while the agent is working stops that run
                (core: the process is killed).
Every run is written to ~/ballistic_runs/<time>_Chat.json, same shape as run_task.py's for that
agent (core runs include `lines`, which harness/replay_core.py replays).
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

import core_bridge  # noqa: E402  (local module, same directory)
import run_task  # noqa: E402  (defines the shared flags, the OpenAI-compatible wrapper and the core helpers)
from android_world.agents import m3a, t3a  # noqa: E402
from android_world.env import env_launcher  # noqa: E402

FLAGS = flags.FLAGS
AGENTS = ("core", "t3a", "m3a")


def run_android_world(env, llm, agent, agent_name: str, goal: str, budget: int) -> None:
    """One instruction with T3A or M3A."""
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
            "task": "Chat", "goal": goal, "agent": agent_name, "model": FLAGS.model,
            "base_url": FLAGS.base_url, "success": None, "done": done, "steps": steps,
            "model_calls": llm.calls, "total_seconds": total,
        }, f, indent=2)


def run_core(env, goal: str, budget: int, confirm_policy: str) -> None:
    """One instruction with ballistic-core. Ctrl+C is handled in run_task.serve_core."""
    host = core_bridge.CoreHost(env, run_task.core_models(), confirm_policy, run_task.print_core_event)
    args = ["run", "--request", goal, "--max-steps", str(budget)]
    run = run_task.serve_core(host, args)
    steps = [e for e in host.events if "step" in e]
    print(f"{'done' if run['done'] else 'not finished'}: {len(steps)} steps, {run['total_seconds']}s, "
          f"exit {run['exit_code']}, {len(host.models.calls)} model calls")
    if run["stderr"].strip():
        print("core stderr:", run["stderr"].strip()[:2000])
    print(f"written {run_task.write_core_record('Chat', goal, 'run', args, host, run, None)}\n")


def main(argv) -> None:
    del argv
    print("Connecting to the emulator...")
    env = env_launcher.load_and_setup_env(console_port=FLAGS.console_port, emulator_setup=False, adb_path=FLAGS.adb_path)
    llm = None
    android_world_agents: dict[str, Any] = {}  # built on first use, kept across lines

    def android_world_agent(name: str):
        nonlocal llm
        if llm is None:
            llm = run_task.OpenAICompatWrapper(FLAGS.base_url, FLAGS.model, run_task.api_key(), FLAGS.temperature, FLAGS.max_tokens, FLAGS.timeout, FLAGS.no_think)
        if name not in android_world_agents:
            android_world_agents[name] = m3a.M3A(env, llm) if name == "m3a" else t3a.T3A(env, llm)
        return android_world_agents[name]

    agent_name = FLAGS.agent
    budget = FLAGS.max_steps
    confirm_policy = FLAGS.confirm

    def describe() -> str:
        if agent_name != "core":
            return f"agent {agent_name}, model {FLAGS.model}"
        roles = {r: m for r, (_, m, _) in run_task.core_models().routes.items()}
        return (f"agent core ({FLAGS.core_bin or core_bridge.default_core_bin()}), "
                f"models {json.dumps(roles)}, confirm {confirm_policy}")

    print(f"Ready. {describe()}, {budget} steps per instruction.")
    print("Type an instruction, or /home, /steps N, /confirm approve|deny, /agent core|t3a|m3a, /quit.\n")
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
            if line.startswith("/confirm"):
                parts = line.split()
                if len(parts) == 2 and parts[1] in ("approve", "deny"):
                    confirm_policy = parts[1]
                    print(f"confirmations: {confirm_policy}")
                else:
                    print(f"usage: /confirm approve|deny   (now {confirm_policy})")
                continue
            if line.startswith("/agent"):
                parts = line.split()
                if len(parts) == 2 and parts[1] in AGENTS:
                    agent_name = parts[1]
                    print(describe())
                else:
                    print(f"usage: /agent core|t3a|m3a   (now {agent_name})")
                continue
            if line.startswith("/"):
                print("commands: /home, /steps N, /confirm approve|deny, /agent core|t3a|m3a, /quit")
                continue
            try:
                if agent_name == "core":
                    run_core(env, line, budget, confirm_policy)
                else:
                    agent = android_world_agent(agent_name)  # also builds llm
                    run_android_world(env, llm, agent, agent_name, line, budget)
            except KeyboardInterrupt:  # a second Ctrl+C while a stopped run is being written up
                print("\n(stopped)")
    except EOFError:
        print()
    env.close()


if __name__ == "__main__":
    app.run(main)
