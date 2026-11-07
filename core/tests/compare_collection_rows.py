#!/usr/bin/env python3
"""Offline filter comparison; output contains counts/locations, never recorded display strings.

Usage: python3 core/tests/compare_collection_rows.py BASELINE_BIN CANDIDATE_BIN RECORDS_DIR
Pass a directory of run JSONs or screen fixtures. No model, device, checker or harness import.
"""
import argparse
import collections
import json
from pathlib import Path
import subprocess


def filtered(binary, screen, request):
    return subprocess.run(
        [binary, "filter", "--request", request], input=json.dumps(screen),
        text=True, capture_output=True, check=True,
    ).stdout.splitlines()


def labels(lines):
    result = {}
    for line in lines:
        element, _, rest = line.split(" ", 2) if line.count(" ") >= 2 else ("", "", "")
        if rest.startswith('[kind:'):
            rest = rest.split('] ', 1)[-1]
        if rest.startswith('"'):
            value, end = json.JSONDecoder().raw_decode(rest)
            if not rest[end:].startswith((" (matches request)", " (typed)")):
                result[element] = value
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline")
    parser.add_argument("candidate")
    parser.add_argument("records", type=Path)
    parser.add_argument("--recursive", action="store_true", help="include nested fixture directories")
    args = parser.parse_args()
    binaries = [str(Path(p).resolve()) for p in [args.baseline, args.candidate]]
    report = []
    paths = args.records.rglob("*.json") if args.recursive else args.records.glob("*.json")
    for path in sorted(paths):
        data = json.loads(path.read_text())
        if not isinstance(data, dict):
            continue
        if "screen" in data:
            screens = [("screen", data["screen"])]
        else:
            screens = [(f"lines[{i}].data", line["data"])
                       for i, line in enumerate(data.get("lines", []))
                       if isinstance(line.get("data"), dict)
                       and "nodes" in line["data"] and "width" in line["data"]]
        if not screens:
            continue
        counts = collections.Counter()
        changes = []
        for location, screen in screens:
            for mode, request in [("inspect", "Inspect the screen"), ("owner", data.get("goal", ""))]:
                before, after = [filtered(binary, screen, request) for binary in binaries]
                counts[f"{mode}_screens"] += 1
                if before == after:
                    continue
                counts[f"{mode}_changed"] += 1
                same_elements = [s.split(" ", 2)[:2] for s in before] == [s.split(" ", 2)[:2] for s in after]
                old, new = labels(before), labels(after)
                lost = sum(e not in new for e in old) if same_elements else None
                gained = sum(e not in old for e in new) if same_elements else None
                renamed = sum(old[e] != new[e] for e in old.keys() & new.keys()) if same_elements else None
                changes.append(dict(location=location, mode=mode, same_elements=same_elements,
                                    labels_lost=lost, labels_gained=gained, labels_changed=renamed))
        report.append(dict(file=str(path.relative_to(args.records)), counts=dict(counts), changes=changes))
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
