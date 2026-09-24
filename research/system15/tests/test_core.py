"""Stdlib-only tests for the harness: schema, recordings, state documents, labels, rules, metrics, CLI."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from s15 import labels, metrics, reading, signals  # noqa: E402
from s15.__main__ import main  # noqa: E402
from s15.backends.base import FillRequest  # noqa: E402
from s15.backends.rules import RulesFiller, classify_chat  # noqa: E402
from s15.recordings import load_run  # noqa: E402
from s15.statedoc import build_docs, dirty_sections  # noqa: E402

FIXTURE = ROOT / "tests" / "fixtures" / "run-a"


class ReadingTest(unittest.TestCase):
    def test_canonical_round_trip_and_spans(self):
        flat = dict(reading.default_reading(), **{"chat.intent": "stop", "say": 'say "hi"', "threats.1.ref": "@r7"})
        text, spans = reading.serialize(flat)
        parsed = reading.parse(text)
        self.assertTrue(parsed.parse_ok)
        self.assertEqual(parsed.reading, flat)
        self.assertEqual(reading.value_spans(text), spans)
        self.assertEqual(text[slice(*spans["threats.1.ref"])], "@r7")

    def test_lenient_parse_repairs_truncation_and_snaps_enums(self):
        parsed = reading.parse('noise {"chat": {"intent": "Come here", "target": "Al')
        self.assertTrue(parsed.parse_ok)
        self.assertEqual(parsed.reading["chat.intent"], "come_here")
        self.assertEqual(parsed.reading["chat.target"], "Al")
        self.assertFalse(parsed.present["escalate.level"])
        self.assertEqual(parsed.reading["escalate.level"], "no")

    def test_invalid_enum_falls_back_to_default(self):
        parsed = reading.parse('{"escalate": {"level": "maybe"}}')
        self.assertEqual(parsed.reading["escalate.level"], "no")
        self.assertFalse(parsed.exact["escalate.level"])

    def test_dirty_slots_follow_declared_dependencies(self):
        self.assertIn("chat.intent", reading.dirty_slots({"RECENT"}))
        self.assertNotIn("chat.intent", reading.dirty_slots({"OBJECTIVE"}))
        self.assertEqual(reading.dirty_slots(set()), set())


class RecordingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.recording = load_run(FIXTURE)
        cls.docs = build_docs(cls.recording, "event")

    def test_structured_contexts_from_canonical_conversation(self):
        self.assertEqual([c.tick for c in self.recording.contexts], [143, 1057, 3502, 4216])
        self.assertTrue(all(c.current is not None for c in self.recording.contexts))

    def test_terminal_llm_records_win(self):
        self.assertEqual(len(self.recording.calls), 6)
        self.assertTrue(all(c.status == "COMPLETED" for c in self.recording.calls))
        self.assertEqual(self.recording.calls[2].tool_calls[0]["name"], "cancel_work")
        self.assertEqual(self.recording.calls[2].latency_ms, 4200)

    def test_fallback_to_llm_calls_without_live_recording(self):
        with tempfile.TemporaryDirectory() as tmp:
            for name in ("llm-calls.jsonl", "events.jsonl"):
                (Path(tmp) / name).write_text((FIXTURE / name).read_text())
            run = load_run(Path(tmp))
        self.assertEqual([c.tick for c in run.contexts], [143, 1057, 3502, 4216])
        self.assertEqual(run.contexts[0].source, "llm-calls:canonical")

    def test_run_ids_are_unique_across_layouts(self):
        from s15.recordings import default_run_id
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            play = root / "automatic_playtest/v1/e/players/p/plays/20260920T0951--ce2d/extensions/airicraft.playtest"
            scenario = root / "eval-output/20260914-171914/01-iron-pickaxe"
            play.mkdir(parents=True)
            scenario.mkdir(parents=True)
            self.assertEqual(default_run_id(play), "20260920T0951--ce2d")
            self.assertEqual(default_run_id(scenario), "20260914-171914__01-iron-pickaxe")
        self.assertEqual(default_run_id(FIXTURE), "run-a")
        with self.assertRaises(SystemExit):
            main(["inspect", "--run", str(FIXTURE), "--run", str(FIXTURE)])

    def test_renamed_export_is_recognised(self):
        with tempfile.TemporaryDirectory() as tmp:
            export = Path(tmp) / "incident-window.jsonl"  # `airicraft agent debug recording export --output ...`
            export.write_text((FIXTURE / "live-recording.jsonl").read_text())
            run = load_run(export)
        self.assertEqual(len(run.contexts), 4)
        self.assertEqual(len(run.events), 19)

    def test_docs_are_ordered_by_volatility_and_include_chat(self):
        self.assertEqual(len(self.docs), 9)
        chat_doc = next(d for d in self.docs if d.doc_id == "run-a:1100:event:41")
        rendered = chat_doc.render()
        self.assertLess(rendered.index("## OBJECTIVE"), rendered.index("## NOW"))
        self.assertIn("Alex said (to agent): @agent stop and come here", chat_doc.sections["RECENT"])
        self.assertIn("NOW is 43 ticks old", chat_doc.sections["NOW"])

    def test_plan_only_shows_decisions_whose_response_had_arrived(self):
        docs = {d.doc_id: d for d in self.docs}
        at_chat = docs["run-a:1100:event:41"].sections["PLAN"]  # the 1057 call is still in flight (+84 ticks)
        self.assertNotIn("collect_resource", at_chat)
        self.assertNotIn("cancel_work", at_chat)
        self.assertIn("wait_for_work", docs["run-a:3600:event:167"].sections["PLAN"])  # 3502 + 84 <= 3600
        self.assertNotIn("equip_item", docs["run-a:3600:event:167"].sections["PLAN"])

    def test_dirty_sections_ignore_tick_counters(self):
        first, second = self.docs[2], self.docs[3]
        self.assertEqual(dirty_sections(first, second), {"RECENT"})
        self.assertEqual(dirty_sections(None, first), {"SELF", "OBJECTIVE", "PLAN", "NOW", "RECENT"})


class LabelsAndRulesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.recording = load_run(FIXTURE)
        cls.docs = {d.doc_id: d for d in build_docs(cls.recording, "event")}

    def test_hindsight_uses_only_the_next_decision(self):
        rows = {row.doc_id: row for row in labels.hindsight_labels(self.recording, list(self.docs.values()))}
        self.assertFalse(rows["run-a:143:decision"].s2_changed_plan)
        self.assertTrue(rows["run-a:1100:event:41"].s2_changed_plan)
        self.assertTrue(rows["run-a:3600:event:167"].wrong_tool)
        self.assertEqual(rows["run-a:3600:event:167"].imitation["fix.arg"], "stone_pickaxe")

    def test_chat_classifier_priorities(self):
        self.assertEqual(classify_chat("@agent stop and come here"), "stop")
        self.assertEqual(classify_chat("please don't attack my dog"), "dont_attack")
        self.assertEqual(classify_chat("where are you?"), "question")
        self.assertEqual(classify_chat("build a castle by the river"), "complex")

    def test_rules_detect_wrong_tool(self):
        result = RulesFiller().fill(FillRequest(self.docs["run-a:3600:event:167"]))
        self.assertEqual(result.reading["anomaly.kind"], "wrong_tool")
        self.assertEqual(result.reading["fix.action"], "equip")
        self.assertEqual(result.reading["fix.arg"], "stone_pickaxe")


class MetricsTest(unittest.TestCase):
    def test_auroc_with_ties(self):
        self.assertAlmostEqual(metrics.auroc([0.1, 0.4, 0.35, 0.8], [False, False, True, True]), 0.75)
        self.assertAlmostEqual(metrics.auroc([1, 1, 1, 1], [True, False, True, False]), 0.5)
        self.assertIsNone(metrics.auroc([1, 2], [True, True]))

    def test_percentile_nearest_rank(self):
        values = [float(v) for v in range(1, 101)]
        self.assertEqual(metrics.percentile(values, 50), 50.0)
        self.assertEqual(metrics.percentile(values, 95), 95.0)
        self.assertEqual(metrics.percentile([7.0], 99), 7.0)

    def test_bootstrap_resamples_groups(self):
        values, groups = [1.0, 1.0, 0.0, 0.0], ["a", "a", "b", "b"]
        low, high = metrics.bootstrap_ci(values, lambda xs: sum(xs) / len(xs), samples=200, groups=groups)
        self.assertEqual((low, high), (0.0, 1.0))

    def test_token_f1(self):
        self.assertEqual(metrics.token_f1("equip the pickaxe", "equip the pickaxe"), 1.0)
        self.assertEqual(metrics.token_f1("", ""), 1.0)
        self.assertAlmostEqual(metrics.token_f1("equip pickaxe", "equip the pickaxe"), 0.8)

    def test_signals_from_trajectory(self):
        trajectory = [{"slots": {"chat.intent": {"value": v, "entropy": e}}}
                      for v, e in (("none", 0.9), ("stop", 0.5), ("none", 0.2), ("stop", 0.01))]
        result = {"reading": dict(reading.default_reading(), **{"chat.intent": "stop"}), "trajectory": trajectory,
                  "slot_confidence": {"chat.intent": 0.99}}
        self.assertEqual(signals.flip_count(result, "chat.intent"), 3.0)
        self.assertAlmostEqual(signals.final_disagreement(result, "chat.intent"), 0.5)
        self.assertAlmostEqual(signals.temporal_entropy(result, "chat.intent"), 0.6931, places=3)
        self.assertGreater(signals.late_entropy(result, "chat.intent"), 0.0)


class CliTest(unittest.TestCase):
    def test_offline_pipeline_with_rules(self):
        with tempfile.TemporaryDirectory() as tmp:
            docs, hind, preds, score, sig = (str(Path(tmp) / n) for n in
                                             ("docs.jsonl", "h.jsonl", "p.jsonl", "score.json", "sig.json"))
            main(["extract", "--run", str(FIXTURE), "--out", docs])
            main(["label-hindsight", "--run", str(FIXTURE), "--docs", docs, "--out", hind])
            main(["fill", "--backend", "rules", "--docs", docs, "--out", preds])
            main(["score", "--preds", preds, "--labels", str(FIXTURE / "teacher-gold.jsonl"), "--out", score])
            main(["signals", "--preds", preds, "--hindsight", hind, "--out", sig])
            summary = json.loads(Path(score).read_text())
            self.assertEqual(summary["n_docs"], 9)
            self.assertEqual(summary["parse_rate"], 1.0)
            self.assertEqual(summary["per_slot"]["anomaly.kind"], 1.0)
            self.assertIsNotNone(summary["revision"]["revision_recall"])
            report = json.loads(Path(sig).read_text())
            self.assertEqual(report["n"], 9)


if __name__ == "__main__":
    unittest.main()
