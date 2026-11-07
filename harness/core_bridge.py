"""Host side of the core bridge: serves `ballistic-core` over JSON lines.

Core asks, this file answers, and nothing here decides anything (core/README.md §15, rule 2).
Four kinds of ask:
  observe   -> ScreenState built from AndroidWorld's accessibility forest (parents kept)
  act       -> one HandsOp executed through AndroidWorld's adb helpers, in screen coordinates
  complete  -> one chat completion at the OpenAI-compatible endpoint configured for the role
  confirm   -> approve or deny from a fixed policy (benchmark: approve; injection tests: deny)
plus `log`, which this file appends to the run record. Every line in both directions is kept in
`CoreHost.lines` so a run can be replayed later with no emulator.

Protocol: core/src/bridge.rs.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import time
from typing import Any, Callable, Optional

try:
    from android_world.env import adb_utils
except ImportError:  # replay_core.py uses start_core/default_core_bin only, with no AndroidWorld
    adb_utils = None

# AndroidWorld's name->activity table, reused as our installed-app labels (first alternative of
# each regex key is the label shown to the planner).
_PATTERN_TO_ACTIVITY = adb_utils._PATTERN_TO_ACTIVITY if adb_utils else {}  # pylint: disable=protected-access

_ROLE_BY_CLASS = [
    (re.compile(r"\.(Button|ImageButton|FloatingActionButton|MaterialButton|Chip)$"), "button"),
    (re.compile(r"\.(EditText|AutoCompleteTextView|TextInputEditText|SearchView)$"), "edit"),
    (re.compile(r"\.(Switch|SwitchCompat|ToggleButton|SwitchMaterial)$"), "switch"),
    (re.compile(r"\.(CheckBox|MaterialCheckBox|RadioButton|CheckedTextView)$"), "checkbox"),
    (re.compile(r"\.(SeekBar|Slider|RangeSlider|ProgressBar)$"), "slider"),
    (re.compile(r"\.(ImageView|AppCompatImageView)$"), "image"),
    (re.compile(r"\.(RecyclerView|ListView|ScrollView|HorizontalScrollView|NestedScrollView|ViewPager2?|GridView)$"), "list"),
    (re.compile(r"\.(TextView|MaterialTextView|AppCompatTextView)$"), "text"),
    (re.compile(r"Tab(Layout|View)?$"), "tab"),
    (re.compile(r"\.(FrameLayout|LinearLayout|RelativeLayout|ConstraintLayout|ViewGroup|CoordinatorLayout|CardView|MaterialCardView|ComposeView|View)$"), "container"),
]


def _role(class_name: Optional[str], clickable: bool, parent_is_list: bool) -> str:
    cn = class_name or ""
    for rx, role in _ROLE_BY_CLASS:
        if rx.search(cn):
            if role == "container" and clickable and parent_is_list:
                return "item"
            if role == "container" and clickable:
                return "button"
            return role
    return "other"


def _none_if_empty(s: Optional[str]) -> Optional[str]:
    return s if s else None


def forest_to_nodes(forest: Any) -> list[dict[str, Any]]:
    """Flatten the a11y forest into our Node dicts, keeping parent ids and reading order."""
    return _windows_to_nodes(forest.windows)


def _windows_to_nodes(windows: Any) -> list[dict[str, Any]]:
    nodes: list[dict[str, Any]] = []
    for window in windows:
        by_uid: dict[int, Any] = {n.unique_id: n for n in window.tree.nodes}
        parent_of: dict[int, int] = {}
        for n in window.tree.nodes:
            for c in n.child_ids:
                parent_of[c] = n.unique_id
        # Our ids are per-observation and must be unique across windows: offset by the running count.
        id_map: dict[int, int] = {}
        roots = [n for n in window.tree.nodes if n.unique_id not in parent_of]
        order: list[Any] = []
        stack = list(reversed(roots))
        while stack:  # pre-order = reading order as the tree stores it
            n = stack.pop()
            order.append(n)
            stack.extend(reversed([by_uid[c] for c in n.child_ids if c in by_uid]))
        for n in order:
            id_map[n.unique_id] = len(nodes) + 1
            b = n.bounds_in_screen
            parent_uid = parent_of.get(n.unique_id)
            parent_node = by_uid.get(parent_uid) if parent_uid is not None else None
            parent_is_list = bool(parent_node is not None and parent_node.is_scrollable)
            label = _none_if_empty(n.content_description) or _none_if_empty(n.hint_text) or _none_if_empty(n.tooltip_text)
            nodes.append({
                "id": id_map[n.unique_id],
                "parent": id_map.get(parent_uid) if parent_uid is not None else None,
                "role": _role(n.class_name, n.is_clickable, parent_is_list),
                "class_name": _none_if_empty(n.class_name),
                "label": label,
                "text": _none_if_empty(n.text),
                "resource_id": _none_if_empty(n.view_id_resource_name),
                "package": _none_if_empty(n.package_name),
                "bounds": {"x1": b.left, "y1": b.top, "x2": b.right, "y2": b.bottom},
                "flags": {
                    "clickable": n.is_clickable, "long_clickable": n.is_long_clickable,
                    "editable": n.is_editable, "scrollable": n.is_scrollable,
                    "checkable": n.is_checkable, "checked": n.is_checked,
                    "selected": n.is_selected, "enabled": n.is_enabled,
                    "focused": n.is_focused, "visible": n.is_visible_to_user,
                },
            })
    return nodes


def installed_apps(controller: Any) -> list[dict[str, Any]]:
    """Label + package (+ activity) for every installed package AndroidWorld knows a launcher for;
    other packages are listed under their package name so `open_app` can still reach them."""
    by_package: dict[str, tuple[str, str]] = {}
    for pattern, activity in _PATTERN_TO_ACTIVITY.items():
        pkg = activity.split("/")[0]
        by_package.setdefault(pkg, (pattern.split("|")[0], activity))
    apps = []
    for pkg in sorted(set(adb_utils.get_all_package_names(controller))):
        if pkg in by_package:
            label, activity = by_package[pkg]
            apps.append({"label": label, "package": pkg, "activity": activity})
    return apps


def foreground_tree_ready(nodes: list[dict[str, Any]], package: str) -> bool:
    """Do not mistake status-bar/keyboard nodes for a loaded foreground app."""
    return bool(package) and any(
        n.get("package") == package
        and n["flags"].get("visible")
        and n["bounds"]["x2"] > n["bounds"]["x1"]
        and n["bounds"]["y2"] > n["bounds"]["y1"]
        and (n.get("label") or n.get("text") or n["flags"].get("clickable")
             or n["flags"].get("editable"))
        for n in nodes
    )


def _foreground_signature(forest: Any, package: str) -> Optional[str]:
    """Fingerprint meaningful foreground windows, excluding background chrome/IME.

    AndroidEnv's forest has no capture time or generation. Window/node IDs are
    identities, not freshness markers. Focus/active flags only describe the
    supplied forest; they do not establish that it was captured after an action.
    """
    if forest is None:
        return None
    windows = [w for w in forest.windows
               if w.window_type in (1, 3)  # TYPE_APPLICATION, TYPE_SYSTEM
               and (w.is_active or w.is_focused)]
    nodes = _windows_to_nodes(windows)
    if not foreground_tree_ready(nodes, package):
        return None
    # Restrict to this package: clock/keyboard updates must not make an old app
    # tree look new. Normalize per-observation IDs after removing other nodes.
    nodes = [n for n in nodes if n.get("package") == package]
    ids = {n["id"]: i for i, n in enumerate(nodes)}
    canonical = [{**n, "id": ids[n["id"]], "parent": ids.get(n["parent"])} for n in nodes]
    return hashlib.sha256(json.dumps(canonical, sort_keys=True).encode()).hexdigest()


class ObservationError(RuntimeError):
    """The host could not obtain a sufficiently consistent, ready observation."""


class CoreHost:
    """Answers core's asks. `confirm_policy` is "approve" or "deny"."""

    def __init__(self, env: Any, models: "ModelClient", confirm_policy: str = "approve",
                 on_event: Optional[Callable[[dict[str, Any]], None]] = None):
        self.env = env
        self.models = models
        self.confirm_policy = confirm_policy
        self.on_event = on_event
        self.lines: list[dict[str, Any]] = []      # every line, both directions, in order
        self.events: list[dict[str, Any]] = []     # core's log events
        self.confirmations: list[dict[str, Any]] = []
        self._apps: Optional[list[dict[str, Any]]] = None
        self._last_observation: Optional[tuple[str, str]] = None

    # ---- observe -------------------------------------------------------------------------
    def observe(self) -> dict[str, Any]:
        """Bounded consistency checks, not a guarantee of post-action freshness.

        AndroidWorld's stable-state check compares ui_elements and can return an
        unchanged old forest (or a changing state at its timeout). Require two
        consecutive ready foreground samples as well. Reject unchanged app trees
        across observed activity transitions, even when they look stable. This
        deliberately may reject legitimate identical screens in distinct activities.
        Same-activity stale trees, and transitions wholly between activity reads,
        remain undetectable without a source capture generation. Never mutate the
        environment's private state or use benchmark state as a readiness oracle.

        Six acquisitions and a 15-second soft deadline bound our retries; a
        blocking environment/ADB call still depends on its own transport timeout.
        """
        deadline = time.monotonic() + 15.0
        previous_candidate = None
        baselines = [self._last_observation] if self._last_observation else []
        reason = "no_ready_foreground"
        for attempt in range(6):
            if time.monotonic() >= deadline:
                break
            before = self.env.foreground_activity_name or ""
            state = self.env.get_state(wait_to_stabilize=True)
            w, h = self.env.logical_screen_size
            foreground = self.env.foreground_activity_name or ""
            package, _, activity = foreground.partition("/")
            signature = _foreground_signature(state.forest, package)
            if time.monotonic() >= deadline:
                reason = "acquisition_deadline"
                break
            if before != foreground:
                # Even on the first observation, remember a tree obtained across
                # a transition. Its association is uncertain: retry until changed.
                prior_signature = _foreground_signature(state.forest, before.partition("/")[0])
                if prior_signature is not None:
                    baselines.append((before, prior_signature))
                reason = "activity_changed_during_observation"
            elif not package or not activity:
                reason = "missing_foreground_activity"
            elif signature is None:
                reason = "no_ready_foreground"
            elif any(old_activity != foreground and old_signature == signature
                     for old_activity, old_signature in baselines):
                reason = "unchanged_tree_after_activity_change"
            else:
                candidate = (foreground, signature, w, h)
                if candidate == previous_candidate:
                    nodes = forest_to_nodes(state.forest)
                    break
                previous_candidate = candidate
                # Also catch transitions between acquisitions before there is
                # any accepted observation (e.g. at startup).
                baselines.append((foreground, signature))
                reason = "foreground_not_settled"
                # get_state(True) already polls with a settling interval. No
                # unconditional post-action sleep or delay on a ready sample.
                continue
            previous_candidate = None
            remaining = deadline - time.monotonic()
            if attempt < 5 and remaining > 0:
                time.sleep(min(0.7 * (attempt + 1), remaining))
        else:
            raise ObservationError(f"environment observation failed: {reason}")
        if time.monotonic() >= deadline:
            raise ObservationError(f"environment observation failed: {reason}")
        if self._apps is None:
            self._apps = installed_apps(self.env.controller)
        # The emulator harness is explicitly granted the synthetic test environment.
        # A phone host must build this list from actual owner grants and API access.
        capabilities = {"operations": ["open_app", "tap", "long_press", "type", "scroll",
                                       "swipe", "back", "home", "wait"],
                        "app_grants": sorted({a["package"] for a in self._apps} | {package})}
        self._last_observation = (foreground, signature)
        return {"package": package, "activity": activity, "width": w, "height": h,
                "nodes": nodes, "installed_apps": self._apps, "capabilities": capabilities}

    # ---- act -----------------------------------------------------------------------------
    def act(self, op: dict[str, Any]) -> dict[str, Any]:
        c = self.env.controller
        kind = op["op"]
        try:
            if kind == "tap":
                adb_utils.tap_screen(op["x"], op["y"], c)
            elif kind == "long_press":
                adb_utils.long_press(op["x"], op["y"], c)
            elif kind == "type":
                adb_utils.tap_screen(op["x"], op["y"], c)
                time.sleep(1.0)
                adb_utils.type_text(op["text"], c, timeout_sec=10)
                if op.get("submit"):
                    adb_utils.press_enter_button(c)
            elif kind in ("scroll", "swipe"):
                a = op["area"]
                cx, cy = (a["x1"] + a["x2"]) // 2, (a["y1"] + a["y2"]) // 2
                d = op["direction"]
                # scroll: finger moves against the direction so content in `direction` comes into view
                # swipe: finger moves in `direction`
                if kind == "scroll":
                    d = {"up": "down", "down": "up", "left": "right", "right": "left"}[d]
                end = {"up": (cx, a["y1"]), "down": (cx, a["y2"]), "left": (a["x1"], cy), "right": (a["x2"], cy)}[d]
                cmd = adb_utils.generate_swipe_command(cx, cy, end[0], end[1], 400 if kind == "swipe" else None)
                adb_utils.issue_generic_request([x for x in cmd if x != ""], c)
            elif kind == "open_app":
                if op.get("activity"):
                    adb_utils.start_activity(op["activity"], [], c, timeout_sec=5)
                else:
                    adb_utils.issue_generic_request(["shell", "monkey", "-p", op["package"], "1"], c, timeout_sec=5)
            elif kind == "back":
                adb_utils.press_back_button(c)
            elif kind == "home":
                adb_utils.press_home_button(c)
            elif kind == "wait":
                time.sleep(op.get("millis", 1000) / 1000)
            else:
                return {"result": "not_executed", "message": f"unknown op {kind}"}
            return {"result": "ok"}
        except Exception as e:  # noqa: BLE001  report, don't decide
            return {"result": "error", "message": f"{type(e).__name__}: {e}"}

    # ---- confirm -------------------------------------------------------------------------
    def confirm(self, request: dict[str, Any]) -> str:
        verdict = "approved" if self.confirm_policy == "approve" else "denied"
        self.confirmations.append({"request": request, "verdict": verdict})
        return verdict

    # ---- serve ---------------------------------------------------------------------------
    def serve(self, proc: subprocess.Popen) -> dict[str, Any]:
        """Answer core until it writes its `finished` event or exits. Returns that event."""
        assert proc.stdout is not None and proc.stdin is not None
        finished: dict[str, Any] = {"finished": True, "error": "core exited without a finished event"}
        for raw in proc.stdout:
            line = raw.decode("utf-8", "replace").strip()
            if not line:
                continue
            try:
                ask = json.loads(line)
            except json.JSONDecodeError:
                self.lines.append({"dir": "core_stdout", "text": line})
                continue
            self.lines.append({"dir": "ask", **ask})
            if ask.get("ask") == "log":
                ev = ask.get("event") or {}
                self.events.append(ev)
                if self.on_event:
                    self.on_event(ev)
                if ev.get("finished"):
                    finished = ev
                    break
                continue
            reply = self._answer(ask)
            self.lines.append({"dir": "reply", **reply})
            proc.stdin.write((json.dumps(reply) + "\n").encode())
            proc.stdin.flush()
        return finished

    def _answer(self, ask: dict[str, Any]) -> dict[str, Any]:
        kind = ask.get("ask")
        try:
            if kind == "observe":
                data: Any = self.observe()
            elif kind == "act":
                data = self.act(ask["op"])
            elif kind == "complete":
                data = self.models.complete(ask["role"], ask["messages"], ask.get("max_tokens", 800))
            elif kind == "confirm":
                data = self.confirm(ask["request"])
            else:
                return {"id": ask.get("id", 0), "ok": False, "error": f"unknown ask {kind}"}
            return {"id": ask["id"], "ok": True, "data": data}
        except Exception as e:  # noqa: BLE001
            if kind == "observe":
                # Environment exceptions can contain device text or raw responses.
                # Keep observation failures distinguishable without logging them.
                detail = str(e) if isinstance(e, ObservationError) else f"environment observation failed: {type(e).__name__}"
                return {"id": ask.get("id", 0), "ok": False, "error": f"ObservationError: {detail}"}
            return {"id": ask.get("id", 0), "ok": False, "error": f"{type(e).__name__}: {e}"}


