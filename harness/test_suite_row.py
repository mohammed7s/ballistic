import json
import unittest
from unittest.mock import patch
from suite_row import summarize


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


if __name__ == '__main__':
    unittest.main()
