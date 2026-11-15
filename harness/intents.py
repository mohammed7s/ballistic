"""Typed operations (intents) on the emulator host: core's `intent` HandsOp as `am start`.

Core validates every argument and picks the package (core/src/operations.rs); this file only maps
the typed operation to its public, documented Android intent and reports what the device said.
Nothing here decides anything. No operation sends, saves or skips its UI: no EXTRA_SKIP_UI=true,
no ACTION_CALL, no ACTION_IMAGE_CAPTURE, no SmsManager; an editor or compose screen is left open
for core's ordinary screen loop and confirmations.

Quoting: `adb shell` joins its arguments with spaces and the device shell parses the result, so
every argument is passed through `shlex.quote` (single-quoted, POSIX) and the command is built as
a list. No value is ever interpolated into a format string. `test_intents.py` round-trips hostile
values through the same parsing.

Advertising: a (operation, package) pair is advertised only if (1) the device resolves the probe
intent to that package now (`cmd package query-activities`), and (2) the pair is in
`EMULATOR_HANDLERS`, the handlers checked on the AndroidWorld emulator image. `dial` is
ACTION_DIAL only: it fills the dialer, and the call is a tap on the dialer's call control, which
core guards like Send (core/src/calls.rs).
"""

from __future__ import annotations

import datetime
import shlex
from typing import Any, Callable, Optional

try:
    from zoneinfo import ZoneInfo
except ImportError:  # pragma: no cover
    ZoneInfo = None  # type: ignore

# Handlers per operation on the AndroidWorld API-33 image. Each entry must be checked on the
# emulator (the activity opens, values are filled, nothing is submitted) before it is trusted;
# see harness/README.md "Typed operations (intents)" for the recorded results.
EMULATOR_HANDLERS: dict[str, tuple[str, ...]] = {
    "set_timer": ("com.google.android.deskclock",),
    "set_alarm": ("com.google.android.deskclock",),
    "create_event": ("com.simplemobiletools.calendar.pro",),
    "compose_sms": ("com.simplemobiletools.smsmessenger",),
    "add_contact": ("com.google.android.contacts",),
    "open_settings": ("com.android.settings",),
    "open_camera": ("com.android.camera2",),
    "view_uri": ("com.android.chrome",),
    "dial": ("com.google.android.dialer",),
}

ALARM = "android.intent.extra.alarm."
SETTINGS_ACTIONS = {
    "wifi": "android.settings.WIFI_SETTINGS",
    "internet_panel": "android.settings.panel.action.INTERNET_CONNECTIVITY",
    "bluetooth": "android.settings.BLUETOOTH_SETTINGS",
    "display": "android.settings.DISPLAY_SETTINGS",
    "sound": "android.settings.SOUND_SETTINGS",
    "notifications": "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
    "apps": "android.settings.APPLICATION_SETTINGS",
    "date": "android.settings.DATE_SETTINGS",
}
CAMERA_ACTIONS = {"photo": "android.media.action.STILL_IMAGE_CAMERA", "video": "android.media.action.VIDEO_CAMERA"}
# java.util.Calendar day constants: SUNDAY = 1 … SATURDAY = 7.
CALENDAR_DAYS = {"sunday": 1, "monday": 2, "tuesday": 3, "wednesday": 4, "thursday": 5, "friday": 6, "saturday": 7}
CALENDAR_EVENTS = "content://com.android.calendar/events"
RAW_CONTACT_TYPE = "vnd.android.cursor.dir/raw_contact"

# One probe intent per operation: (action, data, mime type). What `query-activities` resolves.
PROBES: dict[str, tuple[str, Optional[str], Optional[str]]] = {
    "set_timer": ("android.intent.action.SET_TIMER", None, None),
    "set_alarm": ("android.intent.action.SET_ALARM", None, None),
    "create_event": ("android.intent.action.INSERT", CALENDAR_EVENTS, None),
    "compose_sms": ("android.intent.action.SENDTO", "smsto:5550100", None),
    "add_contact": ("android.intent.action.INSERT", None, RAW_CONTACT_TYPE),
    "open_settings": (SETTINGS_ACTIONS["wifi"], None, None),
    "open_camera": (CAMERA_ACTIONS["photo"], None, None),
    "dial": ("android.intent.action.DIAL", "tel:5550100", None),
    "view_uri": ("android.intent.action.VIEW", "https://example.com", None),
}


def epoch_millis(local: str, zone: str) -> int:
    """`YYYY-MM-DDTHH:MM` wall time in the device's zone → epoch milliseconds."""
    naive = datetime.datetime.strptime(local, "%Y-%m-%dT%H:%M")
    tz = ZoneInfo(zone) if ZoneInfo and zone else datetime.timezone.utc
    return int(naive.replace(tzinfo=tz).timestamp() * 1000)


