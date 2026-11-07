"""Offline real-core/host/ModelClient coverage; HTTP and screen ports are scripted."""
import json
import os
import subprocess
import unittest
from unittest.mock import Mock, patch

from core_bridge import CoreHost, ModelClient
from test_core_bridge_robustness import ok


@unittest.skipUnless(os.environ.get('BALLISTIC_TEST_CORE_BIN'), 'requires fresh offline core binary')
class ReaderCorrectionBridgeTest(unittest.TestCase):
    @patch('requests.post')
    def test_duplicate_correction_and_hard_stop_through_host_protocol(self, post):
        read = json.dumps({'action': 'read', 'targets': ['c1'],
                           'question': 'Which entry?', 'shape': 'element'})
        secret = 'PRIVATE_SOURCE_IN_READER_ONLY'
        screen = {'package': 'example.library', 'activity': 'Main', 'width': 400, 'height': 800,
                  'capabilities': {'operations': ['tap'], 'app_grants': ['example.library']},
                  'nodes': [
                      {'id': 1, 'role': 'button', 'label': 'Browse',
                       'bounds': {'x1': 0, 'y1': 0, 'x2': 400, 'y2': 80},
                       'flags': {'enabled': True, 'visible': True, 'clickable': True}},
                      {'id': 2, 'role': 'list',
                       'bounds': {'x1': 0, 'y1': 80, 'x2': 400, 'y2': 700},
                       'flags': {'enabled': True, 'visible': True, 'scrollable': True}},
                      {'id': 3, 'parent': 2, 'role': 'item', 'text': secret,
                       'bounds': {'x1': 0, 'y1': 80, 'x2': 400, 'y2': 160},
                       'flags': {'enabled': True, 'visible': True, 'clickable': True}},
                  ]}
        for corrected, expected_acts in [(read, 0), ('{"action":"tap","target":"e1"}', 1),
                                         ('{"action":"tap","target":"e3"}', 0)]:
            with self.subTest(corrected=corrected):
                post.reset_mock()
                post.side_effect = [ok(read), ok('none'), ok(read), ok(corrected)]
                client = ModelClient({'planner': ('https://offline.invalid/v1', 'scripted', 'unused'),
                                      'reader': ('https://offline.invalid/v1', 'scripted', 'unused')})
                host = CoreHost(Mock(), client, confirm_policy='deny')
                host.observe = Mock(return_value=screen)
                host.act = Mock(return_value={'result': 'ok'})
                with subprocess.Popen([os.environ['BALLISTIC_TEST_CORE_BIN'], 'run', '--request',
                                       'Find the requested entry', '--max-steps', '2'],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as proc:
                    result = host.serve(proc)
                    proc.stdin.close()
                    proc.wait(timeout=10)
                    self.assertEqual(proc.returncode, 0)
                self.assertFalse(result['report']['done'])
                self.assertEqual(result['report']['steps'], 2)
                self.assertEqual(host.act.call_count, expected_acts)
                self.assertEqual(host.observe.call_count, 1)
                self.assertEqual(len(host.confirmations), 0)
                self.assertEqual(post.call_count, 4)
                self.assertEqual([c['role'] for c in client.calls], ['planner', 'reader', 'planner', 'planner'])
                self.assertTrue(all(not c.get('retried') for c in client.calls))
                payloads = [c.kwargs['json'] for c in post.call_args_list]
                self.assertEqual([p['max_tokens'] for p in payloads], [800, 64, 800, 800])
                self.assertIn(secret, json.dumps(payloads[1]))
                for i in [0, 2, 3]:
                    self.assertNotIn(secret, json.dumps(payloads[i]))
                feedback = payloads[3]['messages'][-1]['content']
                self.assertIn('No reader call was made.', feedback)
                self.assertIn('Return a corrected JSON action.', feedback)
                if corrected == read:
                    self.assertEqual(result['report']['reason'], 'reader made no progress on unchanged screen; stopped')
                    self.assertEqual(result['report']['task_status'], 'pending')
