import json
import tempfile
import unittest
from collections import Counter
from pathlib import Path
from unittest.mock import patch

from harness import apk_suite
from harness.apk_suite import clock_check, eligible, group_packages, load_table, parse_selected_tab, run_one, summarize


class Log:
    """Shared ordered log of operator root, setup, agent and checker events."""
    def __init__(self):
        self.events = []
        self.rooted = False


class FakeRoot:
    def __init__(self, log):
        self.log = log

    def __call__(self, adb):
        return self

    def __enter__(self):
        self.log.rooted = True
        self.log.events.append("root")

    def __exit__(self, *exc):
        self.log.rooted = False
        self.log.events.append("unroot")
        return False


class FakeAdb:
    def __init__(self, log, prefs=None):
        self.log, self.prefs = log, prefs

    def shell(self, *args, **kwargs):
        self.log.events.append(("shell", args[0], self.log.rooted))
        if args[:2] == ("sh", "-c"):
            return self.prefs if self.prefs is not None else "MISSING"
        return ""


class FakeTask:
    goal = "Do the thing."

    def __init__(self, log, success=True):
        self.log, self.success = log, success

    def is_successful(self, env):
        self.log.events.append(("check", self.log.rooted, env.interaction_cache))
        return 1.0 if self.success else 0.0


class FakeEnv:
    interaction_cache = ""


def agent_writing(log, directory, report):
    def agent(adb, goal, timeout, out_dir):
        log.events.append(("agent", log.rooted, goal))
        path = Path(directory) / "agent.json"
        path.write_text(json.dumps({"status": "finished", "report": report, "steps": [{}, {}]}))
        return {"record": str(path)}
    return agent


class SuiteTableTest(unittest.TestCase):
    def test_table_covers_the_frozen_thirty_and_classifies_protected_settings(self):
        table = load_table()
        self.assertEqual(len(table["tasks"]), 30)
        statuses = Counter(e["status"] for e in table["tasks"].values())
        self.assertEqual(statuses["policy_blocked"], 5)
        for name in ("SystemWifiTurnOff", "SystemBluetoothTurnOn", "SystemBrightnessMax", "TurnOnWifiAndOpenApp", "OpenAppTaskEval"):
            self.assertEqual(table["tasks"][name]["status"], "policy_blocked", name)
        # Send/delete tasks are not silently run without the owner's approval.
        for name in ("SimpleSmsSend", "MarkorDeleteNote", "ExpenseDeleteSingle", "FilesDeleteFile"):
            self.assertEqual(table["tasks"][name]["status"], "approval_gated", name)

    def test_one_group_stays_within_the_six_target_session_limit(self):
        table = load_table()
        self.assertLessEqual(len(group_packages(table, "A")), 6)
        self.assertNotIn("com.android.settings", group_packages(table, "A"))
        self.assertEqual(len(eligible(table)), 11)

    def test_nothing_from_the_held_out_set_is_in_the_suite(self):
        doc = (Path(__file__).resolve().parent.parent / "docs" / "ANDROIDWORLD_TASKS.md").read_text()
        section = doc.split("## 5. Recommended held-out set")[1].split("## 6.")[0]
        held_out = {line.split("|")[2].strip() for line in section.splitlines()
                    if line.startswith("|") and line.split("|")[1].strip().isdigit()}
        self.assertEqual(len(held_out), 25)
        self.assertFalse(held_out & set(load_table()["tasks"]))


class ClockCheckTest(unittest.TestCase):
    def test_adapted_clock_checks(self):
        running = '<map><int name="sw_state" value="1"/><int name="selected_tab" value="3"/></map>'
        paused_tab = '<map><int name="sw_state" value="2"/><int name="selected_tab" value="3"/></map>'
        reset_other_tab = '<map><int name="selected_tab" value="1"/></map>'
        self.assertTrue(clock_check("ClockStopWatchRunning", running)["passed"])
        self.assertFalse(clock_check("ClockStopWatchRunning", None)["passed"])
        self.assertTrue(clock_check("ClockStopWatchPausedVerify", paused_tab)["passed"])
        self.assertFalse(clock_check("ClockStopWatchPausedVerify", running)["passed"])
        # Doing nothing leaves the reset state but not the Stopwatch tab: not a pass.
        self.assertFalse(clock_check("ClockStopWatchPausedVerify", None)["passed"])
        self.assertFalse(clock_check("ClockStopWatchPausedVerify", reset_other_tab)["passed"])

    def test_malformed_tab_is_an_error(self):
        with self.assertRaises(ValueError):
            parse_selected_tab('<map><string name="selected_tab">3</string></map>')
        self.assertIsNone(parse_selected_tab("<map/>"))


