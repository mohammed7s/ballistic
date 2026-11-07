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


def _foreground_nodes(forest: Any, package: str) -> Optional[list[dict[str, Any]]]:
    """Meaningful foreground windows' nodes for `package`, excluding background chrome/IME.

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
    return [{**n, "id": ids[n["id"]], "parent": ids.get(n["parent"])} for n in nodes]


def _signature(nodes: Optional[list[dict[str, Any]]]) -> Optional[str]:
    if nodes is None:
        return None
    return hashlib.sha256(json.dumps(nodes, sort_keys=True).encode()).hexdigest()


def _foreground_signature(forest: Any, package: str) -> Optional[str]:
    """Fingerprint of `_foreground_nodes`."""
    return _signature(_foreground_nodes(forest, package))


_SKELETON_KEYS = ("parent", "role", "class_name", "resource_id", "package")


def slightly_different(old: list[dict[str, Any]], new: list[dict[str, Any]]) -> bool:
    """Same skeleton (count, parents, roles, classes, resource ids) and at most a tenth of the
    nodes changed in text, label, bounds or flags: an animation or a ticking value, not another
    screen. A one-node tree tolerates no change."""
    if len(old) != len(new) or not old:
        return False
    if any(tuple(a.get(k) for k in _SKELETON_KEYS) != tuple(b.get(k) for k in _SKELETON_KEYS)
           for a, b in zip(old, new)):
        return False
    return sum(a != b for a, b in zip(old, new)) <= len(old) // 10


SOFT_DEADLINE_SECONDS = 30.0


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

        AndroidWorld's stable-state check compares all ui_elements and can return
        an unchanged old forest, or spend its whole timeout on changing chrome.
        Use raw acquisitions with our own foreground-only settling check. Require two
        consecutive ready foreground samples. Reject unchanged app trees
        across observed activity transitions, even when they look stable. This
        deliberately may reject legitimate identical screens in distinct activities.
        Same-activity stale trees, and transitions wholly between activity reads,
        remain undetectable without a source capture generation. Never mutate the
        environment's private state or use benchmark state as a readiness oracle.

        Two consecutive samples with the same foreground and size settle the
        screen when their trees match, or when they differ only slightly (same
        skeleton, at most a tenth of the nodes changed: an animation or ticking
        value); the later sample is returned. Unsettled samples are spaced 0.3 s,
        then doubling. Six acquisitions and a 30-second soft deadline bound our
        retries; a blocking environment/ADB call still depends on its own
        transport timeout.
        """
        deadline = time.monotonic() + SOFT_DEADLINE_SECONDS
        previous_candidate = None
        previous_nodes: Optional[list[dict[str, Any]]] = None
        unsettled = 0
        baselines = [self._last_observation] if self._last_observation else []
        reason = "no_ready_foreground"
        for attempt in range(6):
            if time.monotonic() >= deadline:
                break
            before = self.env.foreground_activity_name or ""
            state = self.env.get_state(wait_to_stabilize=False)
            w, h = self.env.logical_screen_size
            foreground = self.env.foreground_activity_name or ""
            package, _, activity = foreground.partition("/")
            foreground_nodes = _foreground_nodes(state.forest, package)
            signature = _signature(foreground_nodes)
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
                if candidate == previous_candidate or (
                        previous_candidate is not None and previous_candidate[0] == foreground
                        and previous_candidate[2:] == (w, h) and previous_nodes is not None
                        and slightly_different(previous_nodes, foreground_nodes)):
                    nodes = forest_to_nodes(state.forest)
                    break
                previous_candidate = candidate
                previous_nodes = foreground_nodes
                # Also catch transitions between acquisitions before there is
                # any accepted observation (e.g. at startup).
                baselines.append((foreground, signature))
                reason = "foreground_not_settled"
                # Space stability samples without nesting AndroidWorld's separate
                # six-second whole-screen wait inside our own retry deadline.
                # This is a settling heuristic, not proof of capture freshness.
                remaining = deadline - time.monotonic()
                if attempt < 5 and remaining > 0:
                    time.sleep(min(0.3 * 2 ** unsettled, remaining))
                unsettled += 1
                continue
            previous_candidate = None
            previous_nodes = None
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


class ModelResponseError(RuntimeError):
    """A malformed model response, described by a code-owned reason only."""


