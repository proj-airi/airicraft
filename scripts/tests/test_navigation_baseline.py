#!/usr/bin/env python3
"""Focused tests for the navigation baseline driver."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import json
import sys
import unittest
from pathlib import Path
from types import ModuleType
from typing import Any


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "navigation-baseline"


def load_script() -> ModuleType:
    module_name = "airicraft_navigation_baseline_under_test"
    loader = importlib.machinery.SourceFileLoader(module_name, str(SCRIPT_PATH))
    spec = importlib.util.spec_from_loader(module_name, loader)
    if spec is None:
        raise RuntimeError(f"cannot load {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    loader.exec_module(module)
    return module


baseline = load_script()


def receipt(payload: dict[str, Any]) -> dict[str, Any]:
    return {"result": "Tool result for navigate_to: " + json.dumps(payload)}


class FakeBridge:
    """Replays course, tool and timeline responses and records every call."""

    def __init__(self, built: dict[str, Any], tool_result: dict[str, Any], timelines: list[dict[str, Any]],
                 statuses: list[dict[str, Any]]) -> None:
        self.built = built
        self.tool_result = tool_result
        self.timelines = list(timelines)
        self.statuses = list(statuses)
        self.calls: list[tuple[str, str, Any]] = []

    def __call__(self, method: str, path: str, payload: Any = None) -> Any:
        self.calls.append((method, path, payload))
        if path == baseline.COURSE_ROUTE:
            if payload["action"] == "build":
                return self.built
            if payload["action"] == "status":
                return self.statuses.pop(0)
        if path == baseline.TOOLS_ROUTE:
            if payload["name"] == "navigate_to":
                return self.tool_result
            return {"result": "Tool result for " + payload["name"] + ": {}"}
        if path.startswith(baseline.TIMELINE_ROUTE):
            return self.timelines.pop(0) if self.timelines else {"latestEntryId": 0, "entries": []}
        raise AssertionError(f"unexpected call {method} {path}")

    def tool_names(self) -> list[str]:
        return [payload["name"] for method, path, payload in self.calls if path == baseline.TOOLS_ROUTE]


class FakeClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.now += seconds


FLAT_BUILD = {"course": "flat_walk", "expectation": "arrive", "goal": {"x": 23, "y": 201, "z": 1, "exactY": True}}
DIAGNOSTICS_ENTRY = {
    "entryId": 12,
    "domain": "task",
    "action": "terminal_diagnostics",
    "correlation": {"taskId": "t", "goalType": "NAVIGATE_TO", "terminalState": "COMPLETED"},
    "payload": {"navigation": {"elapsedTicks": 90, "pathLength": 22.4, "stalled": False}},
}


class ToolReceiptTest(unittest.TestCase):
    def test_accepted_receipt_carries_its_work_id(self) -> None:
        parsed = baseline.tool_receipt(receipt({"accepted": True, "workId": "JOB:abc"}))
        self.assertTrue(parsed["accepted"])
        self.assertEqual("JOB:abc", parsed["workId"])

    def test_tool_error_is_not_accepted(self) -> None:
        parsed = baseline.tool_receipt({"result": "TOOL_ERROR: travel_bounds"})
        self.assertFalse(parsed["accepted"])
        self.assertIsNone(parsed["workId"])

    def test_rejected_receipt_is_not_accepted(self) -> None:
        self.assertFalse(baseline.tool_receipt(receipt({"accepted": False}))["accepted"])


class ClassifyTest(unittest.TestCase):
    def test_arrival_requires_completion(self) -> None:
        self.assertTrue(baseline.classify("arrive", "completed", {}))
        self.assertFalse(baseline.classify("arrive", "failed", {}))
        self.assertFalse(baseline.classify("arrive", "timeout", {}))

    def test_refusal_requires_no_arrival_and_staying_in_bounds(self) -> None:
        self.assertTrue(baseline.classify("refuse", "rejected", {"insideTravelBounds": True}))
        self.assertTrue(baseline.classify("refuse", "failed", {"insideTravelBounds": True}))
        self.assertFalse(baseline.classify("refuse", "completed", {"insideTravelBounds": True}))
        self.assertFalse(baseline.classify("refuse", "failed", {"insideTravelBounds": False}))


class RunCourseTest(unittest.TestCase):
    def test_completed_navigation_reports_metrics_and_health_lost(self) -> None:
        bridge = FakeBridge(
            FLAT_BUILD,
            receipt({"accepted": True, "workId": "JOB:1"}),
            [{"latestEntryId": 10}, {"entries": [{"entryId": 11, "domain": "planner"}]}, {"entries": [DIAGNOSTICS_ENTRY]}],
            [{"health": 20.0}, {"health": 18.5, "feet": {"x": 23, "y": 201, "z": 1}}],
        )
        clock = FakeClock()

        result = baseline.run_course(bridge, "flat_walk", 30.0, sleep=clock.sleep, clock=clock)

        self.assertEqual("completed", result["outcome"])
        self.assertTrue(result["passed"])
        self.assertEqual(1.5, result["healthLost"])
        self.assertEqual(22.4, result["navigation"]["pathLength"])
        self.assertEqual(["navigate_to"], bridge.tool_names())
        timeline_paths = [path for method, path, payload in bridge.calls if path.startswith(baseline.TIMELINE_ROUTE)]
        self.assertEqual(f"{baseline.TIMELINE_ROUTE}?since=11", timeline_paths[-1])

    def test_timeout_cancels_the_work_it_started(self) -> None:
        bridge = FakeBridge(
            FLAT_BUILD,
            receipt({"accepted": True, "workId": "JOB:7"}),
            [{"latestEntryId": 3}],
            [{"health": 20.0}, {"health": 20.0}],
        )
        clock = FakeClock()

        result = baseline.run_course(bridge, "flat_walk", 5.0, sleep=clock.sleep, clock=clock)

        self.assertEqual("timeout", result["outcome"])
        self.assertFalse(result["passed"])
        cancel = [payload for method, path, payload in bridge.calls
                  if path == baseline.TOOLS_ROUTE and payload["name"] == "cancel_work"]
        self.assertEqual([{"name": "cancel_work", "arguments": {"workId": "JOB:7", "reason": "navigation baseline timeout"}}], cancel)

    def test_refusal_course_sets_and_clears_strategy_bounds(self) -> None:
        bounds = {"minX": 0, "minY": 199, "minZ": 0, "maxX": 13, "maxY": 204, "maxZ": 4}
        bridge = FakeBridge(
            {**FLAT_BUILD, "course": "travel_bounds_refusal", "expectation": "refuse", "travelBounds": bounds},
            {"result": "TOOL_ERROR: outside_travel_bounds"},
            [{"latestEntryId": 0}],
            [{"health": 20.0}, {"health": 20.0, "insideTravelBounds": True}],
        )
        clock = FakeClock()

        result = baseline.run_course(bridge, "travel_bounds_refusal", 30.0, sleep=clock.sleep, clock=clock)

        self.assertEqual("rejected", result["outcome"])
        self.assertTrue(result["passed"])
        self.assertEqual(["configure_travel", "navigate_to", "configure_travel"], bridge.tool_names())
        travel = [payload["arguments"] for method, path, payload in bridge.calls
                  if path == baseline.TOOLS_ROUTE and payload["name"] == "configure_travel"]
        self.assertEqual([{"scope": "strategy", "bounds": bounds}, {"scope": "strategy"}], travel)


class SummaryTest(unittest.TestCase):
    def test_summary_counts_outcomes_and_takes_medians(self) -> None:
        results = [
            {"course": "a", "outcome": "completed", "passed": True, "healthLost": 0.0,
             "navigation": {"elapsedTicks": 100, "pathLength": 10.0, "stalled": False}},
            {"course": "a", "outcome": "failed", "passed": False, "healthLost": 2.0,
             "navigation": {"elapsedTicks": 300, "pathLength": 30.0, "stalled": True}},
            {"course": "a", "outcome": "completed", "passed": True, "healthLost": 0.0,
             "navigation": {"elapsedTicks": 120, "pathLength": 12.0, "stalled": False}},
            {"course": "b", "outcome": "timeout", "passed": False, "healthLost": 0.0, "navigation": {}},
        ]

        summary = baseline.summarize(results)

        self.assertEqual({"completed": 2, "failed": 1}, summary["a"]["outcomes"])
        self.assertEqual(2, summary["a"]["passed"])
        self.assertEqual(120, summary["a"]["medianElapsedTicks"])
        self.assertEqual(12.0, summary["a"]["medianPathLength"])
        self.assertEqual(2.0, summary["a"]["maxHealthLost"])
        self.assertEqual(1, summary["a"]["stalls"])
        self.assertIsNone(summary["b"]["medianElapsedTicks"])
        self.assertIn("b", baseline.format_summary(summary))


if __name__ == "__main__":
    unittest.main()
