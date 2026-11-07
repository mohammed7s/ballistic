"""Model retries, error bodies, observation settling and the core binary override.
No emulator, network or model: HTTP responses and screens are scripted."""
import os
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

import requests

import core_bridge
from core_bridge import CoreHost, ModelClient, ModelResponseError, ObservationError, slightly_different
from outcomes import failure_category
from test_core_bridge import FakeEnv, forest, sample, window

KEY = 'test-key-123'


def response(status, body):
    r = requests.models.Response()
    r.status_code = status
    r._content = body.encode() if isinstance(body, str) else body
    r.url = 'https://models.example/v1/chat/completions'
    r.reason = 'scripted'
    return r


def ok(text='{"action":"wait"}'):
    return response(200, '{"choices":[{"message":{"content":%s}}],"usage":{"prompt_tokens":5,"completion_tokens":2}}'
                    % requests.compat.json.dumps(text))


class ModelClientTest(unittest.TestCase):
    def setUp(self):
        self.sleep = patch('core_bridge.time.sleep').start()
        self.post = patch('requests.post').start()
        self.addCleanup(patch.stopall)
        self.client = ModelClient({'planner': ('https://models.example/v1', 'm', KEY)})

    def complete(self):
        return self.client.complete('planner', [{'role': 'user', 'content': 'x'}], 10)

    def test_server_errors_are_retried_with_doubling_waits_and_one_call_is_recorded(self):
        self.post.side_effect = [response(503, 'overloaded ' * 50), response(502, 'bad gateway'), ok()]
        self.assertEqual(self.complete()['text'], '{"action":"wait"}')
        self.assertEqual(self.post.call_count, 3)
        self.assertEqual([c.args[0] for c in self.sleep.call_args_list], [2.0, 4.0])
        self.assertEqual(len(self.client.calls), 1)
        call = self.client.calls[0]
        self.assertNotIn('error_type', call)
        self.assertEqual([r['http_status'] for r in call['retried']], [503, 502])
        self.assertNotIn('error_body', repr(call))
        # A retried success is not an environment failure.
        self.assertIsNone(failure_category({'finished': {'report': {}}, 'done': True}, {'benchmark_success': True}, self.client.calls))

    def test_retries_stop_after_three_without_logging_raw_error_bodies(self):
        self.post.side_effect = [response(503, 'unavailable')] * 4
        with self.assertRaises(requests.HTTPError):
            self.complete()
        self.assertEqual(self.post.call_count, 4)
        call = self.client.calls[-1]
        self.assertEqual((call['error_type'], call['http_status']), ('HTTPError', 503))
        self.assertNotIn('unavailable', repr(call))
        self.assertEqual(len(call['retried']), 3)
        self.assertEqual(failure_category({'finished': {}}, {}, self.client.calls), 'environment')

    def test_connection_errors_are_retried(self):
        self.post.side_effect = [requests.ConnectionError('reset'), ok()]
        self.complete()
        self.assertEqual(self.client.calls[0]['retried'], [{'error_type': 'ConnectionError'}])

    def test_quota_429_is_not_retried_and_records_only_its_category(self):
        body = ('{"error":{"code":429,"message":"Quota exceeded for metric '
                'GenerateRequestsPerDayPerProjectPerModel-FreeTier, key %s"}}' % KEY)
        self.post.side_effect = [response(429, body)]
        with self.assertRaises(requests.HTTPError):
            self.complete()
        self.assertEqual(self.post.call_count, 1)
        call = self.client.calls[0]
        self.assertEqual(call['quota_category'], 'daily')
        self.assertNotIn(KEY, repr(self.client.calls))
        self.assertNotIn('error_body', call)

    def test_read_timeouts_are_not_retried(self):
        self.post.side_effect = [requests.ReadTimeout('slow')]
        with self.assertRaises(requests.ReadTimeout):
            self.complete()
        self.assertEqual(self.post.call_count, 1)
        self.assertNotIn('error_body', self.client.calls[0])

    def test_quota_guidance_is_numeric_bounded_and_does_not_retry(self):
        body = {'error': {'message': KEY, 'details': [
            {'@type': 'type.googleapis.com/google.rpc.RetryInfo', 'retryDelay': '12.5s'},
            {'@type': 'type.googleapis.com/google.rpc.QuotaFailure', 'violations': [
                {'quotaId': 'GenerateRequestsPerMinutePerProjectPerModel-FreeTier',
                 'quotaValue': '15', 'quotaDimensions': {'secret': KEY}},
                {'quotaId': KEY, 'quotaValue': '15'},
                {'quotaId': 'GenerateTokensPerMinutePerModel', 'quotaValue': KEY},
            ]},
        ]}}
        r = response(429, requests.compat.json.dumps(body))
        r.headers['Retry-After'] = '30'
        self.post.return_value = r
        with self.assertRaises(requests.HTTPError):
            self.complete()
        call = self.client.calls[-1]
        self.assertEqual(call['retry_after_seconds'], 30.0)
        self.assertEqual(call['quota_limits'], [{'metric': 'requests_per_minute', 'value': 15}])
        self.assertEqual(self.post.call_count, 1)
        self.sleep.assert_not_called()
        self.assertNotIn(KEY, repr(call))
        self.assertNotIn('FreeTier', repr(call))

    def test_malformed_quota_guidance_stays_unreported(self):
        for value in [KEY, '-2s', 'NaN', 'infinity', '999999999999s', 12, None]:
            with self.subTest(value=value):
                body = {'error': {'details': [None, {'@type': 'type.googleapis.com/google.rpc.RetryInfo',
                                                   'retryDelay': value}]}}
                r = response(429, requests.compat.json.dumps(body))
                r.headers['Retry-After'] = value
                self.post.return_value = r
                with self.assertRaises(requests.HTTPError):
                    self.complete()
                call = self.client.calls[-1]
                self.assertNotIn('retry_after_seconds', call)
                self.assertNotIn('quota_limits', call)
                self.assertNotIn(KEY, repr(call))

    def test_missing_null_and_non_string_content_have_safe_typed_errors(self):
        cases = [
            ({}, 'missing_content'),
            ({'content': None}, 'null_content'),
            ({'content': {'secret': KEY}}, 'non_string_content'),
            ({'content': []}, 'non_string_content'),
            ({'content': ''}, 'empty_content'),
            ({'content': '  '}, 'empty_content'),
        ]
        for message, shape in cases:
            with self.subTest(shape=shape, message_type=type(message.get('content'))):
                self.post.reset_mock()
                body = {'choices': [{'message': message, 'finish_reason': 'length'}],
                        'usage': {'prompt_tokens': 123, 'completion_tokens': 800}}
                self.post.return_value = response(200, requests.compat.json.dumps(body))
                with self.assertRaisesRegex(ModelResponseError, '^' + shape + '$'):
                    self.complete()
                self.assertEqual(self.post.call_count, 1)
                call = self.client.calls[-1]
                self.assertEqual(call['error_type'], 'ModelResponseError')
                self.assertEqual(call['http_status'], 200)
                self.assertEqual(call['response_shape'], shape)
                self.assertEqual(call['finish_reason'], 'length')
                self.assertEqual(call['completion_tokens'], 800)
                self.assertNotIn(KEY, repr(call))

    def test_missing_choices_and_malformed_shapes_are_not_key_errors(self):
        for body, shape in [([], 'not_object'), ({}, 'missing_or_invalid_choices'),
                            ({'choices': None}, 'missing_or_invalid_choices'),
                            ({'choices': []}, 'empty_choices'),
                            ({'choices': [None]}, 'invalid_choice'),
                            ({'choices': [{}]}, 'missing_or_invalid_message')]:
            with self.subTest(shape=shape):
                self.post.return_value = response(200, requests.compat.json.dumps(body))
                with self.assertRaisesRegex(ModelResponseError, '^' + shape + '$'):
                    self.complete()
                self.assertEqual(self.client.calls[-1]['response_shape'], shape)

    def test_invalid_json_and_provider_finish_reason_never_echo_secrets(self):
        self.post.return_value = response(200, 'not json ' + KEY)
        with self.assertRaisesRegex(ModelResponseError, '^invalid_json$'):
            self.complete()
        self.post.return_value = response(200, requests.compat.json.dumps({
            'choices': [{'message': {}, 'finish_reason': KEY}]}))
        with self.assertRaises(ModelResponseError):
            self.complete()
        self.assertEqual(self.client.calls[-1]['finish_reason'], 'other')
        self.assertNotIn(KEY, repr(self.client.calls))

    def test_quota_classification_reads_beyond_old_excerpt_and_never_logs_values(self):
        cases = [('GenerateRequestsPerDayPerProjectPerModel-FreeTier', 'daily'),
                 ('GenerateRequestsPerMinutePerProjectPerModel-FreeTier', 'rate'),
                 ('TokensPerDay TokensPerMinute', 'mixed'), ('unknown metric', 'unknown')]
        for metric, category in cases:
            with self.subTest(category=category):
                self.post.reset_mock()
                body = {'error': {'message': 'generic prefix ' * 100,
                                 'details': [{'violations': [{'quotaId': metric,
                                              'quotaDimensions': {'api_key': KEY,
                                                                  'owner_secret': 'another-secret'}}]}]}}
                self.post.return_value = response(429, requests.compat.json.dumps(body))
                with self.assertRaises(requests.HTTPError):
                    self.complete()
                call = self.client.calls[-1]
                self.assertEqual(call['quota_category'], category)
                self.assertEqual(self.post.call_count, 1)
                self.assertNotIn(KEY, repr(call))
                self.assertNotIn('another-secret', repr(call))
                self.assertNotIn(metric, repr(call))

    def test_unknown_usage_stays_unknown_in_telemetry_and_bridge_shape_is_complete(self):
        self.post.return_value = response(200, '{"choices":[{"message":{"content":"ok"}}]}')
        self.assertEqual(self.complete(), {'text': 'ok', 'usage': {'prompt_tokens': 0, 'completion_tokens': 0}})
        self.assertNotIn('prompt_tokens', self.client.calls[-1])
        self.assertNotIn('completion_tokens', self.client.calls[-1])

    def test_host_propagates_sanitized_model_error(self):
        self.post.return_value = response(200, requests.compat.json.dumps({
            'choices': [{'message': {'content': {'secret': KEY}}}]}))
        host = CoreHost(Mock(), self.client)
        reply = host._answer({'id': 7, 'ask': 'complete', 'role': 'planner', 'messages': []})
        self.assertFalse(reply['ok'])
        self.assertEqual(reply['error'], 'ModelResponseError: non_string_content')
        self.assertNotIn(KEY, repr(reply))


