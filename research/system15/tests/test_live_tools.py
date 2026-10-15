"""Probe (E0) and shadow mode (E4a) against a fake bridge that mimics ModBridgeServer's JSON routes."""
import json
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from s15.backends.rules import RulesFiller  # noqa: E402
from s15.bridge import Bridge  # noqa: E402
from s15.probe import run_probe  # noqa: E402
from s15.recordings import iter_jsonl  # noqa: E402
from s15.freeze import run_freeze  # noqa: E402
from s15.shadow import live_runtime_line, run_shadow  # noqa: E402

FIXTURE = ROOT / "tests" / "fixtures" / "run-a"
TOKEN = "test-token"


class FakeRuntime:
    def __init__(self):
        records = [r for r in iter_jsonl(FIXTURE / "live-recording.jsonl") if r.get("type") == "conversation_sources"]
        self.conversation = records[1]["payload"]["canonicalConversation"]  # decision context at tick 1057
        self.events = [{"seqNo": 39, "tick": 1050, "timestampMs": 0, "type": "work.changed", "payload": {}}]
        self.calls = []
        self.dialogue = None
        self.tick = 1060
        self.lock = threading.Lock()
        self.pause_epoch = 0
        self.context_calls = 0
        self.paused = False
        self.pause_log: list[str] = []

    def append(self, event_type, payload):
        with self.lock:
            self.tick += 3
            self.events.append({"seqNo": self.events[-1]["seqNo"] + 1, "tick": self.tick, "timestampMs": 0,
                                "type": event_type, "payload": payload})

    def on_chat(self, sender, message):
        self.append("social.player_spoke", {"player": sender, "message": message, "normalizedMessage": message})
        self.append("social.player_addressed_agent", {"player": sender, "message": message})

        def respond():
            time.sleep(0.3)
            with self.lock:
                call = {"sequenceId": len(self.calls) + 1, "requestKind": "planner", "status": "REQUESTED",
                        "dispatchTick": self.tick}
                self.calls.append(call)
            time.sleep(0.3)
            with self.lock:
                call["status"] = "COMPLETED"
            self.append("planner.response_applied", {"tools": ["cancel_work"]})
            self.append("work.changed", {"workId": "@r9", "state": "CANCELLED"})
            self.dialogue = {"text": "Stopping."}
        threading.Thread(target=respond, daemon=True).start()


def make_handler(runtime: FakeRuntime):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _send(self, status, body):
            data = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _authorized(self):
            if self.headers.get("Authorization") != f"Bearer {TOKEN}":
                self._send(401, {"error": "unauthorized"})
                return False
            return True

        def do_GET(self):
            if not self._authorized():
                return
            url = urlparse(self.path)
            since = int(parse_qs(url.query).get("since", ["-1"])[0])
            with runtime.lock:
                if url.path == "/v1/agent/events/recent":
                    events = [e for e in runtime.events if e["seqNo"] > since]
                    return self._send(200, {"available": True, "latestSeqNo": runtime.events[-1]["seqNo"],
                                            "events": events})
                if url.path == "/v1/agent/context":
                    runtime.context_calls += 1
                    return self._send(200, {"available": True, "canonicalConversation": runtime.conversation,
                                            "reflex": {"state": "IDLE", "safetyEpoch": 0}, "task": {}})
                if url.path == "/v1/agent/goals":
                    return self._send(200, {"available": True, "lastDialogueResponse": runtime.dialogue,
                                            "reflex": {"state": "IDLE", "safetyEpoch": 0},
                                            "task": {"state": "RUNNING", "taskType": "COLLECT_RESOURCE"}})
                if url.path == "/v1/agent/debug/llm-calls":
                    records = [c for c in runtime.calls if c["sequenceId"] > since]
                    latest = runtime.calls[-1]["sequenceId"] if runtime.calls else 0
                    return self._send(200, {"available": True, "latestSequenceId": latest, "records": records})
            self._send(404, {"error": "not_found"})

        def do_POST(self):
            if not self._authorized():
                return
            body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            path = urlparse(self.path).path
            if path == "/v1/agent/debug/ticks/pause":
                with runtime.lock:
                    runtime.pause_epoch += 1
                    runtime.paused = True
                    runtime.pause_log.append("pause")
                return self._send(200, {"available": True, "debugSessionId": "s1", "pauseEpoch": runtime.pause_epoch,
                                        "paused": True})
            if path == "/v1/agent/debug/ticks/continue":
                if body.get("pauseEpoch") != runtime.pause_epoch or not runtime.paused:
                    return self._send(503, {"error": "stale_pause_epoch"})
                with runtime.lock:
                    runtime.paused = False
                    runtime.pause_log.append("continue")
                return self._send(200, {"available": True, "paused": False})
            if path == "/v1/agent/debug/chat":
                runtime.on_chat(body.get("senderName") or "Dev", body["message"])
                return self._send(200, {"available": True, "accepted": True, "senderName": body.get("senderName"),
                                        "sessionMode": "SINGLEPLAYER"})
            self._send(404, {"error": "not_found"})
    return Handler


