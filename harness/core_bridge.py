"""Host side of the core bridge: serves `ballistic-core` over JSON lines.

Core asks, this file answers, and nothing here decides anything (core/README.md §15, rule 2).
Five kinds of ask:
  observe   -> ScreenState built from AndroidWorld's accessibility forest (parents kept)
  act       -> one HandsOp executed through AndroidWorld's adb helpers, in screen coordinates
  complete  -> one chat completion at the OpenAI-compatible endpoint configured for the role
  confirm   -> approve or deny from a fixed policy (benchmark: approve; injection tests: deny)
  tool      -> reserved optional read-only Tools port; core currently supports no requests
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
from typing import Any, Callable, Optional, Protocol

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
    # A removed ancestor is not a root. Keep an invalid comparison-only parent so
    # slight-change tolerance cannot assume complete ancestry through that wrapper.
    # observe still returns the original full forest, never this sentinel.
    return [{**n, "id": ids[n["id"]],
             "parent": None if n["parent"] is None else ids.get(n["parent"], -1)} for n in nodes]


def _signature(nodes: Optional[list[dict[str, Any]]]) -> Optional[str]:
    if nodes is None:
        return None
    return hashlib.sha256(json.dumps(nodes, sort_keys=True).encode()).hexdigest()


def _foreground_signature(forest: Any, package: str) -> Optional[str]:
    """Fingerprint of `_foreground_nodes`."""
    return _signature(_foreground_nodes(forest, package))


_SETTLING_ROLES = ("text", "image", "container", "button", "edit", "switch",
                   "checkbox", "slider", "tab", "item", "list")
_SETTLING_FLAGS = frozenset(("clickable", "long_clickable", "editable", "scrollable",
                            "checkable", "checked", "selected", "focused", "enabled", "visible"))


def slightly_different(old: list[dict[str, Any]], new: list[dict[str, Any]]) -> bool:
    """Only label/text may vary, on at most a tenth of structurally stable nodes.

    All other fields, including identity, ancestry, roles, bounds and flags, must
    match. Controls and scrollable ancestry need not freeze their display strings.
    Unknown roles/flags and malformed ancestry remain conservative. The latest raw
    strings still go through core's ordinary filter, identity and confirmation
    checks: tolerance grants no trust or action authority. This is a consistency
    heuristic, not proof of freshness, harmless content or atomic target stability.
    """
    if len(old) != len(new) or not old:
        return False
    # Normalization emits parents before children. Requiring that order rejects
    # dangling/forward/cyclic edges without recursion or repeated ancestor walks.
    # One boolean per ID records known ancestry: O(n) time and O(n) space
    # for fixed-size node metadata, plus comparison of the supplied field values.
    known_ancestry: dict[int, bool] = {}
    changed = 0
    for a, b in zip(old, new):
        if not isinstance(a, dict) or not isinstance(b, dict):
            return False
        if a.keys() != b.keys() or any(a[k] != b[k] for k in a if k not in ("label", "text")):
            return False
        uid, parent = a.get("id"), a.get("parent")
        if (type(uid) is not int or type(b.get("id")) is not int
                or uid < 0 or uid in known_ancestry or "parent" not in a
                or (b.get("parent") is not None and type(b["parent"]) is not int)
                or (parent is not None and (type(parent) is not int or parent not in known_ancestry))):
            return False
        flags = a.get("flags")
        if (not isinstance(flags, dict) or flags.keys() != _SETTLING_FLAGS
                or not isinstance(b.get("flags"), dict)
                or any(type(flags[k]) is not bool or type(b["flags"][k]) is not bool
                       for k in _SETTLING_FLAGS)):
            return False
        if any(k not in node or (node[k] is not None and not isinstance(node[k], str))
               for node in (a, b) for k in ("label", "text")):
            return False
        known_ancestry[uid] = (a.get("role") in _SETTLING_ROLES
                               and (parent is None or known_ancestry[parent]))
        if a["text"] != b["text"] or a["label"] != b["label"]:
            if not known_ancestry[uid]:
                return False
            changed += 1
            if changed > len(old) // 10:
                return False
    return True


SOFT_DEADLINE_SECONDS = 30.0


class ObservationError(RuntimeError):
    """The host could not obtain a sufficiently consistent, ready observation."""


class CoreHost:
    """Answers core's asks. `confirm_policy` is "approve" or "deny"."""

    def __init__(self, env: Any, models: "ModelClient", confirm_policy: str = "approve",
                 on_event: Optional[Callable[[dict[str, Any]], None]] = None,
                 tools: Optional["Tools"] = None):
        self.env = env
        self.tools = tools
        self.models = models
        self.confirm_policy = confirm_policy
        self.on_event = on_event
        self.lines: list[dict[str, Any]] = []      # every line, both directions, in order
        self.events: list[dict[str, Any]] = []     # core's log events
        self.confirmations: list[dict[str, Any]] = []
        self._apps: Optional[list[dict[str, Any]]] = None
        self._last_observation: Optional[tuple[str, str]] = None
        self._observation_sequence = 0

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
        skeleton and metadata, label/text changes on at most a tenth of nodes,
        including controls with known ancestry); the later sample is returned
        intact for ordinary core filtering and validation. This does not establish
        that changing strings are harmless or the target cannot change afterward.
        Unsettled samples are spaced 0.3 s,
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
                        "app_grants": sorted({a["package"] for a in self._apps} | {package}),
                        "observations": ["foreground_component_v1"]}
        if self.tools:
            capabilities["tool_grants"] = self.tools.grants()
        self._last_observation = (foreground, signature)
        # Only this successful, bracketed and settled acquisition advertises foreground.
        # This sequence is call freshness, not an accessibility capture generation.
        self._observation_sequence += 1
        return {"package": package, "activity": activity, "width": w, "height": h,
                "nodes": nodes, "installed_apps": self._apps, "capabilities": capabilities,
                "foreground": {"package": package, "activity": activity,
                               "sequence": self._observation_sequence}}

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
            elif kind == "tool":
                if self.tools is None:
                    raise RuntimeError("no tools configured on this host")
                data = self.tools.call(ask["request"])
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

    Transient transport/502/503/504 failures and at most one empty textual
    generation are retried within the same total attempt budget. Telemetry
    records safe response metadata, never raw error bodies or discarded output.
    """

    RETRY_STATUSES = (502, 503, 504)
    FINISH_REASONS = {"stop", "length", "tool_calls", "function_call", "content_filter"}
    # Documented Gemini enums can pass through its OpenAI-compatible endpoint. Keep only
    # code-owned names, never arbitrary provider strings. These do not broaden retry policy.
    # https://ai.google.dev/api/generate-content#FinishReason
    PROVIDER_FINISH_REASONS = {
        "FINISH_REASON_UNSPECIFIED", "STOP", "MAX_TOKENS", "SAFETY", "RECITATION", "LANGUAGE",
        "OTHER", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "MALFORMED_FUNCTION_CALL",
        "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_OTHER", "NO_IMAGE", "IMAGE_RECITATION",
        "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "MISSING_THOUGHT_SIGNATURE",
        "MALFORMED_RESPONSE", "ESCALATION",
    }

    def __init__(self, routes: dict[str, tuple[str, str, str]], temperature: float = 0.0, timeout: int = 120,
                 retries: int = 3, backoff_seconds: float = 2.0, min_interval_seconds: float = 0.0,
                 disable_native_tools: bool = False):
        if not 0 <= min_interval_seconds <= 60:
            raise ValueError("model minimum interval must be between 0 and 60 seconds")
        if type(disable_native_tools) is not bool:
            raise ValueError("disable_native_tools must be a boolean")
        self.routes = routes
        self.temperature = temperature
        self.timeout = timeout
        self.retries = retries
        self.backoff_seconds = backoff_seconds
        self.min_interval_seconds = min_interval_seconds
        # Opt-in: compatible endpoints differ on accepting tool_choice without tools.
        self.disable_native_tools = disable_native_tools
        self._last_attempt_started: float | None = None
        self.calls: list[dict[str, Any]] = []

    def _pace(self) -> float:
        """Space HTTP starts across roles/retries of this serial client, using a monotonic clock."""
        if not self.min_interval_seconds:
            return 0.0
        delay = 0.0 if self._last_attempt_started is None else max(
            0.0, self._last_attempt_started + self.min_interval_seconds - time.monotonic())
        if delay:
            time.sleep(delay)
        self._last_attempt_started = time.monotonic()
        return delay

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
        out: dict[str, Any] = {"quota_category": category}
        # Keep only bounded numeric guidance and code-owned metric names. Never retain
        # provider messages, URLs, quota dimensions, project IDs or arbitrary metric IDs.
        def seconds(value):
            if not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,5}(?:\.[0-9]{1,9})?s?", value):
                return None
            number = float(value.removesuffix("s"))
            return number if 0 <= number <= 86400 else None

        delays = []
        header = seconds(getattr(r, "headers", {}).get("Retry-After"))
        if header is not None:
            delays.append(header)
        try:
            envelope = r.json()
        except (ValueError, TypeError):
            envelope = None
        error = envelope.get("error") if isinstance(envelope, dict) else None
        details = error.get("details") if isinstance(error, dict) else None
        limits = []
        for detail in details if isinstance(details, list) else []:
            if not isinstance(detail, dict):
                continue
            if detail.get("@type") == "type.googleapis.com/google.rpc.RetryInfo":
                delay = seconds(detail.get("retryDelay"))
                if delay is not None:
                    delays.append(delay)
            if detail.get("@type") != "type.googleapis.com/google.rpc.QuotaFailure":
                continue
            violations = detail.get("violations")
            for violation in violations if isinstance(violations, list) else []:
                if not isinstance(violation, dict):
                    continue
                identifier = violation.get("quotaId")
                value = violation.get("quotaValue")
                if not isinstance(identifier, str) or not isinstance(value, str) or not re.fullmatch(r"[0-9]{1,12}", value):
                    continue
                metric = next((name for marker, name in (
                    ("generaterequestsperminute", "requests_per_minute"),
                    ("generaterequestsperday", "requests_per_day"),
                    ("generatetokensperminute", "tokens_per_minute"),
                    ("generatetokensperday", "tokens_per_day"),
                ) if identifier.lower().startswith(marker)), None)
                if metric is not None:
                    limit = {"metric": metric, "value": int(value)}
                    if limit not in limits:
                        limits.append(limit)
        if delays:
            out["retry_after_seconds"] = max(delays)
        if limits:
            out["quota_limits"] = limits
        return out

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
        if isinstance(finish, str) and finish not in cls.FINISH_REASONS and finish.upper() in cls.PROVIDER_FINISH_REASONS:
            metadata["finish_reason"] = finish.upper()
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
        for field in ("refusal", "tool_calls", "function_call"):
            metadata[field + "_present"] = bool(message.get(field))
        return {**metadata, "response_shape": shape}

    @staticmethod
    def _usage(body: Any) -> dict[str, int]:
        usage = body.get("usage") if isinstance(body, dict) else None
        if not isinstance(usage, dict):
            return {}
        return {name: value for name in ("prompt_tokens", "completion_tokens")
                if type(value := usage.get(name)) is int and value >= 0}

    @staticmethod
    def _retryable_empty(body: Any, diagnostics: dict[str, Any]) -> bool:
        # Only a single, otherwise valid textual completion can be regenerated.
        # A refusal, tool call or exhausted output budget needs a different response,
        # not repeated requests. Never reinterpret one as an action or a success.
        if (diagnostics.get("choice_count") != 1
                or diagnostics.get("response_shape") not in {"missing_content", "null_content", "empty_content"}
                or diagnostics.get("finish_reason") not in {"stop", "missing"}):
            return False
        message = body["choices"][0]["message"]
        return not any(message.get(field) for field in ("refusal", "tool_calls", "function_call"))

    def complete(self, role: str, messages: list[dict[str, str]], max_tokens: int) -> dict[str, Any]:
        import requests  # here, so replay_core.py runs without it

        base_url, model, api_key = self.routes.get(role) or self.routes["planner"]
        body = {"model": model, "messages": messages, "temperature": self.temperature, "max_tokens": max_tokens}
        if self.disable_native_tools:
            body["tool_choice"] = "none"
        t0 = time.time()
        retried: list[dict[str, Any]] = []
        retried_empty = False
        pacing_seconds = 0.0
        for attempt in range(self.retries + 1):
            r = None
            diagnostics: dict[str, Any] = {}
            try:
                pacing_seconds += self._pace()
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
                # Native calls are never Ballistic actions. Even accompanying text must
                # not reach the planner/reader/verifier when the envelope is non-textual.
                # Keep missing/null diagnostics unchanged for existing observations.
                if diagnostics["response_shape"] == "text_content" and (
                        any(diagnostics.get(field + "_present") for field in ("refusal", "tool_calls", "function_call"))
                        or diagnostics.get("finish_reason") in {"tool_calls", "function_call", "content_filter"}):
                    raise ModelResponseError("non_text_completion")
                if diagnostics["response_shape"] != "text_content":
                    if (not retried_empty and attempt < self.retries
                            and self._retryable_empty(j, diagnostics)):
                        retried_empty = True
                        retried.append({"http_status": r.status_code, "error_type": "ModelResponseError",
                                        **diagnostics})
                        time.sleep(self.backoff_seconds * 2 ** attempt)
                        continue
                    raise ModelResponseError(diagnostics["response_shape"])
                usage = self._usage(j)
                # The bridge's Usage struct requires both counters. Unknown usage stays
                # absent from call telemetry; zero defaults here are protocol compatibility.
                out = {"text": j["choices"][0]["message"]["content"], "usage": {
                    "prompt_tokens": usage.get("prompt_tokens", 0),
                    "completion_tokens": usage.get("completion_tokens", 0)}}
                entry = {"role": role, "model": model, "seconds": round(time.time() - t0, 2),
                         "http_status": r.status_code, **diagnostics}
                if self.min_interval_seconds:
                    entry["pacing_seconds"] = round(pacing_seconds, 3)
                if retried:
                    entry["retried"] = retried
                self.calls.append(entry)
                return out
            except requests.exceptions.ConnectionError as exc:
                if attempt < self.retries:
                    retried.append({"error_type": type(exc).__name__})
                    time.sleep(self.backoff_seconds * 2 ** attempt)
                    continue
                self._failed(role, model, t0, exc, r, retried, diagnostics, pacing_seconds)
                raise
            except Exception as exc:
                self._failed(role, model, t0, exc, r, retried, diagnostics, pacing_seconds)
                raise
        raise AssertionError("unreachable: the last attempt returns or raises")

    def _failed(self, role: str, model: str, t0: float, exc: Exception, r: Any,
                retried: list[dict[str, Any]], diagnostics: dict[str, Any], pacing_seconds: float) -> None:
        entry = {"role": role, "model": model, "seconds": round(time.time() - t0, 2),
                 "error_type": type(exc).__name__,
                 "http_status": r.status_code if r is not None else None,
                 **diagnostics, **self._quota_metadata(r)}
        if self.min_interval_seconds:
            entry["pacing_seconds"] = round(pacing_seconds, 3)
        if retried:
            entry["retried"] = retried
        self.calls.append(entry)



class Tools(Protocol):
    """Optional host contract; no concrete adapters are shipped."""

    def grants(self) -> list[dict[str, Any]]: ...

    def call(self, request: dict[str, Any]) -> dict[str, Any]: ...


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
    env = {k: v for k, v in os.environ.items() if not k.startswith("BALLISTIC_API_KEY")}
    return subprocess.Popen([core_bin, *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, bufsize=0, env=env, start_new_session=True)
