"""Split, SFT export, filler sidecar round trip, and shadow-report on the fixture."""
import json
import sys
import tempfile
import threading
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from s15 import reading  # noqa: E402
from s15.__main__ import main  # noqa: E402
from s15.backends.base import FillRequest  # noqa: E402
from s15.backends.remote import RemoteFiller  # noqa: E402
from s15.backends.rules import RulesFiller  # noqa: E402
from s15.recordings import load_run  # noqa: E402
from s15.server import serve  # noqa: E402
from s15.shadow_report import report  # noqa: E402
from s15.statedoc import build_docs  # noqa: E402

FIXTURE = ROOT / "tests" / "fixtures" / "run-a"


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_split_keeps_runs_whole_and_export_sft_uses_teacher(self):
        docs = str(self.dir / "docs.jsonl")
        main(["extract", "--run", str(FIXTURE), "--out", docs])
        main(["split", "--docs", docs, "--out-dir", str(self.dir / "split"), "--dev", "0", "--test", "0"])
        split = json.loads((self.dir / "split" / "split.json").read_text())
        self.assertEqual(split["runs"], {"run-a": "train"})
        self.assertEqual(split["docs"]["train"], 9)
        sft = self.dir / "sft.jsonl"
        main(["export-sft", "--docs", docs, "--labels", str(FIXTURE / "teacher-gold.jsonl"), "--out", str(sft)])
        rows = [json.loads(line) for line in sft.read_text().splitlines()]
        self.assertEqual(len(rows), 9)
        self.assertEqual([m["role"] for m in rows[0]["messages"]], ["system", "user", "assistant"])
        self.assertTrue(reading.parse(rows[0]["messages"][-1]["content"]).parse_ok)

    def test_sidecar_round_trip_matches_local_filler(self):
        server = serve(RulesFiller(), port=0)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            remote = RemoteFiller(f"http://127.0.0.1:{server.server_address[1]}")
            self.assertEqual(remote.name, "remote:rules")
            doc = build_docs(load_run(FIXTURE), "event")[5]
            local = RulesFiller().fill(FillRequest(doc))
            result = remote.fill(FillRequest(doc, reading.default_reading(), {"anomaly.kind"}, {"RECENT"}))
            self.assertEqual(result.reading, local.reading)
            self.assertIn("server_ms", result.timings)
            self.assertGreaterEqual(result.latency_ms, result.timings["server_ms"])
        finally:
            server.shutdown()
            server.server_close()

    def test_shadow_report_joins_recording(self):
        run = load_run(FIXTURE)
        docs = build_docs(run, "event")
        filler = RulesFiller()
        shadow = []
        for index, doc in enumerate(docs):
            result = filler.fill(FillRequest(doc)).to_json()
            shadow.append({"wall_s": float(index), "tick": doc.tick, "doc_id": doc.doc_id,
                           "dirty_sections": ["RECENT"], "result": result})
        out = report(shadow, run)
        self.assertEqual(out["refreshes"], 9)
        chat = out["chat_reactions"][0]
        self.assertEqual(chat["tick"], 1100)
        self.assertEqual(chat["fast_intent"], "stop")
        self.assertEqual(chat["fast_ticks"], 0)
        self.assertEqual(chat["system2_ticks"], 94)  # call at 1110 + 84 ticks of latency
        self.assertEqual(chat["system2_tools"], ["cancel_work"])
        agreement = out["escalation_vs_system2"]
        self.assertEqual(sum(agreement[k] for k in ("tp", "fp", "fn", "tn")), 6)  # one per System 2 call

    def test_message_bank_and_paired_compare(self):
        from s15.__main__ import _message_bank
        bank = _message_bank(str(ROOT / "data" / "chat-bank.tsv"))
        self.assertGreaterEqual(len(bank), 20)
        self.assertIn(("stop", "stop"), bank)
        self.assertTrue(all(intent in reading.CHAT_INTENTS for intent, _ in bank))

        docs, preds, teacher, out = (str(self.dir / n) for n in ("d.jsonl", "p.jsonl", "t.jsonl", "c.json"))
        main(["extract", "--run", str(FIXTURE), "--out", docs])
        main(["fill", "--backend", "rules", "--docs", docs, "--out", preds])
        main(["teacher-as-preds", "--labels", str(FIXTURE / "teacher-gold.jsonl"), "--out", teacher])
        main(["compare", "--a", preds, "--b", teacher, "--labels", str(FIXTURE / "teacher-gold.jsonl"),
              "--samples", "200", "--out", out])
        result = json.loads(Path(out).read_text())
        self.assertEqual(result["shared_docs"], 9)
        self.assertGreater(result["mean_b_minus_a"], 0)  # the gold labels score perfectly against themselves
        self.assertEqual(result["a_better_docs"], 0)

    def test_probe_report_and_signal_regimes(self):
        probe = self.dir / "probe.jsonl"
        rows = [{"intent": "stop", "message": "stop", "first": {"llm_dispatch": {"ms": 300.0},
                                                               "planner.response_applied": {"ms": 4200.0},
                                                               "work.changed": {"ms": 4300.0}}},
                {"intent": "stop", "message": "stop", "first": {}}]
        probe.write_text("\n".join(json.dumps(r) for r in rows) + "\n")
        out = self.dir / "probe-report.json"
        main(["probe-report", "--probe", str(probe), "--out", str(out)])
        stop = json.loads(out.read_text())["stop"]
        self.assertEqual(stop["first_reaction"]["p50"], 4200.0)
        self.assertEqual(stop["no_reaction_within_watch"], 1)

        docs, hind, preds, sig = (str(self.dir / n) for n in ("d.jsonl", "h.jsonl", "p.jsonl", "s.json"))
        main(["extract", "--run", str(FIXTURE), "--out", docs])
        main(["label-hindsight", "--run", str(FIXTURE), "--docs", docs, "--out", hind])
        main(["fill", "--backend", "rules", "--docs", docs, "--out", preds])
        main(["signals", "--preds", preds, "--hindsight", hind, "--trigger", "social.", "--docs", docs, "--out", sig])
        self.assertEqual(json.loads(Path(sig).read_text())["n"], 2)


if __name__ == "__main__":
    unittest.main()
