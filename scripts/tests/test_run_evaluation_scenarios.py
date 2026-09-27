#!/usr/bin/env python3
"""Focused tests for the evaluation scenario runner."""

from __future__ import annotations

import argparse
import importlib.machinery
import importlib.util
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import ModuleType
from unittest import mock


RUNNER_PATH = Path(__file__).resolve().parents[1] / "run-evaluation-scenarios"
FINALIZED_PLAY_RELATIVE_PATH = (
    Path("v1")
    / "server--instance"
    / "players"
    / "player--uuid"
    / "plays"
    / "start--connection"
)


def load_runner() -> ModuleType:
    module_name = "airicraft_evaluation_runner_under_test"
    loader = importlib.machinery.SourceFileLoader(module_name, str(RUNNER_PATH))
    spec = importlib.util.spec_from_loader(module_name, loader)
    if spec is None:
        raise RuntimeError(f"cannot load {RUNNER_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    loader.exec_module(module)
    return module


runner = load_runner()


class RunnerPolicyTest(unittest.TestCase):
    def test_redacts_secrets_and_normalizes_names(self) -> None:
        redacted = runner.redact_json({
            "token": "abc",
            "nested": [{"apiKey": "secret"}, {"message": "Authorization: Bearer abc123"}],
            "safe": "value",
        })

        self.assertEqual("[REDACTED]", redacted["token"])
        self.assertEqual("[REDACTED]", redacted["nested"][0]["apiKey"])
        self.assertNotIn("abc123", redacted["nested"][1]["message"])
        self.assertEqual("Iron-Pickaxe", runner.safe_name("../Iron Pickaxe!!"))

    def test_selects_only_configured_scenarios(self) -> None:
        scenarios = [
            {"id": "a", "promptConfigured": True},
            {"id": "b", "promptConfigured": False},
        ]

        runnable, skipped = runner.selected_scenarios(scenarios, None)
        self.assertEqual(["a"], [item["id"] for item in runnable])
        self.assertEqual([{"id": "b", "name": None, "reason": "empty_prompt"}], skipped)

        runnable, skipped = runner.selected_scenarios(scenarios, ["a"])
        self.assertEqual(["a"], [item["id"] for item in runnable])
        self.assertEqual([], skipped)

    def test_rejects_an_unknown_scenario_filter(self) -> None:
        scenarios = [{"id": "a", "promptConfigured": True}]

        with self.assertRaises(runner.RunnerError):
            runner.selected_scenarios(scenarios, ["missing"])

    def test_scenario_manifest_is_selected_per_worker_and_cleared_for_discovery(self) -> None:
        source = {runner.EVALUATOR_SCENARIO_MANIFEST_ENVIRONMENT_VARIABLE: "/tmp/other/scenario.yml"}
        for manifest in (None, Path("/tmp/first/scenario.yml"), Path("/tmp/second/scenario.yml")):
            environment = runner.client_environment(
                Path("/tmp/bridge.json"), Path("/tmp/game"), "worker", False,
                runner.RecorderOptions(False, None), source, scenario_manifest=manifest,
            )
            self.assertEqual(
                None if manifest is None else str(manifest.resolve()),
                environment.get(runner.EVALUATOR_SCENARIO_MANIFEST_ENVIRONMENT_VARIABLE),
            )
        self.assertEqual("/tmp/other/scenario.yml", source[runner.EVALUATOR_SCENARIO_MANIFEST_ENVIRONMENT_VARIABLE])

    def test_keeps_only_the_last_client_by_default(self) -> None:
        default_args = argparse.Namespace(stop_client_after_scenario=False)
        stopping_args = argparse.Namespace(stop_client_after_scenario=True)

        self.assertTrue(runner.keep_client_for_scenario(default_args, 1, 1))
        self.assertFalse(runner.keep_client_for_scenario(default_args, 1, 1, True))
        self.assertFalse(runner.keep_client_for_scenario(default_args, 1, 2))
        self.assertTrue(runner.keep_client_for_scenario(default_args, 2, 2))
        self.assertFalse(runner.keep_client_for_scenario(stopping_args, 1, 1))

    def test_worker_and_cleanup_policies(self) -> None:
        self.assertEqual(2, runner.effective_worker_count(4, 2))
        self.assertEqual(0, runner.effective_worker_count(1, 0))
        self.assertEqual(2, runner.positive_int("2"))
        with self.assertRaises(argparse.ArgumentTypeError):
            runner.positive_int("0")

        passed = {"harnessStatus": "OK", "reportStatus": "PASSED", "clientKeptRunning": False}
        review = {"harnessStatus": "OK", "reportStatus": "NEEDS_REVIEW", "clientKeptRunning": False}
        self.assertTrue(runner.should_delete_worker(passed))
        self.assertFalse(runner.should_delete_worker(review))
        self.assertEqual(
            [1, 2],
            [item["index"] for item in runner.ordered_results({2: {"index": 2}, 1: {"index": 1}})],
        )


class RunnerShutdownTest(unittest.TestCase):
    def test_uses_graceful_stop_before_signalling_the_process_group(self) -> None:
        process = mock.Mock()
        process.pid = 42
        process.poll.return_value = None
        client = runner.ClientProcess(
            process=process,
            log_path=Path("/tmp/client.log"),
            log_thread=None,
            line_queue=runner.queue.Queue(),
            log_handle=None,
            output_detached=False,
        )
        graceful_stop = mock.Mock()

        with (
            mock.patch.object(runner, "process_group_alive", return_value=True),
            mock.patch.object(runner, "wait_for_process_group_exit", return_value=True),
            mock.patch.object(runner, "process_alive", return_value=False),
            mock.patch.object(runner.os, "killpg") as killpg,
        ):
            result = runner.stop_client(client, 1, graceful_stop=graceful_stop)

        graceful_stop.assert_called_once_with()
        killpg.assert_not_called()
        self.assertEqual("BRIDGE", result["method"])


class RecorderOptionTest(unittest.TestCase):
    def test_parser_defaults_to_recorder_enabled(self) -> None:
        defaults = runner.parse_args([])
        disabled = runner.parse_args(["--no-recorder"])

        self.assertFalse(defaults.no_recorder)
        self.assertIsNone(defaults.recorder_jar)
        self.assertTrue(disabled.no_recorder)

    def test_command_profile_overrides_the_environment(self) -> None:
        environment = {runner.RECORDER_JAR_ENVIRONMENT_VARIABLE: "environment-profile.jar"}

        from_environment = runner.resolve_recorder_options(None, False, environment)
        from_command = runner.resolve_recorder_options("command-profile.jar", False, environment)
        disabled = runner.resolve_recorder_options(None, True, environment)

        self.assertEqual(runner.RecorderOptions(True, "environment-profile.jar"), from_environment)
        self.assertEqual(runner.RecorderOptions(True, "command-profile.jar"), from_command)
        self.assertEqual(runner.RecorderOptions(False, None), disabled)

    def test_recorder_needs_a_profile(self) -> None:
        with self.assertRaisesRegex(runner.RunnerError, "--recorder-jar.*AIRICRAFT_RECORDER_JAR"):
            runner.resolve_recorder_options(None, False, {})

    def test_recorder_flags_are_mutually_exclusive(self) -> None:
        with self.assertRaisesRegex(runner.RunnerError, "--no-recorder.*--recorder-jar"):
            runner.resolve_recorder_options("command-profile.jar", True, {})


class RunnerFilesystemTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory(prefix="airicraft-evaluator-test-")
        self.root = Path(self.temporary_directory.name)

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def test_client_environment_uses_isolated_paths(self) -> None:
        bridge_path = self.root / "bridge-state.json"
        game_dir = self.root / "game"
        recorder_options = runner.RecorderOptions(True, "command-profile.jar")

        environment = runner.client_environment(
            bridge_path,
            game_dir,
            "worker-01",
            True,
            recorder_options,
            {runner.RECORDER_JAR_ENVIRONMENT_VARIABLE: "environment-profile.jar"},
        )

        expected = {
            runner.BRIDGE_STATE_ENVIRONMENT_VARIABLE: str(bridge_path.resolve()),
            runner.EVALUATOR_GAME_DIR_ENVIRONMENT_VARIABLE: str(game_dir.resolve()),
            runner.EVALUATOR_JDWP_ENVIRONMENT_VARIABLE: "false",
            runner.EVALUATOR_PREPARED_ENVIRONMENT_VARIABLE: "true",
            runner.RECORDER_JAR_ENVIRONMENT_VARIABLE: "command-profile.jar",
        }
        for key, value in expected.items():
            with self.subTest(key=key):
                self.assertEqual(value, environment[key])

        disabled = runner.client_environment(
            bridge_path,
            game_dir,
            "worker-01",
            True,
            runner.RecorderOptions(False, None),
            {runner.RECORDER_JAR_ENVIRONMENT_VARIABLE: "environment-profile.jar"},
        )
        self.assertNotIn(runner.RECORDER_JAR_ENVIRONMENT_VARIABLE, disabled)

    def test_worker_plans_use_separate_directories(self) -> None:
        seed_dir = self.root / "seed"
        seed_dir.mkdir()
        (seed_dir / "options.txt").write_text("onboardAccessibility:false\n", encoding="utf-8")

        plans = runner.worker_plans(
            self.root / "output",
            self.root / "workers",
            seed_dir,
            [{"id": "a"}, {"id": "b"}],
        )

        self.assertNotEqual(plans[0].game_dir, plans[1].game_dir)
        self.assertNotEqual(plans[0].bridge_state_path, plans[1].bridge_state_path)

    def test_recorder_config_contains_the_capture_contract(self) -> None:
        game_dir = self.root / "game"
        artifact_root = (self.root / "output" / "recorder").resolve()
        runner.prepare_recorder_game_directory(game_dir, artifact_root)

        recorder_config = json.loads(
            (game_dir / "config" / "recorder-minecraft.json").read_text(encoding="utf-8")
        )
        expected_recorder_config = {
            "artifacts_root": str(artifact_root),
            "record_all_players": True,
        }
        for key, value in expected_recorder_config.items():
            with self.subTest(config="recorder", key=key):
                self.assertEqual(value, recorder_config[key])

        server_replay_config = json.loads(
            (game_dir / "config" / "server-replay" / "config.json").read_text(encoding="utf-8")
        )
        expected_replay_config = {
            "automatically_record": True,
            "default_encoding": runner.RECORDER_REPLAY_FORMAT,
            "player_predicate": {"type": "all"},
            "max_duration": "0s",
            "max_file_size": "0 B",
            "restart_after_max_duration": False,
            "restart_after_max_file_size": False,
        }
        for key, value in expected_replay_config.items():
            with self.subTest(config="server-replay", key=key):
                self.assertEqual(value, server_replay_config[key])


class RecorderPlayTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory(prefix="airicraft-recorder-test-")
        self.scenario_dir = Path(self.temporary_directory.name) / "scenario"
        self.recorder_root = self.scenario_dir / "recorder"

    def tearDown(self) -> None:
        self.temporary_directory.cleanup()

    def write_play(
        self,
        relative_path: Path = FINALIZED_PLAY_RELATIVE_PATH,
        finalized: bool = True,
        player_uuid: str = "22222222-2222-4222-8222-222222222222",
    ) -> Path:
        play = self.recorder_root / relative_path
        events_path = play / runner.RECORDER_EVENTS_RELATIVE_PATH
        replay_path = play / runner.RECORDER_REPLAY_RELATIVE_PATH
        events_path.parent.mkdir(parents=True)

        connection = {
            "id": "33333333-3333-4333-8333-333333333333",
            "startedAt": "2026-08-08T00:00:00Z",
            "startServerTick": "10",
        }
        if finalized:
            connection.update({"endedAt": "2026-08-08T00:00:00Z", "endServerTick": "20"})
        runner.write_json(play / "metadata.json", {
            "server": {"name": "test", "instanceId": "11111111-1111-4111-8111-111111111111"},
            "player": {"name": "Player", "uuid": player_uuid},
            "connection": connection,
            "capture": {
                "events": runner.RECORDER_EVENTS_RELATIVE_PATH.as_posix(),
                "replay": runner.RECORDER_REPLAY_RELATIVE_PATH.as_posix(),
                "replayFormat": runner.RECORDER_REPLAY_FORMAT,
            },
        })
        events_path.write_text("{}\n", encoding="utf-8")
        with zipfile.ZipFile(replay_path, "w") as replay:
            replay.writestr("capture.flashback", "flashback")
        return play

    def write_planner_calls(self, submitted: str = "12", completed: str = "15") -> None:
        record = {
            "schemaVersion": 1,
            "callId": "planner-call-0001",
            "sequence": "1",
            "turnId": "planner-generation-1",
            "plannerAttempt": {"generation": "1", "attempt": 1, "phase": "PLANNER_REQUEST"},
            "timeline": {
                "submitted": {"serverTick": submitted},
                "completed": {"serverTick": completed},
            },
            "timing": {"requestedAtUnixMs": "1000", "completedAtUnixMs": "1100", "latencyMs": "100"},
            "model": {"provider": "openai-compatible", "name": "planner-model"},
            "request": {"messages": [], "tools": []},
            "outcome": {"status": "completed", "toolCalls": [], "usage": {}},
        }
        self.scenario_dir.mkdir(parents=True, exist_ok=True)
        (self.scenario_dir / runner.PLANNER_CALLS_FILE_NAME).write_text(
            json.dumps(record) + "\n",
            encoding="utf-8",
        )

    def test_discovers_one_finalized_play(self) -> None:
        play = self.write_play()

        self.assertEqual(play, runner.discover_finalized_recorder_play(self.recorder_root))

    def test_ignores_an_incomplete_play(self) -> None:
        finalized_play = self.write_play()
        self.write_play(Path("v1/other/players/player/plays/active"), finalized=False)

        self.assertEqual(finalized_play, runner.discover_finalized_recorder_play(self.recorder_root))

    def test_returns_none_when_no_play_exists(self) -> None:
        self.assertIsNone(runner.discover_finalized_recorder_play(self.recorder_root))

    def test_rejects_multiple_finalized_plays(self) -> None:
        self.write_play()
        self.write_play(Path("v1/other/players/player/plays/finalized"))

        with self.assertRaisesRegex(runner.RunnerError, "one finalized Recorder Play"):
            runner.discover_finalized_recorder_play(self.recorder_root)

    def test_selects_one_players_play_among_connected_testers(self) -> None:
        companion = self.write_play()
        tester = "44444444-4444-4444-8444-444444444444"
        self.write_play(Path("v1/server/players/tester/plays/first"), player_uuid=tester)
        self.write_play(Path("v1/server/players/tester/plays/reconnect"), player_uuid=tester)

        self.assertEqual(companion, runner.discover_finalized_recorder_play(
            self.recorder_root, "22222222-2222-4222-8222-222222222222"))
        with self.assertRaisesRegex(runner.RunnerError, f"for player {tester}, found 2"):
            runner.discover_finalized_recorder_play(self.recorder_root, tester)
        self.assertIsNone(runner.discover_finalized_recorder_play(
            self.recorder_root, "55555555-5555-4555-8555-555555555555"))

    def test_missing_player_play_names_the_player(self) -> None:
        self.write_play(player_uuid="44444444-4444-4444-8444-444444444444")
        result = {"harnessStatus": "OK"}

        runner.finalize_recorder_capture(result, self.scenario_dir, self.recorder_root,
                                         "22222222-2222-4222-8222-222222222222")

        self.assertEqual("CAPTURE_ERROR", result["harnessStatus"])
        self.assertIn("for player 22222222-2222-4222-8222-222222222222", result["message"])

    def test_stores_the_relative_play_path(self) -> None:
        self.write_play()
        result = {"harnessStatus": "OK", "reportStatus": "PASSED"}

        runner.finalize_recorder_capture(result, self.scenario_dir, self.recorder_root)

        self.assertEqual({
            "harnessStatus": "OK",
            "reportStatus": "PASSED",
            "recorderPlayPath": f"recorder/{FINALIZED_PLAY_RELATIVE_PATH.as_posix()}",
        }, result)
        self.assertTrue(runner.should_delete_worker(result))

    def test_publishes_planner_calls_as_a_play_extension(self) -> None:
        play = self.write_play()
        self.write_planner_calls()
        result = {"harnessStatus": "OK", "reportStatus": "PASSED"}

        runner.finalize_recorder_capture(result, self.scenario_dir, self.recorder_root)

        extension = play / "extensions" / runner.PLANNER_EXTENSION_TYPE
        manifest = json.loads((extension / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("airicraft.planner", manifest["extensionType"])
        self.assertEqual("33333333-3333-4333-8333-333333333333", manifest["play"]["connectionId"])
        self.assertEqual("PLAY_EXTENSION_TIME_DOMAIN_SERVER_TICK", manifest["timeDomain"])
        self.assertEqual("airicraft.planner-call.v1", manifest["assets"][0]["schema"])
        self.assertEqual(
            (self.scenario_dir / runner.PLANNER_CALLS_FILE_NAME).read_text(encoding="utf-8"),
            (extension / runner.PLANNER_CALLS_FILE_NAME).read_text(encoding="utf-8"),
        )
        self.assertEqual("OK", result["harnessStatus"])

    def test_rejects_planner_ticks_outside_the_play(self) -> None:
        play = self.write_play()
        self.write_planner_calls(submitted="9")
        result = {"harnessStatus": "OK", "reportStatus": "PASSED"}

        runner.finalize_recorder_capture(result, self.scenario_dir, self.recorder_root)

        self.assertEqual("CAPTURE_ERROR", result["harnessStatus"])
        self.assertIn("outside Play range", result["message"])
        self.assertFalse((play / "extensions" / runner.PLANNER_EXTENSION_TYPE).exists())

    def test_missing_capture_preserves_the_scenario_result(self) -> None:
        result = {
            "harnessStatus": "OK",
            "reportStatus": "FAILED",
            "message": "scenario outcome",
        }

        runner.finalize_recorder_capture(result, self.scenario_dir, self.recorder_root)

        self.assertEqual({
            "harnessStatus": "CAPTURE_ERROR",
            "reportStatus": "FAILED",
            "message": "scenario outcome",
            "recorderPlayPath": None,
        }, result)
        self.assertFalse(runner.should_delete_worker(result))

    def test_missing_capture_does_not_replace_interruption(self) -> None:
        result = {
            "harnessStatus": "INTERRUPTED",
            "reportStatus": None,
            "message": "Interrupted by user",
        }

        runner.finalize_recorder_capture(result, self.scenario_dir, self.recorder_root)

        self.assertEqual({
            "harnessStatus": "INTERRUPTED",
            "reportStatus": None,
            "message": "Interrupted by user",
            "recorderPlayPath": None,
        }, result)


if __name__ == "__main__":
    unittest.main(verbosity=2)
