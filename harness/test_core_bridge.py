"""Regression tests for host screen readiness; no emulator or model required."""
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from core_bridge import CoreHost, ObservationError, foreground_tree_ready
from outcomes import failure_category


class ScreenReadinessTest(unittest.TestCase):
    def node(self, package, **flags):
        return {'package': package, 'label': 'Clock', 'text': None,
                'flags': {'visible': True, **flags},
                'bounds': {'x1': 0, 'y1': 0, 'x2': 100, 'y2': 100}}

    def test_system_chrome_is_not_loaded_app(self):
        nodes = [self.node('com.android.systemui') for _ in range(6)]
        nodes.append(self.node('com.android.inputmethod.latin'))
        self.assertFalse(foreground_tree_ready(nodes, 'com.google.android.contacts'))
        nodes.append(self.node('com.google.android.contacts', editable=True))
        self.assertTrue(foreground_tree_ready(nodes, 'com.google.android.contacts'))

    def test_invisible_app_is_not_ready(self):
        self.assertFalse(foreground_tree_ready([self.node('app', visible=False)], 'app'))


def window(package='app', text='Screen A', *, active=True, focused=True,
           window_type=1, uid=1, visible=True, clickable=False):
    """Proto-shaped raw window: exercise real flattening/readiness, not a mock."""
    node = SimpleNamespace(
        unique_id=uid, child_ids=[], package_name=package, text=text,
        class_name='android.widget.TextView', content_description='', hint_text='',
        tooltip_text='', view_id_resource_name=package + ':id/title',
        bounds_in_screen=SimpleNamespace(left=0, top=0, right=100, bottom=100),
        is_clickable=clickable, is_long_clickable=False, is_editable=False,
        is_scrollable=False, is_checkable=False, is_checked=False,
        is_selected=False, is_enabled=True, is_focused=False,
        is_visible_to_user=visible,
    )
    return SimpleNamespace(window_type=window_type, is_active=active,
                           is_focused=focused, tree=SimpleNamespace(nodes=[node]))


def forest(*windows):
    return SimpleNamespace(windows=windows)


def sample(tree, activity='app/A', *, after=None):
    return activity, tree, activity if after is None else after


