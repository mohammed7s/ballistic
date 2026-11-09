"""Answer delivery to AndroidWorld's answer checkers (harness only, outcomes.py)."""
import unittest
from unittest.mock import Mock

from outcomes import (answer_for_checker, combined_verdict, deliver_answer, evaluate_benchmark,
                      failure_category, task_scores_answer)


class FakeEnv:
    """Records ANSWER actions the way AndroidWorld's env would: execute_action sets the cache."""
    def __init__(self):
        self.actions = []
        self.interaction_cache = ''

    def execute_action(self, action):
        self.actions.append(action)
        self.interaction_cache = action['text']


def make_action(text):
    return {'action_type': 'answer', 'text': text}


def answered(text='March 3 2026 14:30', display=None, basis='answer_values_sourced'):
    return {'done': False, 'task_status': 'pending',
            'reason': display or f'{text} (from app:com.example)',
            'verification': {'status': 'unknown', 'evidence_basis': basis, 'nodes': []},
            'answer': {'text': text, 'display': display or f'{text} (from app:com.example)', 'values': []}}


def checker(result):
    task = Mock(); task.is_successful.return_value = result
    return task


ANSWER_TASK = lambda task: True   # noqa: E731  stands in for an InformationRetrieval task
STATE_TASK = lambda task: False   # noqa: E731


class AnswerDeliveryTests(unittest.TestCase):
    def test_raw_answer_text_is_delivered_never_the_display_string(self):
        env = FakeEnv()
        report = answered('Tuesday, 3 pm', display='Tuesday, 3 pm (from app:com.simplemobiletools.calendar.pro)')
        delivery = deliver_answer(env, report, make_action)
        self.assertEqual(delivery, {'delivered': True, 'source': 'answer_text', 'chars': len('Tuesday, 3 pm')})
        self.assertEqual(env.interaction_cache, 'Tuesday, 3 pm')
        self.assertNotIn('(from', env.interaction_cache)
        self.assertEqual(len(env.actions), 1)

    def test_no_delivery_without_a_sourced_answer(self):
        for report in [answered(basis='model_assessment'), answered(basis='none'), {'done': True}, None,
                       {'answer': {'text': '   '}, 'verification': {'evidence_basis': 'answer_values_sourced'}}]:
            env = FakeEnv()
            self.assertEqual(deliver_answer(env, report, make_action)['delivered'], False, report)
            self.assertEqual(env.interaction_cache, '')
            self.assertEqual(env.actions, [])

    def test_owner_excerpts_are_joined_with_comma_space(self):
        report = {'done': False, 'verification': {'evidence_basis': 'model_selected_source_excerpt'},
                  'owner_output': {'status': 'available', 'items': [
                      {'ref': 'c1', 'text': 'Team sync', 'node': 4, 'source': 'x', 'evidence': 'Team sync at 10'},
                      {'ref': 'c2', 'text': ' Dentist ', 'node': 7, 'source': 'x', 'evidence': 'Dentist'}]}}
        self.assertEqual(answer_for_checker(report), ('Team sync, Dentist', 'owner_excerpts'))
        report['owner_output']['status'] = 'stale'
        self.assertEqual(answer_for_checker(report), (None, None))

    def test_fallback_sets_the_cache_when_env_has_no_execute_action(self):
        class Bare:
            interaction_cache = ''
        env = Bare()
        self.assertTrue(deliver_answer(env, answered('42'), make_action)['delivered'])
        self.assertEqual(env.interaction_cache, '42')

    def test_answer_task_delivered_and_checker_passes_is_pass(self):
        result = evaluate_benchmark(checker(1), FakeEnv(), False, answer_delivered=True, scores_answer=ANSWER_TASK)
        self.assertEqual(combined_verdict(result), 'PASS')
        self.assertFalse(result['agent_reported_done'])
        self.assertEqual(result['agent_complete_basis'], 'answer')
        self.assertTrue(result['answer_scored_by_checker'])
        self.assertIsNone(failure_category({'done': False, 'finished': {'report': answered()}}, result, []))

    def test_answer_task_delivered_and_checker_fails_is_fail(self):
        result = evaluate_benchmark(checker(0), FakeEnv(), False, answer_delivered=True, scores_answer=ANSWER_TASK)
        self.assertEqual(combined_verdict(result), 'FAIL')
        self.assertEqual(failure_category({'done': False, 'finished': {'report': answered()}}, result, []), 'answer')

    def test_state_task_still_needs_done(self):
        result = evaluate_benchmark(checker(1), FakeEnv(), False, answer_delivered=True, scores_answer=STATE_TASK)
        self.assertEqual(combined_verdict(result), 'FAIL')
        self.assertFalse(result['answer_scored_by_checker'])
        self.assertIsNone(result['agent_complete_basis'])
        self.assertEqual(failure_category({'done': False, 'finished': {'report': {}}}, result, []), 'verification')
        done = evaluate_benchmark(checker(1), FakeEnv(), True, answer_delivered=True, scores_answer=STATE_TASK)
        self.assertEqual(combined_verdict(done), 'PASS')
        self.assertEqual(done['agent_complete_basis'], 'done')

    def test_undelivered_answer_on_an_answer_task_is_not_complete(self):
        result = evaluate_benchmark(checker(1), FakeEnv(), False, answer_delivered=False, scores_answer=ANSWER_TASK)
        self.assertEqual(combined_verdict(result), 'FAIL')

    def test_without_android_world_no_task_scores_an_answer(self):
        try:
            import android_world  # noqa: F401
        except ImportError:
            self.assertFalse(task_scores_answer(Mock()))
        self.assertFalse(task_scores_answer(None))


if __name__ == '__main__':
    unittest.main()
