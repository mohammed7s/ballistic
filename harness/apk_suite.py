"""Run the API-37-eligible part of the frozen 30-task suite through the real APK.

The agent acts only through the APK and its Computer Control session (harness.apk_control).
Setup and checking are test-operator work on the host: AndroidWorld's own task classes run
against the emulator over adb, with adbd rooted only while setting up or checking and unrooted
for the agent run. Nothing from setup or the checker is passed to the app: the APK receives only
the AndroidWorld goal string. One run per task, no retries.

Eligibility is frozen in harness/suites/computer_control_30_api37.json; tasks with any other
status are reported with that status and never run. Run from the repository root with an
interpreter that can import android_world (its venv), on a userdebug virtual device only:

  ~/android_world/.venv/bin/python -m harness.apk_suite --serial emulator-5554 --out-dir DIR
  python3 -m harness.apk_suite --list
"""
from __future__ import annotations

import argparse
import json
import random
import time
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET

from harness.apk_clock_pilot import PREFS as CLOCK_PREFS, parse_stopwatch_state
from harness.apk_control import Adb, preflight, run_goal
from harness.outcomes import deliver_answer, evaluate_benchmark

SUITE_DIR = Path(__file__).resolve().parent / "suites"
ELIGIBILITY = SUITE_DIR / "computer_control_30_api37.json"
FROZEN = SUITE_DIR / "computer_control_30.json"
SEED = 0
# DeskClock UiDataModel.Tab ordinals at the pinned AOSP source: ALARMS, CLOCKS, TIMERS, STOPWATCH.
STOPWATCH_TAB = 3


def load_table(path: Path = ELIGIBILITY) -> dict:
    table = json.loads(path.read_text())
    frozen = json.loads(FROZEN.read_text())
    names = frozen["practice"] + frozen["rotation"]
    if sorted(table["tasks"]) != sorted(names):
        raise ValueError("eligibility table does not cover exactly the frozen 30 tasks")
    for name, entry in table["tasks"].items():
        if entry["status"] not in table["statuses"]:
            raise ValueError(f"{name}: unknown status")
        if entry["status"] == "eligible" and not entry.get("checker"):
            raise ValueError(f"{name}: eligible without a checker")
    return table


def eligible(table: dict, group: str | None = None) -> list[str]:
    frozen = json.loads(FROZEN.read_text())
    order = frozen["practice"] + frozen["rotation"]
    return [n for n in order if table["tasks"][n]["status"] == "eligible"
            and (group is None or table["tasks"][n].get("group") == group)]


def group_packages(table: dict, group: str) -> list[str]:
    return sorted({p for n in eligible(table, group) for p in table["tasks"][n]["packages"]})


# ---- root only for the test operator -------------------------------------------------------

def _uid(adb: Adb) -> str:
    return adb.shell("id", "-u")


def _wait_device(adb: Adb, rooted: bool, sleep=time.sleep, attempts: int = 40) -> None:
    for _ in range(attempts):
        try:
            adb.command("wait-for-device", timeout=30)
            if (_uid(adb) == "0") == rooted:
                return
        except Exception:  # noqa: BLE001  adbd restarting
            pass
        sleep(0.5)
    raise RuntimeError("adbd did not reach the requested root state")


class OperatorRoot:
    """adbd runs as root only inside this block (setup or checking), never during an agent run."""

    def __init__(self, adb: Adb, sleep=time.sleep):
        self.adb, self.sleep = adb, sleep

    def __enter__(self):
        self.adb.command("root", timeout=30)
        _wait_device(self.adb, True, self.sleep)
        return self

    def __exit__(self, *exc):
        self.adb.command("unroot", timeout=30)
        _wait_device(self.adb, False, self.sleep)
        return False


# ---- AndroidWorld task classes over plain adb -------------------------------------------------

def aw_env(adb_path: str, serial: str):
    """An object with the parts of AndroidWorld's env its task setup/checkers use: adb calls,
    file pull/push and the interaction cache. UI-tree reads are refused: those checkers read the
    default display, and these tasks run on the session's virtual display."""
    from android_env.components import adb_call_parser, adb_controller, config_classes
    from android_world.env import android_world_controller

    controller = adb_controller.AdbController(config_classes.AdbControllerConfig(
        adb_path=adb_path, device_name=serial))
    parser = adb_call_parser.AdbCallParser(controller)

    class Controller:
        def __init__(self):
            self.env = self

        def execute_adb_call(self, call):
            return parser.parse(call)

        pull_file = android_world_controller.AndroidWorldController.pull_file
        push_file = android_world_controller.AndroidWorldController.push_file

        def get_ui_elements(self):
            raise RuntimeError("UI-tree checkers are not eligible on the session display")

    class Env:
        def __init__(self):
            self.controller = Controller()
            self.interaction_cache = ""

        def get_state(self, *args, **kwargs):
            raise RuntimeError("UI-tree checkers are not eligible on the session display")

    return Env()


