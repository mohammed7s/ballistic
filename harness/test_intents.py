"""harness/intents.py: argument lists, quoting, outcomes and advertising. No emulator."""
import shlex
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import intents
from core_bridge import CoreHost

HOSTILE = ["it's $(reboot) `id`; rm -rf / && echo \"x\" | cat > /sdcard/x", "line one\nline two", "--es evil 1", "* & ~ # !"]


class ArgumentListTest(unittest.TestCase):
    def test_every_operation_maps_to_its_documented_intent_with_the_package(self):
        cases = {
            "set_timer": ({"seconds": 300, "label": None}, "android.intent.action.SET_TIMER"),
            "set_alarm": ({"hour": 7, "minute": 30, "label": "Gym", "days": ["monday", "sunday"]}, "android.intent.action.SET_ALARM"),
            "create_event": ({"title": "T", "begin": "2023-10-23T06:00", "end": "2023-10-23T07:00", "location": None, "description": None},
                             "android.intent.action.INSERT"),
            "compose_sms": ({"to": "+15550100", "body": "hi"}, "android.intent.action.SENDTO"),
            "add_contact": ({"name": "Ana", "phone": "5550100", "email": None}, "android.intent.action.INSERT"),
            "open_settings": ({"page": "bluetooth"}, "android.settings.BLUETOOTH_SETTINGS"),
            "open_camera": ({"mode": "video"}, "android.media.action.VIDEO_CAMERA"),
            "dial": ({"number": "5550100"}, "android.intent.action.DIAL"),
            "view_uri": ({"uri": "https://example.com"}, "android.intent.action.VIEW"),
        }
        for op, (fields, action) in cases.items():
            args = intents.am_start_args("pkg.x", {"operation": op, **fields}, "UTC")
            self.assertEqual(args[:4], ["am", "start", "-a", action], op)
            self.assertEqual(args[args.index("-p") + 1], "pkg.x", op)
            joined = " ".join(args)
            for forbidden in ["SKIP_UI true", "android.intent.action.CALL", "IMAGE_CAPTURE", "--activity-clear"]:
                self.assertNotIn(forbidden, joined, op)
        alarm = intents.am_start_args("p", {"operation": "set_alarm", **cases["set_alarm"][0]}, "UTC")
        self.assertIn("2,1", alarm, "Calendar.MONDAY=2, SUNDAY=1")
        self.assertEqual(alarm[-3:], ["--ez", "android.intent.extra.alarm.SKIP_UI", "false"])
        sms = intents.am_start_args("p", {"operation": "compose_sms", **cases["compose_sms"][0]}, "UTC")
        self.assertEqual(sms[sms.index("-d") + 1], "smsto:+15550100")
        self.assertEqual(sms[-3:], ["--es", "sms_body", "hi"])
        dial = intents.am_start_args("p", {"operation": "dial", "number": "5550100"}, "UTC")
        self.assertEqual(dial[dial.index("-d") + 1], "tel:5550100")

    def test_event_times_use_the_device_zone(self):
        args = intents.am_start_args("p", {"operation": "create_event", "title": "T", "begin": "2023-10-23T06:00",
                                           "end": "2023-10-23T07:00", "location": "Gym", "description": "D"}, "UTC")
        self.assertEqual(args[args.index("beginTime") + 1], "1698040800000")
        self.assertEqual(args[args.index("endTime") + 1], "1698044400000")
        self.assertEqual(intents.epoch_millis("2023-10-23T06:00", "America/New_York"), 1698055200000)

    def test_hostile_values_survive_device_shell_parsing_unchanged(self):
        for value in HOSTILE:
            for op in [{"operation": "compose_sms", "to": "5550100", "body": value},
                       {"operation": "create_event", "title": value, "begin": "2023-10-23T06:00", "end": "2023-10-23T07:00",
                        "location": value, "description": value},
                       {"operation": "add_contact", "name": value, "phone": None, "email": None}]:
                args = intents.am_start_args("p", op, "UTC")
                # What the device shell sees: adb joins with spaces, sh splits.
                self.assertEqual(shlex.split(" ".join(intents.shell_words(args))), args)


class OutcomeTest(unittest.TestCase):
    def test_outcomes(self):
        self.assertEqual(intents.outcome("Starting: Intent { act=android.intent.action.SET_TIMER }")["result"], "ok")
        self.assertEqual(intents.outcome("Error: Activity not started, unable to resolve Intent { act=x }")["result"], "not_executed")
        self.assertEqual(intents.outcome("java.lang.SecurityException: Permission Denial: starting Intent")["result"], "not_executed")
        self.assertEqual(intents.outcome("Error: something else")["result"], "error")
        self.assertEqual(intents.act(Mock(side_effect=OSError("adb")), {"package": "p", "intent": {"operation": "dial", "number": "1"}}, "UTC")["result"], "error")
        self.assertEqual(intents.act(Mock(), {"package": "p", "intent": {"operation": "nope"}}, "UTC")["result"], "not_executed")

    def test_advertises_only_resolved_checked_and_granted_handlers(self):
        out = {"set_timer": "  com.google.android.deskclock/com.android.deskclock.HandleApiCalls\n",
               "compose_sms": "com.google.android.apps.messaging/.ui.conversation.LaunchConversationActivity\ncom.simplemobiletools.smsmessenger/.activities.ThreadActivity\n",
               "dial": "com.google.android.dialer/.DialtactsActivity\n"}

        def shell(args):
            action = args[args.index("-a") + 1]
            op = next(o for o, p in intents.PROBES.items() if p[0] == action and (("-d" not in args) or p[1] == args[args.index("-d") + 1]))
            return out.get(op, "No activities found\n")
        handlers = {"set_timer": ("com.google.android.deskclock",), "compose_sms": ("com.simplemobiletools.smsmessenger",),
                    "add_contact": ("com.google.android.contacts",)}
        grants = intents.grants(shell, {"com.google.android.deskclock", "com.simplemobiletools.smsmessenger", "com.google.android.apps.messaging"}, handlers)
        self.assertEqual(grants, [{"operation": "set_timer", "package": "com.google.android.deskclock"},
                                  {"operation": "compose_sms", "package": "com.simplemobiletools.smsmessenger"}])
        self.assertEqual(intents.PROBES["dial"][0], "android.intent.action.DIAL", "ACTION_DIAL only, never ACTION_CALL")


class CoreHostTest(unittest.TestCase):
    def test_intent_op_goes_through_quoted_adb_shell(self):
        response = SimpleNamespace(generic=SimpleNamespace(output=b"Starting: Intent"))
        adb = Mock()
        adb.issue_generic_request.return_value = response
        host = CoreHost(SimpleNamespace(controller="c"), Mock(), intents=True)
        host._zone = "UTC"
        with patch("core_bridge.adb_utils", adb):
            result = host.act({"op": "intent", "package": "com.simplemobiletools.smsmessenger",
                               "intent": {"operation": "compose_sms", "to": "5550100", "body": HOSTILE[0]}})
        self.assertEqual(result, {"result": "ok"})
        sent = adb.issue_generic_request.call_args[0][0]
        self.assertEqual(sent[0], "shell")
        self.assertEqual(sent[-1], "2>&1", "stderr is read: am start reports unresolved intents there")
        self.assertEqual(shlex.split(" ".join(sent[1:-1]))[-1], HOSTILE[0])
        self.assertTrue(host._input_since_observation)

    def test_intents_are_off_by_default(self):
        self.assertFalse(CoreHost(SimpleNamespace(controller="c"), Mock()).intents)


if __name__ == "__main__":
    unittest.main()
