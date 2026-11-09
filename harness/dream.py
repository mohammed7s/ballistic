"""Offline "dream" pass over a core suite: cluster failures, write a short markdown report.

docs/research/OPENCLAW_DREAMING.md, proposal C (4.3). Reads run JSONs written by run_task.py
--agent core and uses only core-written or harness-written fields: the report's fixed phrases,
step action types and core outcome lines, readback words, failure categories, benchmark status,
observation headers (package and activity, never node text or labels) and usage counts. Planner
prompts, reader prompts, screen text and answer text are never read into the report, so nothing an
app shows can reach it (or the optional summary model). No model call unless --summarize is
given. The report proposes; it changes nothing.

  python3 harness/dream.py ballistic_runs/suite_X/ [more dirs or run JSONs] [--out report.md]
      [--compare OLD_SUITE_DIR] [--group "practice:TaskA,TaskB" --group "rotation:TaskC"]
      [--price_in 0.10 --price_out 0.50] [--summarize [--summary_model claude-haiku-5-5]]

Stdlib only (the optional summary imports the Anthropic SDK lazily).
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from suite_row import model_usage  # noqa: E402

# Core's own report phrases that may be quoted as they are. Any other reason (an answer's
# display string, a planner give-up reason, a judge reason) is withheld.
FIXED_REASONS = ("step budget exhausted", "planner gave up", "stopped by owner",
                 "host capability or app grant denied", "owner denied confirmation",
                 "execution outcome unknown; stopped to prevent duplicate effects",
                 "host did not execute action")
FIXED_REASON_PREFIXES = ("stopped:", "invalid action:", "guarded input", "reader made no progress",
                         "current screen does not establish", "owner summary unavailable",
                         "core checked the result", "same guarded input")
WITHHELD = "(planner/answer text withheld)"
OUTCOME_CHARS = 120


def fixed_reason(reason) -> str:
    if not isinstance(reason, str) or not reason:
        return ""
    r = reason.strip()
    if r in FIXED_REASONS or r.startswith(FIXED_REASON_PREFIXES):
        return r[:OUTCOME_CHARS]
    return WITHHELD


def outcome_kind(record: dict, report: dict) -> str:
    reason = report.get("reason") if isinstance(report.get("reason"), str) else ""
    if record.get("exit_code") or (record.get("finished") or {}).get("error"):
        return "error"
    if report.get("done"):
        return "done"
    if isinstance(report.get("answer"), dict):
        return "answered"
    if report.get("owner_output"):
        return "owner_excerpts"
    if reason == "step budget exhausted":
        return "budget"
    if reason == "planner gave up":
        return "give_up"
    if reason.startswith("stopped:"):
        return "loop_stop"
    if reason.startswith("invalid action"):
        return "invalid_action"
    if reason.startswith("reader made no progress"):
        return "reader_no_progress"
    if "guarded input" in reason:
        return "guarded_repeat"
    if report.get("blocked_by"):
        return "judge_block"
    if report.get("task_status") == "unsupported":
        return "unsupported"
    return "other"


def result_of(record: dict) -> str:
    if (record.get("benchmark_status") == "error" or record.get("exit_code")
            or record.get("failure_category") == "environment"):
        return "ERROR"
    return "PASS" if record.get("success") else "FAIL"


def last_observation_header(lines: list) -> tuple[str, str]:
    for line in reversed(lines or []):
        data = line.get("data") if isinstance(line, dict) and line.get("dir") == "reply" else None
        if isinstance(data, dict) and "nodes" in data:
            return str(data.get("package") or ""), str(data.get("activity") or "")
    return "", ""


def invalid_corrections(lines: list) -> int:
    n = 0
    for line in lines or []:
        if isinstance(line, dict) and line.get("dir") == "ask" and line.get("ask") == "complete" \
                and line.get("role") == "planner":
            msgs = line.get("messages") or []
            last = msgs[-1] if msgs else {}
            if isinstance(last, dict) and last.get("role") == "user" \
                    and str(last.get("content", "")).startswith("Invalid action"):
                n += 1
    return n


def summarize_run(record: dict, path: str = "") -> dict:
    """One failure record from allowlisted fields only."""
    finished = record.get("finished") or {}
    report = finished.get("report") if isinstance(finished.get("report"), dict) else {}
    steps = [s for s in record.get("steps") or [] if isinstance(s, dict)]
    acts = [s.get("action", {}).get("action") for s in steps if isinstance(s.get("action"), dict)]
    effective = [a for a in acts if a not in ("done", "give_up")]
    readbacks = [s.get("readback") for s in steps if isinstance(s.get("readback"), dict)]
    last_rb = readbacks[-1] if readbacks else None
    outcomes = [str(s.get("outcome", ""))[:OUTCOME_CHARS] for s in steps]
    package, activity = last_observation_header(record.get("lines"))
    usage = model_usage(record.get("model_calls") or [])
    delivery = record.get("answer_delivery") if isinstance(record.get("answer_delivery"), dict) else {}
    return {
        "task": str(record.get("task", "")), "path": path, "result": result_of(record),
        "benchmark": record.get("benchmark_status") or "", "done": bool(record.get("done")),
        "failure_category": record.get("failure_category") or "",
        # A done run's reason is the check's feedback; its evidence basis says more, in fixed words.
        "reason": (f"done ({(report.get('verification') or {}).get('evidence_basis', '')})" if report.get("done")
                   else fixed_reason(report.get("reason"))),
        "kind": outcome_kind(record, report),
        "package": package, "activity": activity.rsplit(".", 1)[-1] if activity else "",
        "last_action": effective[-1] if effective else "", "actions": acts,
        "readback": (f"{last_rb.get('outcome', '')}({','.join(last_rb.get('signals') or [])})"
                     if last_rb else ""),
        "readback_word": last_rb.get("outcome", "") if last_rb else "",
        "reader_rejections": sum(1 for s in steps if isinstance(s.get("reader"), dict) and s["reader"].get("rejected")),
        "element_none": sum(1 for o in outcomes if o.startswith("reader: element none")),
        "invalid_corrections": invalid_corrections(record.get("lines")),
        "typed_postcondition": any(s.get("typed_postcondition") == "verified" for s in steps),
        "evidence_basis": (report.get("verification") or {}).get("evidence_basis", ""),
        "steps": len(steps), "model_calls": len(record.get("model_calls") or []),
        "prompt_tokens": usage["prompt_tokens"], "completion_tokens": usage["completion_tokens"],
        "answered": isinstance(report.get("answer"), dict),
        "answer_delivered": bool(delivery.get("delivered")),
        "outcome_tail": outcomes[-3:],
    }


def load(inputs: list[str]) -> tuple[list[dict], int]:
    """Core run records from directories and files; the latest file per task wins."""
    paths: list[Path] = []
    for item in inputs:
        p = Path(item).expanduser()
        paths.extend(sorted(p.glob("*.json")) if p.is_dir() else [p])
    by_task: dict[str, dict] = {}
    superseded = 0
    for p in sorted(paths, key=lambda q: q.name):
        try:
            record = json.loads(p.read_text())
        except (OSError, ValueError):
            continue
        if not isinstance(record, dict) or record.get("agent") != "core" or not record.get("task"):
            continue
        run = summarize_run(record, str(p))
        if run["task"] in by_task:
            superseded += 1
        by_task[run["task"]] = run
    return list(by_task.values()), superseded


def cluster_key(r: dict) -> tuple:
    return (r["failure_category"] or "-", r["package"] or "-", r["activity"] or "-",
            r["last_action"] or "-", r["readback_word"] or "-", r["kind"])


def clusters(runs: list[dict]) -> list[tuple[tuple, list[dict]]]:
    groups: dict[tuple, list[dict]] = defaultdict(list)
    for r in runs:
        if r["result"] != "PASS":
            groups[cluster_key(r)].append(r)
    return sorted(groups.items(), key=lambda kv: (-len({r["task"] for r in kv[1]}), -len(kv[1]), kv[0]))


def totals(runs: list[dict], price_in: float, price_out: float) -> dict:
    t = {"tasks": len(runs), "pass": sum(r["result"] == "PASS" for r in runs),
         "error": sum(r["result"] == "ERROR" for r in runs),
         "state_passed": sum(r["benchmark"] == "passed" for r in runs),
         "false_done": sum(r["done"] and r["benchmark"] == "failed" for r in runs),
         "answered": sum(r["answered"] for r in runs),
         "answer_delivered": sum(r["answer_delivered"] for r in runs),
         # Completions core checked in code (code_validated_*), after an effect or on done.
         "typed_checks": sum(r["done"] and str(r["evidence_basis"]).startswith("code_validated") for r in runs),
         "model_calls": sum(r["model_calls"] for r in runs),
         "prompt_tokens": sum(r["prompt_tokens"] for r in runs),
         "completion_tokens": sum(r["completion_tokens"] for r in runs)}
    t["cost"] = (t["prompt_tokens"] * price_in + t["completion_tokens"] * price_out) / 1e6 \
        if (price_in or price_out) else None
    return t


def md_cell(v) -> str:
    return str(v).replace("|", "/").replace("\n", " ")


def cluster_table(runs: list[dict]) -> list[str]:
    out = ["| # | Tasks | Runs | Failure category | Package | Activity | Last action | Readback | Outcome kind | Task names |",
           "|---:|---:|---:|---|---|---|---|---|---|---|"]
    for i, (key, members) in enumerate(clusters(runs), 1):
        names = sorted({r["task"] for r in members})
        out.append(f"| {i} | {len(names)} | {len(members)} | " + " | ".join(md_cell(k) for k in key)
                   + f" | {', '.join(names)} |")
    return out


def totals_line(label: str, t: dict) -> str:
    cost = f" · cost ${t['cost']:.4f}" if t["cost"] is not None else ""
    return (f"| {label} | {t['tasks']} | **{t['pass']}** | {t['state_passed']} | {t['false_done']} | "
            f"{t['answered']} / {t['answer_delivered']} | {t['typed_checks']} | {t['error']} | {t['model_calls']} | "
            f"{t['prompt_tokens']:,} / {t['completion_tokens']:,}{cost} |")


def report(runs: list[dict], superseded: int, *, title: str, groups: list[tuple[str, set]] | None = None,
           old: list[dict] | None = None, price_in: float = 0.0, price_out: float = 0.0,
           summary: str | None = None) -> str:
    runs = sorted(runs, key=lambda r: r["task"])
    lines = [f"# Dream pass: {title}", "",
             "Offline clustering of core-written fields (no screen, reader or planner text). Proposals only.", ""]
    lines += ["| Set | Tasks | PASS | State passed | False done | Answered / delivered | Typed checks | ERROR | Model calls | Prompt / output tokens |",
              "|---|---:|---:|---:|---:|---:|---:|---:|---:|---|"]
    for label, names in groups or []:
        lines.append(totals_line(label, totals([r for r in runs if r["task"] in names], price_in, price_out)))
    lines.append(totals_line("all", totals(runs, price_in, price_out)))
    if superseded:
        lines += ["", f"{superseded} earlier run(s) of a repeated task were superseded by the latest file."]
    lines += ["", "## Failure clusters", "", "Ranked by distinct tasks, then runs.", ""] + cluster_table(runs)
    uncredited = [r for r in runs if r["benchmark"] == "passed" and r["result"] != "PASS"]
    lines += ["", "## Done but not credited (state passed, not PASS)", ""]
    lines += [f"- {r['task']}: {r['kind']}; {r['failure_category'] or '-'}; last action {r['last_action'] or '-'}; "
              f"tail: {md_cell(' / '.join(r['outcome_tail']))}" for r in uncredited] or ["- none"]
    false_done = [r for r in runs if r["done"] and r["benchmark"] == "failed"]
    lines += ["", "## False done (agent done, state failed)", ""]
    lines += [f"- {r['task']}: evidence {r['evidence_basis'] or '-'}" for r in false_done] or ["- none"]
    lines += ["", "## Per task", "",
              "| Task | Result | State | Done | Category | Kind | Steps | Calls | Final screen | Last action | Readback | Element none | Rejected reads | Corrections | Reason |",
              "|---|---|---|---|---|---|---:|---:|---|---|---|---:|---:|---:|---|"]
    for r in runs:
        lines.append(f"| {r['task']} | {r['result']} | {r['benchmark']} | {str(r['done']).lower()} | "
                     f"{r['failure_category'] or '-'} | {r['kind']} | {r['steps']} | {r['model_calls']} | "
                     f"{md_cell(r['package'])}/{md_cell(r['activity'])} | {r['last_action'] or '-'} | "
                     f"{md_cell(r['readback']) or '-'} | {r['element_none']} | {r['reader_rejections']} | "
                     f"{r['invalid_corrections']} | {md_cell(r['reason']) or '-'} |")
    if old is not None:
        before = {r["task"]: r for r in old}
        lines += ["", "## Against the comparison suite", "",
                  "| Task | Old | New | Old state | New state | Old steps | New steps |",
                  "|---|---|---|---|---|---:|---:|"]
        for name in sorted(set(before) | {r["task"] for r in runs}):
            o, n = before.get(name), next((r for r in runs if r["task"] == name), None)
            mark = lambda r, k: (r[k] if r else "not run")  # noqa: E731
            change = " **changed**" if o and n and o["result"] != n["result"] else ""
            lines.append(f"| {name} | {mark(o, 'result')} | {mark(n, 'result')}{change} | {mark(o, 'benchmark')} | "
                         f"{mark(n, 'benchmark')} | {mark(o, 'steps')} | {mark(n, 'steps')} |")
    if summary is not None:
        lines += ["", "## Model summary (proposals, unverified)", "", summary.strip()]
    return "\n".join(lines) + "\n"


def model_summary(runs: list[dict], model: str) -> str:
    """One call; the prompt holds only the cluster table built from allowlisted fields."""
    import anthropic  # lazy: optional
    table = "\n".join(cluster_table(runs))
    client = anthropic.Anthropic()  # key from ANTHROPIC_API_KEY; never printed
    reply = client.messages.create(model=model, max_tokens=600, messages=[{"role": "user", "content":
        "Below is a table of failure clusters from an Android UI agent benchmark (fields written by the "
        "agent's code: failure category, app package, activity, last action type, readback word, outcome "
        "kind). Propose at most five next fixes as a short bullet list, each naming the cluster number. "
        "Proposals only; say what evidence would confirm each.\n\n" + table}])
    return "".join(getattr(b, "text", "") for b in reply.content)


def parse_groups(specs: list[str]) -> list[tuple[str, set]]:
    groups = []
    for spec in specs or []:
        label, _, names = spec.partition(":")
        groups.append((label.strip(), {n.strip() for n in names.replace(" ", ",").split(",") if n.strip()}))
    return groups


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("inputs", nargs="+", help="suite directories or run JSON files")
    p.add_argument("--compare", nargs="*", default=None, help="older suite directories or files")
    p.add_argument("--group", action="append", default=[], help='"label:TaskA,TaskB" totals per task set')
    p.add_argument("--title", default=None)
    p.add_argument("--out", default=None)
    p.add_argument("--price_in", type=float, default=0.0, help="USD per million prompt tokens")
    p.add_argument("--price_out", type=float, default=0.0, help="USD per million output tokens")
    p.add_argument("--summarize", action="store_true", help="one model call over the cluster table (off by default)")
    p.add_argument("--summary_model", default="claude-haiku-5-5")
    a = p.parse_args(argv)
    runs, superseded = load(a.inputs)
    if not runs:
        print("no core run JSONs found", file=sys.stderr)
        return 2
    old = load(a.compare)[0] if a.compare else None
    summary = model_summary(runs, a.summary_model) if a.summarize else None
    text = report(runs, superseded, title=a.title or ", ".join(Path(i).name for i in a.inputs),
                  groups=parse_groups(a.group), old=old, price_in=a.price_in, price_out=a.price_out, summary=summary)
    if a.out:
        Path(a.out).write_text(text)
        print(f"written {a.out}")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
