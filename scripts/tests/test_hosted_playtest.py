"""Hosted playtest session control: tester presence, planner recovery and LAN readiness."""
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location("hosted_playtest", Path(__file__).resolve().parents[1] / "hosted_playtest.py")
hosted = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hosted)

ALEX = {"uuid": "00000000-0000-4000-8000-000000000004", "name": "Alex"}


class TesterPresenceTest(unittest.TestCase):
    def test_waits_for_a_first_tester_until_the_join_timeout(self):
        presence = hosted.TesterPresence(opened_at=10, join_timeout=30, leave_grace=5)
        self.assertIsNone(presence.observe(0, 39))
        self.assertEqual("no_tester_joined", presence.observe(0, 40))

    def test_zero_join_timeout_waits_forever(self):
        presence = hosted.TesterPresence(opened_at=0, join_timeout=0, leave_grace=5)
        self.assertIsNone(presence.observe(0, 10 ** 9))

    def test_rejoining_within_the_grace_keeps_the_session(self):
        presence = hosted.TesterPresence(opened_at=0, join_timeout=30, leave_grace=10)
        self.assertIsNone(presence.observe(1, 100))
        self.assertIsNone(presence.observe(0, 101))
        self.assertIsNone(presence.observe(1, 110), "A reconnect is not a departure")
        self.assertIsNone(presence.observe(0, 120))
        self.assertIsNone(presence.observe(0, 129))
        self.assertEqual("testers_left", presence.observe(0, 130))


