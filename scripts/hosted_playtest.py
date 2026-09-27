"""Host one recorded Airicraft companion world that human testers join over LAN or a forwarded port."""
from __future__ import annotations

import argparse
import importlib.util
import json
import math
import os
import re
from pathlib import Path
import shlex
import shutil
import signal
import socket
import sys
import time
import uuid


REPO = Path(__file__).resolve().parent.parent
playtest_spec = importlib.util.spec_from_file_location("automatic_playtest", REPO / "scripts/automatic_playtest.py")
playtest = importlib.util.module_from_spec(playtest_spec)
playtest_spec.loader.exec_module(playtest)
evaluation = playtest.evaluation


COMPANION_NAME = re.compile(r"[A-Za-z0-9_]{3,16}")
BRIDGE_FAILURE_TIMEOUT_SECONDS = 60
LAN_OPEN_TIMEOUT_SECONDS = 60
POLL_SECONDS = 1.0
RESET_COMMAND = "@agent reset"


class TesterPresence:
    """Ends a session once its testers are gone, tolerating a brief reconnect."""

    def __init__(self, opened_at: float, join_timeout: float, leave_grace: float):
        self.opened_at = opened_at
        self.join_timeout = join_timeout
        self.leave_grace = leave_grace
        self.joined = False
        self.empty_since: float | None = None

    def observe(self, testers: int, now: float) -> str | None:
        if testers:
            self.joined = True
            self.empty_since = None
            return None
        if not self.joined:
            return "no_tester_joined" if self.join_timeout and now - self.opened_at >= self.join_timeout else None
        if self.empty_since is None:
            self.empty_since = now
        return "testers_left" if now - self.empty_since >= self.leave_grace else None


class DegradedRecovery:
    """Attempt one operator reset during a hosted session."""

    def __init__(self, reset_after: float):
        self.reset_after = reset_after
        self.since: float | None = None
        self.reset_attempted = False

    def observe(self, degraded: bool, now: float) -> str | None:
        if not degraded:
            recovered = self.since is not None
            self.since = None
            return "recovered" if recovered else None
        if self.since is None:
            self.since = now
            return "reset_exhausted" if self.reset_attempted else "degraded"
        if self.reset_after and not self.reset_attempted and now - self.since >= self.reset_after:
            self.reset_attempted = True
            return "reset"
        return None


def client_command(run_id: str, output: Path, game: Path, profile: Path, companion_name: str,
                   lan_port: int, reset_degraded_after: float) -> str:
    return shlex.join([
        "./gradlew", "--no-daemon", "-Pairicraft.includeEvaluator=true", "-Pairicraft.includeCompat=true",
        "-Pairicraft.automaticPlaytest=true", "-Pairicraft.automaticPlaytestMode=hosted",
        f"-Pairicraft.hostedPlaytestAutoReset={str(reset_degraded_after > 0).lower()}",
        f"-Pairicraft.automaticPlaytestId={run_id}", f"-Pairicraft.automaticPlaytestDir={output}",
        f"-Pairicraft.devPlayerName={companion_name}", f"-Pairicraft.lanPort={lan_port}",
        f"-Pairicraft.evaluator.runDir={game}", "-Pairicraft.evaluator.jdwp.enabled=false",
        f"-Pairicraft.evaluator.recorderJar={profile}", "wrapper:installDist", ":runClientCompatEvaluator",
    ])