class FakeEnv:
    """Each acquisition brackets a scripted forest with two activity reads.

    The last sample repeats to model a permanently stuck producer. Only the
    public observation API is exposed; no AndroidWorld or device is involved.
    """
    logical_screen_size = (1080, 2400)

    def __init__(self, samples):
        self.samples = samples
        self.index = 0
        self.activity_reads = 0
        self.state_reads = 0

    @property
    def foreground_activity_name(self):
        before, _, after = self.samples[min(self.index, len(self.samples) - 1)]
        self.activity_reads += 1
        if self.activity_reads % 2:
            return before
        self.index += 1
        return after

    def get_state(self, *, wait_to_stabilize):
        # Do not nest AndroidWorld's whole-screen settling timeout inside the
        # host's foreground settling loop (it timed out on the real launcher).
        assert wait_to_stabilize is False
        assert self.activity_reads % 2 == 1
        self.state_reads += 1
        return SimpleNamespace(forest=self.samples[min(self.index, len(self.samples) - 1)][1])


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.sleep = patch('core_bridge.time.sleep').start()
        self.clock = patch('core_bridge.time.monotonic', return_value=0).start()
        self.addCleanup(patch.stopall)
        self.old = forest(window())
        self.new = forest(window(text='Screen B'))

    def host(self, *samples):
        host = CoreHost(FakeEnv(samples), Mock())
        host._apps = []  # No installed-app/ADB lookup in these unit tests.
        return host

    def test_settled_unchanged_screen_is_allowed(self):
        host = self.host(sample(self.old))
        first = host.observe()
        second = host.observe()
        self.assertEqual(first['foreground'], {'package': 'app', 'activity': 'A', 'sequence': 1})
        self.assertEqual(second['foreground'], {'package': 'app', 'activity': 'A', 'sequence': 2})
        self.assertEqual(first['capabilities']['observations'], ['foreground_component_v1'])
        self.assertEqual({k: v for k, v in second.items() if k != 'foreground'},
                         {k: v for k, v in first.items() if k != 'foreground'})
        self.assertEqual(first['activity'], 'A')
        self.assertEqual(first['nodes'][0]['text'], 'Screen A')
        self.assertEqual(host.env.state_reads, 4)
        self.assertEqual(host.env.activity_reads, 8)  # No third metadata read.
        self.assertEqual(self.sleep.call_count, 2)
        self.sleep.assert_called_with(0.3)

    def test_activity_transition_during_acquisition_retries(self):
        host = self.host(sample(self.old, after='app/B'), sample(self.old, 'app/B'),
                         sample(self.new, 'app/B'))
        result = host.observe()
        self.assertEqual((result['package'], result['activity']), ('app', 'B'))
        self.assertEqual(result['nodes'][0]['text'], 'Screen B')
        self.assertEqual(result['foreground'], {'package': 'app', 'activity': 'B', 'sequence': 1})
        self.assertEqual(host.env.state_reads, 4)

    def test_startup_transition_between_acquisitions_rejects_unchanged_tree(self):
        host = self.host(sample(self.old), sample(self.old, 'app/B'))
        with self.assertRaisesRegex(ObservationError, 'unchanged_tree_after_activity_change'):
            host.observe()

    def test_prior_app_tree_cannot_follow_a_new_activity(self):
        # Recorded Wi-Fi shape: Settings -> SubSettings with identical app nodes.
        # A clock update and regenerated raw node IDs are not app freshness.
        changed_chrome = forest(
            window('com.android.systemui', '12:01', active=False, focused=False, window_type=3),
            window(uid=99),
        )
        host = self.host(sample(self.old), sample(self.old),
                         sample(changed_chrome, 'app/B'))
        host.observe()
        with self.assertRaisesRegex(ObservationError, 'unchanged_tree_after_activity_change'):
            host.observe()
        self.assertEqual(host.env.state_reads, 8)  # 2 accepted + 6 rejected.
        self.assertEqual(self.sleep.call_count, 6)  # One settle + five rejected; none after last try.
        # Failure must not bless the mixed sample as the baseline for a retry.
        with self.assertRaisesRegex(ObservationError, 'unchanged_tree_after_activity_change'):
            host.observe()
        host.env.samples = (sample(self.new, 'app/B'),)
        self.assertEqual(host.observe()['nodes'][0]['text'], 'Screen B')

    def test_changed_activity_waits_for_changed_and_settled_tree(self):
        host = self.host(sample(self.old), sample(self.old), sample(self.old, 'app/B'),
                         sample(self.new, 'app/B'))
        host.observe()
        self.assertEqual(host.observe()['activity'], 'B')
        self.assertEqual(host.env.state_reads, 5)

    def test_systemui_status_bar_does_not_prove_dialog_readiness(self):
        # Recorded Brightness shape: SystemUI activity + old Settings app tree.
        tree = forest(window('com.android.settings', 'Display'),
                      window('com.android.systemui', '12:00', active=False,
                             focused=False, window_type=3))
        host = self.host(sample(tree, 'com.android.systemui/Dialog'))
        with self.assertRaisesRegex(ObservationError, 'no_ready_foreground'):
            host.observe()

    def test_foreground_system_dialog_is_ready(self):
        tree = forest(window('com.android.settings', 'Display', active=False, focused=False),
                      window('com.android.systemui', 'Dialog', window_type=3))
        host = self.host(sample(tree, 'com.android.systemui/Dialog'))
        self.assertEqual(host.observe()['package'], 'com.android.systemui')

    def test_keyboard_does_not_disqualify_focused_app(self):
        tree = forest(window(active=False),
                      window('ime', 'Keyboard', window_type=2))
        self.assertEqual(self.host(sample(tree)).observe()['activity'], 'A')

    def test_no_ready_tree_fails_instead_of_returning_last_sample(self):
        cases = [None, forest(), forest(window('different.app')),
                 forest(window(visible=False)),
                 forest(window(active=False, focused=False)),
                 forest(window(window_type=2)), forest(window(text=''))]
        for tree in cases:
            with self.subTest(tree=tree):
                host = self.host(sample(tree))
                reply = host._answer({'id': 7, 'ask': 'observe'})
                self.assertFalse(reply['ok'])
                self.assertNotIn('data', reply)
                self.assertEqual(host._observation_sequence, 0)
                self.assertIn('environment observation failed: no_ready_foreground', reply['error'])
                self.assertEqual(host.env.state_reads, 6)
                host.models.complete.assert_not_called()

    def test_missing_or_partial_activity_is_not_ready(self):
        for activity in ('', 'app', 'app/', '/A'):
            with self.subTest(activity=activity):
                host = self.host(sample(self.old, activity))
                with self.assertRaisesRegex(ObservationError, 'missing_foreground_activity'):
                    host.observe()

    def test_changing_foreground_never_settles(self):
        host = self.host(*(sample(self.old if i % 2 else self.new) for i in range(6)))
        with self.assertRaisesRegex(ObservationError, 'foreground_not_settled'):
            host.observe()

    def test_unready_sample_resets_consecutive_check(self):
        host = self.host(sample(self.old), sample(None), sample(self.old))
        host.observe()
        self.assertEqual(host.env.state_reads, 4)

    def test_one_ready_sample_at_retry_limit_is_not_returned(self):
        host = self.host(*([sample(None)] * 5), sample(self.old))
        with self.assertRaisesRegex(ObservationError, 'foreground_not_settled'):
            host.observe()
        self.assertEqual(host.env.state_reads, 6)

    def test_activity_churn_never_returns_mixed_view(self):
        host = self.host(sample(self.old, after='app/B'))
        with self.assertRaisesRegex(ObservationError, 'activity_changed_during_observation'):
            host.observe()
        self.assertEqual(host.env.state_reads, 6)

    def test_slow_acquisition_hits_soft_deadline(self):
        host = self.host(sample(self.old))
        self.clock.side_effect = [0, 0, 31, 31]  # past the 30-second soft deadline
        with self.assertRaisesRegex(ObservationError, 'acquisition_deadline'):
            host.observe()
        self.assertEqual(host.env.state_reads, 1)
        self.sleep.assert_not_called()

    def test_observation_transport_error_is_sanitized_environment_failure(self):
        host = self.host(sample(self.old))
        host.env.get_state = Mock(side_effect=RuntimeError('sensitive device response'))
        reply = host._answer({'id': 42, 'ask': 'observe'})
        self.assertEqual(reply, {'id': 42, 'ok': False,
                                'error': 'ObservationError: environment observation failed: RuntimeError'})
        # Core propagates failed Screen asks as a finished.error, which the
        # harness already classifies as infrastructure rather than model failure.
        run = {'finished': {'error': 'host error: ' + reply['error']}}
        self.assertEqual(failure_category(run, {'benchmark_status': 'failed'}, []), 'environment')


if __name__ == '__main__':
    unittest.main()