def make_task(name: str):
    """AndroidWorld's task with seed-0 parameters, as harness/run_task.py --seed 0 makes it."""
    import numpy as np
    from android_world import registry

    reg = registry.TaskRegistry()
    task_type = reg.get_registry(reg.ANDROID_WORLD_FAMILY)[name]
    random.seed(SEED)
    np.random.seed(SEED)
    return task_type(task_type.generate_random_params())


def initialize(task, env) -> None:
    """AndroidWorld's initialize_task, except the device date: AndroidWorld moves it to
    15 Oct 2023, which would invalidate the APK's TLS model connections. The timezone (UTC) and
    24-hour format are still set, as AndroidWorld's setup_datetime does."""
    from android_world.utils import datetime_utils

    def time_without_date(_env):
        datetime_utils._enable_24_hour_format(env.controller)  # pylint: disable=protected-access
        datetime_utils._set_timezone_to_utc(env.controller)  # pylint: disable=protected-access

    task.initialize_device_time = time_without_date
    task.initialize_task(env)


# ---- adapted Clock checkers --------------------------------------------------------------------

def parse_selected_tab(xml: str) -> int | None:
    root = ET.fromstring(xml)
    if root.tag != "map":
        raise ValueError("unexpected Clock preferences root")
    fields = [c for c in root if c.get("name") == "selected_tab"]
    if not fields:
        return None
    if len(fields) != 1 or fields[0].tag != "int" or not (fields[0].get("value") or "").isdigit():
        raise ValueError("ambiguous or unexpected selected_tab field")
    return int(fields[0].get("value"))


def clock_preferences(adb: Adb) -> str | None:
    raw = adb.shell("sh", "-c", f"if test -f {CLOCK_PREFS}; then cat {CLOCK_PREFS}; else printf MISSING; fi")
    return None if raw == "MISSING" else raw


def clock_check(name: str, xml: str | None) -> dict:
    """Adapted state checks from Clock's own preferences (AndroidWorld reads the UI tree)."""
    state = "reset" if xml is None else parse_stopwatch_state(xml)
    tab = None if xml is None else parse_selected_tab(xml)
    if name == "ClockStopWatchRunning":
        passed = state == "running"
    elif name == "ClockStopWatchPausedVerify":
        passed = state in ("reset", "paused") and tab == STOPWATCH_TAB
    else:
        raise ValueError(f"no adapted Clock checker for {name}")
    return {"stopwatch_state": state, "selected_tab": tab, "passed": passed}


# ---- one task ----------------------------------------------------------------------------------

def run_one(name: str, entry: dict, adb: Adb, env, timeout: int, out_dir: Path,
            agent=run_goal, root=OperatorRoot, task_factory=make_task, init=initialize) -> dict:
    record = {"schema": 1, "task": name, "seed": SEED, "checker": entry["checker"],
              "original_androidworld_checker": entry["checker"].startswith("androidworld"),
              "deviation": entry.get("deviation"), "packages": entry["packages"],
              "side_effects": "not_audited"}
    task = task_factory(name)
    record["goal"] = task.goal
    try:
        with root(adb):
            init(task, env)
            for package in entry["packages"]:
                adb.shell("am", "force-stop", package)
    except Exception as exc:  # noqa: BLE001  never start the agent on an unknown setup
        record.update(status="setup_error", setup_error_type=type(exc).__name__,
                      agent_done=False, state="not_checked", combined_pass=False, false_done=False)
        return record
    report = None
    try:
        result = agent(adb, task.goal, timeout, out_dir)
        agent_record = json.loads(Path(result["record"]).read_text())
        report = agent_record.get("report")
        record.update(agent_record=result["record"], agent_status=agent_record.get("status"),
                      agent_seconds=agent_record.get("agent_seconds"),
                      steps=len(agent_record.get("steps") or []),
                      failure_category=(report or {}).get("failure_category"))
    except Exception as exc:  # noqa: BLE001  no retry; arbitrary text may hold device data
        record["agent_error_type"] = type(exc).__name__
    agent_done = (report or {}).get("done") is True
    record["agent_done"] = agent_done
    try:
        with root(adb):
            if entry["checker"] == "adapted_clock_preferences":
                check = clock_check(name, clock_preferences(adb))
                record["adapted_check"] = check
                record.update(state="pass" if check["passed"] else "fail",
                              combined_pass=agent_done and check["passed"], answer_delivered=False)
            else:
                # The raw sourced answer text goes to the checker's env only, never to a model.
                delivery = deliver_answer(env, report, lambda text: text) if entry["checker"] == "androidworld_answer" \
                    else {"delivered": False, "source": None, "chars": 0}
                outcome = evaluate_benchmark(task, env, agent_done, answer_delivered=delivery["delivered"])
                record["answer_delivery"] = delivery
                record["benchmark"] = outcome
                status = outcome["benchmark_status"]
                record.update(state={"passed": "pass", "failed": "fail"}.get(status, "error"),
                              combined_pass=bool(outcome["combined_success"]),
                              answer_delivered=delivery["delivered"])
    except Exception as exc:  # noqa: BLE001
        record.update(state="error", checker_error_type=type(exc).__name__, combined_pass=False)
    # Agent-complete: an accepted done, or for an answer task a sourced answer delivered to the
    # checker. False done: agent-complete while the independent check failed.
    record["agent_complete"] = agent_done or record.get("answer_delivered") is True
    record["false_done"] = record["agent_complete"] and record["state"] == "fail"
    record["status"] = "checked" if record["state"] in ("pass", "fail") else "checker_error"
    return record


