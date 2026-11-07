"""HTTP pacing uses a fake clock; no network, emulator or real waits."""
import unittest
from unittest.mock import patch

import requests

from core_bridge import ModelClient
from test_core_bridge_robustness import ok, response


class ModelPacingTests(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.starts = []
        self.responses = []
        self.client = ModelClient({'planner': ('https://models.example/v1', 'm', 'test')},
                                  min_interval_seconds=5)
        patch('core_bridge.time.monotonic', side_effect=lambda: self.now).start()
        patch('core_bridge.time.sleep', side_effect=self.advance).start()
        patch('requests.post', side_effect=self.post).start()
        self.addCleanup(patch.stopall)

    def advance(self, seconds):
        self.now += seconds

    def post(self, *args, **kwargs):
        self.starts.append(self.now)
        self.advance(0.2)
        return self.responses.pop(0) if self.responses else ok()

    def complete(self, role='planner'):
        return self.client.complete(role, [{'role': 'user', 'content': 'x'}], 10)

    def test_roles_share_spacing_and_idle_time_counts(self):
        self.complete()
        self.complete('verifier')
        self.advance(8)
        self.complete('reader')
        self.assertEqual(self.starts[:2], [100.0, 105.0])
        self.assertAlmostEqual(self.starts[2], 113.2)
        self.assertEqual([c['pacing_seconds'] for c in self.client.calls], [0, 4.8, 0])

    def test_transient_retry_obeys_spacing_without_extra_attempts(self):
        self.responses = [response(503, 'temporary'), ok()]
        self.complete()
        self.assertEqual(self.starts, [100.0, 105.0])
        # The existing two-second retry backoff counts toward the five-second interval.
        self.assertEqual(self.client.calls[0]['pacing_seconds'], 2.8)
        self.assertEqual(len(self.client.calls[0]['retried']), 1)

    def test_quota_still_ends_call_without_retry(self):
        self.complete()
        self.responses = [response(429, 'per minute quota')]
        with self.assertRaises(requests.HTTPError):
            self.complete()
        self.assertEqual(self.starts, [100.0, 105.0])
        self.assertEqual(self.client.calls[-1]['http_status'], 429)
        self.assertEqual(self.client.calls[-1]['pacing_seconds'], 4.8)
        self.assertNotIn('retried', self.client.calls[-1])

    def test_default_disables_pacing_and_invalid_intervals_fail(self):
        self.client = ModelClient(self.client.routes)
        self.complete()
        self.complete()
        self.assertAlmostEqual(self.starts[1] - self.starts[0], 0.2)
        self.assertNotIn('pacing_seconds', self.client.calls[-1])
        for interval in [-1, 61, float('nan'), float('inf')]:
            with self.subTest(interval=interval), self.assertRaises(ValueError):
                ModelClient(self.client.routes, min_interval_seconds=interval)


if __name__ == '__main__':
    unittest.main()
