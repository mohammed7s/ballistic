"""Text-only model ports: prepared HTTP requests and synthetic responses, no live model."""
import json
import os
import subprocess
import unittest
from unittest.mock import Mock, patch

from core_bridge import CoreHost, ModelClient, ModelResponseError
from test_core_bridge_robustness import ok, response


ROLES = ('planner', 'reader', 'judge', 'verifier')
TEXTS = ('{"action":"home"}', 'e7', '{"action":"wait"}',
         '{"status":"unknown","nodes":[],"evidence_basis":"visible_ui"}')


def native_response(text=None, field='tool_calls', finish='tool_calls'):
    message = {} if text is None else {'content': text}
    if field:
        message[field] = [{'name': 'PRIVATE_TOOL', 'arguments': 'PRIVATE_ARGS'}]
    return response(200, json.dumps({'choices': [{'message': message, 'finish_reason': finish}],
                                    'unknown': 'PRIVATE_ENVELOPE'}))


class TextContractTest(unittest.TestCase):
    def client(self, **kwargs):
        return ModelClient({'planner': ('https://fixture.invalid/v1', 'same-model', 'fake-key')}, **kwargs)

    @patch('requests.sessions.Session.send')
    def test_serialized_requests_for_every_role_default_and_opt_in(self, send):
        for enabled in (False, True):
            client = self.client(disable_native_tools=enabled)
            for role, text in zip(ROLES, TEXTS):
                with self.subTest(enabled=enabled, role=role):
                    send.return_value = ok(text)
                    messages = [{'role': 'user', 'content': 'fixture'}]
                    self.assertEqual(client.complete(role, messages, 137)['text'], text)
                    request = send.call_args.args[0]
                    expected = {'model': 'same-model', 'messages': messages,
                                'temperature': 0.0, 'max_tokens': 137}
                    if enabled:
                        expected['tool_choice'] = 'none'
                    self.assertEqual(json.loads(request.body), expected)
                    self.assertEqual(request.url, 'https://fixture.invalid/v1/chat/completions')
                    self.assertEqual(request.headers['Authorization'], 'Bearer fake-key')

    def test_option_is_boolean_only(self):
        for value in ('none', 'false', 1, None):
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.client(disable_native_tools=value)

    @patch('requests.sessions.Session.send')
    def test_default_omits_option_and_empty_signals_preserve_text(self, send):
        send.return_value = response(200, json.dumps({'choices': [{
            'message': {'content': 'e7', 'tool_calls': [], 'function_call': None,
                        'refusal': None, 'unknown': 'PRIVATE'}, 'finish_reason': 'PRIVATE_REASON'}]}))
        client = self.client()
        self.assertEqual(client.complete('reader', [], 137)['text'], 'e7')
        self.assertNotIn('tool_choice', json.loads(send.call_args.args[0].body))
        self.assertNotIn('PRIVATE', repr(client.calls))

    @patch('core_bridge.time.sleep')
    @patch('requests.post')
    def test_native_and_mixed_responses_are_terminal_and_secret_safe(self, post, sleep):
        for role, text in zip(ROLES, TEXTS):
            for field, finish in [('tool_calls', 'tool_calls'), ('function_call', 'stop'),
                                  ('refusal', 'stop'), (None, 'content_filter'),
                                  (None, 'tool_calls'), (None, 'function_call'),
                                  ('tool_calls', 'PRIVATE_REASON')]:
                for content in (None, text):
                    with self.subTest(role=role, field=field, finish=finish, content=content):
                        post.reset_mock()
                        client = self.client()
                        post.side_effect = [native_response(content, field, finish), ok(text)]
                        with self.assertRaises(ModelResponseError) as error:
                            client.complete(role, [], 137)
                        self.assertEqual(post.call_count, 1)
                        self.assertNotIn('PRIVATE', str(error.exception) + repr(client.calls))
                        self.assertNotIn('retried', client.calls[0])
                        if field:
                            self.assertTrue(client.calls[0][field + '_present'])
        sleep.assert_not_called()

    @patch('requests.post')
    def test_opt_in_http_rejection_does_not_fallback(self, post):
        import requests
        post.side_effect = [response(400, 'PRIVATE invalid tool_choice'), ok('e7')]
        client = self.client(disable_native_tools=True)
        with self.assertRaises(requests.HTTPError):
            client.complete('reader', [], 137)
        self.assertEqual(post.call_count, 1)
        self.assertNotIn('PRIVATE', repr(client.calls))

    @unittest.skipUnless(os.environ.get('BALLISTIC_TEST_CORE_BIN'), 'requires offline core binary')
    @patch('requests.post')
    def test_native_response_never_dispatches_or_redispatches_hands(self, post):
        screen = {'package': 'org.fixture.dispatch', 'activity': 'Compose', 'width': 320, 'height': 640,
                  'capabilities': {'operations': ['tap'], 'app_grants': ['org.fixture.dispatch']},
                  'nodes': [{'id': 11, 'role': 'button', 'label': 'Send',
                             'bounds': {'x1': 20, 'y1': 420, 'x2': 90, 'y2': 460},
                             'flags': {'enabled': True, 'visible': True, 'clickable': True}}]}
        for after_action in (False, True):
            for mixed in (False, True):
                with self.subTest(after_action=after_action, mixed=mixed):
                    client = self.client()
                    post.reset_mock()
                    prefix = [ok('{"action":"tap","target":"e1"}'),
                              ok('{"action":"done","evidence":"acknowledged"}')] if after_action else []
                    text = TEXTS[3] if after_action else '{"action":"tap","target":"e1"}'
                    post.side_effect = prefix + [native_response(text if mixed else None)]
                    host = CoreHost(Mock(), client, confirm_policy='approve')
                    host.observe = Mock(return_value=screen)
                    host.act = Mock(return_value={'result': 'ok'})
                    with subprocess.Popen([os.environ['BALLISTIC_TEST_CORE_BIN'], 'run', '--request',
                                           'Send the prepared message', '--max-steps', '2'],
                                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as proc:
                        result = host.serve(proc)
                        proc.stdin.close()
                        proc.wait(timeout=10)
                        self.assertEqual(proc.returncode, 1)
                    self.assertIn('ModelResponseError', result['error'])
                    self.assertEqual(post.call_count, len(prefix) + 1)
                    self.assertEqual(host.act.call_count, int(after_action))
                    self.assertEqual(len(host.confirmations), int(after_action))
