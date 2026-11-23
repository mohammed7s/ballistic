"""One bounded APK stopwatch case with an independent, host-only state check.

Clears only disposable emulator Clock data. Agent observations/actions still come from the APK.
This is not the full AndroidWorld suite or its original UI checker.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import uuid
import xml.etree.ElementTree as ET

from harness.apk_control import Adb, preflight, run_goal

CLOCK = "com.android.deskclock"
PREFS = f"/data/user_de/0/{CLOCK}/shared_prefs/{CLOCK}_preferences.xml"
GOAL = "Run the stopwatch."


def parse_stopwatch_state(xml: str) -> str:
    """Pinned AOSP StopwatchDAO defaults absent sw_state to RESET; ordinals are 0/1/2."""
    root = ET.fromstring(xml)
    if root.tag != "map":
        raise ValueError("unexpected Clock preferences root")
    fields = [child for child in root if child.get("name") == "sw_state"]
    if not fields:
        return "reset"
    if len(fields) != 1 or fields[0].tag != "int":
        raise ValueError("ambiguous or unexpected stopwatch field")
    values = {"0": "reset", "1": "running", "2": "paused"}
    value = fields[0].get("value")
    if value not in values:
        raise ValueError("unknown stopwatch state")
    return values[value]


def read_stopwatch(adb: Adb) -> dict:
    # Root is checker-only on the verified userdebug emulator. No grants or data reach the app.
    if adb.shell("su", "0", "id", "-u") != "0":
        raise RuntimeError("independent checker requires emulator root")
    raw = adb.shell("su", "0", "sh", "-c",
                    f"if test -f {PREFS}; then cat {PREFS}; else printf MISSING; fi")
    if raw == "MISSING":
        return {"state": "reset", "preferences_exist": False}
    return {"state": parse_stopwatch_state(raw), "preferences_exist": True}


def run_stopwatch(adb: Adb, timeout: int, out_dir: Path) -> dict:
    if not 10 <= timeout <= 1800:
        raise ValueError("timeout must be between 10 and 1800 seconds")
    checked = preflight(adb)
    if not checked["ready_for_app_checks"]:
        raise RuntimeError("APK preflight failed; no Clock data changed")
    if checked["properties"]["ro.build.type"] != "userdebug":
        raise RuntimeError("independent checker requires a userdebug emulator")
    if not adb.shell("pm", "path", CLOCK).startswith("package:"):
        raise RuntimeError("Clock is not installed")
    # Validate checker access and data format before the destructive test reset.
    read_stopwatch(adb)
    if adb.shell("pm", "clear", CLOCK) != "Success":
        raise RuntimeError("Clock reset failed; task not started")
    before = read_stopwatch(adb)
    if before["state"] != "reset":
        raise RuntimeError("Clock reset did not establish the requested initial state")
    result = {"schema": 1, "task": "ClockStopWatchRunning", "goal": GOAL,
              "checker": "host_clock_preferences", "original_androidworld_checker": False,
              "preflight": checked, "before": before}
    try:
        record = run_goal(adb, GOAL, timeout, out_dir)
        agent = json.loads(Path(record["record"]).read_text())
        result["agent_record"] = record["record"]
        result["agent_done"] = (agent.get("report") or {}).get("done") is True
        result["agent_status"] = agent.get("status")
        result["agent_seconds"] = agent.get("agent_seconds")
    except Exception as exc:
        # Never retry or expose arbitrary transport error text, which may contain device data.
        result["agent_error_type"] = type(exc).__name__
        result["agent_done"] = False
    try:
        result["after"] = read_stopwatch(adb)
        result["independent_state"] = "pass" if result["after"]["state"] == "running" else "fail"
    except Exception as exc:
        result["independent_state"] = "error"
        result["checker_error_type"] = type(exc).__name__
    result["combined_pass"] = result["agent_done"] and result["independent_state"] == "pass"
    result["side_effects"] = "not_audited"
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"clock_state_{uuid.uuid4().hex}.json"
    path.write_text(json.dumps(result, indent=2) + "\n")
    return {"record": str(path), "combined_pass": result["combined_pass"],
            "independent_state": result["independent_state"]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=str(Path.home() / "Android/Sdk/platform-tools/adb"))
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    if not 10 <= args.timeout <= 1800:
        parser.error("timeout must be between 10 and 1800 seconds")
    print(json.dumps(run_stopwatch(Adb(args.adb, args.serial), args.timeout, args.out_dir), indent=2))


if __name__ == "__main__":
    main()
