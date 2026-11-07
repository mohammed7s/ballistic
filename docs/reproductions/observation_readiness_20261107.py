"""Offline diagnosis, not a runtime change or a task-success test.

Run from any directory with Python's standard library. --strict-contract asserts
the proposed control-stability invariant and intentionally exits 1 on the reviewed
host. --verify-pair PATH checks frozen extracts against the original local bundle.
No upstream environment is imported; no actions, model calls or real sleeps occur.
"""
from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import sys
import tarfile
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = Path(__file__).with_name("observation_readiness_20261107.json")
sys.path.insert(0, str(ROOT / "harness"))
with patch.dict(sys.modules, {"android_world": None, "android_world.env": None}):
    import core_bridge as bridge
    from test_core_bridge import FakeEnv, forest, sample, window


def sha(data):
    return hashlib.sha256(data).hexdigest()


def extract(record):
    """Only the accepted normalized observation and next planner screen, not a proto."""
    for i, line in enumerate(record["lines"]):
        data = line.get("data", {})
        if line.get("dir") == "reply" and data.get("package") == "com.android.camera2":
            prompt = record["lines"][i + 1]
            assert prompt["ask"] == "complete" and prompt["role"] == "planner"
            return {
                "reply_line_index": i, "reply_id": line["id"],
                "screen": {k: data[k] for k in
                           ("package", "activity", "width", "height", "foreground", "nodes")},
                "planner_screen": prompt["messages"][-1]["content"].split("\nScreen ", 1)[1],
            }
    raise AssertionError("missing camera reply")


def verify_pair(directory, frozen):
    for arm in ("A", "B"):
        spec = frozen["arms"][arm]
        data = (directory / spec["record"]).read_bytes()
        assert sha(data) == spec["record_sha256"]
        assert extract(json.loads(data)) == spec["extract"]
        with tarfile.open(directory / arm / "tracked_source.tar") as archive:
            for name, expected in frozen["source_sha256"].items():
                assert sha(archive.extractfile(name).read()) == expected, (arm, name)
    print("Original A/B record hashes, extracts and archived host/runner sources: verified")


def reconstructed_forest(nodes):
    """Synthetic proto-shaped forest; original window metadata was NOT recorded."""
    raw = []
    for node in nodes:
        b, f = node["bounds"], node["flags"]
        raw.append(SimpleNamespace(
            unique_id=node["id"],
            child_ids=[n["id"] for n in nodes if n["parent"] == node["id"]],
            package_name=node["package"], class_name=node["class_name"] or "",
            content_description=node["label"] or "", hint_text="", tooltip_text="",
            text=node["text"] or "", view_id_resource_name=node["resource_id"] or "",
            bounds_in_screen=SimpleNamespace(left=b["x1"], top=b["y1"],
                                            right=b["x2"], bottom=b["y2"]),
            **{"is_" + k: v for k, v in f.items() if k != "visible"},
            is_visible_to_user=f["visible"],
        ))
    return forest(SimpleNamespace(window_type=1, is_active=True, is_focused=True,
                                  tree=SimpleNamespace(nodes=raw)))


def observe(trees, activity="app/A"):
    env = FakeEnv([sample(t, activity) for t in trees])
    host = bridge.CoreHost(env, None)
    host._apps = []  # Suppress installed-app discovery; no real controller exists.
    with patch.object(bridge.time, "sleep"), patch.object(bridge.time, "monotonic", return_value=0):
        result = host.observe()
    return result, env.state_reads


def generic_tree(count=10):
    # One actionable button plus inert texts, all in one foreground window.
    w = window(text="Continue", clickable=True)
    button = w.tree.nodes[0]
    button.class_name = "android.widget.Button"
    button.view_id_resource_name = "app:id/continue"
    w.tree.nodes += [window(text=f"Static {i}", uid=i).tree.nodes[0]
                     for i in range(2, count + 1)]
    return forest(w)


