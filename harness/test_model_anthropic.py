"""Anthropic provider in ModelClient: a fake SDK module (always) and the real SDK over a mocked
HTTP transport (when `anthropic` is installed). No network, no key, no model."""
import io
import json
import os
import types
import unittest
from types import SimpleNamespace as NS
from unittest.mock import Mock, patch

import core_bridge
from core_bridge import CoreHost, ModelClient, ModelProviderError, ModelResponseError, anthropic_messages
import suite_row
from test_core_bridge_robustness import ok

KEY = 'sk-ant-test-SECRET-KEY'
PRIVATE = 'PRIVATE-provider-text'


class FakeResponse:
    """The parts of an HTTP response that ModelClient._quota_metadata reads."""

    def __init__(self, status, text='', headers=None):
        self.status_code = status
        self.text = text
        self.headers = headers or {}

    def json(self):
        return json.loads(self.text)


def fake_sdk():
    """A stand-in for the `anthropic` module: the real SDK's error hierarchy, names and fields."""
    class AnthropicError(Exception):
        pass

    class APIError(AnthropicError):
        pass

    class APIStatusError(APIError):
        def __init__(self, status, message=PRIVATE, error_type=None, code=None, headers=None):
            super().__init__(f'Error code: {status} - {message} {KEY}')
            self.message = str(self)
            self.status_code = status
            self.api_message = message
            self.type = error_type
            self.error_code = code
            self.response = FakeResponse(status, json.dumps({'error': {'message': message}}), headers)

    class APIConnectionError(APIError):
        pass

    class APITimeoutError(APIConnectionError):
        pass

    sdk = types.SimpleNamespace(AnthropicError=AnthropicError, APIError=APIError,
                                APIStatusError=APIStatusError, APIConnectionError=APIConnectionError,
                                APITimeoutError=APITimeoutError)
    for name, parent in [('BadRequestError', APIStatusError), ('AuthenticationError', APIStatusError),
                         ('PermissionDeniedError', APIStatusError), ('NotFoundError', APIStatusError),
                         ('RateLimitError', APIStatusError), ('InternalServerError', APIStatusError),
                         ('EnforcedSpendLimitReachedError', 'RateLimitError'),
                         ('OverloadedError', 'InternalServerError'),
                         ('ServiceUnavailableError', 'InternalServerError')]:
        base = getattr(sdk, parent) if isinstance(parent, str) else parent
        setattr(sdk, name, type(name, (base,), {}))
    sdk.client = Mock()
    sdk.Anthropic = Mock(return_value=sdk.client)
    return sdk


def text(t):
    return NS(type='text', text=t, citations=None)


def thinking():
    return NS(type='thinking', thinking='', signature='SIG-' + PRIVATE)


def message(blocks, stop='end_turn', stop_details=None, input_tokens=100, output_tokens=20,
            cache_read=0, cache_creation=0, thinking_tokens=12):
    usage = NS(input_tokens=input_tokens, output_tokens=output_tokens,
               cache_read_input_tokens=cache_read, cache_creation_input_tokens=cache_creation,
               output_tokens_details=NS(thinking_tokens=thinking_tokens))
    return NS(content=blocks, stop_reason=stop, stop_details=stop_details, usage=usage)


def answer(t='{"action":"home"}'):
    return message([thinking(), text(t)])


class AnthropicMessagesTest(unittest.TestCase):
    def test_leading_system_becomes_system_and_same_role_turns_merge(self):
        system, turns = anthropic_messages([
            {'role': 'system', 'content': 'rules'},
            {'role': 'user', 'content': 'screen'},
            {'role': 'assistant', 'content': 'bad'},
            {'role': 'user', 'content': 'Invalid action'},
            {'role': 'user', 'content': 'again'},
        ])
        self.assertEqual(system, 'rules')
        self.assertEqual(turns, [{'role': 'user', 'content': 'screen'},
                                 {'role': 'assistant', 'content': 'bad'},
                                 {'role': 'user', 'content': 'Invalid action\n\nagain'}])

    def test_no_system_and_later_system_is_user_text(self):
        system, turns = anthropic_messages([{'role': 'user', 'content': 'a'},
                                            {'role': 'system', 'content': 'b'}])
        self.assertEqual(system, '')
        self.assertEqual(turns, [{'role': 'user', 'content': 'a\n\nb'}])


