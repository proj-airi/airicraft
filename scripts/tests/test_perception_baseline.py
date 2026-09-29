#!/usr/bin/env python3
"""Focused tests for the perception baseline driver."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import sys
import unittest
from pathlib import Path
from types import ModuleType

SCRIPT_PATH = Path(__file__).resolve().parents[1] / "perception-baseline"


def load_script() -> ModuleType:
    module_name = "airicraft_perception_baseline_under_test"
    loader = importlib.machinery.SourceFileLoader(module_name, str(SCRIPT_PATH))
    spec = importlib.util.spec_from_loader(module_name, loader)
    if spec is None:
        raise RuntimeError(f"cannot load {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    loader.exec_module(module)
    return module


BASELINE = load_script()
BUILT = {
    "course": "notice_walk",
    "goal": {"x": 28, "y": 201, "z": 1, "exactY": True},
    "expectNoticed": [{"blockId": "minecraft:diamond_ore", "positions": [
        {"x": 10, "y": 201, "z": -1}, {"x": 11, "y": 201, "z": -1}, {"x": 11, "y": 202, "z": -1}]}],
    "expectNeverNoticed": [{"blockId": "minecraft:emerald_ore", "position": {"x": 20, "y": 202, "z": -3}}],
    "items": [{"itemId": "minecraft:bread", "uuid": "b", "expect": "noticed"},
              {"itemId": "minecraft:cobblestone", "uuid": "c", "expect": "garbage"}],
}
HONEST_EVENTS = [
    {"type": "perception.block_noticed", "payload": {"blockId": "minecraft:diamond_ore", "positions": [
        {"x": 10, "y": 201, "z": -1}, {"x": 11, "y": 201, "z": -1}]}},
    {"type": "perception.item_noticed", "payload": {"itemId": "minecraft:bread", "itemEntityUuid": "b"}},
]
HONEST_RECENT = [{"candidateId": "item:c", "outcome": "dropped:garbage"}]


class EvaluateTest(unittest.TestCase):
    def test_an_honest_walk_passes_every_check(self) -> None:
        checks = BASELINE.evaluate(BUILT, HONEST_EVENTS, HONEST_RECENT)
        self.assertEqual([True] * 4, [check["passed"] for check in checks], checks)

    def test_a_sealed_ore_candidate_or_percept_is_an_xray_failure(self) -> None:
        recent = HONEST_RECENT + [{"candidateId": "block:minecraft:emerald_ore@20,202,-3", "outcome": "dropped:budget"}]
        checks = BASELINE.evaluate(BUILT, HONEST_EVENTS, recent)
        self.assertFalse(checks[1]["passed"])
        leaked = HONEST_EVENTS + [{"type": "perception.block_noticed", "payload": {"blockId": "minecraft:emerald_ore"}}]
        self.assertFalse(BASELINE.evaluate(BUILT, leaked, HONEST_RECENT)[1]["passed"])

    def test_a_missed_vein_and_a_noticed_garbage_item_fail(self) -> None:
        events = [{"type": "perception.item_noticed", "payload": {"itemId": "minecraft:cobblestone", "itemEntityUuid": "c"}}]
        checks = BASELINE.evaluate(BUILT, events, [])
        by_name = {check["check"]: check["passed"] for check in checks}
        self.assertFalse(by_name["noticed minecraft:diamond_ore"])
        self.assertFalse(by_name["noticed minecraft:bread"])
        self.assertFalse(by_name["minecraft:cobblestone dropped as garbage"])

    def test_percept_wakes_and_costs(self) -> None:
        before = {"attention": {"countsByRule": {"percept.notice": 2, "catalog.trigger": 5}}}
        after = {"attention": {"countsByRule": {"percept.notice": 5, "percept.dusk_idle": 1, "catalog.trigger": 9}},
                 "perception": {"maxStepMicros": 800, "steps": 3, "failures": 0,
                                "sensors": {"notable_blocks": {"meanNanos": 12000, "p99Nanos": 41000, "maxNanos": 90000, "samples": 400}}}}
        self.assertEqual(4, BASELINE.percept_wakes(before, after))
        costs = BASELINE.sensor_costs(after)
        self.assertEqual(41.0, costs["sensors"]["notable_blocks"]["p99Micros"])
        self.assertEqual(800, costs["salienceMaxStepMicros"])

    def test_run_course_drives_the_walk_and_reports(self) -> None:
        calls: list[tuple[str, str]] = []

        def call(method: str, path: str, payload=None):
            calls.append((method, path))
            if path == BASELINE.COURSE_ROUTE:
                return BUILT
            if path.startswith(BASELINE.EVENTS_ROUTE):
                return {"latestSeqNo": 10, "events": HONEST_EVENTS, "truncated": False}
            if path == BASELINE.STATE_ROUTE:
                return {"attention": {"countsByRule": {}}, "perception": {"recent": HONEST_RECENT, "sensors": {}}}
            if path.startswith(BASELINE.TIMELINE_ROUTE):
                return {"latestEntryId": 1, "entries": [{"entryId": 2, "domain": "task", "action": "terminal_diagnostics",
                                                          "correlation": {"terminalState": "COMPLETED"}}]}
            if path == BASELINE.TOOLS_ROUTE:
                return {"result": 'Tool result for navigate_to: {"accepted":true,"workId":"JOB:1"}'}
            raise AssertionError(path)

        ticks = iter(range(100))
        result = BASELINE.run_course(call, 10, sleep=lambda seconds: None, clock=lambda: float(next(ticks)))
        self.assertEqual("completed", result["walk"])
        self.assertTrue(result["passed"], result)
        self.assertEqual(["perception.block_noticed", "perception.item_noticed"], result["perceptEvents"])


if __name__ == "__main__":
    unittest.main()
