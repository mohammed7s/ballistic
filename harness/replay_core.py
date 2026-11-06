"""Replay a recorded core run through ballistic-core with no emulator and no model.

A run JSON written by run_task.py --agent core or chat.py --agent core keeps every bridge line in
`lines` (core's asks and the host's replies, in order). This starts ballistic-core with the same
arguments and answers each ask with the recorded reply: observe -> the recorded screen, complete ->
the recorded completion, act -> the recorded result, confirm -> the recorded verdict. Every line
core writes is compared with the recorded ask at the same position; the first difference is
reported and the replay stops there. core/README.md §15, rule 4, done on the host side.

  python3 harness/replay_core.py ~/ballistic_runs/20261107-101500_Chat.json
  python3 harness/replay_core.py RUN.json --core_bin core/target/debug/ballistic-core --ignore_keys seconds

Needs only the standard library and a built ballistic-core (AndroidWorld's venv is not required).
Exit status: 0 if core's asks match the recording to its end, 1 if they diverge, 2 on bad input.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from typing import Any, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import core_bridge  # noqa: E402  start_core, default_core_bin

# Log events carry timings that differ on every run; these keys are dropped before comparing.
DEFAULT_IGNORE = "seconds,elapsed_ms,millis_taken,time,timestamp,ts"


def strip_keys(v: Any, ignore: set[str]) -> Any:
    if isinstance(v, dict):
        return {k: strip_keys(x, ignore) for k, x in v.items() if k not in ignore}
    if isinstance(v, list):
        return [strip_keys(x, ignore) for x in v]
    return v


def first_difference(a: Any, b: Any, path: str = "") -> Optional[tuple[str, Any, Any]]:
    """Path of the first place two JSON values differ, with both values there; None if equal."""
    if isinstance(a, dict) and isinstance(b, dict):
        for k in list(a) + [k for k in b if k not in a]:
            if k not in a or k not in b:
                return f"{path}.{k}", a.get(k, "<missing>"), b.get(k, "<missing>")
            d = first_difference(a[k], b[k], f"{path}.{k}")
            if d:
                return d
        return None
    if isinstance(a, list) and isinstance(b, list):
        for i, (x, y) in enumerate(zip(a, b)):
            d = first_difference(x, y, f"{path}[{i}]")
            if d:
                return d
        if len(a) != len(b):
            return f"{path} (length)", len(a), len(b)
        return None
    return None if a == b else (path or "(top)", a, b)


def short(v: Any, n: int) -> str:
    s = v if isinstance(v, str) else json.dumps(v)
    return s if len(s) <= n else s[:n] + f"... ({len(s)} chars)"


def core_args_for(run: dict[str, Any], max_steps: Optional[int]) -> list[str]:
    """The arguments core was started with. Older records have no `core_args`; rebuild them."""
    if run.get("core_args") and max_steps is None:
        return list(run["core_args"])
    if run.get("core_mode") == "observe":
        return ["observe"]
    if max_steps is None:
        sys.exit("this record has no core_args; pass --max_steps (the step budget of the original run)")
    return ["run", "--request", run["goal"], "--max-steps", str(max_steps)]


def replay(run: dict[str, Any], core_bin: str, args: list[str], ignore: set[str], width: int) -> int:
    recorded = [ln for ln in run.get("lines", []) if ln.get("dir") in ("ask", "reply")]
    asks = [{k: v for k, v in ln.items() if k != "dir"} for ln in recorded if ln["dir"] == "ask"]
    replies: dict[int, list[dict[str, Any]]] = {}  # ask id -> replies, in order (ids are unique per run)
    for ln in recorded:
        if ln["dir"] == "reply":
            replies.setdefault(ln.get("id", 0), []).append({k: v for k, v in ln.items() if k != "dir"})
    if not asks:
        print("no recorded asks in this file (was it written by an agent other than core?)")
        return 2

    print(f"replaying {len(asks)} recorded asks: {core_bin} {' '.join(json.dumps(a) for a in args)}")
    proc = core_bridge.start_core(core_bin, args)
    assert proc.stdout is not None and proc.stdin is not None
    i = 0          # recorded asks matched so far
    outcome = ""   # diverged | finished | unanswered; empty if core closed stdout first
    noise = 0
    try:
        for raw in proc.stdout:
            line = raw.decode("utf-8", "replace").strip()
            if not line:
                continue
            try:
                ask = json.loads(line)
            except json.JSONDecodeError:
                noise += 1  # recorded as core_stdout; not part of the protocol
                continue
            if i >= len(asks):
                print(f"\ndiverged at ask {i}: the recording ends here, core goes on")
                print(f"  core:     {short(ask, width)}")
                outcome = "diverged"
                break
            want = asks[i]
            if want.get("ask") != ask.get("ask"):
                diff: Optional[tuple[str, Any, Any]] = (".ask", want.get("ask"), ask.get("ask"))
            else:
                diff = first_difference(strip_keys(want, ignore), strip_keys(ask, ignore))
            if diff:
                path, a, b = diff
                print(f"\ndiverged at ask {i} ({want.get('ask')} recorded, {ask.get('ask')} from core), at {path}")
                print(f"  recorded: {short(a, width)}")
                print(f"  core:     {short(b, width)}")
                print(f"  recorded line: {short(want, width)}")
                print(f"  core line:     {short(ask, width)}")
                outcome = "diverged"
                break
            i += 1
            if ask.get("ask") == "log":
                if (ask.get("event") or {}).get("finished"):
                    outcome = "finished"
                    break
                continue
            queue = replies.get(ask.get("id", 0)) or []
            if not queue:
                outcome = "unanswered"  # the original run was stopped (Ctrl+C) while this ask was open
                break
            proc.stdin.write((json.dumps(queue.pop(0)) + "\n").encode())
            proc.stdin.flush()
    except BrokenPipeError:
        pass
    finally:
        try:
            proc.stdin.close()
        except OSError:
            pass
        try:
            proc.wait(timeout=10)
        except Exception:  # noqa: BLE001
            proc.kill()
            proc.wait()
    stderr = proc.stderr.read().decode("utf-8", "replace") if proc.stderr else ""

    result = 1 if outcome == "diverged" else 0
    if outcome == "unanswered":
        print(f"\nmatch up to the end of the recording: ask {i - 1} has no recorded reply (the run was stopped there)")
    elif outcome in ("finished", "") and i < len(asks):
        print(f"\ndiverged at ask {i}: core {'finished' if outcome else 'exited'}, the recording goes on")
        print(f"  recorded: {short(asks[i], width)}")
        result = 1
    elif outcome != "diverged":
        print(f"\nmatch: core's {i} asks equal the recording's {len(asks)}")
    print(f"core exit {proc.returncode}" + (f", {noise} non-JSON stdout lines" if noise else ""))
    if stderr.strip():
        print("core stderr:", stderr.strip()[:2000])
    return result


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("run_json", help="run file from run_task.py or chat.py with --agent core")
    p.add_argument("--core_bin", default=None, help="ballistic-core binary (default: core/target/{release,debug})")
    p.add_argument("--max_steps", type=int, default=None, help="override the step budget (needed for records without core_args)")
    p.add_argument("--ignore_keys", default=DEFAULT_IGNORE, help=f"comma-separated keys left out of the comparison (default {DEFAULT_IGNORE})")
    p.add_argument("--width", type=int, default=600, help="characters of each differing line to print")
    a = p.parse_args()
    try:
        with open(os.path.expanduser(a.run_json)) as f:
            run = json.load(f)
    except (OSError, json.JSONDecodeError) as e:
        print(f"cannot read {a.run_json}: {e}")
        sys.exit(2)
    if run.get("agent") != "core":
        print(f"{a.run_json} is a {run.get('agent')} run; only core runs have bridge lines")
        sys.exit(2)
    ignore = {k for k in a.ignore_keys.split(",") if k}
    core_bin = a.core_bin or core_bridge.default_core_bin()
    sys.exit(replay(run, core_bin, core_args_for(run, a.max_steps), ignore, a.width))


if __name__ == "__main__":
    main()