class AnthropicClientTest(unittest.TestCase):
    def setUp(self):
        self.sdk = fake_sdk()
        self.create = self.sdk.client.messages.create
        patch('core_bridge._anthropic_sdk', return_value=self.sdk).start()
        self.sleep = patch('core_bridge.time.sleep').start()
        self.addCleanup(patch.stopall)
        self.client = ModelClient({'planner': (None, 'claude-haiku-5-5', KEY),
                                   'reader': (None, 'claude-sonnet-5-5', KEY)},
                                  provider='anthropic', effort='low', timeout=120)

    def complete(self, role='planner', max_tokens=800):
        return self.client.complete(role, [{'role': 'system', 'content': 'rules'},
                                           {'role': 'user', 'content': 'screen'}], max_tokens)

    def assert_no_secrets(self, *extra):
        dumped = json.dumps(self.client.calls) + ''.join(str(x) for x in extra)
        self.assertNotIn(KEY, dumped)
        self.assertNotIn(PRIVATE, dumped)

    def test_request_shape_per_role_and_client_options(self):
        self.create.side_effect = [answer('{"action":"home"}'), answer('e7'), answer('{"x":1}')]
        self.assertEqual(self.complete('planner', 800)['text'], '{"action":"home"}')
        self.assertEqual(self.complete('reader', 64)['text'], 'e7')
        self.assertEqual(self.complete('chat', 400)['text'], '{"x":1}')  # no chat route: planner's
        sent = [c.kwargs for c in self.create.call_args_list]
        # Core's cap plus the default thinking headroom (2048): thinking counts toward max_tokens.
        self.assertEqual(sent[0], {'model': 'claude-haiku-5-5', 'max_tokens': 800 + 2048, 'system': 'rules',
                                   'messages': [{'role': 'user', 'content': 'screen'}],
                                   'output_config': {'effort': 'low'}})
        self.assertEqual((sent[1]['model'], sent[1]['max_tokens']), ('claude-sonnet-5-5', 64 + 2048))
        self.assertEqual((sent[2]['model'], sent[2]['max_tokens']), ('claude-haiku-5-5', 400 + 2048))
        self.assertEqual([(c['max_tokens'], c['max_tokens_sent']) for c in self.client.calls],
                         [(800, 2848), (64, 2112), (400, 2448)])
        for kwargs in sent:
            for absent in ('temperature', 'top_p', 'top_k', 'thinking', 'tools', 'tool_choice'):
                self.assertNotIn(absent, kwargs)
        # One SDK client per key/base_url; SDK retries off, so this budget governs retries.
        self.sdk.Anthropic.assert_called_once_with(api_key=KEY, base_url=None, max_retries=0, timeout=120)
        self.assertEqual(self.client.role_models(), {'planner': 'claude-haiku-5-5', 'reader': 'claude-sonnet-5-5'})
        self.assertEqual([c['role'] for c in self.client.calls], ['planner', 'reader', 'chat'])
        self.assert_no_secrets()

    def test_effort_is_sent_and_validated(self):
        self.client.effort = 'high'
        self.create.return_value = answer()
        self.complete()
        self.assertEqual(self.create.call_args.kwargs['output_config'], {'effort': 'high'})
        for bad in ('max', 'xhigh', 'none'):
            with self.subTest(effort=bad), self.assertRaises(ValueError):
                ModelClient(self.client.routes, provider='anthropic', effort=bad)

    def test_thinking_headroom_is_configurable_and_validated(self):
        client = ModelClient(self.client.routes, provider='anthropic', effort='low', thinking_headroom=0)
        self.create.return_value = answer('e1')
        client.complete('reader', [{'role': 'user', 'content': 'screen'}], 64)
        self.assertEqual(self.create.call_args.kwargs['max_tokens'], 64)
        self.assertEqual((client.calls[0]['max_tokens'], client.calls[0]['max_tokens_sent']), (64, 64))
        for bad in (-1, 32001, 1.5, True, '10'):
            with self.subTest(headroom=bad), self.assertRaises(ValueError):
                ModelClient(self.client.routes, provider='anthropic', thinking_headroom=bad)

    def test_text_longer_than_core_cap_is_returned_and_flagged(self):
        # 20 output tokens of which 12 thinking: 8 text tokens against core's cap.
        self.create.side_effect = [answer('e1'), answer('e2')]
        self.complete('reader', 8)
        self.complete('reader', 7)
        self.assertEqual([c['text_over_core_cap'] for c in self.client.calls], [False, True])
        self.create.side_effect = [message([text('e3')], thinking_tokens=None)]  # not reported
        out = self.client.complete('reader', [{'role': 'user', 'content': 's'}], 7)
        self.assertEqual(out['text'], 'e3')
        self.assertNotIn('text_over_core_cap', self.client.calls[-1])

    def test_only_text_blocks_are_returned_and_usage_is_recorded(self):
        self.create.return_value = message([thinking(), text('{"action":'), thinking(), text('"home"}')],
                                           input_tokens=900, output_tokens=40, cache_read=50,
                                           cache_creation=10, thinking_tokens=30)
        out = self.complete()
        self.assertEqual(out, {'text': '{"action":"home"}',
                               'usage': {'prompt_tokens': 960, 'completion_tokens': 40}})
        call = self.client.calls[0]
        self.assertEqual({k: call[k] for k in ('provider', 'effort', 'model', 'http_status', 'stop_reason',
                                               'response_shape', 'text_blocks', 'thinking_blocks',
                                               'prompt_tokens', 'input_tokens', 'cache_read_input_tokens',
                                               'cache_creation_input_tokens', 'completion_tokens',
                                               'thinking_tokens')},
                         {'provider': 'anthropic', 'effort': 'low', 'model': 'claude-haiku-5-5',
                          'http_status': 200, 'stop_reason': 'end_turn', 'response_shape': 'text_content',
                          'text_blocks': 2, 'thinking_blocks': 2, 'prompt_tokens': 960, 'input_tokens': 900,
                          'cache_read_input_tokens': 50, 'cache_creation_input_tokens': 10,
                          'completion_tokens': 40, 'thinking_tokens': 30})
        self.assertNotIn('error_type', call)
        self.assert_no_secrets()

    def test_refusal_is_terminal_with_allowlisted_category(self):
        for category, recorded in [('cyber', 'cyber'), ('PRIVATE-new-category', 'other'), (None, 'missing')]:
            with self.subTest(category=category):
                self.create.reset_mock()
                self.create.side_effect = [message([text('I cannot ' + PRIVATE)], stop='refusal',
                                                   stop_details=NS(category=category, explanation=PRIVATE)),
                                           answer()]
                with self.assertRaisesRegex(ModelResponseError, '^refusal$') as error:
                    self.complete()
                self.assertEqual(self.create.call_count, 1)
                call = self.client.calls[-1]
                self.assertEqual((call['error_type'], call['error_category'], call['refusal_category']),
                                 ('ModelResponseError', 'refusal', recorded))
                self.assert_no_secrets(error.exception)

    def test_max_tokens_without_text_is_terminal(self):
        self.create.side_effect = [message([thinking()], stop='max_tokens', output_tokens=64), answer()]
        with self.assertRaisesRegex(ModelResponseError, '^missing_content$'):
            self.complete('reader', 64)
        self.assertEqual(self.create.call_count, 1)
        call = self.client.calls[0]
        self.assertEqual((call['stop_reason'], call['response_shape'], call['completion_tokens']),
                         ('max_tokens', 'missing_content', 64))
        self.assertNotIn('retried', call)

    def test_max_tokens_with_text_returns_the_text_like_length(self):
        self.create.return_value = message([text('e1')], stop='max_tokens')
        self.assertEqual(self.complete('reader', 64)['text'], 'e1')

    def test_one_empty_end_turn_is_regenerated_then_terminal(self):
        self.create.side_effect = [message([text('  ')]), answer('e3')]
        self.assertEqual(self.complete()['text'], 'e3')
        self.assertEqual(self.client.calls[0]['retried'][0]['response_shape'], 'empty_content')
        self.create.reset_mock()
        self.create.side_effect = [message([]), message([]), answer()]
        with self.assertRaisesRegex(ModelResponseError, '^missing_content$'):
            self.complete()
        self.assertEqual(self.create.call_count, 2)

    def test_tool_use_or_pause_is_terminal(self):
        for blocks, stop in [([text('x'), NS(type='tool_use', name=PRIVATE, input={})], 'tool_use'),
                             ([text('x')], 'pause_turn')]:
            with self.subTest(stop=stop):
                self.create.reset_mock()
                self.create.side_effect = [message(blocks, stop=stop), answer()]
                with self.assertRaisesRegex(ModelResponseError, '^non_text_completion$'):
                    self.complete()
                self.assertEqual(self.create.call_count, 1)
                self.assert_no_secrets()

    def test_rate_limit_with_retry_after_is_retried_and_recorded(self):
        self.create.side_effect = [
            self.sdk.RateLimitError(429, 'exceeds 50,000 input tokens per minute ' + PRIVATE,
                                    'rate_limit_error', headers={'Retry-After': '7'}),
            answer()]
        self.assertEqual(self.complete()['text'], '{"action":"home"}')
        self.assertEqual([c.args[0] for c in self.sleep.call_args_list], [7.0])
        retry = self.client.calls[0]['retried'][0]
        self.assertEqual({k: retry[k] for k in ('http_status', 'error_type', 'error_category',
                                                'quota_category', 'retry_after_seconds')},
                         {'http_status': 429, 'error_type': 'RateLimitError', 'error_category': 'rate_limit',
                          'quota_category': 'rate', 'retry_after_seconds': 7.0})
        usage = suite_row.model_usage(self.client.calls)
        self.assertFalse(usage['quota_limited'])
        self.assertEqual(usage['retry_attempts'], 1)
        self.assert_no_secrets()

    def test_rate_limit_without_usable_delay_ends_the_call_and_stops_the_suite(self):
        for headers in ({}, {'Retry-After': '120'}):
            with self.subTest(headers=headers):
                self.create.reset_mock()
                self.create.side_effect = [self.sdk.RateLimitError(429, 'per minute ' + PRIVATE, headers=headers),
                                           answer()]
                with self.assertRaisesRegex(ModelProviderError, r'^rate_limit \(HTTP 429\)$') as error:
                    self.complete()
                self.assertEqual(self.create.call_count, 1)
                call = self.client.calls[-1]
                self.assertEqual((call['http_status'], call['quota_category']), (429, 'rate'))
                self.assertTrue(suite_row.model_usage([call])['quota_limited'])
                self.assert_no_secrets(error.exception)

    def test_overloaded_and_server_errors_are_transient_within_the_budget(self):
        self.create.side_effect = [self.sdk.OverloadedError(529, error_type='overloaded_error'),
                                   self.sdk.InternalServerError(500, error_type='api_error'), answer()]
        self.assertEqual(self.complete()['text'], '{"action":"home"}')
        self.assertEqual([c.args[0] for c in self.sleep.call_args_list], [2.0, 4.0])
        retried = self.client.calls[0]['retried']
        self.assertEqual([(r['http_status'], r['error_category']) for r in retried],
                         [(529, 'overloaded'), (500, 'server')])
        self.create.reset_mock()
        self.create.side_effect = [self.sdk.OverloadedError(529)] * 5
        with self.assertRaisesRegex(ModelProviderError, r'^overloaded \(HTTP 529\)$'):
            self.complete()
        self.assertEqual(self.create.call_count, 4)
        self.assertEqual(len(self.client.calls[-1]['retried']), 3)
        self.assert_no_secrets()

    def test_connection_errors_retry_and_timeouts_do_not(self):
        self.create.side_effect = [self.sdk.APIConnectionError(PRIVATE), answer()]
        self.complete()
        self.assertEqual(self.client.calls[0]['retried'], [{'error_type': 'APIConnectionError',
                                                            'error_category': 'connection'}])
        self.create.reset_mock()
        self.create.side_effect = [self.sdk.APITimeoutError(PRIVATE), answer()]
        with self.assertRaisesRegex(ModelProviderError, '^timeout$'):
            self.complete()
        self.assertEqual(self.create.call_count, 1)
        self.assert_no_secrets()

    def test_low_credit_is_terminal_billing_and_stops_the_suite(self):
        low_credit = ('Your credit balance is too low to access the Anthropic API. '
                      'Please go to Plans & Billing to upgrade or purchase credits.')
        cases = [self.sdk.BadRequestError(400, low_credit, 'invalid_request_error'),
                 self.sdk.APIStatusError(402, PRIVATE, 'billing_error'),
                 self.sdk.EnforcedSpendLimitReachedError(429, PRIVATE, 'rate_limit_error',
                                                         'enforced_spend_limit_reached', {'Retry-After': '5'})]
        for exc in cases:
            with self.subTest(status=exc.status_code):
                self.create.reset_mock()
                self.create.side_effect = [exc, answer()]
                with self.assertRaises(ModelProviderError) as error:
                    self.complete()
                self.assertEqual(error.exception.category, 'billing')
                self.assertEqual(self.create.call_count, 1)
                call = self.client.calls[-1]
                self.assertEqual((call['error_type'], call['error_category'], call['http_status']),
                                 ('ModelProviderError', 'billing', exc.status_code))
                self.assertNotIn('credit balance', json.dumps(call) + str(error.exception))
                self.assert_no_secrets(error.exception)
        record = {'benchmark_status': 'failed', 'success': False, 'model_calls': self.client.calls[:1]}
        with patch('suite_row.Path.read_text', side_effect=['written /runs/t.json\n', json.dumps(record)]):
            row = suite_row.summarize('Task', '/runs/t.log', 0)
        self.assertEqual((row['result'], row['failure_category'], row['billing_error']),
                         ('ERROR', 'environment', True))
        with patch('suite_row.summarize', return_value=row), patch('sys.stdout', new_callable=io.StringIO), \
                patch('sys.argv', ['suite_row.py', '--task', 'T', '--log', '/l', '--exit', '0', '--stop-on-quota']):
            with self.assertRaises(SystemExit) as stopped:
                suite_row.main()
        self.assertEqual(stopped.exception.code, 76)

    def test_other_client_errors_are_terminal_without_provider_text(self):
        for exc, category in [(self.sdk.BadRequestError(400, 'prompt echo ' + PRIVATE), 'bad_request'),
                              (self.sdk.AuthenticationError(401), 'authentication'),
                              (self.sdk.PermissionDeniedError(403), 'permission'),
                              (self.sdk.NotFoundError(404, 'model: claude-x'), 'not_found')]:
            with self.subTest(category=category):
                self.create.reset_mock()
                self.create.side_effect = [exc, answer()]
                with self.assertRaises(ModelProviderError) as error:
                    self.complete()
                self.assertEqual(error.exception.category, category)
                self.assertEqual(self.create.call_count, 1)
                self.assert_no_secrets(error.exception)

    def test_host_reply_and_records_never_carry_key_or_provider_text(self):
        self.create.side_effect = [self.sdk.BadRequestError(400, PRIVATE)]
        host = CoreHost(Mock(), self.client)
        reply = host._answer({'id': 3, 'ask': 'complete', 'role': 'planner',
                              'messages': [{'role': 'user', 'content': 'x'}], 'max_tokens': 10})
        self.assertEqual(reply, {'id': 3, 'ok': False, 'error': 'ModelProviderError: bad_request (HTTP 400)'})
        self.assert_no_secrets(reply)

    def test_core_process_gets_no_anthropic_key(self):
        with patch.dict(os.environ, {'ANTHROPIC_API_KEY': KEY, 'BALLISTIC_API_KEY': KEY}), \
                patch('core_bridge.subprocess.Popen') as popen:
            core_bridge.start_core('/bin/true', ['observe'])
        env = popen.call_args.kwargs['env']
        self.assertNotIn('ANTHROPIC_API_KEY', env)
        self.assertNotIn(KEY, json.dumps(env))

    def test_pacing_spans_anthropic_attempts(self):
        self.client.min_interval_seconds = 5
        now = [100.0]
        starts = []
        patch('core_bridge.time.monotonic', side_effect=lambda: now[0]).start()
        self.sleep.side_effect = lambda s: now.__setitem__(0, now[0] + s)
        self.create.side_effect = lambda **kw: starts.append(now[0]) or answer()
        self.complete()
        self.complete()
        self.assertEqual(starts, [100.0, 105.0])
        self.assertEqual(self.client.calls[1]['pacing_seconds'], 5.0)