class ModelClient:
    """One OpenAI-compatible endpoint per role. `routes` maps role -> (base_url, model, api_key)."""

    def __init__(self, routes: dict[str, tuple[str, str, str]], temperature: float = 0.0, timeout: int = 120):
        self.routes = routes
        self.temperature = temperature
        self.timeout = timeout
        self.calls: list[dict[str, Any]] = []

    def complete(self, role: str, messages: list[dict[str, str]], max_tokens: int) -> dict[str, Any]:
        import requests  # here, so replay_core.py runs without it

        base_url, model, api_key = self.routes.get(role) or self.routes["planner"]
        body = {"model": model, "messages": messages, "temperature": self.temperature, "max_tokens": max_tokens}
        t0 = time.time()
        r = None
        try:
            r = requests.post(base_url.rstrip("/") + "/chat/completions", json=body, timeout=self.timeout,
                              headers={"Authorization": f"Bearer {api_key}"})
            r.raise_for_status()
            j = r.json()
            text = j["choices"][0]["message"]["content"] or ""
            usage = j.get("usage") or {}
            out = {"text": text, "usage": {"prompt_tokens": usage.get("prompt_tokens", 0),
                                           "completion_tokens": usage.get("completion_tokens", 0)}}
            self.calls.append({"role": role, "model": model, "seconds": round(time.time() - t0, 2), **out["usage"]})
            return out
        except Exception as exc:
            # Store transport category/status without headers, key or response content.
            self.calls.append({"role": role, "model": model, "seconds": round(time.time() - t0, 2),
                               "error_type": type(exc).__name__,
                               "http_status": r.status_code if r is not None else None})
            raise



