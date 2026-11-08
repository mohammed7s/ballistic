"""Terminal chat over `ballistic-core converse`: history kept here, turns decided in core.

No emulator or model: the model is scripted and a reply turn never observes the screen.
"""
import json
import os
import unittest

import core_bridge


class ScriptedModels:
    """Answers `complete` asks from a list; records every call."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.calls = []
        self.routes = {}
        self.min_interval_seconds = 0.0
        self.disable_native_tools = False

    def complete(self, role, messages, max_tokens):
        self.calls.append({"role": role, "messages": messages})
        return {"text": self.replies.pop(0)}


class NoScreen:
    """A reply turn must never ask for the screen."""

    def __getattr__(self, name):
        raise AssertionError(f"env.{name} used during a reply turn")


class ChatHistoryTest(unittest.TestCase):
    def test_appends_exactly_the_returned_turns_and_caps(self):
        h = core_bridge.ChatHistory(max_turns=3)
        h.append({"history": [{"source": "owner", "text": "a"}, {"source": "assistant", "text": "b"}]})
        h.append({"history": [{"source": "owner", "text": "c"}, {"source": "assistant", "text": "d"}]})
        self.assertEqual([t["text"] for t in h.turns], ["b", "c", "d"])
        h.append({"error": "x"})
        h.append(None)
        self.assertEqual(len(h.turns), 3)
        h.clear()
        self.assertEqual(h.turns, [])

    def test_converse_args(self):
        args = core_bridge.converse_args("now set it to 8", [{"source": "owner", "text": "hi"}], 10, 12, judge=True)
        self.assertEqual(args[:3], ["converse", "--message", "now set it to 8"])
        self.assertEqual(json.loads(args[args.index("--history") + 1]), [{"source": "owner", "text": "hi"}])
        self.assertIn("--judge", args)
        self.assertNotIn("--judge", core_bridge.converse_args("x", [], 10))


@unittest.skipUnless(os.path.exists(core_bridge.default_core_bin()), "ballistic-core is not built")
class ConverseBinaryTest(unittest.TestCase):
    def turn(self, models, history, message):
        host = core_bridge.CoreHost(NoScreen(), models)
        proc = core_bridge.start_core(core_bridge.default_core_bin(), core_bridge.converse_args(message, history, 5))
        finished = host.serve(proc)
        proc.stdin.close()
        proc.wait(timeout=30)
        proc.stdout.close()
        proc.stderr.close()
        return finished, host

    def test_reply_turn_keeps_history_and_never_observes(self):
        history = core_bridge.ChatHistory()
        models = ScriptedModels(['{"kind":"reply","text":"Hello."}', '{"kind":"reply","text":"You said hi."}'])
        finished, host = self.turn(models, history.turns, "hi")
        self.assertEqual(finished["turn"]["kind"], "reply")
        history.append(finished["turn"])
        finished, host = self.turn(models, history.turns, "what did I say?")
        self.assertEqual(finished["turn"]["text"], "You said hi.")
        self.assertEqual([c["role"] for c in models.calls], ["chat", "chat"])
        self.assertIn('{\\"source\\":\\"owner\\",\\"text\\":\\"hi\\"}', json.dumps(models.calls[1]["messages"]))
        self.assertFalse(any(line.get("ask") in ("observe", "act", "confirm") for line in host.lines))

    def test_unreadable_model_output_is_a_fixed_turn(self):
        models = ScriptedModels(["sure", "still not json"])
        finished, _ = self.turn(models, [], "do it")
        self.assertEqual(finished["turn"]["kind"], "not_understood")
        self.assertEqual(len(models.calls), 2)


if __name__ == "__main__":
    unittest.main()
