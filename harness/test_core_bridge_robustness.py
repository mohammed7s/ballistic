"""Model retries, error bodies, observation settling and the core binary override.
No emulator, network or model: HTTP responses and screens are scripted."""
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
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


class LabelTextSettlingTest(unittest.TestCase):
    """Compatibility matrix: stable metadata, bounded latest label/text snapshots.

    Replaces passive-text-only assertions frozen in the historical settling report.
    Readiness grants no identity, confirmation exemption or completion evidence."""

    def setUp(self):
        self.sleep = patch('core_bridge.time.sleep').start()
        self.clock = patch('core_bridge.time.monotonic', return_value=0).start()
        self.addCleanup(patch.stopall)

    def host(self, *samples):
        host = CoreHost(FakeEnv(samples), Mock())
        host._apps = []
        return host

    def base(self, count=10):
        raw = tree([f'Static {i}' for i in range(count - 1)])
        raw.windows[0].tree.nodes[0].class_name = 'android.widget.LinearLayout'
        return raw

    def nodes(self, raw):
        return core_bridge._foreground_nodes(raw, 'app')

    def assert_transition(self, old, new, *, tolerated=False):
        # A later reversal distinguishes early tolerance from genuinely settling.
        host = self.host(sample(old), sample(new), sample(old), sample(old))
        result = host.observe()
        self.assertEqual((slightly_different(self.nodes(old), self.nodes(new)),
                          host.env.state_reads), (tolerated, 2 if tolerated else 4))
        self.assertEqual(result['nodes'], core_bridge.forest_to_nodes(new if tolerated else old))
        self.assertEqual(host.env.activity_reads, 2 * host.env.state_reads)
        self.assertEqual(result['foreground']['sequence'], 1)

    def button(self):
        raw = self.base()
        control = raw.windows[0].tree.nodes[1]
        control.class_name = 'android.widget.Button'
        control.is_clickable = True
        control.text = 'Continue'
        return raw

    def test_control_flags_and_bounds_reset_but_latest_text_and_label_settle(self):
        old = self.button()
        for field, value in [('is_enabled', False), ('is_visible_to_user', False),
                             ('is_clickable', False), ('text', 'Delete'),
                             ('content_description', 'Delete'), ('bounds', 300)]:
            with self.subTest(field=field):
                new = deepcopy(old)
                node = new.windows[0].tree.nodes[1]
                if field == 'bounds':
                    node.bounds_in_screen.right = value
                else:
                    setattr(node, field, value)
                self.assert_transition(old, new, tolerated=field in ('text', 'content_description'))

    def test_flags_and_bounds_stay_fixed_while_all_label_sources_may_update(self):
        for field in ('is_clickable', 'is_long_clickable', 'is_editable', 'is_scrollable',
                      'is_checkable', 'is_checked', 'is_selected', 'is_enabled',
                      'is_focused', 'is_visible_to_user', 'content_description',
                      'hint_text', 'tooltip_text', 'bounds'):
            with self.subTest(field=field):
                old = self.base()
                new = deepcopy(old)
                node = new.windows[0].tree.nodes[-1]
                if field == 'bounds':
                    node.bounds_in_screen.bottom += 1
                else:
                    setattr(node, field, not getattr(node, field) if field.startswith('is_') else 'New label')
                self.assert_transition(old, new, tolerated=field in
                                       ('content_description', 'hint_text', 'tooltip_text'))

    def caption_tree(self, wrapped=False):
        raw = self.button()
        nodes = raw.windows[0].tree.nodes
        nodes[1].text = ''
        nodes[0].child_ids.remove(nodes[2].unique_id)
        nodes[1].child_ids = [nodes[2].unique_id]
        if wrapped:
            nodes[2].class_name = 'android.widget.LinearLayout'
            nodes[2].text = ''
            nodes[0].child_ids.remove(nodes[3].unique_id)
            nodes[2].child_ids = [nodes[3].unique_id]
        return raw, 3 if wrapped else 2

    def test_descendant_caption_including_pruned_wrapper_returns_latest(self):
        for wrapped in (False, True):
            with self.subTest(wrapped=wrapped):
                old, index = self.caption_tree(wrapped)
                new = deepcopy(old)
                new.windows[0].tree.nodes[index].text = 'Changed caption'
                self.assert_transition(old, new, tolerated=True)

    def test_caption_really_folds_into_button_in_current_rust(self):
        for wrapped in (False, True):
            with self.subTest(wrapped=wrapped):
                raw, index = self.caption_tree(wrapped)
                raw.windows[0].tree.nodes[index].text = 'Caption marker'
                screen = {'package': 'app', 'activity': 'A', 'width': 1080, 'height': 2400,
                          'nodes': core_bridge.forest_to_nodes(raw), 'installed_apps': []}
                binary = os.environ.get('BALLISTIC_TEST_CORE_BIN') or core_bridge.default_core_bin()
                result = subprocess.run([binary, 'filter'], input=json.dumps(screen), text=True,
                                        capture_output=True, check=True)
                self.assertIn('button "Caption marker"', result.stdout)

    def test_edit_and_toggle_text_may_update_but_checked_flag_must_match(self):
        for role, field, value in [('EditText', 'text', 'new draft'),
                                   ('Switch', 'is_checked', True), ('Switch', 'text', 'OFF')]:
            with self.subTest(role=role, field=field):
                old = self.base()
                node = old.windows[0].tree.nodes[1]
                node.class_name = 'android.widget.' + role
                node.is_editable = role == 'EditText'
                node.is_checkable = role == 'Switch'
                new = deepcopy(old)
                setattr(new.windows[0].tree.nodes[1], field, value)
                self.assert_transition(old, new, tolerated=field == 'text')
        old, index = self.caption_tree()
        nodes = old.windows[0].tree.nodes
        nodes[0].child_ids.remove(nodes[3].unique_id)
        nodes[1].child_ids.append(nodes[3].unique_id)
        nodes[3].class_name = 'android.widget.Switch'
        nodes[3].is_checkable = True
        new = deepcopy(old)
        new.windows[0].tree.nodes[index].text = 'Different setting'
        self.assert_transition(old, new, tolerated=True)

    def test_unchanged_interactive_flags_on_text_or_ancestors_allow_text_variation(self):
        for field in ('is_clickable', 'is_long_clickable', 'is_editable', 'is_scrollable',
                      'is_checkable', 'is_checked', 'is_selected', 'is_focused'):
            for ancestor in (False, True):
                with self.subTest(field=field, ancestor=ancestor):
                    old = self.base()
                    setattr(old.windows[0].tree.nodes[0 if ancestor else -1], field, True)
                    new = deepcopy(old)
                    new.windows[0].tree.nodes[-1].text = '00:02'
                    self.assert_transition(old, new, tolerated=True)

    def test_known_control_roles_and_disabled_ancestors_allow_latest_strings(self):
        for class_name in ('Button', 'EditText', 'Switch', 'CheckBox', 'SeekBar',
                           'TabView', 'ListView'):
            with self.subTest(class_name=class_name):
                old = self.base()
                old.windows[0].tree.nodes[0].class_name = 'android.widget.' + class_name
                new = deepcopy(old)
                new.windows[0].tree.nodes[-1].text = '00:02'
                self.assert_transition(old, new, tolerated=True)
        for field in ('is_enabled', 'is_visible_to_user'):
            with self.subTest(field=field):
                old, index = self.caption_tree(True)
                setattr(old.windows[0].tree.nodes[1], field, False)
                new = deepcopy(old)
                new.windows[0].tree.nodes[index].text = 'Changed'
                self.assert_transition(old, new, tolerated=True)

    def test_known_non_text_roles_allow_strings_but_unknown_role_stays_conservative(self):
        for class_name in ('ImageView', 'LinearLayout', 'UnknownWidget', 'Button'):
            with self.subTest(class_name=class_name):
                old = self.base()
                old.windows[0].tree.nodes[-1].class_name = 'android.widget.' + class_name
                new = deepcopy(old)
                new.windows[0].tree.nodes[-1].text = 'changed'
                self.assert_transition(old, new, tolerated=class_name != 'UnknownWidget')

    def test_skeleton_count_and_id_changes_reject(self):
        old = self.nodes(self.base())
        for field, value in [('resource_id', 'app:id/replaced'), ('parent', None),
                             ('role', 'image'), ('class_name', 'example.Other'),
                             ('package', 'other'), ('id', 100)]:
            with self.subTest(field=field):
                new = deepcopy(old)
                new[-1][field] = value
                self.assertFalse(slightly_different(old, new))
        self.assert_transition(self.base(), self.base(11))
        self.assert_transition(self.base(), self.base(9))
        self.assertFalse(slightly_different([], []))

    def test_percentage_boundaries_are_unchanged(self):
        for count, changes, allowed in ((9, 1, False), (10, 1, True), (10, 2, False),
                                         (20, 2, True), (20, 3, False)):
            with self.subTest(count=count, changes=changes):
                old = self.base(count)
                new = deepcopy(old)
                for node in new.windows[0].tree.nodes[-changes:]:
                    node.text = '00:02'
                self.assert_transition(old, new, tolerated=allowed)

    def test_ticking_text_outside_actionable_ancestry_including_unrelated_button(self):
        for before, after in [('00:01', '00:02'), ('', '00:01'), ('00:01', '')]:
            with self.subTest(before=before, after=after):
                old = self.button()
                old.windows[0].tree.nodes[-1].text = before
                new = deepcopy(old)
                new.windows[0].tree.nodes[-1].text = after
                self.assert_transition(old, new, tolerated=True)
        # A standalone text root is also legitimate (ten separate roots).
        old = self.base()
        old.windows[0].tree.nodes[0].child_ids = []
        new = deepcopy(old)
        new.windows[0].tree.nodes[-1].text = '00:02'
        self.assert_transition(old, new, tolerated=True)

    def test_missing_malformed_and_cyclic_ancestry_rejects_variation(self):
        cases = ['missing_id', 'duplicate_id', 'bad_id', 'bool_id', 'missing_parent',
                 'dangling_parent', 'bad_parent', 'bool_parent', 'self_parent',
                 'forward_parent', 'cycle', 'missing_flags', 'partial_flags',
                 'bad_flags', 'non_boolean_flag', 'unknown_role', 'malformed_text']
        for case in cases:
            with self.subTest(case=case):
                old = self.nodes(self.base())
                if case == 'missing_id': del old[-1]['id']
                elif case == 'duplicate_id': old[-1]['id'] = old[0]['id']
                elif case == 'bad_id': old[-1]['id'] = []
                elif case == 'bool_id': old[0]['id'] = False
                elif case == 'missing_parent': del old[-1]['parent']
                elif case == 'dangling_parent': old[-1]['parent'] = 999
                elif case == 'bad_parent': old[-1]['parent'] = []
                elif case == 'bool_parent': old[-1]['parent'] = False
                elif case == 'self_parent': old[-1]['parent'] = old[-1]['id']
                elif case == 'forward_parent': old[0]['parent'] = old[-1]['id']
                elif case == 'cycle':
                    old[-2]['parent'] = old[-1]['id']
                    old[-1]['parent'] = old[-2]['id']
                elif case == 'missing_flags': del old[0]['flags']
                elif case == 'partial_flags': del old[0]['flags']['clickable']
                elif case == 'bad_flags': old[0]['flags'] = []
                elif case == 'non_boolean_flag': old[0]['flags']['clickable'] = 0
                elif case == 'unknown_role': old[0]['role'] = 'future_control'
                elif case == 'malformed_text': old[-1]['text'] = []
                new = deepcopy(old)
                new[-1]['text'] = '00:02'
                self.assertFalse(slightly_different(old, new))

    def test_deep_known_ancestry_is_iterative_even_with_distant_control(self):
        old = self.nodes(self.base(2000))
        for i, node in enumerate(old):
            node['parent'] = old[i - 1]['id'] if i else None
        new = deepcopy(old)
        for node in new[-200:]:
            node['text'] = '00:02'
        self.assertTrue(slightly_different(old, new))
        old[0]['flags']['clickable'] = new[0]['flags']['clickable'] = True
        self.assertTrue(slightly_different(old, new))

    def test_exact_observation_keeps_existing_path_without_predicate(self):
        host = self.host(sample(self.button()))
        with patch('core_bridge.slightly_different', side_effect=AssertionError('exact path')):
            host.observe()
        self.assertEqual(host.env.state_reads, 2)

    def test_chrome_excluded_from_comparison_but_later_full_forest_returned(self):
        old, new = self.base(), self.base()
        new.windows[0].tree.nodes[-1].text = '00:02'
        old = forest(*old.windows, window('chrome', 'old', window_type=3),
                     window('app', 'old keyboard', window_type=2))
        new = forest(*new.windows, window('chrome', 'changed', window_type=3, clickable=True),
                     window('app', 'new keyboard', window_type=2))
        self.assert_transition(old, new, tolerated=True)
        # Excluded nodes cannot pad a nine-node foreground into the 10% allowance.
        small = self.base(9)
        changed = deepcopy(small)
        changed.windows[0].tree.nodes[-1].text = '00:02'
        self.assert_transition(forest(*small.windows, window('chrome', 'clock')),
                               forest(*changed.windows, window('chrome', 'clock')))

    def test_unready_and_activity_transition_reset_ticking_candidates(self):
        old, new = self.base(), self.base()
        new.windows[0].tree.nodes[-1].text = '00:02'
        host = self.host(sample(old), sample(None), sample(new))
        self.assertEqual(host.observe()['nodes'], core_bridge.forest_to_nodes(new))
        self.assertEqual(host.env.state_reads, 4)
        host = self.host(sample(old), sample(new, 'app/B'))
        self.assertEqual(host.observe()['activity'], 'B')
        self.assertEqual(host.env.state_reads, 3)
        fresh = self.base()
        fresh.windows[0].tree.nodes[-1].text = '00:03'
        host = self.host(sample(old), sample(new, after='app/B'), sample(new, 'app/B'),
                         sample(fresh, 'app/B'))
        self.assertEqual(host.observe()['activity'], 'B')
        self.assertEqual(host.env.state_reads, 5)

    def test_excluded_parent_cannot_hide_folded_caption_ancestry(self):
        old, index = self.caption_tree(True)
        nodes = old.windows[0].tree.nodes
        nodes[2].package_name = 'other.package'
        # Keep ten foreground nodes after the foreign wrapper is excluded.
        nodes.append(window(uid=11).tree.nodes[0])
        nodes[0].child_ids.append(11)
        nodes[index].text = 'Caption marker'
        screen = {'package': 'app', 'activity': 'A', 'width': 1080, 'height': 2400,
                  'nodes': core_bridge.forest_to_nodes(old), 'installed_apps': []}
        binary = os.environ.get('BALLISTIC_TEST_CORE_BIN') or core_bridge.default_core_bin()
        result = subprocess.run([binary, 'filter'], input=json.dumps(screen), text=True,
                                capture_output=True, check=True)
        self.assertIn('button "Caption marker"', result.stdout)
        new = deepcopy(old)
        new.windows[0].tree.nodes[index].text = 'Changed caption'
        self.assert_transition(old, new)
        # Exact matching retains the existing path even with an excluded ancestor.
        self.assertEqual(self.host(sample(old)).observe()['nodes'], screen['nodes'])

    def test_malformed_right_hand_ancestry_is_not_numeric_equality(self):
        for field in ('id', 'parent'):
            with self.subTest(field=field):
                old = self.nodes(self.base())
                new = deepcopy(old)
                new[0 if field == 'id' else -1][field] = False  # False == 0 in Python.
                new[-1]['text'] = '00:02'
                self.assertFalse(slightly_different(old, new))

    def test_size_change_resets_candidate(self):
        raw = self.base()
        host = self.host(sample(raw))
        class ResizingEnv(FakeEnv):
            @property
            def logical_screen_size(self):
                return (1080, 2400 if self.state_reads == 1 else 2300)
        host.env = ResizingEnv([sample(raw)])
        self.assertEqual(host.observe()['height'], 2300)
        self.assertEqual(host.env.state_reads, 3)

    def test_sparse_control_flag_churn_exhausts_then_fresh_call_restarts(self):
        old, new = self.button(), self.button()
        new.windows[0].tree.nodes[1].is_enabled = False
        host = self.host(*(sample(old if i % 2 == 0 else new) for i in range(6)))
        with self.assertRaisesRegex(ObservationError, 'foreground_not_settled'):
            host.observe()
        self.assertEqual((host.env.state_reads, host._observation_sequence), (6, 0))
        self.assertIsNone(host._last_observation)
        self.assertEqual([c.args[0] for c in self.sleep.call_args_list], [0.3, 0.6, 1.2, 2.4, 4.8])
        host.observe()  # Last sample repeats, but a failed call cannot supply its first candidate.
        self.assertEqual((host.env.state_reads, host._observation_sequence), (8, 1))
        self.sleep.assert_called_with(0.3)

    def test_deadline_rejects_second_sample_and_clamps_settling_sleep(self):
        host = self.host(sample(self.base()))
        self.clock.side_effect = [0, 0, 0, 0, 29, 30, 30]
        with self.assertRaisesRegex(ObservationError, 'acquisition_deadline'):
            host.observe()
        self.assertEqual((host.env.state_reads, host._observation_sequence), (2, 0))
        host = self.host(sample(self.base()))
        self.sleep.reset_mock()
        self.clock.side_effect = [0, 0, 29.9, 29.9, 30, 30]
        with self.assertRaises(ObservationError):
            host.observe()
        self.assertAlmostEqual(self.sleep.call_args.args[0], 0.1)
        self.assertEqual((host.env.state_reads, host._observation_sequence), (1, 0))

    def test_exact_captured_pairs_and_identical_sample_return_latest(self):
        fixture = json.loads((Path(__file__).parent / 'fixtures/settling_capture_20261107.json').read_text())

        def restore(value):
            if isinstance(value, dict):
                return SimpleNamespace(**{k: restore(v) for k, v in value.items()})
            if isinstance(value, list):
                return [restore(v) for v in value]
            return value

        package = fixture['foreground'].split('/')[0]
        for attempt in fixture['attempts']:
            forests = []
            for captured in attempt['samples']:
                raw = deepcopy(fixture['base_window'])
                raw['tree']['nodes'][fixture['changed_raw_index']].update(captured['values'])
                forests.append(forest(restore(raw)))
            for i, (old, new) in enumerate(zip(forests, forests[1:])):
                with self.subTest(attempt=attempt['attempt'], pair=i):
                    self.assertTrue(slightly_different(core_bridge._foreground_nodes(old, package),
                                                      core_bridge._foreground_nodes(new, package)))
                    host = self.host(sample(old, fixture['foreground']), sample(new, fixture['foreground']))
                    result = host.observe()
                    self.assertEqual(host.env.state_reads, 2)
                    self.assertEqual(result['nodes'], core_bridge.forest_to_nodes(new))
                    self.assertEqual((result['width'], result['height']), tuple(fixture['size']))

    def test_generic_clickable_unicode_labels_and_nested_scrollable_ancestry(self):
        for before, after in [('Continue', 'Delete'), ('مرحبا', '再試行 🧭'),
                              ('α\nβ', 'not a clock'), ('', 'new content'), ('gone', '')]:
            for field in ('text', 'content_description'):
                with self.subTest(before=before, after=after, field=field):
                    old, index = self.caption_tree(True)
                    nodes = old.windows[0].tree.nodes
                    nodes[0].class_name = 'android.widget.ScrollView'
                    nodes[0].is_scrollable = True
                    nodes[2].class_name = 'android.widget.NestedScrollView'
                    nodes[2].is_scrollable = True
                    nodes[index].is_clickable = True
                    setattr(nodes[index], field, before)
                    new = deepcopy(old)
                    setattr(new.windows[0].tree.nodes[index], field, after)
                    self.assert_transition(old, new, tolerated=True)

    def test_label_and_text_count_changed_nodes_once_under_original_cap(self):
        for count, changes, allowed in ((9, 1, False), (10, 1, True), (10, 2, False),
                                        (20, 2, True), (20, 3, False)):
            with self.subTest(count=count, changes=changes):
                old = self.base(count)
                for node in old.windows[0].tree.nodes[-changes:]:
                    node.class_name = 'android.widget.Button'
                    node.is_clickable = True
                new = deepcopy(old)
                for node in new.windows[0].tree.nodes[-changes:]:
                    node.text = 'arbitrary new text'
                    node.content_description = '異なる説明'
                self.assert_transition(old, new, tolerated=allowed)

    def test_malformed_or_missing_display_fields_do_not_gain_tolerance(self):
        for field in ('text', 'label'):
            for value in ([], {}, 1, False):
                for side in ('old', 'new', 'unchanged_ancestor'):
                    with self.subTest(field=field, value=value, side=side):
                        old = self.nodes(self.base())
                        new = deepcopy(old)
                        new[-1]['text'] = 'updated'
                        if side == 'unchanged_ancestor':
                            old[0][field] = new[0][field] = value
                        else:
                            (old if side == 'old' else new)[-1][field] = value
                        self.assertFalse(slightly_different(old, new))
            with self.subTest(field=field, missing=True):
                old = self.nodes(self.base())
                del old[0][field]
                new = deepcopy(old)
                new[-1]['text'] = 'updated'
                self.assertFalse(slightly_different(old, new))

    def test_excluded_parent_marker_remains_private_on_later_exact_return(self):
        old, index = self.caption_tree(True)
        old.windows[0].tree.nodes[2].package_name = 'foreign'
        new = deepcopy(old)
        new.windows[0].tree.nodes[index].text = 'latest caption'
        self.assertIn(-1, [n['parent'] for n in self.nodes(new)])
        host = self.host(sample(old), sample(new))
        result = host.observe()
        self.assertEqual(host.env.state_reads, 3)
        self.assertEqual(result['nodes'], core_bridge.forest_to_nodes(new))
        self.assertNotIn(-1, [n['parent'] for n in result['nodes']])

    def test_latest_folded_caption_reaches_real_core_filter(self):
        binary = os.environ.get('BALLISTIC_TEST_CORE_BIN') or core_bridge.default_core_bin()
        for wrapped in (False, True):
            with self.subTest(wrapped=wrapped):
                old, index = self.caption_tree(wrapped)
                old.windows[0].tree.nodes[index].text = 'Continue'
                new = deepcopy(old)
                new.windows[0].tree.nodes[index].text = 'Delete'
                host = self.host(sample(old), sample(new))
                screen = host.observe()
                self.assertEqual(host.env.state_reads, 2)
                result = subprocess.run([binary, 'filter'], input=json.dumps(screen), text=True,
                                        capture_output=True, check=True, timeout=10)
                self.assertIn('button "Delete"', result.stdout)
                self.assertNotIn('Continue', result.stdout)

    def test_latest_button_text_still_requires_confirmation_and_collection_identity(self):
        binary = os.environ.get('BALLISTIC_TEST_CORE_BIN') or core_bridge.default_core_bin()
        for collection in (False, True):
            with self.subTest(collection=collection):
                old = self.button()
                # Empty root is pruned for the ordinary button (e1). A scrollable
                # collection root is retained and its unidentified child is e2.
                old.windows[0].tree.nodes[0].text = ''
                if collection:
                    old.windows[0].tree.nodes[0].class_name = 'androidx.recyclerview.widget.RecyclerView'
                    old.windows[0].tree.nodes[0].is_scrollable = True
                new = deepcopy(old)
                new.windows[0].tree.nodes[1].text = 'Delete'
                host = self.host(sample(old), sample(new))
                host.confirm_policy = 'deny'
                target = 'e2' if collection else 'e1'
                host.models.complete.return_value = {'text': json.dumps({'action': 'tap', 'target': target})}
                host.act = Mock(side_effect=AssertionError('no dispatch authorized'))
                with subprocess.Popen([binary, 'run', '--request', 'Inspect this screen', '--max-steps', '1'],
                                      stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as proc:
                    result = host.serve(proc)
                    proc.stdin.close()
                    proc.wait(timeout=10)
                    self.assertEqual(proc.returncode, 0)
                self.assertEqual(host.env.state_reads, 2)
                self.assertEqual(next(line['data']['nodes'] for line in host.lines
                                      if line.get('dir') == 'reply' and 'nodes' in line.get('data', {})),
                                 core_bridge.forest_to_nodes(new))
                self.assertFalse(result['report']['done'])
                host.act.assert_not_called()
                self.assertEqual(len(host.confirmations), 0 if collection else 1)
                if collection:
                    self.assertIn('no identified control', result['report']['reason'])
                    self.assertNotIn('Delete', repr(host.models.complete.call_args_list))
                else:
                    self.assertIn('delete', json.dumps(host.confirmations).lower())
                    self.assertNotIn('Continue', repr(host.models.complete.call_args_list))


class CoreBinTest(unittest.TestCase):
    def test_environment_override_wins(self):
        with patch.dict(os.environ, {'BALLISTIC_CORE_BIN': '/opt/corefix/ballistic-core'}):
            self.assertEqual(core_bridge.default_core_bin(), '/opt/corefix/ballistic-core')


if __name__ == '__main__':
    unittest.main()
