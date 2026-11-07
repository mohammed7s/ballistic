import json
import io
import unittest
from unittest.mock import patch
from suite_row import main, summarize


class SuiteRowTests(unittest.TestCase):
    def row(self, record):
        with patch('suite_row.Path.read_text', side_effect=['written /runs/task.json\n', json.dumps(record)]):
            return summarize('Task', '/runs/task.log', 0)

    def test_state_success_without_done_is_not_lost(self):
        row = self.row({'benchmark_status': 'passed', 'done': False, 'success': False})
        self.assertEqual(row['result'], 'FAIL')
        self.assertEqual(row['benchmark'], 'passed')
        self.assertEqual(row['agent_done'], 'false')

    def test_core_crash_is_environment_error(self):
        row = self.row({'benchmark_status': 'failed', 'exit_code': 1, 'success': False})
        self.assertEqual(row['result'], 'ERROR')
        self.assertEqual(row['failure_category'], 'environment')

    def test_missing_record_is_error(self):
        with patch('suite_row.Path.read_text', return_value='initialization failed\n'):
            row = summarize('Task', '/runs/task.log', 1)
        self.assertEqual(row['result'], 'ERROR')

    def test_quota_stops_suite_without_erasing_independent_success(self):
        record = {'benchmark_status': 'passed', 'success': False, 'done': False,
                  'model_calls': [{'http_status': 429, 'quota_category': 'rate'}]}
        row = self.row(record)
        self.assertTrue(row['quota_limited'])
        self.assertEqual(row['result'], 'ERROR')
        self.assertEqual(row['benchmark'], 'passed')
        with patch('suite_row.summarize', return_value=row), patch('sys.stdout', new_callable=io.StringIO) as output, \
                patch('sys.argv', ['suite_row.py', '--task', 'Task', '--log', '/run.log', '--exit', '0', '--stop-on-quota']):
            with self.assertRaises(SystemExit) as stopped:
                main()
        self.assertEqual(stopped.exception.code, 75)
        self.assertIn('ERROR', output.getvalue())

    def test_nonquota_failure_does_not_stop_suite(self):
        row = self.row({'benchmark_status': 'failed', 'success': False,
                        'model_calls': [{'http_status': 503}]})
        self.assertFalse(row['quota_limited'])
        with patch('suite_row.summarize', return_value=row), patch('sys.stdout', new_callable=io.StringIO), \
                patch('sys.argv', ['suite_row.py', '--task', 'Task', '--log', '/run.log', '--exit', '0', '--stop-on-quota']):
            main()


if __name__ == '__main__':
    unittest.main()