class ModelClient:
    """One OpenAI-compatible endpoint per role.

    Only transient transport/502/503/504 failures are retried. Telemetry records
    response shape, allowlisted finish reasons and quota categories, never raw
    error bodies (which can echo request content or credentials).
    """

    RETRY_STATUSES = (502, 503, 504)
    FINISH_REASONS = {"stop", "length", "tool_calls", "function_call", "content_filter"}

    def __init__(self, routes: dict[str, tuple[str, str, str]], temperature: float = 0.0, timeout: int = 120,
                 retries: int = 3, backoff_seconds: float = 2.0):
        self.routes = routes
        self.temperature = temperature
        self.timeout = timeout
        self.retries = retries
        self.backoff_seconds = backoff_seconds
        self.calls: list[dict[str, Any]] = []

    @staticmethod
    def _quota_metadata(r: Any) -> dict[str, Any]:
        if r is None or r.status_code != 429:
            return {}
        # Inspect the full response: Google's useful quota metric can occur well
        # beyond the old 300-character excerpt. Emit only fixed categories; quota
        # ids, dimensions, messages, keys and URLs never enter telemetry.
        try:
            body = (r.text or "").lower()
        except Exception:  # noqa: BLE001
            body = ""
        daily = bool(re.search(r"per[ _-]?day|daily|requests/day|tokens/day", body))
        rate = bool(re.search(r"per[ _-]?(?:minute|second)|requests/minute|tokens/minute", body))
        category = "mixed" if daily and rate else "daily" if daily else "rate" if rate else "unknown"
        return {"quota_category": category}

    @classmethod
    def _response_metadata(cls, body: Any) -> dict[str, Any]:
        """Safe diagnostics even when choices/message/content has the wrong shape."""
        if not isinstance(body, dict):
            return {"response_shape": "not_object"}
        choices = body.get("choices")
        if not isinstance(choices, list):
            return {"response_shape": "missing_or_invalid_choices"}
        metadata: dict[str, Any] = {"choice_count": len(choices)}
        if not choices:
            return {**metadata, "response_shape": "empty_choices"}
        choice = choices[0]
        if not isinstance(choice, dict):
            return {**metadata, "response_shape": "invalid_choice"}
        finish = choice.get("finish_reason")
        metadata["finish_reason"] = finish if isinstance(finish, str) and finish in cls.FINISH_REASONS else (
            "missing" if finish is None else "other")
        message = choice.get("message")
        if not isinstance(message, dict):
            return {**metadata, "response_shape": "missing_or_invalid_message"}
        if "content" not in message:
            shape = "missing_content"
        elif message["content"] is None:
            shape = "null_content"
        elif not isinstance(message["content"], str):
            shape = "non_string_content"
        elif not message["content"].strip():
            shape = "empty_content"
        else:
            shape = "text_content"
        return {**metadata, "response_shape": shape}

    @staticmethod
    def _usage(body: Any) -> dict[str, int]:
        usage = body.get("usage") if isinstance(body, dict) else None
        if not isinstance(usage, dict):
            return {}
        return {name: value for name in ("prompt_tokens", "completion_tokens")
                if type(value := usage.get(name)) is int and value >= 0}

    def complete(self, role: str, messages: list[dict[str, str]], max_tokens: int) -> dict[str, Any]:
        import requests  # here, so replay_core.py runs without it

        base_url, model, api_key = self.routes.get(role) or self.routes["planner"]
        body = {"model": model, "messages": messages, "temperature": self.temperature, "max_tokens": max_tokens}
        t0 = time.time()
        retried: list[dict[str, Any]] = []
        for attempt in range(self.retries + 1):
            r = None
            diagnostics: dict[str, Any] = {}
            try:
                r = requests.post(base_url.rstrip("/") + "/chat/completions", json=body, timeout=self.timeout,
                                  headers={"Authorization": f"Bearer {api_key}"})
                if r.status_code in self.RETRY_STATUSES and attempt < self.retries:
                    retried.append({"http_status": r.status_code})
                    time.sleep(self.backoff_seconds * 2 ** attempt)
                    continue
                r.raise_for_status()
                try:
                    j = r.json()
                except ValueError:
                    diagnostics["response_shape"] = "invalid_json"
                    raise ModelResponseError("invalid_json") from None
                diagnostics = {**self._response_metadata(j), **self._usage(j)}
                if diagnostics["response_shape"] != "text_content":
                    raise ModelResponseError(diagnostics["response_shape"])
                usage = self._usage(j)
                # The bridge's Usage struct requires both counters. Unknown usage stays
                # absent from call telemetry; zero defaults here are protocol compatibility.
                out = {"text": j["choices"][0]["message"]["content"], "usage": {
                    "prompt_tokens": usage.get("prompt_tokens", 0),
                    "completion_tokens": usage.get("completion_tokens", 0)}}
                entry = {"role": role, "model": model, "seconds": round(time.time() - t0, 2),
                         "http_status": r.status_code, **diagnostics}
                if retried:
                    entry["retried"] = retried
                self.calls.append(entry)
                return out
            except requests.exceptions.ConnectionError as exc:
                if attempt < self.retries:
                    retried.append({"error_type": type(exc).__name__})
                    time.sleep(self.backoff_seconds * 2 ** attempt)
                    continue
                self._failed(role, model, t0, exc, r, retried, diagnostics)
                raise
            except Exception as exc:
                self._failed(role, model, t0, exc, r, retried, diagnostics)
                raise
        raise AssertionError("unreachable: the last attempt returns or raises")

    def _failed(self, role: str, model: str, t0: float, exc: Exception, r: Any,
                retried: list[dict[str, Any]], diagnostics: dict[str, Any]) -> None:
        entry = {"role": role, "model": model, "seconds": round(time.time() - t0, 2),
                 "error_type": type(exc).__name__,
                 "http_status": r.status_code if r is not None else None,
                 **diagnostics, **self._quota_metadata(r)}
        if retried:
            entry["retried"] = retried
        self.calls.append(entry)



def default_core_bin() -> str:
    """$BALLISTIC_CORE_BIN if set; else core/target/{release,debug}/ballistic-core, honouring
    CARGO_TARGET_DIR and cargo's config.toml `build.target-dir`; otherwise whatever
    `ballistic-core` is on PATH."""
    if os.environ.get("BALLISTIC_CORE_BIN"):
        return os.environ["BALLISTIC_CORE_BIN"]
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