def tree(texts, bounds_shift=0):
    """One window, a root and one text node per entry: enough nodes for a tolerance."""
    def node(uid, text, children=()):
        return SimpleNamespace(
            unique_id=uid, child_ids=list(children), package_name='app', text=text,
            class_name='android.widget.TextView', content_description='', hint_text='',
            tooltip_text='', view_id_resource_name='app:id/row',
            bounds_in_screen=SimpleNamespace(left=0, top=uid * 10 + bounds_shift, right=100, bottom=uid * 10 + 9 + bounds_shift),
            is_clickable=False, is_long_clickable=False, is_editable=False, is_scrollable=False,
            is_checkable=False, is_checked=False, is_selected=False, is_enabled=True,
            is_focused=False, is_visible_to_user=True)
    nodes = [node(1, 'Title', range(2, len(texts) + 2))] + [node(i + 2, t) for i, t in enumerate(texts)]
    return forest(SimpleNamespace(window_type=1, is_active=True, is_focused=True, tree=SimpleNamespace(nodes=nodes)))


class SettlingTest(unittest.TestCase):
    def setUp(self):
        self.sleep = patch('core_bridge.time.sleep').start()
        self.clock = patch('core_bridge.time.monotonic', return_value=0).start()
        self.addCleanup(patch.stopall)

    def host(self, *samples):
        host = CoreHost(FakeEnv(samples), Mock())
        host._apps = []
        return host

    def test_a_ticking_value_on_a_stable_screen_settles_on_the_later_sample(self):
        rows = [f'Row {i}' for i in range(19)]
        first, second = tree(rows + ['0:01']), tree(rows + ['0:02'])
        host = self.host(sample(first), sample(second), sample(tree(rows + ['0:03'])))
        result = host.observe()
        self.assertEqual(result['nodes'][-1]['text'], '0:02')
        self.assertEqual(host.env.state_reads, 2)

    def test_a_different_screen_is_not_a_slight_difference(self):
        rows = [f'Row {i}' for i in range(19)]
        other = [f'Other {i}' for i in range(19)]
        self.assertFalse(slightly_different(core_bridge._foreground_nodes(tree(rows + ['x']), 'app'),
                                            core_bridge._foreground_nodes(tree(other + ['x']), 'app')))
        self.assertFalse(slightly_different(core_bridge._foreground_nodes(tree(rows), 'app'),
                                            core_bridge._foreground_nodes(tree(rows + ['extra']), 'app')))
        # Moving every row (scrolling) changes more than a tenth of the nodes.
        self.assertFalse(slightly_different(core_bridge._foreground_nodes(tree(rows), 'app'),
                                            core_bridge._foreground_nodes(tree(rows, bounds_shift=5), 'app')))

    def test_unsettled_samples_are_spaced_by_doubling_waits(self):
        a, b = forest(window(text='A')), forest(window(text='B'))
        host = self.host(*(sample(a if i % 2 else b) for i in range(6)))
        with self.assertRaisesRegex(ObservationError, 'foreground_not_settled'):
            host.observe()
        self.assertEqual([c.args[0] for c in self.sleep.call_args_list], [0.3, 0.6, 1.2, 2.4, 4.8])

    def test_soft_deadline_is_thirty_seconds(self):
        self.assertEqual(core_bridge.SOFT_DEADLINE_SECONDS, 30.0)
        host = self.host(sample(forest(window())))
        self.clock.side_effect = [0, 0, 29, 29, 29, 29, 29]
        self.assertEqual(host.observe()['activity'], 'A')


class CoreBinTest(unittest.TestCase):
    def test_environment_override_wins(self):
        with patch.dict(os.environ, {'BALLISTIC_CORE_BIN': '/opt/corefix/ballistic-core'}):
            self.assertEqual(core_bridge.default_core_bin(), '/opt/corefix/ballistic-core')


if __name__ == '__main__':
    unittest.main()
