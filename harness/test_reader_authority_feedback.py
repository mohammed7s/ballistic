"""Fresh binary tests. All model replies and host operations are scripted offline."""
import json
import os
import subprocess
import unittest
from unittest.mock import Mock, patch

from compare_reader_authority import CASES, check_feedback, correction, run_script
from core_bridge import CoreHost, ModelClient
from test_core_bridge_robustness import ok


@unittest.skipUnless(os.environ.get('BALLISTIC_TEST_CORE_BIN'), 'requires fresh offline core binary')
class ReaderAuthorityFeedbackTest(unittest.TestCase):
    def test_frozen_scripted_admission_and_budget_cases(self):
        for case in json.loads(CASES.read_text()):
            with self.subTest(case=case['name']):
                calls = run_script(os.environ['BALLISTIC_TEST_CORE_BIN'], case)
                check_feedback(calls, case, True)

    @patch('requests.post')
    def test_real_host_and_model_serialization_keep_correction_bounded(self, post):
        for name in ['fresh_to_tap', 'repeat_long', 'repeat_read_stop', 'grant_denial', 'confirmation_denied']:
            with self.subTest(case=name):
                case = next(c for c in json.loads(CASES.read_text()) if c['name'] == name)
                post.reset_mock()
                post.side_effect = [ok(r['text']) for r in case['replies']
                                    if isinstance(r, dict) and 'text' in r]
                client = ModelClient({role: ('https://offline.invalid/v1', 'scripted', 'unused')
                                      for role in ['planner', 'reader']})
                host = CoreHost(Mock(), client, confirm_policy='deny')
                host.observe = Mock(return_value=case['replies'][0])
                host.act = Mock(return_value={'result': 'ok'})
                with subprocess.Popen([os.environ['BALLISTIC_TEST_CORE_BIN'], *case['args']],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as proc:
                    result = host.serve(proc)
                    proc.stdin.close()
                    proc.wait(timeout=10)
                    self.assertEqual(proc.returncode, 0)
                self.assertFalse(result['report']['done'])
                self.assertEqual(host.act.call_count, case['acts'])
                self.assertEqual(host.observe.call_count, 1)
                self.assertEqual(len(host.confirmations), int(name == 'confirmation_denied'))
                self.assertEqual(post.call_count, 4)
                self.assertEqual([c['role'] for c in client.calls], ['planner', 'reader', 'planner', 'planner'])
                self.assertTrue(all(not c.get('retried') for c in client.calls))
                payloads = [c.kwargs['json'] for c in post.call_args_list]
                self.assertEqual([p['max_tokens'] for p in payloads], [800, 64, 800, 800])
                self.assertEqual(payloads[-1]['messages'][-1]['content'], correction(2, True))
                private = case['replies'][0]['nodes'][1]['text']
                self.assertIn(private, json.dumps(payloads[1]))
                for i in [0, 2, 3]:
                    self.assertNotIn(private, json.dumps(payloads[i]))