class DegradedRecoveryTest(unittest.TestCase):
    def test_one_automatic_reset_per_session_even_if_degradation_returns(self):
        recovery = hosted.DegradedRecovery(reset_after=20)
        self.assertIsNone(recovery.observe(False, 0))
        self.assertEqual("degraded", recovery.observe(True, 5))
        self.assertIsNone(recovery.observe(True, 24))
        self.assertEqual("reset", recovery.observe(True, 25))
        self.assertIsNone(recovery.observe(True, 60), "A reset that has not landed yet is not repeated")
        self.assertEqual("recovered", recovery.observe(False, 61))
        self.assertEqual("reset_exhausted", recovery.observe(True, 70))
        self.assertIsNone(recovery.observe(True, 90))

    def test_zero_delay_only_records_degradation(self):
        recovery = hosted.DegradedRecovery(reset_after=0)
        self.assertEqual("degraded", recovery.observe(True, 0))
        self.assertIsNone(recovery.observe(True, 10 ** 6))


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class HostedSessionTest(unittest.TestCase):
    def session(self, *, testers=lambda now: [], degraded=lambda now: False, lan=lambda now: (True, 25565),
                mode="hosted", state=lambda now: "RECORDING", **overrides):
        """Runs the launcher loop against a scripted bridge; returns finalize arguments, requests, events and output."""
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            world = root / "world"
            world.mkdir()
            (world / "level.dat").touch()
            profile = root / "recorder.jar"
            profile.touch()
            options = dict(world=world, output=root / "output", recorder_jar=str(profile), companion_name="Airi",
                           lan_port=25565, join_timeout=600, leave_grace=120, reset_degraded_after=20,
                           max_seconds=0, startup_timeout=240)
            options.update(overrides)
            args = SimpleNamespace(**options)
            clock = FakeClock()
            client = Mock()
            client.process.pid = 122
            client.process.poll.return_value = None
            client.line_queue.empty.return_value = True
            bridge = SimpleNamespace(process_id=123)
            requests = []

            def bridge_json(connection, method, path, body=None):
                requests.append((clock.now, method, path, body))
                if path == "/v1/worlds":
                    return {"worlds": [{"worldId": "playtest"}]}
                if path == "/v1/status":
                    return {"worldLoaded": True, "automaticPlaytest": {
                        "state": state(clock.now), "mode": mode, "connectedTesters": testers(clock.now)}}
                if path == "/v1/agent/status":
                    return {"degraded": degraded(clock.now)}
                if path == "/v1/agent/session":
                    published, port = lan(clock.now)
                    return {"lanPublished": published, "lanPort": port}
                return {}

            def stop_client(actual_client, timeout, pid, graceful_stop):
                graceful_stop()
                return {"minecraftExited": True, "method": "BRIDGE"}

            printed = []
            with (patch.object(hosted, "REPO", root),
                  patch.object(hosted.uuid, "uuid4", return_value="run"),
                  patch.object(hosted.evaluation, "local_timestamp", return_value="test"),
                  patch.object(hosted.shutil, "which", return_value="available"),
                  patch.object(hosted.playtest, "prepare_game", return_value=root / "game"),
                  patch.object(hosted.evaluation, "start_client", return_value=client) as start,
                  patch.object(hosted.evaluation, "wait_for_title_bridge", return_value=(bridge, {})),
                  patch.object(hosted.evaluation, "bridge_json", side_effect=bridge_json),
                  patch.object(hosted.evaluation, "stop_client", side_effect=stop_client) as stop,
                  patch.object(hosted.playtest, "finalize_recording", return_value=(root / "final", 0)) as finalize,
                  patch.object(hosted.signal, "signal"),
                  patch.object(hosted.time, "monotonic", side_effect=clock.monotonic),
                  patch.object(hosted.time, "sleep", side_effect=clock.sleep),
                  patch("builtins.print", side_effect=lambda *values, **kwargs: printed.append(" ".join(map(str, values))))):
                self.assertEqual(0, hosted.run(args))
            stop.assert_called_once()
            finalize.assert_called_once()
            pending = root / "output/.in-progress/test-run"
            events = [json.loads(line) for line in (pending / "launcher-events.jsonl").read_text().splitlines()]
            launch = json.loads((pending / "launch.json").read_text())
            return SimpleNamespace(finalize=finalize.call_args.args, requests=requests, events=events,
                                   printed=printed, command=start.call_args.args[1], launch=launch, clock=clock)

    def test_session_ends_after_the_last_tester_leaves_and_resets_a_degraded_planner(self):
        result = self.session(testers=lambda now: [ALEX] if 5 <= now < 30 else [],
                              degraded=lambda now: 10 <= now < 40)
        self.assertEqual((None, "testers_left"), result.finalize[4:])
        self.assertGreaterEqual(result.clock.now, 150)
        self.assertLess(result.clock.now, 153)
        resets = [request for request in result.requests if request[2] == "/v1/agent/debug/chat"]
        self.assertEqual(1, len(resets))
        self.assertEqual({"message": "@agent reset"}, resets[0][3])
        self.assertGreaterEqual(resets[0][0], 30)
        self.assertEqual(["lan_opened", "planner_degraded", "planner_reset_sent", "planner_recovered", "session_ended"],
                         [event["event"] for event in result.events])
        self.assertEqual("testers_left", result.events[-1]["terminationReason"])
        self.assertIn("Tester joined: Alex", result.printed)
        self.assertIn("Tester left: Alex", result.printed)
        self.assertTrue(any("hosting on port 25565" in line for line in result.printed))
        self.assertEqual(("POST", "/v1/evaluation/client-stop", None), result.requests[-1][1:])
        self.assertEqual(1, sum(request[2] == "/v1/agent/session" for request in result.requests),
                         "LAN state is read until the port opens, not on every poll")

    def test_redegradation_after_reset_does_not_send_another_reset(self):
        result = self.session(testers=lambda now: [ALEX] if now < 50 else [],
                              degraded=lambda now: 10 <= now < 22 or now >= 24,
                              reset_degraded_after=5, leave_grace=5)
        resets = [request for request in result.requests if request[2] == "/v1/agent/debug/chat"]
        self.assertEqual(1, len(resets))
        self.assertEqual(["lan_opened", "planner_degraded", "planner_reset_sent", "planner_recovered",
                          "planner_reset_exhausted", "session_ended"],
                         [event["event"] for event in result.events])

    def test_launch_uses_hosted_mode_companion_name_and_fixed_port(self):
        result = self.session(testers=lambda now: [], join_timeout=3, companion_name="Mochi", lan_port=25570,
                              lan=lambda now: (True, 25570))
        for argument in ("-Pairicraft.automaticPlaytest=true", "-Pairicraft.automaticPlaytestMode=hosted",
                         "-Pairicraft.hostedPlaytestAutoReset=true",
                         "-Pairicraft.devPlayerName=Mochi", "-Pairicraft.lanPort=25570",
                         "-Pairicraft.evaluator.jdwp.enabled=false", ":runClientCompatEvaluator"):
            self.assertIn(argument, result.command.split())
        self.assertEqual({"mode": "hosted", "companionName": "Mochi", "lanPort": 25570},
                         {key: result.launch[key] for key in ("mode", "companionName", "lanPort")})
        self.assertEqual((None, "no_tester_joined"), result.finalize[4:])

    def test_unopened_lan_port_stops_before_testers_are_told_to_join(self):
        result = self.session(lan=lambda now: (False, 0))
        self.assertEqual("lan_unavailable", result.finalize[5])
        self.assertIn("did not open within 60 seconds", result.finalize[4])
        self.assertFalse(any("hosting on port" in line for line in result.printed))

    def test_lan_on_an_unexpected_port_is_not_announced(self):
        result = self.session(lan=lambda now: (True, 25566))
        self.assertEqual("lan_unavailable", result.finalize[5])
        self.assertIn("port 25566, not the requested port 25565", result.finalize[4])

    def test_a_client_without_hosted_mode_is_rejected(self):
        result = self.session(mode="automatic")
        self.assertIn("not in hosted playtest mode", result.finalize[4])
        self.assertEqual("capture_error", result.finalize[5])

    def test_a_world_that_never_starts_recording_stops_at_the_startup_timeout(self):
        result = self.session(state=lambda now: "IDLE", startup_timeout=30)
        self.assertEqual("startup_failed", result.finalize[5])
        self.assertLess(result.clock.now, 32)

    def test_the_time_budget_still_applies_while_testers_play(self):
        result = self.session(testers=lambda now: [ALEX], max_seconds=90)
        self.assertEqual((None, "time_limit"), result.finalize[4:])

    def test_disabled_recovery_never_sends_the_operator_reset(self):
        result = self.session(testers=lambda now: [ALEX] if now < 100 else [], degraded=lambda now: True,
                              reset_degraded_after=0, leave_grace=5)
        self.assertIn("-Pairicraft.hostedPlaytestAutoReset=false", result.command.split())
        self.assertFalse(any(request[2] == "/v1/agent/debug/chat" for request in result.requests))
        self.assertIn("planner_degraded", [event["event"] for event in result.events])