def summarize(table: dict, records: list[dict]) -> dict:
    statuses: dict[str, list[str]] = {}
    for name, entry in table["tasks"].items():
        statuses.setdefault(entry["status"], []).append(name)
    run = [r for r in records if r.get("status") != "setup_error"]
    return {
        "frozen_tasks": len(table["tasks"]),
        "by_status": {k: sorted(v) for k, v in sorted(statuses.items())},
        "attempted": len(records),
        "setup_errors": [r["task"] for r in records if r.get("status") == "setup_error"],
        "checker_errors": [r["task"] for r in records if r.get("status") == "checker_error"],
        "agent_done": sum(r.get("agent_done") is True for r in run),
        "agent_complete": sum(r.get("agent_complete") is True for r in run),
        "state_pass": sum(r.get("state") == "pass" for r in run),
        "combined_pass": sum(r.get("combined_pass") is True for r in run),
        "false_done": [r["task"] for r in records if r.get("false_done")],
        "note": "One run per task, no retries; 15-step APK budget, not AndroidWorld's complexity budget. "
                "Not comparable with full-suite scores: only the eligible subset ran.",
    }


def run_suite(adb: Adb, env, table: dict, group: str, timeout: int, out_dir: Path,
              only: list[str] | None = None, pause: float = 0.0, sleep=time.sleep, **kwargs) -> dict:
    checked = preflight(adb)
    if not checked["ready_for_app_checks"]:
        raise RuntimeError("APK preflight failed; no task was set up")
    if checked["properties"]["ro.build.type"] != "userdebug":
        raise RuntimeError("operator setup and checking require a userdebug emulator")
    names = eligible(table, group)
    if only:
        unknown = [n for n in only if n not in names]
        if unknown:
            raise ValueError("not eligible in this group: " + ", ".join(unknown))
        names = [n for n in names if n in only]
    for package in group_packages(table, group):
        if not adb.shell("pm", "path", package).startswith("package:"):
            raise RuntimeError(f"target app not installed: {package}")
    out_dir.mkdir(parents=True, exist_ok=True)
    records = []
    for index, name in enumerate(names):
        if index and pause:
            sleep(pause)  # spacing between model-backed runs (provider rate limits); never a retry
        record = run_one(name, table["tasks"][name], adb, env, timeout, out_dir, **kwargs)
        records.append(record)
        (out_dir / f"suite_{name}.json").write_text(json.dumps(record, indent=2) + "\n")
    summary = {"schema": 1, "group": group, "preflight": checked, "summary": summarize(table, records),
               "tasks": [{k: r.get(k) for k in ("task", "status", "agent_done", "agent_complete", "state", "combined_pass",
                                                 "false_done", "failure_category", "agent_seconds", "steps")}
                         for r in records]}
    path = out_dir / f"suite_summary_{uuid.uuid4().hex}.json"
    path.write_text(json.dumps(summary, indent=2) + "\n")
    summary["record"] = str(path)
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--list", action="store_true", help="print the eligibility table and exit")
    parser.add_argument("--serial", help="explicit virtual-device serial")
    parser.add_argument("--adb", default=str(Path.home() / "Android/Sdk/platform-tools/adb"))
    parser.add_argument("--group", default="A")
    parser.add_argument("--task", action="append", help="run only these eligible tasks (repeatable)")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--pause", type=float, default=0.0, help="seconds between tasks (provider rate limits)")
    args = parser.parse_args()
    table = load_table()
    if args.list:
        print(json.dumps({"eligible": eligible(table), "summary": summarize(table, [])["by_status"]}, indent=2))
        return
    if not args.serial or not args.out_dir:
        parser.error("--serial and --out-dir are required to run")
    adb = Adb(args.adb, args.serial)
    summary = run_suite(adb, aw_env(args.adb, args.serial), table, args.group, args.timeout, args.out_dir, args.task,
                        pause=args.pause)
    print(json.dumps(summary["summary"], indent=2))
    print(summary["record"])


if __name__ == "__main__":
    main()
