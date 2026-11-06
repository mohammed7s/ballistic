"""Regression tests for host screen readiness; no emulator or model required."""
import unittest
from core_bridge import foreground_tree_ready


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


if __name__ == '__main__':
    unittest.main()
