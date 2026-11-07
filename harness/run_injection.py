"""Ballistic's injection tests: the pass/fail safety gate (core/README.md §14,
docs/AGENT_SECURITY_SURVEY.md §5). Cases are tests/injection/NN_<slug>.json.

For each case: reset the emulator to a known state and the home screen, run the setup (SMS,
Markor note, contact, ...), drive `ballistic-core run --request ...` through
core_bridge.CoreHost with confirmations DENIED, measure the device state, and evaluate every
`expect` assertion over the captured lines, confirmations and finished event.

Live (emulator booted, AndroidWorld's venv; the key from BALLISTIC_API_KEY in the environment):

  set -a; source ~/.ballistic_env; set +a
  ~/android_world/.venv/bin/python harness/run_injection.py                 # every case
  ~/android_world/.venv/bin/python harness/run_injection.py --cases 01 03   # some
  ~/android_world/.venv/bin/python harness/run_injection.py --core_bin PATH

Dry run (no emulator, standard library only): evaluate the assertions over existing run JSONs.

  python3 harness/run_injection.py --dry-run ~/ballistic_runs/injection_<stamp>/       # this runner's output
  python3 harness/run_injection.py --dry-run RUN.json --cases 01                       # any core run JSON

Writes ~/ballistic_runs/injection_<stamp>.tsv and ~/ballistic_runs/injection_<stamp>/NN_<slug>.json.
Model route flags (--base_url, --model, --max_tokens, --timeout, --temperature, --reader_model,
--judge_model, --verifier_model, --core_bin, --console_port, --adb_path) are run_task.py's; the
default route is Gemini flash-lite as in run_suite.sh.

Emulator lock protocol: refuses to start while run_suite/run_task/chat.py/gradlew is running or
~/ballistic_runs/emulator.lock exists; holds the lock for the whole run and removes it on exit.
A 429 whose body says PerDay stops the run after that case.
"""

from __future__ import annotations

import argparse
import glob
import hashlib
import json
import os
import subprocess
import sys
import time
from typing import Any, Optional

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import injection_checks as checks  # noqa: E402  local module

GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/openai/"
GEMINI_MODEL = "gemini-flash-lite-latest"
LOCK = os.path.expanduser("~/ballistic_runs/emulator.lock")
BUSY_PATTERN = "[r]un_suite|[r]un_task|[c]hat.py|[g]radlew"


# ---- table -------------------------------------------------------------------------------------

def failed_text(ev: dict[str, Any]) -> str:
    if ev["result"] == "SKIP":
        return ev["note"]
    parts = [f"{a['assertion']}: {a['detail']}" for a in ev["failed"]]
    if ev.get("setup_problems"):
        parts.append("setup: " + "; ".join(ev["setup_problems"]))
    return (ev.get("failure_category", "") + ": " if ev.get("failure_category") else "") + " | ".join(parts)


def print_table(evaluations: list[dict[str, Any]]) -> None:
    print(f"\n{'case':<32} {'result':<6} {'calls':>5}  {'outcome':<12} failed assertions (offending excerpt)")
    for ev in evaluations:
        print(f"{ev['case']:<32} {ev['result']:<6} {ev['model_calls']:>5}  {ev['outcome']:<12} {failed_text(ev)[:400]}")
        if ev.get("note") and ev["result"] != "SKIP":
            print(f"{'':<32} note: {ev['note']}")
    counts = {r: sum(1 for e in evaluations if e["result"] == r) for r in ("PASS", "FAIL", "SKIP", "ERROR")}
    print("\n" + "  ".join(f"{k} {v}" for k, v in counts.items()))


def write_tsv(path: str, evaluations: list[dict[str, Any]], core_bin: str, commit: str) -> None:
    with open(path, "w") as f:
        f.write("case\tresult\tmodel_calls\toutcome\tfailed\tnote\tjson\tcore_bin\tcore_commit\tfailure_category\n")
        for ev in evaluations:
            row = [ev["case"], ev["result"], str(ev["model_calls"]), ev["outcome"],
                   failed_text(ev) if ev["result"] != "SKIP" else "", ev.get("note", ""),
                   ev.get("json", ""), core_bin, commit, ev.get("failure_category", "")]
            f.write("\t".join(x.replace("\t", " ").replace("\n", " ") for x in row) + "\n")


# ---- dry run -----------------------------------------------------------------------------------

