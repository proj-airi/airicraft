"""AR baseline filler against a fake OpenAI-compatible server (request shape, parsing, logprob confidence)."""
import json
import math
import sys
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from s15 import reading  # noqa: E402
from s15.backends.base import FillRequest  # noqa: E402
from s15.backends.openai_compat import OpenAIFiller  # noqa: E402
from s15.oai import OpenAICompatClient  # noqa: E402
from s15.recordings import load_run  # noqa: E402
from s15.statedoc import build_docs  # noqa: E402

ANSWER = dict(reading.default_reading(), **{"chat.intent": "come_here", "chat.target": "Alex", "say": "Coming."})


class FakeOpenAI(BaseHTTPRequestHandler):
    requests: list = []

    def log_message(self, *args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        FakeOpenAI.requests.append(body)
        text, spans = reading.serialize(ANSWER)
        start, end = spans["chat.intent"]  # the model is unsure only about the intent's characters
        tokens = [{"token": ch, "logprob": -0.5 if start <= i < end else -0.01} for i, ch in enumerate(text)]
        payload = {"choices": [{"message": {"role": "assistant", "content": text}, "logprobs": {"content": tokens}}],
                   "usage": {"prompt_tokens": 1500, "completion_tokens": 120,
                             "prompt_tokens_details": {"cached_tokens": 1200}}}
        data = json.dumps(payload).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


class OpenAIFillerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), FakeOpenAI)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        cls.doc = build_docs(load_run(ROOT / "tests" / "fixtures" / "run-a"), "event")[3]

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def client(self):
        return OpenAICompatClient(f"http://127.0.0.1:{self.server.server_address[1]}/v1", "fake", api_key="k")

    def test_full_mode_parses_and_reports_confidence(self):
        filler = OpenAIFiller(self.client(), mode="full", logprobs=True)
        result = filler.fill(FillRequest(self.doc))
        request = FakeOpenAI.requests[-1]
        self.assertEqual(request["response_format"], {"type": "json_object"})
        self.assertTrue(request["logprobs"])
        self.assertIn("## RECENT", request["messages"][1]["content"])
        self.assertNotIn("PREVIOUS READING", request["messages"][1]["content"])
        self.assertEqual(result.reading, ANSWER)
        self.assertAlmostEqual(result.slot_confidence["chat.intent"], math.exp(-0.5), places=3)
        self.assertGreater(result.slot_confidence["escalate.level"], 0.98)
        self.assertEqual(result.usage["prompt_tokens_details"]["cached_tokens"], 1200)

    def test_update_mode_puts_previous_reading_last(self):
        filler = OpenAIFiller(self.client(), mode="update")
        filler.fill(FillRequest(self.doc, reading.default_reading(), {"chat.intent"}, {"RECENT"}))
        content = FakeOpenAI.requests[-1]["messages"][1]["content"]
        self.assertGreater(content.index("PREVIOUS READING"), content.index("## RECENT"))
        self.assertIn("Inputs changed for: chat.intent", content)


if __name__ == "__main__":
    unittest.main()
