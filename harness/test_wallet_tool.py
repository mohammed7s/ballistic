"""End to end through the bridge: ballistic-core asks `tool`, CoreHost runs ballistic-wallet.

No emulator and no model: the screen is a fixed stub and the planner replies are scripted. The
wallet adapter talks to a JSON-RPC stub on loopback (direct transport). With
BALLISTIC_WALLET_LIVE=1 one more case queries a public mainnet endpoint, over direct HTTPS or,
with BALLISTIC_WALLET_TRANSPORT=tor and BALLISTIC_WALLET_BIN set to a `--features tor` build,
over Tor.

Needs core/target/*/ballistic-core and wallet/target/*/ballistic-wallet (cargo build in each).

  python3 -m unittest harness/test_wallet_tool.py -v
"""
import json
import os
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from core_bridge import CoreHost, WalletTools, default_core_bin, default_wallet_bin, start_core  # noqa: E402

ADDRESS = "0xd8dA6BF26964aF9D7eEd9e03E53415D37aA96045"
HASH = "0x" + "ab" * 32
REQUEST = "Ballistic, what's my Ethereum balance?"
TOOL = {"action": "tool", "tool": "wallet.balance",
        "args": {"wallet_id": "main", "chain_id": 1, "asset": "native"}, "why": "owner asked"}


class WalletHostGrantTest(unittest.TestCase):
    def test_revoked_grant_is_checked_again_at_dispatch(self):
        config = {"wallets": [{"id": "main", "chain_id": 1, "grants": ["wallet.balance"]}]}
        tools = WalletTools(config, "/not/run")
        self.assertTrue(tools.grants())
        config["wallets"][0]["grants"] = []
        with patch("core_bridge.subprocess.run") as child:
            result = tools.call({"tool": "wallet.balance", "wallet_id": "main", "chain_id": 1, "asset": "native"})
        self.assertEqual(result["code"], "not_granted")
        child.assert_not_called()

    def test_core_child_does_not_inherit_numbered_model_keys(self):
        with patch.dict(os.environ, {"BALLISTIC_API_KEY": "test-a", "BALLISTIC_API_KEY_2": "test-b"}), \
             patch("core_bridge.subprocess.Popen") as child:
            start_core("/not/run", [])
        child_env = child.call_args.kwargs["env"]
        self.assertNotIn("BALLISTIC_API_KEY", child_env)
        self.assertNotIn("BALLISTIC_API_KEY_2", child_env)


class RpcStub(BaseHTTPRequestHandler):
    """Chain 1 at block 21000000 with 1.2345 ETH (the spec's illustrative fixture)."""
    seen: list = []

    def do_POST(self):  # noqa: N802
        req = json.loads(self.rfile.read(int(self.headers["content-length"])))
        RpcStub.seen.append(req["method"])
        result = {"eth_chainId": "0x1",
                  "eth_getBlockByNumber": {"number": "0x1406f40", "hash": HASH},
                  "eth_getBalance": hex(12345 * 10**14)}[req["method"]]
        body = json.dumps({"jsonrpc": "2.0", "id": req["id"], "result": result}).encode()
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class ScriptedModels:
    """Planner replies in order; records every prompt it was shown."""

    def __init__(self, replies):
        self.replies = list(replies)
        self.prompts = []

    def complete(self, role, messages, max_tokens):
        self.prompts.append(messages)
        return {"text": json.dumps(self.replies.pop(0)), "usage": {"prompt_tokens": 0, "completion_tokens": 0}}


class StubScreenHost(CoreHost):
    """CoreHost with a fixed screen instead of AndroidWorld; everything else is the real host."""

    def observe(self):
        caps = {"operations": ["open_app"], "app_grants": []}
        if self.tools:
            caps["tool_grants"] = self.tools.grants()
        return {"package": "app.ballistic.assistant", "activity": "Main", "width": 1080,
                "height": 2400, "nodes": [], "installed_apps": [], "capabilities": caps}


def binaries():
    core = default_core_bin()
    wallet = os.environ.get("BALLISTIC_WALLET_BIN") or default_wallet_bin()
    if not (os.path.exists(core) and os.path.exists(wallet)):
        raise unittest.SkipTest("build core and wallet first (cargo build in core/ and wallet/)")
    return core, wallet


class WalletToolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.core_bin, cls.wallet_bin = binaries()
        cls.server = HTTPServer(("127.0.0.1", 0), RpcStub)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.server.server_port}/"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        RpcStub.seen = []

    def run_core(self, config, replies):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "wallets.json")
            with open(path, "w") as f:
                json.dump(config, f)
            tools = WalletTools.from_file(path, self.wallet_bin)
        models = ScriptedModels(replies)
        host = StubScreenHost(env=None, models=models, tools=tools)
        proc = start_core(self.core_bin, ["run", "--request", REQUEST, "--max-steps", "3"])
        try:
            finished = host.serve(proc)
        finally:
            proc.kill()
            proc.wait()
            for pipe in (proc.stdin, proc.stdout, proc.stderr):
                pipe.close()
        return finished, host, models

    def config(self, url, transport="https", grants=("wallet.balance",)):
        return {"wallets": [{"id": "main", "alias": "Main wallet", "address": ADDRESS, "chain_id": 1,
                             "grants": list(grants)}],
                "rpc": {"url": url, "transport": transport}}

    def test_granted_balance_reaches_the_report_with_rpc_provenance(self):
        answer = {"action": "answer", "text": "Main wallet has 1.2345 ETH."}
        finished, host, models = self.run_core(self.config(self.url), [TOOL, answer])
        report = finished["report"]
        card = report["tool_results"][0]
        self.assertEqual(card["balance_wei"], "1234500000000000000")
        self.assertEqual(card["balance_eth"], "1.2345")
        self.assertEqual((card["wallet_id"], card["address"], card["chain_id"]), ("main", ADDRESS, 1))
        self.assertEqual((card["block_number"], card["block_hash"]), (21000000, HASH))
        self.assertEqual((card["transport"], card["trust"]), ("https", "rpc_reported"))
        self.assertTrue(card["source"].startswith("127.0.0.1:"))
        self.assertEqual(report["answer"]["text"], "Main wallet has 1.2345 ETH on Ethereum mainnet.")
        self.assertEqual(report["answer"]["model_text_check"], "consistent")
        self.assertEqual(report["answer"]["values"][0]["source"]["kind"], "rpc")
        self.assertEqual(report["task_status"], "answered")
        self.assertEqual(RpcStub.seen, ["eth_chainId", "eth_getBlockByNumber", "eth_getBalance", "eth_getBlockByNumber"])
        tool_asks = [ln for ln in host.lines if ln.get("ask") == "tool"]
        self.assertEqual(tool_asks[0]["request"], {"tool": "wallet.balance", "wallet_id": "main", "chain_id": 1, "asset": "native"})
        # Adapter logs mask the address; prompts never contain it.
        self.assertFalse(any(ADDRESS in ln for ln in host.tools.stderr))
        self.assertIn("0xd8dA...6045", "\n".join(host.tools.stderr))
        self.assertNotIn(ADDRESS, json.dumps(models.prompts))

    def test_altered_amount_is_not_shown(self):
        lie = {"action": "answer", "text": "Main wallet has 99 ETH."}
        finished, _, _ = self.run_core(self.config(self.url), [TOOL, lie])
        answer = finished["report"]["answer"]
        self.assertIsNone(answer["model_text"])
        self.assertEqual(answer["model_text_check"], "rejected: 99 not in the tool result")
        self.assertEqual(answer["text"], "Main wallet has 1.2345 ETH on Ethereum mainnet.")

    def test_no_grant_means_no_network_request(self):
        finished, host, _ = self.run_core(self.config(self.url, grants=()), [TOOL])
        self.assertEqual(finished["report"]["reason"], "not granted")
        self.assertFalse(any(ln.get("ask") == "tool" for ln in host.lines))
        self.assertEqual(RpcStub.seen, [])

    def test_unreachable_endpoint_fails_closed(self):
        dead = "http://127.0.0.1:9/"
        finished, _, _ = self.run_core(self.config(dead), [TOOL])
        report = finished["report"]
        self.assertEqual(report["reason"], "balance could not be refreshed: network")
        self.assertNotIn("tool_results", report)

    @unittest.skipUnless(os.environ.get("BALLISTIC_WALLET_LIVE") == "1", "set BALLISTIC_WALLET_LIVE=1 for the live endpoint")
    def test_live_mainnet(self):
        answer = {"action": "answer", "text": "Here is the balance of Main wallet."}
        url = os.environ.get("BALLISTIC_WALLET_RPC", "https://ethereum-rpc.publicnode.com")
        transport = os.environ.get("BALLISTIC_WALLET_TRANSPORT", "https")
        finished, _, _ = self.run_core(self.config(url, transport), [TOOL, answer])
        report = finished["report"]
        card = report["tool_results"][0]
        self.assertTrue(card["balance_wei"].isdigit())
        self.assertEqual((card["transport"], card["trust"], card["chain_id"]), (transport, "rpc_reported", 1))
        self.assertEqual(report["answer"]["values"][0]["source"],
                         {"kind": "rpc", "detail": {"source": card["source"], "transport": transport}})
        print(f"\nlive ({transport}): {card['balance_eth']} ETH ({card['balance_wei']} wei) at block {card['block_number']} "
              f"{card['block_hash']} from {card['source']} at {card['observed_at']}")


if __name__ == "__main__":
    unittest.main()
