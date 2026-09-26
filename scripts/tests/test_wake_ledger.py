import copy
import json
import pathlib
import tempfile
import unittest

from scripts import wake_ledger


FIXTURE = pathlib.Path(__file__).parent / "fixtures" / "wake-ledger" / "run-1"
CANONICAL_FIXTURE = FIXTURE.parent / "run-2"


class WakeLedgerTest(unittest.TestCase):
    def test_fixture_metrics_and_attribution(self):
        ledger = wake_ledger.build_ledger(FIXTURE)
        self.assertEqual(ledger["schema"], "airicraft.wake-ledger.v1")
        self.assertEqual([r["phase"] for r in ledger["requests"]], ["INITIAL", "TOOL_FOLLOW_UP", "INITIAL"])
        self.assertEqual([r["dispatchAgentTick"] for r in ledger["requests"]], [50, 60, 150])
        self.assertEqual([r["dispatchServerTick"] for r in ledger["requests"]], [100, 110, 200])
        self.assertEqual(ledger["requests"][0]["wakes"][0]["path"], "W1")
        self.assertTrue(ledger["requests"][0]["wakes"][0]["submittedAttempt"])
        self.assertTrue(ledger["requests"][0]["userTurn"])
        self.assertEqual(ledger["requests"][2]["wakes"][0]["path"], "unknown")
        self.assertIn("manual trigger", ledger["requests"][2]["triggerHint"])
        self.assertEqual(ledger["requests"][0]["newEvents"][0]["seqNo"], 1)
        self.assertEqual(ledger["metrics"]["followUpsPerTurn"], 0.5)
        self.assertEqual(ledger["metrics"]["requestsPerMinute"], {"overall": 24.0, "byOwner": {"controller": 24.0}, "byPath": {"W1": 12.0, "unknown": 12.0}})
        self.assertEqual(ledger["metrics"]["emptyWakes"]["unknown"], 1)
        self.assertEqual(ledger["metrics"]["outcomeLatencyTicks"], {"p50": 5, "p90": 5, "max": 5, "count": 1})
        self.assertEqual(ledger["metrics"]["chatReplyLatencyTicks"], {"toRequest": {"p50": 5, "p90": 5, "max": 5, "count": 1}, "toApplied": {"p50": 10, "p90": 10, "max": 10, "count": 1}})
        self.assertEqual(ledger["metrics"]["droppedWakes"], {"G5.run_policy": 1})
        self.assertTrue(ledger["drops"][0]["retainedPending"])
        self.assertEqual(ledger["metrics"]["tokensPerHour"], 43200.0)
        self.assertFalse(ledger["metrics"]["timelineGaps"])
        self.assertFalse(ledger["metrics"]["eventGaps"])

    def test_diff_detects_changed_path(self):
        before = wake_ledger.build_ledger(FIXTURE)
        after = copy.deepcopy(before)
        after["requests"][0]["wakes"][0]["path"] = "W2"
        result = wake_ledger.diff_ledgers(before, after)
        self.assertIn("wakePaths", result["changedRequests"][0]["differences"])

    def test_canonical_observe_uses_raw_event_sequence(self):
        ledger = wake_ledger.build_ledger(CANONICAL_FIXTURE)
        first = ledger["requests"][0]
        self.assertEqual((first["dispatchAgentTick"], first["observeServerTick"]), (55, 100))
        self.assertEqual(first["owner"], "controller")
        self.assertEqual(first["newEvents"], [{"seqNo": 3, "rawSeqNo": 3, "tick": 52,
                                               "type": "work.changed", "recorded": True}])
        self.assertTrue(first["userTurn"])
        self.assertEqual(ledger["metrics"]["outcomeLatencyTicks"], {"p50": 3, "p90": 3, "max": 3, "count": 1})
        self.assertEqual(ledger["metrics"]["chatReplyLatencyTicks"]["toRequest"]["p50"], 5)
        self.assertEqual(ledger["metrics"]["chatReplyLatencyTicks"]["toApplied"]["p50"], 15)
        self.assertFalse(ledger["metrics"]["eventGaps"])
        self.assertFalse(ledger["requests"][1]["userTurn"])

    def test_observe_result_requires_matching_call_id(self):
        messages = [{"role": "assistant", "tool_calls": [{"id": "observe-1", "function": {"name": "observe"}}]},
                    {"role": "tool", "tool_call_id": "observe-1", "content": json.dumps({"tick": 5,
                        "serverTick": 10, "afterEventSequence": 0, "throughEventSequence": 1, "events": []})},
                    {"role": "tool", "tool_call_id": "unrelated", "content": json.dumps({"tick": 99,
                        "serverTick": 99, "afterEventSequence": 0, "throughEventSequence": 99, "events": []})}]
        self.assertEqual(wake_ledger.observation(messages)[0]["tick"], 5)

    def test_summary_truncation_and_token_window(self):
        ledger = wake_ledger.build_ledger(CANONICAL_FIXTURE)
        metrics = ledger["metrics"]
        self.assertEqual(metrics["tokensTotalRecorded"], 130)
        self.assertEqual(metrics["tokensInWindow"], 30)
        self.assertEqual(metrics["tokenWindow"], {"startServerTick": 100, "endServerTick": 200, "durationTicks": 100})
        self.assertEqual(metrics["tokensPerHour"], 21600.0)
        with tempfile.TemporaryDirectory() as tmp:
            target = pathlib.Path(tmp)
            for path in CANONICAL_FIXTURE.iterdir():
                (target / path.name).write_bytes(path.read_bytes())
            calls = (target / "planner-calls.jsonl").read_text().splitlines()
            (target / "planner-calls.jsonl").write_text(calls[0] + "\n")
            single = wake_ledger.build_ledger(target)["metrics"]
            self.assertEqual(single["tokensInWindow"], 10)
            self.assertEqual(single["tokenWindow"]["durationTicks"], 1)
            self.assertEqual(single["tokensPerHour"], 720000.0)
            (target / "planner-calls.jsonl").write_text("\n".join(calls) + "\n")
            (target / "summary.json").write_text(json.dumps({"eventsTruncated": True,
                "debugTimelineTruncated": True, "llmCallsTruncated": True}))
            partial = wake_ledger.build_ledger(target)["metrics"]
            self.assertTrue(partial["eventGaps"])
            self.assertTrue(partial["timelineGaps"])
            self.assertTrue(partial["llmGaps"])
            self.assertIsNone(partial["tokensPerHour"])

    def test_cli_writes_and_summarizes(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = pathlib.Path(tmp) / "ledger.json"
            self.assertEqual(wake_ledger.main(["ledger", str(FIXTURE), "-o", str(output)]), 0)
            self.assertEqual(json.loads(output.read_text())["schema"], "airicraft.wake-ledger.v1")
            self.assertEqual(wake_ledger.main(["summarize", str(FIXTURE)]), 0)


if __name__ == "__main__":
    unittest.main()
