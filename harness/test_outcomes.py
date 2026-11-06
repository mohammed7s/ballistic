import unittest
from unittest.mock import Mock
from outcomes import evaluate_benchmark, combined_verdict


class OutcomeTests(unittest.TestCase):
    def test_state_success_without_agent_done_is_preserved(self):
        task = Mock(); task.is_successful.return_value = 1
        result = evaluate_benchmark(task, 'env', False)
        task.is_successful.assert_called_once_with('env')
        self.assertTrue(result['benchmark_success'])
        self.assertFalse(result['combined_success'])
        self.assertEqual(combined_verdict(result), 'FAIL')

    def test_false_completion_is_independent(self):
        task = Mock(); task.is_successful.return_value = 0
        result = evaluate_benchmark(task, 'env', True)
        self.assertTrue(result['agent_reported_done'])
        self.assertFalse(result['benchmark_success'])

    def test_checker_error_is_unknown_not_model_failure(self):
        task = Mock(); task.is_successful.side_effect = RuntimeError('checker disconnected')
        result = evaluate_benchmark(task, None, False)
        self.assertIsNone(result['benchmark_success'])
        self.assertIsNone(result['combined_success'])
        self.assertEqual(combined_verdict(result), 'ERROR')
        self.assertIn('checker disconnected', result['benchmark_error'])

    def test_free_prompt_has_no_benchmark(self):
        result = evaluate_benchmark(None, None, True)
        self.assertIsNone(result['benchmark_success'])
        self.assertEqual(combined_verdict(result), 'DONE')


if __name__ == '__main__':
    unittest.main()
