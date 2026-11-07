"""Display core records to the owner; never build model context from these records."""
import json
from typing import Any


def print_core_event(ev: dict[str, Any]) -> None:
    """One line per step, or the terminal report including bounded owner output."""
    if "step" in ev:
        print(f"step {ev.get('step'):>2}  {ev.get('seconds', 0):5.1f}s  {str(ev.get('action'))[:110]}")
    elif ev.get("finished"):
        report = ev.get("report")
        if (isinstance(report, dict) and not report.get("stopped")
                and isinstance(report.get("owner_output"), dict)
                and report["owner_output"].get("status") == "available"):
            # Core already bounds this output. Escape control characters for the terminal;
            # never linkify, execute it, or truncate away its source/evidence labels.
            print("owner output:", json.dumps(report.get("reason"), ensure_ascii=True))
        else:
            print("finished:", json.dumps(report or ev.get("error"))[:300])