def run(frozen, strict):
    a, b = [frozen["arms"][arm]["extract"]["screen"] for arm in ("A", "B")]
    assert [len(s["nodes"]) for s in (a, b)] == [8, 9]
    # Compare all surviving node fields after mapping each parent to its list position.
    def without_shutter(nodes):
        nodes = [n for n in nodes if n["resource_id"] != "com.android.camera2:id/shutter_button"]
        ids = {n["id"]: i for i, n in enumerate(nodes)}
        return [{**n, "id": ids[n["id"]], "parent": ids.get(n["parent"])} for n in nodes]
    assert without_shutter(a["nodes"]) == without_shutter(b["nodes"])
    assert all(bridge.foreground_tree_ready(s["nodes"], s["package"]) for s in (a, b))
    assert not bridge.slightly_different(a["nodes"], b["nodes"])
    for s in (a, b):
        assert bridge.forest_to_nodes(reconstructed_forest(s["nodes"])) == s["nodes"]
    print("Frozen pair: A 8 nodes; B same 8 plus Shutter; both ready; 8->9 is NOT slight")

    fa, fb = [reconstructed_forest(s["nodes"]) for s in (a, b)]
    activity = a["package"] + "/" + a["activity"]
    result, reads = observe([fa, fa, fb], activity)
    assert reads == 2 and result["nodes"] == a["nodes"]
    print("Synthetic A,A,B: returns A after 2 samples; later B is unobserved (inherent limit)")
    result, reads = observe([fa, fb, fb], activity)
    assert reads == 3 and result["nodes"] == b["nodes"]
    print("Synthetic A,B,B: rejects count transition and returns B after 3 samples")
    hidden = deepcopy(fb)
    shutter = hidden.windows[0].tree.nodes[4]
    shutter.is_enabled = shutter.is_visible_to_user = False
    result, reads = observe([hidden, hidden], activity)
    assert len(result["nodes"]) == 9
    assert not result["nodes"][4]["flags"]["visible"]
    assert not result["nodes"][4]["flags"]["enabled"]
    print("Synthetic disabled+invisible Shutter: normalizer retains node and both flags")

    old = generic_tree()
    variants = {}
    for flag in ("is_enabled", "is_visible_to_user", "is_clickable"):
        new = deepcopy(old)
        setattr(new.windows[0].tree.nodes[0], flag, False)
        variants[flag] = new
    new = deepcopy(old)
    new.windows[0].tree.nodes[0].text = "Delete"
    variants["button_text"] = new
    new = deepcopy(old)
    new.windows[0].tree.nodes[0].content_description = "Delete"
    variants["button_label"] = new
    new = deepcopy(old)
    new.windows[0].tree.nodes[0].bounds_in_screen.right = 300
    variants["button_bounds"] = new

    failures = []
    for name, new in variants.items():
        # A subsequent reversal would be material too, but current observe never samples it.
        result, reads = observe([old, new, old, old])
        changed_control = bridge.forest_to_nodes(new)[0]
        if reads == 2:
            assert result["nodes"][0] == changed_control
            failures.append(name)
        else:
            assert reads == 4 and result["nodes"] == bridge.forest_to_nodes(old)
        print(f"Generic {name}: accepted after {reads} acquisitions; "
              f"returned second sample={reads == 2}")
    # Baseline characterization, not an assertion that permissive behavior is desirable.
    if not strict:
        assert len(failures) == len(variants)

    # Negative controls: smaller tree, skeleton replacement, added node all require a third sample.
    small = generic_tree(9)
    small_changed = deepcopy(small)
    small_changed.windows[0].tree.nodes[0].is_enabled = False
    replaced = deepcopy(old)
    replaced.windows[0].tree.nodes[0].view_id_resource_name = "app:id/other"
    for before, after in ((small, small_changed), (old, replaced), (old, generic_tree(11))):
        _, reads = observe([before, after, after])
        assert reads == 3
    print("Negative controls: 9-node flag change, resource replacement, added node need 3 samples")
    tick = deepcopy(old)
    tick.windows[0].tree.nodes[-1].text = "00:02"
    old_tick = deepcopy(old)
    old_tick.windows[0].tree.nodes[-1].text = "00:01"
    _, reads = observe([old_tick, tick])
    assert reads == 2
    print("Positive control: one nonactionable ticking text settles after 2 samples")
    if strict and failures:
        print("FAIL proposed invariant: changing actionable control must reset settling: "
              + ", ".join(failures))
        return 1
    return 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-pair", type=Path)
    parser.add_argument("--strict-contract", action="store_true")
    args = parser.parse_args()
    frozen = json.loads(FIXTURE.read_text())
    print("Current host SHA-256:", sha((ROOT / "harness/core_bridge.py").read_bytes()))
    if args.verify_pair:
        verify_pair(args.verify_pair, frozen)
    sys.exit(run(frozen, args.strict_contract))
