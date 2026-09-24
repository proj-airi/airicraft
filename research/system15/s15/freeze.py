"""E0c latency tax: freeze the world while System 2 thinks.

Polls the LLM flight records; while any gameplay planner request (planner/follow_up) is in flight, the client and
integrated-server ticks are paused through the existing tick-debug routes, and resumed when none is. The provider
call keeps running while paused (docs/live-playtest-recording.md), so the agent experiences a near zero-latency
System 2. Comparing scenario outcomes with and without this driver bounds what faster cognition could buy.
Resolution is the poll period (default 50 ms) plus pause/continue round trips; the log records both.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

from .bridge import Bridge, BridgeError

GAMEPLAY_KINDS = ("planner", "follow_up", "")
TERMINAL = ("COMPLETED", "FAILED")


class Freezer:
    def __init__(self, bridge: Bridge):
        self.bridge = bridge
        self.session = None  # (debugSessionId, pauseEpoch) while paused
        self.paused_at = 0.0

    def pause(self) -> None:
        response = self.bridge._call("POST", "/v1/agent/debug/ticks/pause", body={"playerActions": False})
        self.session = (response["debugSessionId"], response["pauseEpoch"])
        self.paused_at = time.perf_counter()

    def resume(self) -> float:
        session_id, epoch = self.session
        self.bridge._call("POST", "/v1/agent/debug/ticks/continue",
                          body={"debugSessionId": session_id, "pauseEpoch": epoch})
        self.session = None
        return (time.perf_counter() - self.paused_at) * 1000.0


def run_freeze(bridge: Bridge, out_path: Path, duration_s: float, poll_s: float = 0.05) -> dict:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    freezer = Freezer(bridge)
    started = time.perf_counter()
    in_flight: dict[int, dict] = {}
    since = bridge.llm_calls_since(None).get("latestSequenceId")
    frozen_ms = 0.0
    intervals = 0
    with open(out_path, "a", encoding="utf-8") as log:
        try:
            while time.perf_counter() - started < duration_s:
                query_from = min(in_flight) - 1 if in_flight else since
                result = bridge.llm_calls_since(query_from)
                for record in result.get("records") or []:
                    seq = int(record.get("sequenceId", 0))
                    since = max(since or 0, seq)
                    if record.get("requestKind") not in GAMEPLAY_KINDS:
                        continue
                    if record.get("status") in TERMINAL:
                        in_flight.pop(seq, None)
                    else:
                        in_flight[seq] = record
                if in_flight and freezer.session is None:
                    detected_ms = (time.perf_counter() - started) * 1000.0
                    freezer.pause()
                    log.write(json.dumps({"event": "pause", "wall_ms": round(detected_ms, 1),
                                          "calls": sorted(in_flight)}) + "\n")
                elif not in_flight and freezer.session is not None:
                    held = freezer.resume()
                    frozen_ms += held
                    intervals += 1
                    log.write(json.dumps({"event": "continue", "held_ms": round(held, 1)}) + "\n")
                log.flush()
                time.sleep(poll_s)
        except BridgeError as error:
            log.write(json.dumps({"event": "error", "message": str(error)}) + "\n")
        finally:
            if freezer.session is not None:  # never leave the world paused
                try:
                    frozen_ms += freezer.resume()
                    intervals += 1
                except BridgeError as error:
                    log.write(json.dumps({"event": "resume_failed", "message": str(error)}) + "\n")
    summary = {"duration_s": round(time.perf_counter() - started, 1), "freeze_intervals": intervals,
               "frozen_ms": round(frozen_ms, 1)}
    with open(out_path, "a", encoding="utf-8") as log:
        log.write(json.dumps(dict(summary, event="summary")) + "\n")
    return summary