class RunOneTest(unittest.TestCase):
    entry = {"status": "eligible", "checker": "androidworld", "packages": ["com.example"]}

    def run_task(self, log, report, entry=None, success=True, init=None, prefs=None, name="Task"):
        with tempfile.TemporaryDirectory() as directory:
            return run_one(name, entry or self.entry, FakeAdb(log, prefs), FakeEnv(), 30, Path(directory),
                           agent=agent_writing(log, directory, report), root=FakeRoot(log),
                           task_factory=lambda n: FakeTask(log, success),
                           init=init or (lambda task, env: log.events.append(("setup", log.rooted))))

    def test_setup_and_check_rooted_agent_unrooted_checker_after_agent(self):
        log = Log()
        record = self.run_task(log, {"done": True})
        kinds = [e if isinstance(e, str) else e[0] for e in log.events]
        self.assertEqual(kinds, ["root", "setup", "shell", "unroot", "agent", "root", "check", "unroot"])
        self.assertIn(("setup", True), log.events)
        self.assertIn(("agent", False, "Do the thing."), log.events, "the agent gets only the goal, unrooted")
        self.assertTrue(record["combined_pass"])
        self.assertFalse(record["false_done"])

    def test_done_with_failed_state_is_false_done(self):
        log = Log()
        record = self.run_task(log, {"done": True}, success=False)
        self.assertEqual(record["state"], "fail")
        self.assertTrue(record["false_done"])
        self.assertFalse(record["combined_pass"])
        self.assertEqual(summarize(load_table(), [{"task": "Task", **record}])["false_done"], ["Task"])

    def test_state_pass_without_done_is_not_combined_pass(self):
        log = Log()
        record = self.run_task(log, {"done": False})
        self.assertEqual(record["state"], "pass")
        self.assertFalse(record["combined_pass"])
        self.assertFalse(record["false_done"])

    def test_setup_failure_never_starts_the_agent(self):
        log = Log()
        def broken(task, env):
            raise RuntimeError("no db")
        record = self.run_task(log, {"done": True}, init=broken)
        self.assertEqual(record["status"], "setup_error")
        self.assertFalse(any(isinstance(e, tuple) and e[0] == "agent" for e in log.events))
        self.assertEqual(log.events[-1], "unroot")

    def test_answer_text_reaches_only_the_checker_env(self):
        log = Log()
        report = {"done": False, "answer": {"text": "Alpha, Beta", "display": "Alpha (from app), Beta"},
                  "verification": {"evidence_basis": "answer_values_sourced"}}
        entry = {"status": "eligible", "checker": "androidworld_answer", "packages": ["com.example"]}
        record = self.run_task(log, report, entry=entry)
        self.assertIn(("check", True, "Alpha, Beta"), log.events)
        self.assertTrue(record["answer_delivered"])
        self.assertTrue(record["agent_complete"])
        # Non-answer tasks never deliver text.
        log2 = Log()
        self.assertFalse(self.run_task(log2, report)["answer_delivered"])
        self.assertIn(("check", True, ""), log2.events)

    def test_clock_tasks_use_the_adapted_preferences_check(self):
        log = Log()
        entry = {"status": "eligible", "checker": "adapted_clock_preferences", "packages": ["com.android.deskclock"]}
        record = self.run_task(log, {"done": True}, entry=entry, name="ClockStopWatchRunning",
                               prefs='<map><int name="sw_state" value="1"/></map>')
        self.assertEqual(record["state"], "pass")
        self.assertTrue(record["combined_pass"])
        self.assertFalse(record["original_androidworld_checker"])
        self.assertIn(("shell", "sh", True), log.events, "preferences are read only while rooted")


class RunSuiteTest(unittest.TestCase):
    def test_refuses_non_userdebug_before_any_setup(self):
        with patch("harness.apk_suite.preflight", return_value={"ready_for_app_checks": True,
                                                                  "properties": {"ro.build.type": "user"}}):
            with self.assertRaisesRegex(RuntimeError, "userdebug"):
                apk_suite.run_suite(object(), FakeEnv(), load_table(), "A", 30, Path("unused"))

    def test_only_eligible_tasks_can_be_selected(self):
        with patch("harness.apk_suite.preflight", return_value={"ready_for_app_checks": True,
                                                                  "properties": {"ro.build.type": "userdebug"}}):
            with self.assertRaisesRegex(ValueError, "SystemWifiTurnOff"):
                apk_suite.run_suite(object(), FakeEnv(), load_table(), "A", 30, Path("unused"), only=["SystemWifiTurnOff"])


if __name__ == "__main__":
    unittest.main()
