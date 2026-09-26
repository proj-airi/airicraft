import copy
import json
import pathlib
import tempfile
import unittest

from scripts import wake_ledger


FIXTURE = pathlib.Path(__file__).parent / "fixtures" / "wake-ledger" / "run-1"


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

    def test_cli_writes_and_summarizes(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = pathlib.Path(tmp) / "ledger.json"
            self.assertEqual(wake_ledger.main(["ledger", str(FIXTURE), "-o", str(output)]), 0)
            self.assertEqual(json.loads(output.read_text())["schema"], "airicraft.wake-ledger.v1")
            self.assertEqual(wake_ledger.main(["summarize", str(FIXTURE)]), 0)


if __name__ == "__main__":
    unittest.main()
