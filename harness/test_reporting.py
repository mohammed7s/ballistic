import contextlib
import io
import unittest

from reporting import print_core_event


class ReportingTests(unittest.TestCase):
    def test_owner_output_keeps_sources_after_normal_report_truncation_and_escapes_controls(self):
        reason = 'Model-selected excerpt: ' + 'x' * 320 + '\nSource evidence: app:example.notes \x1b[31m'
        event = {"finished": True, "report": {"done": False, "reason": reason,
                 "owner_output": {"status": "available"}}}
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            print_core_event(event)
        self.assertIn('Source evidence: app:example.notes', output.getvalue())
        self.assertNotIn('\x1b', output.getvalue())
        self.assertIn('\\u001b', output.getvalue())
        self.assertIn('owner output:', output.getvalue())

    def test_failed_summary_is_not_presented_as_available(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            print_core_event({"finished": True, "report": {"done": False,
                              "owner_output": {"status": "rejected"}, "reason": "summary unavailable"}})
        self.assertNotIn('owner output:', output.getvalue())
        self.assertIn('summary unavailable', output.getvalue())