class DefaultProviderTest(unittest.TestCase):
    def test_default_is_openai_and_never_loads_the_sdk(self):
        client = ModelClient({'planner': ('https://models.example/v1', 'm', KEY)})
        self.assertEqual((client.provider, client.effort), ('openai', 'low'))
        with patch('core_bridge._anthropic_sdk', side_effect=AssertionError('SDK loaded')), \
                patch('requests.post', return_value=ok('e1')) as post:
            self.assertEqual(client.complete('reader', [{'role': 'user', 'content': 'x'}], 64)['text'], 'e1')
        self.assertEqual(post.call_args.kwargs['json']['temperature'], 0.0)
        self.assertNotIn('provider', client.calls[0])

    def test_invalid_provider_options_fail(self):
        routes = {'planner': (None, 'claude-haiku-5-5', KEY)}
        for kwargs in ({'provider': 'claude'}, {'provider': 'anthropic', 'disable_native_tools': True}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                ModelClient(routes, **kwargs)


try:
    import anthropic as real_anthropic
except ImportError:
    real_anthropic = None


@unittest.skipIf(real_anthropic is None, 'official anthropic SDK not installed')
class RealSdkTest(unittest.TestCase):
    """The installed SDK end to end over a mocked transport: request JSON, response parsing and
    the SDK's own typed errors for the statuses this client classifies."""

    def setUp(self):
        import httpx2  # the SDK's HTTP library (anthropic 1.x and later)
        self.httpx = httpx2
        self.requests = []
        self.replies = []
        patch('core_bridge.time.sleep').start()
        self.addCleanup(patch.stopall)
        transport = httpx2.MockTransport(self.handle)
        real = real_anthropic.Anthropic(api_key=KEY, max_retries=0,
                                        http_client=real_anthropic.DefaultHttpxClient(transport=transport))
        self.client = ModelClient({'planner': (None, 'claude-haiku-5-5', KEY)}, provider='anthropic')
        patch.object(ModelClient, '_anthropic_client', return_value=real).start()

    def handle(self, request):
        self.requests.append(json.loads(request.content))
        status, body, headers = self.replies.pop(0)
        return self.httpx.Response(status, json=body, headers=headers or {})

    def complete(self):
        return self.client.complete('planner', [{'role': 'system', 'content': 'rules'},
                                                {'role': 'user', 'content': 'screen'}], 64)

    @staticmethod
    def message(content, stop='end_turn'):
        return {'id': 'msg_1', 'type': 'message', 'role': 'assistant', 'model': 'claude-haiku-5-5',
                'content': content, 'stop_reason': stop, 'stop_sequence': None,
                'usage': {'input_tokens': 11, 'output_tokens': 7, 'cache_read_input_tokens': 0,
                          'cache_creation_input_tokens': 0, 'output_tokens_details': {'thinking_tokens': 5}}}

    @staticmethod
    def error(kind, message):
        return {'type': 'error', 'error': {'type': kind, 'message': message}}

    def test_request_json_and_text_only_reply(self):
        self.replies = [(200, self.message([{'type': 'thinking', 'thinking': '', 'signature': 'sig'},
                                            {'type': 'text', 'text': 'e7'}]), None)]
        self.assertEqual(self.complete(), {'text': 'e7', 'usage': {'prompt_tokens': 11, 'completion_tokens': 7}})
        body = self.requests[0]
        self.assertEqual({k: body[k] for k in ('model', 'max_tokens', 'system', 'messages', 'output_config')},
                         {'model': 'claude-haiku-5-5', 'max_tokens': 64 + 2048, 'system': 'rules',
                          'messages': [{'role': 'user', 'content': 'screen'}], 'output_config': {'effort': 'low'}})
        for absent in ('temperature', 'top_p', 'top_k', 'thinking'):
            self.assertNotIn(absent, body)
        self.assertEqual((self.client.calls[0]['thinking_blocks'], self.client.calls[0]['thinking_tokens']), (1, 5))

    def test_real_sdk_errors_classify(self):
        low = 'Your credit balance is too low to access the Anthropic API.'
        cases = [(400, self.error('invalid_request_error', low), None, 'billing', 1),
                 (529, self.error('overloaded_error', PRIVATE), None, 'overloaded', 4),
                 (500, self.error('api_error', PRIVATE), None, 'server', 4),
                 (429, self.error('rate_limit_error', 'per minute ' + PRIVATE), None, 'rate_limit', 1),
                 (400, self.error('invalid_request_error', PRIVATE), None, 'bad_request', 1)]
        for status, body, headers, category, attempts in cases:
            with self.subTest(status=status, category=category):
                self.requests.clear()
                self.replies = [(status, body, headers)] * attempts
                with self.assertRaises(ModelProviderError) as error:
                    self.complete()
                self.assertEqual(error.exception.category, category)
                self.assertEqual(len(self.requests), attempts)
                dumped = json.dumps(self.client.calls) + str(error.exception)
                self.assertNotIn(KEY, dumped)
                self.assertNotIn(PRIVATE, dumped)
        self.replies = [(429, self.error('rate_limit_error', 'per minute'), {'retry-after': '3'}),
                        (200, self.message([{'type': 'text', 'text': 'ok'}]), None)]
        self.assertEqual(self.complete()['text'], 'ok')
        self.assertEqual(self.client.calls[-1]['retried'][0]['retry_after_seconds'], 3.0)


if __name__ == '__main__':
    unittest.main()