def dry_run(paths: list[str], only: Optional[list[str]]) -> int:
    """Evaluate assertions over recorded runs. A record written by this runner names its case;
    any other core run JSON is checked against every case selected with --cases."""
    files: list[str] = []
    for p in paths:
        files.extend(sorted(glob.glob(os.path.join(p, "*.json"))) if os.path.isdir(p) else [p])
    cases = {c["name"]: c for c in checks.load_cases()}
    selected = checks.load_cases(only=only) if only else []
    evaluations = []
    for path in files:
        with open(path) as f:
            record = json.load(f)
        if "lines" not in record:
            continue
        targets = [cases[record["case"]]] if record.get("case") in cases else selected
        for case in targets:
            ev = checks.evaluate(case, record)
            ev["json"] = path
            evaluations.append(ev)
    if not evaluations:
        print("nothing to evaluate (no injection records; pass --cases to check other core runs)")
        return 2
    print_table(evaluations)
    return 1 if any(e["result"] in ("FAIL", "ERROR") for e in evaluations) else 0


# ---- live run ----------------------------------------------------------------------------------

def emulator_free() -> Optional[str]:
    """None if the emulator may be used; else why not."""
    if os.path.exists(LOCK):
        with open(LOCK) as f:
            return f"lock held: {f.read().strip()[:200]}"
    busy = subprocess.run(["pgrep", "-f", BUSY_PATTERN], capture_output=True, text=True).stdout.split()
    busy = [p for p in busy if p != str(os.getpid())]
    return f"busy: pids {' '.join(busy)}" if busy else None


def core_commit(core_bin: str) -> str:
    """The commit of the checkout the binary sits in or was resolved from (plus `-dirty` when its
    core/ has uncommitted changes) and the binary's mtime. A binary in a shared cargo target dir
    may have been built from another checkout; the mtime is the honest part."""
    here = os.path.dirname(os.path.abspath(__file__))
    try:
        head = subprocess.run(["git", "-C", here, "log", "-1", "--format=%h", "--", "../core"],
                              capture_output=True, text=True, timeout=10).stdout.strip()
        dirty = subprocess.run(["git", "-C", here, "status", "--porcelain", "--", "../core"],
                               capture_output=True, text=True, timeout=10).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        head, dirty = "", ""
    try:
        built = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(os.path.getmtime(core_bin)))
    except OSError:
        built = "?"
    with open(core_bin, "rb") as binary:
        digest = hashlib.file_digest(binary, "sha256").hexdigest()
    return f"source-checkout={head or '?'}{'-dirty' if dirty else ''}; binary-source-unverified; sha256={digest}; mtime={built}"


def live_run(opts: argparse.Namespace, route_argv: list[str]) -> int:
    why = emulator_free()
    if why:
        print(f"emulator not free ({why}); not starting")
        return 3
    os.makedirs(os.path.dirname(LOCK), exist_ok=True)
    try:
        lock = open(LOCK, "x")
    except FileExistsError:
        print("emulator lock acquired by another process; not starting")
        return 3
    token = f"run_injection pid={os.getpid()} {time.time_ns()}"
    with lock:
        lock.write(token)
    try:
        return _live_run(opts, route_argv)
    finally:
        try:
            with open(LOCK) as f:
                owned = f.read() == token
            if owned:
                os.remove(LOCK)
        except OSError:
            pass


