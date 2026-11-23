import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from harness.apk_clock_pilot import parse_stopwatch_state, run_stopwatch
from harness.test_apk_control import FakeAdb


class ClockStateTest(unittest.TestCase):
    def test_clock_states_and_default(self):
        self.assertEqual(parse_stopwatch_state('<map><boolean name="other" value="true"/></map>'), "reset")
        for number, expected in [("0", "reset"), ("1", "running"), ("2", "paused")]:
            self.assertEqual(parse_stopwatch_state(f'<map><int name="sw_state" value="{number}"/></map>'), expected)

    def test_unknown_or_ambiguous_format_is_not_failure_or_pass(self):
        for xml in ['<map><int name="sw_state" value="3"/></map>',
                    '<map><string name="sw_state">1</string></map>',
                    '<map><int name="sw_state" value="1"/><int name="sw_state" value="0"/></map>',
                    '<unexpected/>']:
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                parse_stopwatch_state(xml)

    def test_refuses_phone_before_clock_read_or_reset(self):
        device = FakeAdb(hardware="blazer")
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "preflight failed"):
                run_stopwatch(device, 30, Path(directory))
        self.assertFalse(any(call[0] == "su" or call[:2] == ("pm", "clear") for call in device.calls))

    def test_agent_done_is_not_a_state_pass(self):
        class ClockAdb:
            def shell(self, *args):
                return "Success" if args[:2] == ("pm", "clear") else "package:/system/Clock.apk"
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            agent = root / "agent.json"
            agent.write_text(json.dumps({"report": {"done": True}, "status": "finished"}))
            with patch("harness.apk_clock_pilot.preflight", return_value={
                "ready_for_app_checks": True, "properties": {"ro.build.type": "userdebug"}
            }), patch("harness.apk_clock_pilot.read_stopwatch", return_value={"state": "reset"}), \
                    patch("harness.apk_clock_pilot.run_goal", return_value={"record": str(agent)}):
                result = run_stopwatch(ClockAdb(), 30, root)
            self.assertFalse(result["combined_pass"])
            self.assertEqual(result["independent_state"], "fail")

    def test_invalid_timeout_cannot_reset_clock(self):
        device = FakeAdb()
        with self.assertRaises(ValueError):
            run_stopwatch(device, 0, Path("unused"))
        self.assertEqual(device.calls, [])


if __name__ == "__main__":
    unittest.main()
