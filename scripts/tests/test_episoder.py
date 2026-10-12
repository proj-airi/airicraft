import importlib.util
import json
import pathlib
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("episoder", pathlib.Path(__file__).parents[1] / "episoder.py")
episoder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(episoder)


def write(path, rows):
    path.write_text("".join(json.dumps(r) + "\n" for r in rows))


class EpisoderTest(unittest.TestCase):
    def run_dir(self, events, timeline=()):
        directory = pathlib.Path(tempfile.mkdtemp())
        write(directory / "events.jsonl", [{"event": e} for e in events])
        write(directory / "debug-timeline.jsonl", [{"entry": t} for t in timeline])
        return directory

    def test_cuts_on_goal_boundaries_and_drops_noise(self):
        directory = self.run_dir([
            {"tick": 10, "type": "planner.goal_set", "payload": {"goal": "get wood"}},
            {"tick": 20, "type": "perception.block_noticed", "payload": {}},
            {"tick": 30, "type": "task.completed", "payload": {"id": "w1"}},
            {"tick": 40, "type": "planner.goal_set", "payload": {"goal": "get stone"}},
            {"tick": 50, "type": "task.failed", "payload": {"code": "no_path"}},
        ])
        segments = episoder.segment(episoder.load_records(directory))
        self.assertEqual([[r["type"] for r in s] for s in segments],
                         [["planner.goal_set", "task.completed"], ["planner.goal_set", "task.failed"]])

    def test_long_gap_splits(self):
        directory = self.run_dir([
            {"tick": 10, "type": "work.changed", "payload": {"n": 1}},
            {"tick": 10 + episoder.GAP_TICKS + 1, "type": "work.changed", "payload": {"n": 2}},
        ])
        self.assertEqual(len(episoder.segment(episoder.load_records(directory))), 2)

    def test_timeline_merged_by_tick_with_source_pointer(self):
        directory = self.run_dir(
            [{"tick": 5, "type": "task.started", "payload": {}}],
            [{"tick": 3, "domain": "tool", "action": "call", "summary": "navigate_to x=1"}])
        records = episoder.load_records(directory)
        self.assertEqual([r["src"] for r in records], ["debug-timeline.jsonl:1", "events.jsonl:1"])

    def test_repeated_pings_collapse_and_secrets_are_redacted(self):
        directory = self.run_dir(
            [{"tick": 1, "type": "task.started", "payload": {"url": "http://x/#token=abc123"}}],
            [{"tick": t, "domain": "task", "action": "mining", "summary": "progress"} for t in range(2, 7)])
        (segment,) = episoder.segment(episoder.load_records(directory))
        self.assertEqual(len(segment), 2)
        self.assertIn("x5", segment[1]["text"])
        self.assertNotIn("abc123", segment[0]["text"])

    def test_markdown_has_table_and_sections(self):
        text = episoder.render_markdown([{"id": 0, "tickRange": [1, 2], "outcome": "failed", "title": "Mining failed",
                                          "why": "no_path", "facts": ["a"], "sources": ["x:1", "x:2"]}])
        self.assertIn("| 0 | 1–2 | ❌ failed | Mining failed |", text)
        self.assertIn("**Why:** no_path", text)

    def test_parse_json_tolerates_prose(self):
        self.assertEqual(episoder.parse_json('sure: {"a": 1} done'), {"a": 1})


if __name__ == "__main__":
    unittest.main()
