"""Frozen protocol cases in docs/log/2026-11-07-reliability.md; no network or device."""
import json
import os
import subprocess
import unittest
from unittest.mock import Mock, patch

import requests

from core_bridge import CoreHost, ModelClient, ModelResponseError
from test_core_bridge_robustness import ok, response


def empty(message=None, finish='stop', usage=None):
    return response(200, json.dumps({'choices': [{'message': {} if message is None else message,
                                                'finish_reason': finish}], 'usage': usage}))


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.post = patch('requests.post').start()
        self.sleep = patch('core_bridge.time.sleep').start()
        self.addCleanup(patch.stopall)
        self.client = ModelClient({'planner': ('https://fixture.invalid/v1', 'unchanged-model', 'test-secret')})

    def complete(self, role='planner'):
        return self.client.complete(role, [{'role': 'user', 'content': 'fixture request'}], 137)

    def test_p1_reader_null_recovers_once_with_safe_attempt_usage(self):
        self.post.side_effect = [empty({'content': None}, usage={'prompt_tokens': 19, 'completion_tokens': 8}), ok('e7')]
        self.assertEqual(self.complete('reader')['text'], 'e7')
        self.assertEqual(self.post.call_count, 2)
        retry = self.client.calls[0]['retried'][0]
        self.assertEqual(retry['response_shape'], 'null_content')
        self.assertEqual((retry['prompt_tokens'], retry['completion_tokens']), (19, 8))
        self.assertNotIn('test-secret', repr(self.client.calls))

    def test_p2_verifier_blank_recovers_failed_without_changing_request(self):
        verdict = '{"status":"failed","nodes":["e3"],"evidence_basis":"visible_ui"}'
        self.post.side_effect = [empty({'content': ' \n\t'}, finish=None), ok(verdict)]
        self.assertEqual(self.complete('verifier')['text'], verdict)
        self.assertEqual(self.post.call_count, 2)
        self.assertEqual(self.post.call_args_list[0], self.post.call_args_list[1])
        self.assertEqual(self.post.call_args.kwargs['json']['max_tokens'], 137)

    def test_p3_repeated_absence_stops_after_two_requests(self):
        self.post.side_effect = [empty(), empty(), ok()]
        with self.assertRaisesRegex(ModelResponseError, '^missing_content$'):
            self.complete()
        self.assertEqual(self.post.call_count, 2)
        self.assertEqual(len(self.client.calls), 1)
        self.assertEqual(len(self.client.calls[0]['retried']), 1)

    def test_p4_transport_and_content_share_the_total_budget(self):
        self.post.side_effect = [response(503, 'private provider details'), empty(), ok()]
        self.complete()
        self.assertEqual(self.post.call_count, 3)
        self.assertEqual(len(self.client.calls[0]['retried']), 2)
        self.assertNotIn('private provider details', repr(self.client.calls))
        self.client.retries = 1
        self.post.reset_mock()
        self.post.side_effect = [response(503, 'unavailable'), empty(), ok()]
        with self.assertRaises(ModelResponseError):
            self.complete()
        self.assertEqual(self.post.call_count, 2)

    def test_p5_recovery_does_not_retry_or_rotate_after_quota(self):
        self.post.side_effect = [empty(), response(429, 'GenerateRequestsPerDay test-secret'), ok()]
        with self.assertRaises(requests.HTTPError):
            self.complete()
        self.assertEqual(self.post.call_count, 2)
        self.assertEqual(self.client.calls[-1]['quota_category'], 'daily')
        self.assertEqual(self.post.call_args_list[0], self.post.call_args_list[1])
        self.assertNotIn('test-secret', repr(self.client.calls))

    def test_p6_refusal_tool_calls_and_terminal_finish_reasons_never_retry(self):
        cases = [({'content': None}, finish) for finish in
                 ['length', 'content_filter', 'tool_calls', 'function_call', 'private-provider-reason']]
        cases += [({'content': None, field: value}, 'stop') for field, value in
                  [('refusal', 'private-refusal'), ('tool_calls', [{'name': 'private-tool'}]),
                   ('function_call', {'name': 'private-tool'})]]
        for message, finish in cases:
            with self.subTest(message=message, finish=finish):
                self.post.reset_mock()
                self.post.side_effect = [empty(message, finish), ok()]
                with self.assertRaises(ModelResponseError):
                    self.complete()
                self.assertEqual(self.post.call_count, 1)
                self.assertNotIn('private-', repr(self.client.calls))

    def test_p7_invalid_shapes_are_terminal_and_unknown_usage_stays_absent(self):
        for body in [[], {}, {'choices': []}, {'choices': [None]},
                     {'choices': [{'message': {'content': ['private']}}]},
                     {'choices': [{'message': {}}, {'message': {}}]}]:
            with self.subTest(body=body):
                self.post.reset_mock()
                self.post.side_effect = [response(200, json.dumps(body)), ok()]
                with self.assertRaises(ModelResponseError):
                    self.complete()
                self.assertEqual(self.post.call_count, 1)
        self.post.side_effect = [empty(), ok()]
        self.complete()
        retry = self.client.calls[-1]['retried'][0]
        self.assertNotIn('prompt_tokens', retry)
        self.assertNotIn('completion_tokens', retry)

    def test_provider_finish_diagnostics_do_not_expand_retry_or_leak_payloads(self):
        for finish in ModelClient.PROVIDER_FINISH_REASONS:
            with self.subTest(finish=finish):
                self.post.reset_mock()
                self.post.side_effect = [empty({'tool_calls': [{'name': 'private-tool-secret'}]}, finish), ok()]
                with self.assertRaises(ModelResponseError):
                    self.complete()
                self.assertEqual(self.post.call_count, 1)
                call = self.client.calls[-1]
                self.assertEqual(call['finish_reason'], finish)
                self.assertTrue(call['tool_calls_present'])
                self.assertFalse(call['refusal_present'])
                self.assertFalse(call['function_call_present'])
                self.assertNotIn('private-tool-secret', repr(call))
        # Even an otherwise empty provider-native STOP is still outside existing retry rules.
        self.post.reset_mock()
        self.post.side_effect = [empty(finish='STOP'), ok()]
        with self.assertRaises(ModelResponseError):
            self.complete()
        self.assertEqual(self.post.call_count, 1)

    @unittest.skipUnless(os.environ.get('BALLISTIC_TEST_CORE_BIN'), 'set BALLISTIC_TEST_CORE_BIN for offline bridge integration')
    def test_p8_recovery_or_exhaustion_after_guarded_input_never_redispatches(self):
        screen = {'package': 'org.fixture.dispatch', 'activity': 'Compose', 'width': 320, 'height': 640,
                  'capabilities': {'operations': ['tap'], 'app_grants': ['org.fixture.dispatch']},
                  'nodes': [{'id': 11, 'role': 'button', 'label': 'Send',
                             'bounds': {'x1': 20, 'y1': 420, 'x2': 90, 'y2': 460},
                             'flags': {'enabled': True, 'visible': True, 'clickable': True}}]}
        for recover in [True, False]:
            with self.subTest(recover=recover):
                self.post.side_effect = [ok('{"action":"tap","target":"e1"}'),
                                        ok('{"action":"done","evidence":"acknowledged"}'), empty(),
                                        ok('{"status":"unknown","nodes":[],"evidence_basis":"visible_ui"}')
                                        if recover else empty()]
                host = CoreHost(Mock(), self.client, confirm_policy='approve')
                host.observe = Mock(return_value=screen)
                host.act = Mock(return_value={'result': 'ok'})
                with subprocess.Popen([os.environ['BALLISTIC_TEST_CORE_BIN'], 'run', '--request',
                                       'Send the prepared message', '--max-steps', '2'],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as proc:
                    result = host.serve(proc)
                    proc.stdin.close()
                    proc.wait(timeout=10)
                    self.assertEqual(proc.returncode, 0 if recover else 1)
                self.assertEqual(host.act.call_count, 1)
                self.assertEqual(len(host.confirmations), 1)
                if recover:
                    self.assertFalse(result['report']['done'])
                    self.assertEqual(result['report']['verification']['status'], 'unknown')
                    self.assertEqual(result['report']['execution_status'], 'executed')
                else:
                    self.assertIn('ModelResponseError', result['error'])


if __name__ == '__main__':
    unittest.main()
