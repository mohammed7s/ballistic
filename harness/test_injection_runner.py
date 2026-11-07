"""Offline runner orchestration: fake live modules, host and environment; no device/model."""
import builtins
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import run_injection


class InjectionRunnerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.lock = self.root / "emulator.lock"
        self.events = []
        self.clients = []
        self.status = 429
        self.category = "rate"
        self.raise_from_run = False
        self.output = io.StringIO()
        self.flags = Mock(console_port=5554, core_bin="fake-core", out_dir=str(self.root),
                          model="fake-model", base_url="https://fixture.invalid", adb_path="unused",
                          core_model_min_interval=5.0)
        self.env = Mock()
        self.env.reset.side_effect = lambda **kwargs: self.events.append("reset")
        self.env.close.side_effect = lambda: self.events.append("close")
        self.setup = SimpleNamespace(
            reset=Mock(), run_setup=Mock(return_value=[]), setup_problems=Mock(side_effect=lambda *args: []),
            state_after=Mock(side_effect=self.measure),
            teardown=Mock(side_effect=lambda env, case: self.events.append("teardown")))
        self.host_factory = Mock(side_effect=lambda env, models, policy, callback: SimpleNamespace(
            models=models, confirm_policy=policy, events=[], lines=[], confirmations=[]))
        self.task = SimpleNamespace(FLAGS=self.flags, core_models=Mock(side_effect=self.make_client),
                                    print_core_event=Mock(), serve_core=Mock(side_effect=self.serve))
        self.launcher = SimpleNamespace(load_and_setup_env=Mock(return_value=self.env))
        modules = {"run_task": self.task, "core_bridge": SimpleNamespace(CoreHost=self.host_factory),
                   "injection_setup": self.setup, "android_world": SimpleNamespace(),
                   "android_world.env": SimpleNamespace(env_launcher=self.launcher)}
        for patcher in (patch.dict(sys.modules, modules),
                        patch.object(run_injection, "LOCK", str(self.lock)),
                        patch.object(run_injection, "emulator_free", return_value=None),
                        patch.object(run_injection, "core_commit", return_value="offline")):
            patcher.start()
            self.addCleanup(patcher.stop)

    def make_client(self):
        client = SimpleNamespace(routes={"planner": (self.flags.base_url, self.flags.model,
                                                     "SECRET_TEST_KEY")},
                                 min_interval_seconds=self.flags.core_model_min_interval, calls=[])
        self.clients.append(client)
        return client

    def serve(self, host, args):
        self.assertTrue(self.lock.exists())
        self.assertIs(host.models, self.clients[-1])
        self.assertEqual(host.models.min_interval_seconds, 5.0)
        self.assertEqual(host.confirm_policy, "deny")
        self.events.append("serve")
        call = {"http_status": self.status, "pacing_seconds": 4.8}
        if self.category is not None:
            call["quota_category"] = self.category
        host.models.calls.append(call)
        if self.raise_from_run:
            raise RuntimeError("RAW_PROVIDER_ERROR SECRET_TEST_KEY project/private")
        return {"finished": {"error": "HTTPError"}, "done": False,
                "exit_code": 1, "stderr": "", "total_seconds": 0}

    def measure(self, env, case):
        self.assertIs(env, self.env)
        self.events.append("measure")
        return [{"check": c["check"], "args": c.get("args"), "ok": True, "measured": True}
                for c in case["expect"]["state_after"]]

    def run_cases(self):
        with contextlib.redirect_stdout(self.output):
            return run_injection.live_run(SimpleNamespace(cases=["01", "02"]),
                                          ["--core_model_min_interval", "5"])

    def test_every_quota_category_stops_after_record_and_cleanup(self):
        # Missing classification and both host return/raise paths must stop too.
        for category in ("rate", "daily", "mixed", "unknown", None):
            for raises in (False, True):
                with self.subTest(category=category, raises=raises):
                    self.category, self.raise_from_run = category, raises
                    self.events.clear()
                    self.clients.clear()
                    self.task.core_models.reset_mock()
                    self.assertEqual(self.run_cases(), 75)
                    self.assertEqual(self.events, ["reset", "serve", "measure", "teardown", "close"])
                    self.task.core_models.assert_called_once_with()
                    self.assertFalse(self.lock.exists())
                    paths = list(self.root.glob("injection_*/*.json"))
                    self.assertEqual(len(paths), 1)
                    record = json.loads(paths[0].read_text())
                    self.assertEqual(record["case"], "01_sms_instruction")
                    self.assertEqual(record["core_model_min_interval"], 5.0)
                    self.assertEqual(record["model_calls"], self.clients[0].calls)
                    self.assertTrue(record["state_after"])
                    self.assertEqual(record["confirm"], "deny")
                    self.assertEqual(record["evaluation"]["result"], "ERROR")
                    tsvs = list(self.root.glob("injection_*.tsv"))
                    self.assertEqual(len(tsvs), 1)
                    self.assertEqual(len(tsvs[0].read_text().splitlines()), 2)
                    text = paths[0].read_text() + tsvs[0].read_text() + self.output.getvalue()
                    for secret in ("SECRET_TEST_KEY", "RAW_PROVIDER_ERROR", "project/private"):
                        self.assertNotIn(secret, text)
                    for path in (paths[0], tsvs[0]):
                        path.unlink()
        self.flags.assert_called_with(["run_task", "--core_model_min_interval", "5",
                                       "--agent", "core", "--confirm", "deny"])

    def test_non_quota_failure_continues_with_per_case_records(self):
        self.status, self.category = 503, None
        self.assertEqual(self.run_cases(), 1)
        self.assertEqual(self.events, ["reset", "serve", "measure", "teardown"] * 2 + ["close"])
        self.assertEqual(len(list(self.root.glob("injection_*/*.json"))), 2)
        self.assertFalse(self.lock.exists())

    def test_record_failure_still_closes_environment_and_releases_lock(self):
        with patch.object(run_injection.json, "dump", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                self.run_cases()
        self.assertEqual(self.events, ["reset", "serve", "measure", "teardown", "close"])
        self.assertFalse(self.lock.exists())


class InjectionDryRunTest(unittest.TestCase):
    def test_import_and_dry_run_never_import_live_dependencies(self):
        real_import = builtins.__import__

        def offline_import(name, *args, **kwargs):
            if name.split(".")[0] in {"requests", "run_task", "core_bridge", "injection_setup", "android_world"}:
                raise AssertionError(f"dry run imported {name}")
            return real_import(name, *args, **kwargs)

        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / "record.json"
            record.write_text(json.dumps({"case": "01_sms_instruction", "lines": []}))
            spec = importlib.util.spec_from_file_location("offline_injection", run_injection.__file__)
            module = importlib.util.module_from_spec(spec)
            with patch("builtins.__import__", side_effect=offline_import), \
                 patch.object(sys, "argv", ["run_injection", "--dry-run", str(record)]), \
                 contextlib.redirect_stdout(io.StringIO()):
                spec.loader.exec_module(module)
                self.assertEqual(module.main(), 1)


if __name__ == "__main__":
    unittest.main()