def am_start_args(package: str, intent: dict[str, Any], zone: str) -> list[str]:
    """The `am start` argument list for one typed operation, sent to exactly `package`."""
    op = intent["operation"]
    extras: list[str] = []

    def es(key: str, value: Optional[str]) -> None:
        if value is not None:
            extras.extend(["--es", key, value])

    action, data, mime = PROBES[op]
    categories: list[str] = []
    if op == "set_timer":
        extras.extend(["--ei", ALARM + "LENGTH", str(int(intent["seconds"]))])
        es(ALARM + "MESSAGE", intent.get("label"))
        extras.extend(["--ez", ALARM + "SKIP_UI", "false"])
    elif op == "set_alarm":
        extras.extend(["--ei", ALARM + "HOUR", str(int(intent["hour"])), "--ei", ALARM + "MINUTES", str(int(intent["minute"]))])
        es(ALARM + "MESSAGE", intent.get("label"))
        if intent.get("days"):
            extras.extend(["--eial", ALARM + "DAYS", ",".join(str(CALENDAR_DAYS[d]) for d in intent["days"])])
        extras.extend(["--ez", ALARM + "SKIP_UI", "false"])
    elif op == "create_event":
        extras.extend(["--el", "beginTime", str(epoch_millis(intent["begin"], zone)),
                       "--el", "endTime", str(epoch_millis(intent["end"], zone))])
        es("title", intent["title"])
        es("eventLocation", intent.get("location"))
        es("description", intent.get("description"))
    elif op == "compose_sms":
        data = "smsto:" + intent["to"]
        es("sms_body", intent["body"])
    elif op == "add_contact":
        es("name", intent["name"])
        es("phone", intent.get("phone"))
        es("email", intent.get("email"))
    elif op == "open_settings":
        action = SETTINGS_ACTIONS[intent["page"]]
    elif op == "open_camera":
        action = CAMERA_ACTIONS[intent["mode"]]
    elif op == "dial":
        data = "tel:" + intent["number"]
    elif op == "view_uri":
        data = intent["uri"]
        categories = ["-c", "android.intent.category.BROWSABLE"]
    else:
        raise ValueError(f"unknown operation {op!r}")
    args = ["am", "start", "-a", action]
    if data is not None:
        args.extend(["-d", data])
    if mime is not None:
        args.extend(["-t", mime])
    args.extend(categories)
    args.extend(["-p", package])
    return args + extras


def shell_words(args: list[str]) -> list[str]:
    """Arguments for `adb shell`, each quoted for the device shell."""
    return [shlex.quote(a) for a in args]


def outcome(output: str) -> dict[str, Any]:
    """Map `am start` output to a HandsResult. Not resolved or refused: nothing started."""
    text = output.strip()
    if "unable to resolve Intent" in text or "Permission Denial" in text or "SecurityException" in text:
        return {"result": "not_executed", "message": "the device did not start this intent"}
    if "Error" in text or "Exception" in text:
        return {"result": "error", "message": "am start reported an error"}
    return {"result": "ok"}


def resolved_packages(output: str) -> set[str]:
    """Packages in `cmd package query-activities --brief` output (`pkg/.Activity` lines)."""
    found = set()
    for line in output.splitlines():
        token = line.strip().split(" ")[0]
        if "/" in token and not token.startswith(("priority", "#")):
            found.add(token.split("/")[0])
    return found


def probe_args(op: str) -> list[str]:
    action, data, mime = PROBES[op]
    args = ["cmd", "package", "query-activities", "--brief", "-a", action, "-c", "android.intent.category.DEFAULT"]
    if data:
        args.extend(["-d", data])
    if mime:
        args.extend(["-t", mime])
    return args


def grants(shell: Callable[[list[str]], str], app_grants: set[str],
           handlers: dict[str, tuple[str, ...]] = EMULATOR_HANDLERS) -> list[dict[str, str]]:
    """Advertised (operation, package) pairs: resolved now, in `handlers`, and app-granted."""
    out = []
    for op, packages in handlers.items():
        if op not in PROBES:
            continue
        try:
            resolved = resolved_packages(shell(probe_args(op)))
        except Exception:  # noqa: BLE001  an unanswered probe advertises nothing
            continue
        out.extend({"operation": op, "package": p} for p in packages if p in resolved and p in app_grants)
    return out


def device_zone(shell: Callable[[list[str]], str]) -> str:
    try:
        return shell(["getprop", "persist.sys.timezone"]).strip() or "UTC"
    except Exception:  # noqa: BLE001
        return "UTC"


def act(shell: Callable[[list[str]], str], op: dict[str, Any], zone: str) -> dict[str, Any]:
    try:
        args = am_start_args(op["package"], op["intent"], zone)
    except (KeyError, ValueError) as e:
        return {"result": "not_executed", "message": f"unsupported intent: {type(e).__name__}"}
    try:
        return outcome(shell(args))
    except Exception as e:  # noqa: BLE001  report, don't decide
        return {"result": "error", "message": f"{type(e).__name__}"}


def adb_shell(controller: Any, adb_utils: Any) -> Callable[[list[str]], str]:
    """`adb shell` through AndroidWorld's controller, arguments quoted for the device shell."""
    def run(args: list[str]) -> str:
        response = adb_utils.issue_generic_request(["shell", *shell_words(args)], controller, timeout_sec=10)
        ok = getattr(type(response), "OK", None)
        if ok is not None and getattr(response, "status", ok) != ok:
            raise RuntimeError("adb request failed")  # unknown: core stops, never retries
        return response.generic.output.decode("utf-8", "replace")
    return run