def local_address() -> str | None:
    """The address other machines on this network would dial; a UDP connect sends no packet."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            return probe.getsockname()[0]
    except OSError:
        return None


def log_event(path: Path, event: str, **fields) -> None:
    evaluation.append_jsonl(path, {"event": event, "at": evaluation.utc_now_iso(), **fields})


def announce_hosting(args: argparse.Namespace, port: int) -> None:
    address = local_address() or "<this machine's address>"
    limits = [f"{args.leave_grace:g}s after the last tester leaves"]
    if args.join_timeout:
        limits.append(f"if nobody joins within {args.join_timeout:g}s")
    if args.max_seconds:
        limits.append(f"after {args.max_seconds:g}s in total")
    print(f"{args.companion_name} is hosting on port {port}.\n"
          f"  Same network: Multiplayer lists the world under LAN, or Direct Connect to {address}:{port}\n"
          f"  Internet: forward TCP {port} to this machine (or tunnel it), then share <public address>:{port}\n"
          f"The session ends {', '.join(limits)}. Ctrl-C ends it now.", flush=True)


def run(args: argparse.Namespace) -> int:
    if not shutil.which("ffmpeg") or not shutil.which("ffprobe"):
        raise ValueError("Hosted playtests require ffmpeg and ffprobe on PATH for MP4 screen capture")
    world = args.world.resolve()
    if not (world / "level.dat").is_file():
        raise ValueError(f"Not a Minecraft world directory: {world}")
    recorder = evaluation.resolve_recorder_options(args.recorder_jar, False, os.environ)
    profile = Path(recorder.profile).expanduser().resolve()
    if not profile.is_file():
        raise ValueError(f"Recording profile does not exist: {profile}")
    recorder = evaluation.RecorderOptions(True, str(profile))
    run_id = evaluation.local_timestamp() + "-" + str(uuid.uuid4())
    output = args.output.resolve()
    pending = output / ".in-progress" / run_id
    destination = output / run_id
    worker = REPO / "run/hosted-playtest-workers" / run_id
    pending.mkdir(parents=True)
    # Every session plays a fresh copy, so the template world never accumulates testers' changes.
    game = playtest.prepare_game(world, worker, pending)
    bridge_file = worker / "bridge-state.json"
    events = pending / "launcher-events.jsonl"
    evaluation.write_json(pending / "launch.json", {
        "id": run_id, "mode": "hosted", "sourceWorld": str(world), "companionName": args.companion_name,
        "lanPort": args.lan_port, "workerDirectory": str(worker), "recordingProfile": str(profile),
        "maxSeconds": args.max_seconds, "joinTimeout": args.join_timeout, "leaveGrace": args.leave_grace,
        "resetDegradedAfter": args.reset_degraded_after, "launcherPid": os.getpid()})
    command = client_command(run_id, output, game, profile, args.companion_name, args.lan_port,
                             args.reset_degraded_after)
    client = None
    bridge = None
    failure = None
    termination_reason = "startup_failed"
    client_exit = {"method": "NOT_STARTED", "minecraftExited": True}
    print(f"Recording: {pending}", flush=True)
    try:
        launch_ms = int(time.time() * 1000)
        client = evaluation.start_client(REPO, command, pending / "client.log", bridge_file, game, run_id, False, recorder)
        processes = {"launcherChildPid": client.process.pid}
        evaluation.write_json(pending / "processes.json", processes)
        bridge, _ = evaluation.wait_for_title_bridge(client, bridge_file, launch_ms, args.startup_timeout)
        processes["minecraftPid"] = bridge.process_id
        evaluation.write_json(pending / "processes.json", processes)
        worlds = evaluation.bridge_json(bridge, "GET", "/v1/worlds")["worlds"]
        if len(worlds) != 1:
            raise RuntimeError("Expected exactly one copied playtest world")
        evaluation.bridge_json(bridge, "POST", "/v1/worlds/join", {"worldId": worlds[0]["worldId"]})
        joined_at = time.monotonic()
        deadline = joined_at + args.max_seconds if args.max_seconds else math.inf
        termination_reason = "capture_error"
        bridge_failed_since = None
        recording_since = None
        presence = None
        recovery = DegradedRecovery(args.reset_degraded_after)
        connected: dict[str, str] = {}
        while time.monotonic() < deadline:
            failure = playtest.drain_fatal_client_error(client)
            if failure:
                termination_reason = "runtime_fatal_error"
                break
            if client.process.poll() is not None:
                termination_reason = "client_exit" if client.process.returncode == 0 else "crash"
                if client.process.returncode != 0:
                    failure = f"Client exited with code {client.process.returncode}"
                break
            try:
                status = evaluation.bridge_json(bridge, "GET", "/v1/status")
                recording = status.get("automaticPlaytest", {})
                state = recording.get("state")
                if state == "RECORDING":
                    agent = evaluation.bridge_json(bridge, "GET", "/v1/agent/status")
                    session = evaluation.bridge_json(bridge, "GET", "/v1/agent/session") if presence is None else {}
            except (evaluation.BridgeHttpError, OSError) as error:
                if isinstance(error, evaluation.BridgeHttpError) and error.status != 500:
                    raise
                now = time.monotonic()
                if bridge_failed_since is None:
                    bridge_failed_since = now
                if now - bridge_failed_since >= BRIDGE_FAILURE_TIMEOUT_SECONDS:
                    termination_reason = "bridge_unresponsive"
                    failure = f"Minecraft bridge unavailable for at least {BRIDGE_FAILURE_TIMEOUT_SECONDS} seconds"
                    break
                time.sleep(0.5)
                continue
            bridge_failed_since = None
            now = time.monotonic()
            if state == "FAILED":
                raise RuntimeError(recording.get("error") or "Hosted playtest capture failed")
            if state == "FINISHED":
                termination_reason = "world_left"
                failure = recording.get("error") or None
                break
            if state != "RECORDING" and recording_since is None and now - joined_at >= args.startup_timeout:
                termination_reason = "startup_failed"
                failure = f"The world did not start recording within {args.startup_timeout:g} seconds"
                break
            if state == "RECORDING":
                if recording.get("mode") != "hosted":
                    raise RuntimeError("Client is not in hosted playtest mode; its Airicraft build predates this launcher")
                recording_since = recording_since if recording_since is not None else now
                if presence is None:
                    if session.get("lanPublished"):
                        port = session.get("lanPort")
                        if port != args.lan_port:
                            termination_reason = "lan_unavailable"
                            failure = f"LAN opened on port {port}, not the requested port {args.lan_port}"
                            break
                        presence = TesterPresence(now, args.join_timeout, args.leave_grace)
                        log_event(events, "lan_opened", port=port)
                        announce_hosting(args, port)
                    elif now - recording_since >= LAN_OPEN_TIMEOUT_SECONDS:
                        termination_reason = "lan_unavailable"
                        failure = f"LAN port {args.lan_port} did not open within {LAN_OPEN_TIMEOUT_SECONDS} seconds; is it already in use?"
                        break
                if presence is not None:
                    testers = {tester["uuid"]: tester["name"] for tester in recording.get("connectedTesters", [])}
                    for tester_uuid, name in testers.items():
                        if tester_uuid not in connected:
                            print(f"Tester joined: {name}", flush=True)
                    for tester_uuid, name in connected.items():
                        if tester_uuid not in testers:
                            print(f"Tester left: {name}", flush=True)
                    connected = testers
                    reason = presence.observe(len(testers), now)
                    if reason:
                        termination_reason = reason
                        break
                change = recovery.observe(agent.get("degraded") is True, now)
                if change == "degraded":
                    log_event(events, "planner_degraded")
                    print("Planner degraded" + (f"; resetting in {args.reset_degraded_after:g}s" if args.reset_degraded_after else ""), flush=True)
                elif change == "reset":
                    evaluation.bridge_json(bridge, "POST", "/v1/agent/debug/chat", {"message": RESET_COMMAND})
                    log_event(events, "planner_reset_sent", command=RESET_COMMAND)
                    print("Planner reset sent", flush=True)
                elif change == "recovered":
                    log_event(events, "planner_recovered")
                elif change == "reset_exhausted":
                    log_event(events, "planner_reset_exhausted")
                    print("Planner degraded again after its automatic reset; no further reset will be sent", flush=True)
            time.sleep(POLL_SECONDS)
        else:
            termination_reason = "time_limit"
    except KeyboardInterrupt:
        termination_reason = "manual_interrupt"
    except Exception as error:
        failure = f"{type(error).__name__}: {error}"
        if client is not None and client.process.poll() == 0:
            termination_reason = "client_exit"
    finally:
        # A second interrupt must not kill finalization midway through recorder shutdown.
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        log_event(events, "session_ended", terminationReason=termination_reason, error=failure)
        if client is not None:
            bridge = bridge or evaluation.read_bridge_state(bridge_file)
            client_exit = evaluation.stop_client(client, 45, bridge.process_id if bridge else -1,
                (lambda: evaluation.bridge_json(bridge, "POST", "/v1/evaluation/client-stop")) if bridge else None)
        bridge_file.unlink(missing_ok=True)
    result, code = playtest.finalize_recording(pending, destination, game / "saves/playtest", client_exit, failure, termination_reason)
    outcome = json.loads((result / "summary.json").read_text()) if (result / "summary.json").exists() else {}
    print(json.dumps({"status": outcome.get("status", "INCOMPLETE"), "terminationReason": termination_reason,
                      "outputDir": str(result), "error": outcome.get("message")}), flush=True)
    return code


def nonnegative_seconds(value: str) -> float:
    seconds = float(value)
    if not math.isfinite(seconds) or seconds < 0:
        raise argparse.ArgumentTypeError("must be finite and nonnegative")
    return seconds


def main() -> int:
    parser = argparse.ArgumentParser(description="Host a recorded Airicraft companion world that human playtesters join.")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--world", type=Path, help="Template world directory; each session plays a fresh copy.")
    source.add_argument("--recover", type=Path, help="Archive a stopped .in-progress session; refuses active clients or launchers.")
    parser.add_argument("--recorder-jar", help="Prebuilt recording profile; defaults to AIRICRAFT_RECORDER_JAR.")
    parser.add_argument("--output", type=Path, default=REPO / "hosted_playtest")
    parser.add_argument("--companion-name", default="Airi",
                        help="The companion's in-game name: 3-16 letters, digits or underscores (default Airi).")
    parser.add_argument("--lan-port", type=int, default=25565,
                        help="The only port the world opens on, so it can be forwarded (default 25565).")
    parser.add_argument("--join-timeout", type=nonnegative_seconds, default=1800,
                        help="End if no tester joins within this many seconds of opening; 0 waits forever (default 1800).")
    parser.add_argument("--leave-grace", type=nonnegative_seconds, default=120,
                        help="End this many seconds after the last tester leaves, unless one rejoins (default 120).")
    parser.add_argument("--reset-degraded-after", type=nonnegative_seconds, default=20,
                        help="Send the session's single automatic operator reset after the planner stays degraded this long; "
                             "0 disables automatic reset (default 20).")
    parser.add_argument("--max-seconds", type=nonnegative_seconds, default=14400,
                        help="Wall-clock budget after joining; 0 means no limit (default 14400).")
    parser.add_argument("--startup-timeout", type=float, default=240)
    args = parser.parse_args()
    if not COMPANION_NAME.fullmatch(args.companion_name):
        parser.error("--companion-name must be 3-16 letters, digits or underscores")
    if not 1 <= args.lan_port <= 65535:
        parser.error("--lan-port must be between 1 and 65535")
    if not math.isfinite(args.startup_timeout) or args.startup_timeout <= 0:
        parser.error("--startup-timeout must be finite and positive")
    try:
        if args.recover:
            return playtest.recover_recording(args.recover)
        signal.signal(signal.SIGTERM, playtest.interrupt_run)
        return run(args)
    except KeyboardInterrupt:
        return 130
    except (ValueError, OSError, evaluation.RunnerError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
