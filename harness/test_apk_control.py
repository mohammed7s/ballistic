import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from harness.apk_control import Adb, PACKAGE, RUNNER, preflight, run_goal


class FakeAdb:
    def __init__(self, hardware="ranchu", sdk="37", library=True):
        self.hardware, self.sdk, self.library = hardware, sdk, library
        self.calls = []

    def shell(self, *args, **kwargs):
        self.calls.append(args)
        if args[0] == "getprop":
            return {"ro.hardware": self.hardware, "ro.build.version.sdk": self.sdk}.get(args[1], "test")
        if args[:3] == ("pm", "list", "libraries"):
            return "library:com.android.extensions.computercontrol" if self.library else ""
        if args[:2] == ("cmd", "role"):
            return PACKAGE
        if args[:2] == ("pm", "path"):
            return "package:/data/app/base.apk"
        if args[:3] == ("pm", "list", "instrumentation"):
            return f"instrumentation:{RUNNER} (target={PACKAGE})"
        raise AssertionError(f"unexpected mutation {args}")


class ApkControlTest(unittest.TestCase):
    def test_physical_phone_cannot_be_used_for_run(self):
        device = FakeAdb(hardware="blazer")
        with self.assertRaisesRegex(RuntimeError, "virtual_device"):
            run_goal(device, "open Clock", 30, Path("unused"))
        self.assertFalse(any(c[0] in ("run-as", "am") for c in device.calls))

    def test_old_image_cannot_be_used_even_with_test_app(self):
        device = FakeAdb(sdk="33", library=False)
        with self.assertRaisesRegex(RuntimeError, "api_37_or_later"):
            run_goal(device, "open Clock", 30, Path("unused"))
        self.assertFalse(any(c[0] in ("run-as", "am") for c in device.calls))

    def test_library_and_role_are_not_claimed_as_consent(self):
        report = preflight(FakeAdb())
        self.assertTrue(report["ready_for_app_checks"])
        self.assertIn("target consent", report["note"])

    def test_shell_metacharacters_are_quoted_and_data_uses_stdin(self):
        with patch("harness.apk_control.subprocess.run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, "ok", "")
            Adb("adb", "emulator-5554").shell("run-as", PACKAGE, "sh", "-c", "cat > files/input", data="$(touch /bad)")
            argv = run.call_args.args[0]
            self.assertEqual(argv[-1], "run-as app.ballistic.assistant sh -c 'cat > files/input'")
            self.assertNotIn("$(touch", " ".join(argv))
            self.assertEqual(run.call_args.kwargs["input"], "$(touch /bad)")

    def test_timeout_stops_test_app_without_retry(self):
        device = FakeAdb()
        original = device.shell
        mutations = []
        def shell(*args, **kwargs):
            if args[0] in ("run-as", "am"):
                mutations.append(args)
                if args[:2] == ("am", "instrument"):
                    raise subprocess.TimeoutExpired("instrument", 120)
                return ""
            return original(*args, **kwargs)
        device.shell = shell
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(RuntimeError, "effects are unknown"):
                run_goal(device, "open Clock", 30, Path(directory))
        self.assertEqual(sum(c[:2] == ("am", "instrument") for c in mutations), 1)
        self.assertEqual(mutations[-1], ("am", "force-stop", PACKAGE))


if __name__ == "__main__":
    unittest.main()