class LiveToolsTest(unittest.TestCase):
    def setUp(self):
        self.runtime = FakeRuntime()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.runtime))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.tmp = tempfile.TemporaryDirectory()
        state = Path(self.tmp.name) / "bridge-state.json"
        state.write_text(json.dumps({"port": self.server.server_address[1], "token": TOKEN,
                                     "startedAtEpochMillis": 0, "processId": 1}))
        self.bridge = Bridge(str(state))

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def test_probe_times_the_response_chain(self):
        result = run_probe(self.bridge, "airi stop", "Alex", watch_s=1.5, poll_s=0.05)
        first = result["first"]
        for key in ("llm_dispatch", "planner.response_applied", "work.changed", "dialogue_reply"):
            self.assertIn(key, first)
        self.assertLess(first["llm_dispatch"]["ms"], first["planner.response_applied"]["ms"])
        self.assertGreaterEqual(first["planner.response_applied"]["ms"], 500)
        self.assertEqual(first["work.changed"]["ticks_after_chat"], 9)

    def test_shadow_logs_warm_refreshes_without_acting(self):
        out = Path(self.tmp.name) / "shadow.jsonl"
        threading.Timer(0.3, lambda: self.runtime.on_chat("Alex", "airi come here")).start()
        count = run_shadow(self.bridge, RulesFiller(), out, duration_s=1.2, period_s=0.1)
        rows = list(iter_jsonl(out))
        self.assertGreaterEqual(count, 5)
        self.assertEqual(rows[0]["context_tick"], 1057)
        self.assertIn("SELF", rows[0]["dirty_sections"])
        intents = [row["result"]["reading"]["chat.intent"] for row in rows]
        self.assertEqual(intents[0], "none")
        self.assertIn("come_here", intents)
        self.assertEqual(len(self.runtime.calls), 1)  # only the injected chat caused a (fake) planner call
        self.assertLessEqual(self.runtime.context_calls, 2)  # start + the one new System 2 call, not every refresh

    def test_live_line_uses_goals_snapshot(self):
        line = live_runtime_line(self.bridge.goals())
        self.assertIn('Live reflex: {"state":"IDLE","safetyEpoch":0}', line)
        self.assertIn('"taskType":"COLLECT_RESOURCE"', line)

    def test_freeze_pauses_only_while_a_planner_call_is_in_flight(self):
        out = Path(self.tmp.name) / "freeze.jsonl"
        threading.Timer(0.2, lambda: self.runtime.on_chat("Alex", "airi stop")).start()
        summary = run_freeze(self.bridge, out, duration_s=1.5, poll_s=0.02)
        self.assertEqual(self.runtime.pause_log, ["pause", "continue"])
        self.assertFalse(self.runtime.paused)
        self.assertEqual(summary["freeze_intervals"], 1)
        self.assertGreater(summary["frozen_ms"], 200)
        rows = list(iter_jsonl(out))
        self.assertEqual([row["event"] for row in rows], ["pause", "continue", "summary"])
        self.assertEqual((rows[0]["debugSessionId"], rows[0]["pauseEpoch"]), ("s1", 1))


if __name__ == "__main__":
    unittest.main()