def _live_run(opts: argparse.Namespace, route_argv: list[str]) -> int:
    # AndroidWorld and run_task's flags only for live runs, so the dry run needs neither.
    import requests
    import core_bridge
    import injection_setup
    import run_task
    from android_world.env import env_launcher

    run_task.FLAGS(["run_task", *route_argv, "--agent", "core", "--confirm", "deny"])
    F = run_task.FLAGS
    if F.console_port != 5554:
        raise ValueError("injection tests are restricted to emulator-5554")
    core_bin = F.core_bin or core_bridge.default_core_bin()
    commit = core_commit(core_bin)

    class QuotaAwareModelClient(core_bridge.ModelClient):
        """core_bridge.ModelClient that notes a per-day quota 429 (flag only; no body is kept)."""
        per_day_quota = False

        def complete(self, role, messages, max_tokens):
            try:
                return super().complete(role, messages, max_tokens)
            except requests.HTTPError as e:
                metadata = self._quota_metadata(e.response)
                self.per_day_quota = metadata.get("quota_category") in ("daily", "mixed")
                raise

    cases = checks.load_cases(only=opts.cases)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = os.path.join(F.out_dir, f"injection_{stamp}")
    os.makedirs(out_dir, exist_ok=True)
    print(f"{len(cases)} cases  core {core_bin} ({commit})  model {F.model} @ {F.base_url}  confirm deny\n")

    if not cases:
        raise ValueError("no matching injection cases")
    env = env_launcher.load_and_setup_env(console_port=F.console_port, emulator_setup=False, adb_path=F.adb_path)
    evaluations = []
    try:
        for case in cases:
            print(f"== {case['name']}: {case['request']}")
            if case.get("skip_reason"):
                ev = checks.evaluate(case, {})
                evaluations.append(ev)
                print(f"   SKIP {case['skip_reason']}")
                continue
            models = QuotaAwareModelClient(run_task.core_models().routes, F.temperature, F.timeout)
            host = core_bridge.CoreHost(env, models, "deny", run_task.print_core_event)
            args = ["run", "--request", case["request"], "--max-steps", str(case.get("max_steps", 10))]
            setup_log, problems, measured = [], [], []
            started = time.monotonic()
            run = {"finished": {}, "done": False, "exit_code": None, "stderr": "", "total_seconds": 0}
            try:
                env.reset(go_home=True)
                injection_setup.reset(env, case)
                setup_log = injection_setup.run_setup(env, case)
                problems = injection_setup.setup_problems(case, setup_log)
                if not problems:
                    run = run_task.serve_core(host, args)
                    measured = injection_setup.state_after(env, case)
            except Exception as exc:
                problems.append(f"setup/run/readback error: {type(exc).__name__}: {str(exc)[:400]}")
                # Still inspect effects after a failed run where possible.
                try:
                    measured = injection_setup.state_after(env, case)
                except Exception as readback_error:
                    problems.append(f"state readback unavailable: {type(readback_error).__name__}")
            finally:
                try:
                    injection_setup.teardown(env, case)
                except Exception as cleanup_error:
                    problems.append(f"cleanup failed: {type(cleanup_error).__name__}")
            record = {
                "case": case["name"], "request": case["request"], "expect": case["expect"],
                "setup": case.get("setup", []), "setup_log": setup_log, "setup_problems": problems,
                "core_bin": core_bin, "core_commit": commit, "core_args": args,
                "model": F.model, "base_url": F.base_url,
                "roles": {r: m for r, (_, m, _) in models.routes.items()}, "confirm": "deny",
                "steps": [e for e in host.events if "step" in e], "finished": run["finished"],
                "model_calls": models.calls, "confirmations": host.confirmations,
                "state_after": measured, "total_seconds": time.monotonic() - started, "exit_code": run["exit_code"],
                "stderr": run["stderr"][-4000:], "lines": host.lines,
            }
            ev = checks.evaluate(case, record)
            ev["setup_problems"] = problems
            record["evaluation"] = ev
            path = os.path.join(out_dir, f"{case['name']}.json")
            with open(path, "w") as f:
                json.dump(record, f, indent=1)
            ev["json"] = path
            evaluations.append(ev)
            write_tsv(os.path.join(F.out_dir, f"injection_{stamp}.tsv"), evaluations, core_bin, commit)
            print(f"   {ev['result']}  outcome={ev['outcome']}  model_calls={ev['model_calls']}  {failed_text(ev)[:300]}")
            if models.per_day_quota:
                print("\nper-day quota exhausted (429 PerDay); stopping")
                break
    finally:
        env.close()

    print_table(evaluations)
    tsv = os.path.join(F.out_dir, f"injection_{stamp}.tsv")
    write_tsv(tsv, evaluations, core_bin, commit)
    print(f"written {tsv}\nwritten {out_dir}/")
    return 1 if any(e["result"] in ("FAIL", "ERROR") for e in evaluations) else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                 epilog="Other flags (--model, --core_bin, ...) are passed to run_task.py's flags.")
    ap.add_argument("--dry-run", nargs="+", metavar="RUN_JSON_OR_DIR",
                    help="evaluate assertions over recorded run JSONs; no emulator")
    ap.add_argument("--cases", nargs="+", help="case ids or names (01, 03_sms_new_number, ...)")
    opts, route_argv = ap.parse_known_args()
    if opts.dry_run:
        return dry_run(opts.dry_run, opts.cases)
    defaults = {"--base_url": GEMINI_URL, "--model": GEMINI_MODEL, "--max_tokens": "2000", "--timeout": "120"}
    given = {a.split("=", 1)[0] for a in route_argv if a.startswith("--")}
    for flag, value in defaults.items():
        if flag not in given:
            route_argv += [flag, value]
    return live_run(opts, route_argv)


if __name__ == "__main__":
    sys.exit(main())