def default_core_bin() -> str:
    """core/target/{release,debug}/ballistic-core, honouring CARGO_TARGET_DIR and cargo's
    config.toml `build.target-dir`; otherwise whatever `ballistic-core` is on PATH."""
    here = os.path.dirname(os.path.abspath(__file__))
    core_dir = os.path.normpath(os.path.join(here, "..", "core"))
    target_dirs = [os.path.join(core_dir, "target")]
    if os.environ.get("CARGO_TARGET_DIR"):
        target_dirs.insert(0, os.environ["CARGO_TARGET_DIR"])
    for cfg in (os.path.join(core_dir, ".cargo", "config.toml"), os.path.expanduser("~/.cargo/config.toml")):
        try:
            with open(cfg) as f:
                m = re.search(r'^\s*target-dir\s*=\s*"([^"]+)"', f.read(), re.M)
            if m:
                target_dirs.insert(0, os.path.expanduser(m.group(1)))
        except OSError:
            pass
    for td in target_dirs:
        for profile in ("release", "debug"):
            p = os.path.join(td, profile, "ballistic-core")
            if os.path.exists(p):
                return p
    return "ballistic-core"


def start_core(core_bin: str, args: list[str]) -> subprocess.Popen:
    """Core gets its own session, so Ctrl+C reaches only the harness, which then kills core; and
    no API key in its environment, since model calls are made here, not in core."""
    env = {k: v for k, v in os.environ.items() if k != "BALLISTIC_API_KEY"}
    return subprocess.Popen([core_bin, *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, bufsize=0, env=env, start_new_session=True)