class HostedArgumentsTest(unittest.TestCase):
    def parse_error(self, *argv):
        with patch.object(sys, "argv", ["hosted-playtest", "--world", "world", *argv]), \
                patch("sys.stderr"), self.assertRaises(SystemExit) as exit_:
            hosted.main()
        return exit_.exception.code

    def test_rejects_names_minecraft_would_not_accept(self):
        for name in ("ai", "a-name", "seventeen_letters", "space name"):
            with self.subTest(name=name):
                self.assertEqual(2, self.parse_error("--companion-name", name))

    def test_rejects_invalid_ports_and_durations(self):
        for argv in (("--lan-port", "0"), ("--lan-port", "65536"), ("--leave-grace", "-1"),
                     ("--join-timeout", "inf"), ("--max-seconds", "nan"), ("--startup-timeout", "0")):
            with self.subTest(argv=argv):
                self.assertEqual(2, self.parse_error(*argv))

    def test_recover_uses_the_shared_archive_path(self):
        with patch.object(sys, "argv", ["hosted-playtest", "--recover", "/runs/.in-progress/run"]), \
                patch.object(hosted.playtest, "recover_recording", return_value=0) as recover:
            self.assertEqual(0, hosted.main())
        recover.assert_called_once_with(Path("/runs/.in-progress/run"))


if __name__ == "__main__":
    unittest.main()
