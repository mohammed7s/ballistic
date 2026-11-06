"""Record raw screens while the stock agent (T3A) runs a task, for core's filter tests and the
token measurement (core/README.md §5, §14).

For every step it saves, under core/tests/screens/<Task>/<step>.json:
  screen        our ScreenState (built from the accessibility forest, parents kept)
  t3a_text      the exact UI-element text T3A would put in its prompt for the same screen
  goal, task, step, t3a_action
The screens are test fixtures: core's unit tests read them with no emulator.

Run on the ThinkPad (emulator up, nothing else using it); the key comes from BALLISTIC_API_KEY in
the environment, never from the command line:
  set -a; source ~/.ballistic_env; set +a
  ~/android_world/.venv/bin/python harness/record_screens.py --tasks ContactsAddContact,SimpleSmsSend \
     --model gemini-flash-lite-latest --base_url https://generativelanguage.googleapis.com/v1beta/openai/
`--tasks suite` records the 20 tasks of run_suite.sh.
"""

from __future__ import annotations

import json
import os
import re
import sys
import time

from absl import app, flags

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import core_bridge  # noqa: E402
import run_task  # noqa: E402  shares flags (--model, --base_url, ...) and the model wrapper

from android_world import registry  # noqa: E402
from android_world.agents import t3a  # noqa: E402
from android_world.env import env_launcher  # noqa: E402

FLAGS = flags.FLAGS
flags.DEFINE_string("tasks", "ContactsAddContact", "Comma-separated task names, or 'suite'")
flags.DEFINE_string("screens_dir", os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "core", "tests", "screens")), "Where to write fixtures")


def suite_tasks() -> list[str]:
    here = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(here, "run_suite.sh")) as f:
        text = f.read()
    m = re.search(r'TASKS="\$\{TASKS:-([^}]*)\}"', text)
    if not m or not m.group(1).split():
        raise ValueError("No default suite tasks found in run_suite.sh")
    return m.group(1).split()


def record_task(env, aw, name: str, llm) -> int:
    task_type = aw[name]
    task = task_type(task_type.generate_random_params())
    task.initialize_task(env)
    goal, budget = task.goal, int(task.complexity * 10)
    agent = t3a.T3A(env, llm)
    out_dir = os.path.join(FLAGS.screens_dir, name)
    os.makedirs(out_dir, exist_ok=True)
    host = core_bridge.CoreHost(env, models=None)  # only its observe() is used
    print(f"\n{name}: {goal}")
    n = 0
    for i in range(budget):
        screen = host.observe()                      # same screen T3A is about to see
        state = env.get_state(wait_to_stabilize=False)
        t3a_text = t3a._generate_ui_elements_description_list_full(state.ui_elements, env.logical_screen_size)  # pylint: disable=protected-access
        resp = agent.step(goal)
        d = resp.data or {}
        action = str(d.get("action_output") or d.get("action") or "")
        with open(os.path.join(out_dir, f"{i + 1:02d}.json"), "w") as f:
            json.dump({"task": name, "goal": goal, "step": i + 1, "t3a_action": action,
                       "t3a_text": t3a_text, "screen": screen}, f, indent=1)
        n += 1
        print(f"  step {i + 1:>2}: {len(screen['nodes'])} nodes, t3a {len(t3a_text)} chars  {action[:80]}")
        if resp.done:
            break
    ok = task.is_successful(env) == 1
    print(f"  {'PASS' if ok else 'FAIL'}  {n} screens -> {out_dir}")
    return n


def main(argv) -> None:
    del argv
    task_registry = registry.TaskRegistry()
    aw = task_registry.get_registry(task_registry.ANDROID_WORLD_FAMILY)
    names = suite_tasks() if FLAGS.tasks == "suite" else FLAGS.tasks.split(",")
    env = env_launcher.load_and_setup_env(console_port=FLAGS.console_port, emulator_setup=False, adb_path=FLAGS.adb_path)
    env.reset(go_home=True)
    llm = run_task.OpenAICompatWrapper(FLAGS.base_url, FLAGS.model, run_task.api_key(), FLAGS.temperature, FLAGS.max_tokens, FLAGS.timeout, FLAGS.no_think)
    total = 0
    t0 = time.time()
    for name in names:
        if name not in aw:
            print(f"skip {name}: not a task")
            continue
        env.reset(go_home=True)
        total += record_task(env, aw, name, llm)
    print(f"\n{total} screens recorded in {round(time.time() - t0)}s")
    env.close()


if __name__ == "__main__":
    app.run(main)
