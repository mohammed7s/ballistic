"""Drive the real APK on a virtual device; never substitute ADB input for Computer Control.

`preflight` is read-only. `run` requires installed app/test APKs and completed owner setup.
This pilot driver is independent of AndroidWorld's API-33/gRPC environment launcher.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import uuid

PACKAGE = "app.ballistic.assistant"
RUNNER = f"{PACKAGE}.test/{PACKAGE}.benchmark.ComputerControlBenchmark"
VIRTUAL_HARDWARE = {"ranchu", "goldfish", "cutf_cvm", "cutf_ivsh"}


class Adb:
    def __init__(self, executable: str, serial: str):
        if not serial or serial.startswith("-") or any(c.isspace() for c in serial):
            raise ValueError("an explicit, valid device serial is required")
        self.prefix = [executable, "-s", serial]

    def command(self, *args: str, data: str | None = None, timeout: int = 30) -> str:
        result = subprocess.run(self.prefix + list(args), input=data, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            # Never echo command data or arbitrary device stderr into a benchmark record.
            raise RuntimeError(f"adb failed with exit {result.returncode}")
        return result.stdout.strip()

    def shell(self, *args: str, **kwargs) -> str:
        # adb shell reparses on-device: quote each argument, including sh -c bodies.
        return self.command("shell", shlex.join(args), **kwargs)


def preflight(adb: Adb) -> dict:
    properties = {name: adb.shell("getprop", name) for name in (
        "ro.hardware", "ro.build.version.sdk", "ro.build.fingerprint", "ro.build.type")}
    libraries = adb.shell("pm", "list", "libraries")
    role = adb.shell("cmd", "role", "get-role-holders", "--user", "0", "android.app.role.ASSISTANT")
    installed = adb.shell("pm", "path", PACKAGE).startswith("package:")
    instrumentations = adb.shell("pm", "list", "instrumentation")
    checks = {
        "virtual_device": properties["ro.hardware"] in VIRTUAL_HARDWARE,
        "api_37_or_later": properties["ro.build.version.sdk"].isdigit() and int(properties["ro.build.version.sdk"]) >= 37,
        "computer_control_library": "library:com.android.extensions.computercontrol" in libraries.splitlines(),
        "app_installed": installed,
        "test_runner_installed": f"instrumentation:{RUNNER} (target={PACKAGE})" in instrumentations.splitlines(),
        "assistant_selected": PACKAGE in role.splitlines(),
    }
    return {"properties": properties, "checks": checks,
            "ready_for_app_checks": all(checks.values()),
            "note": "Owner switches, app rules, model setup and target consent are checked inside the app."}


def run_goal(adb: Adb, goal: str, timeout: int, out_dir: Path, mode: str = "agent") -> dict:
    if mode not in ("agent", "adapter-smoke"):
        raise ValueError("unknown test mode")
    if not goal.strip() or len(goal) > 8000:
        raise ValueError("goal must contain 1–8000 characters")
    if not 10 <= timeout <= 1800:
        raise ValueError("timeout must be between 10 and 1800 seconds")
    report = preflight(adb)
    if not report["ready_for_app_checks"]:
        failed = [key for key, ok in report["checks"].items() if not ok]
        raise RuntimeError("preflight blocked: " + ", ".join(failed))
    request_id = uuid.uuid4().hex
    directory = "files/cc_benchmark"
    adb.shell("run-as", PACKAGE, "mkdir", "-p", directory)
    request = json.dumps({"goal": goal, "timeout_seconds": timeout, "mode": mode})
    # UUID and fixed path only; goal and other request data travel over stdin, never shell code.
    adb.shell("run-as", PACKAGE, "sh", "-c", f"cat > {directory}/{request_id}.request.json", data=request)
    try:
        adb.shell("am", "instrument", "-w", "-e", "request_id", request_id, RUNNER, timeout=timeout + 90)
    except subprocess.TimeoutExpired as exc:
        # A hung instrumentation may leave model/input work alive. Terminate only this test app
        # on the already-verified virtual device, never retry a possibly effective request.
        adb.shell("am", "force-stop", PACKAGE)
        raise RuntimeError("instrumentation timed out; test app stopped; effects are unknown, do not retry automatically") from exc
    raw = adb.shell("run-as", PACKAGE, "cat", f"{directory}/{request_id}.result.json")
    result = json.loads(raw)
    if result.get("request_id") != request_id or result.get("transport") != "apk_computer_control":
        raise RuntimeError("mismatched benchmark result")
    if result.get("mode", "agent") != mode:
        raise RuntimeError("test APK does not support the requested mode; install the matching test APK")
    result["preflight"] = report
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"cc_{request_id}.json"
    path.write_text(json.dumps(result, indent=2) + "\n")
    return {"status": result.get("status"), "reason": result.get("reason"), "record": str(path)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["preflight", "run"])
    parser.add_argument("--serial", required=True, help="explicit virtual-device serial; never auto-selects a phone")
    parser.add_argument("--adb", default=os.path.expanduser("~/Android/Sdk/platform-tools/adb"))
    parser.add_argument("--goal", help="request for the real app; no checker information")
    parser.add_argument("--mode", choices=["agent", "adapter-smoke"], default="agent",
                        help="adapter-smoke is a fixed Clock adapter test with zero model calls, not an agent benchmark")
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--out-dir", type=Path, default=Path.home() / "ballistic_runs")
    args = parser.parse_args()
    adb = Adb(args.adb, args.serial)
    if args.command == "preflight":
        result = preflight(adb)
    else:
        if args.goal is None:
            parser.error("run requires --goal")
        result = run_goal(adb, args.goal, args.timeout, args.out_dir, args.mode)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
