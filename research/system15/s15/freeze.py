"""E0c latency tax: freeze the world while System 2 thinks.

While any gameplay planner request (planner/follow_up) is in flight, the client and integrated-server ticks are
paused through the existing tick-debug routes, and resumed when none is. The provider call keeps running while paused
(docs/live-playtest-recording.md), so the agent experiences a near zero-latency System 2. Comparing scenario outcomes
with and without this driver bounds what faster cognition could buy.

Polling is kept light because every bridge route is served on the client thread: new calls are detected with
`llm-calls?since=<latest>` (only new records), completion with planner events, and an in-flight record is re-read at
most once per `recheck_s` as a fallback. Resolution is the poll period plus pause/continue round trips.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

from .bridge import Bridge, BridgeError

GAMEPLAY_KINDS = ("planner", "follow_up", "")
TERMINAL = ("COMPLETED", "FAILED")
COMPLETION_EVENTS = ("planner.response_applied", "planner.timeout", "planner.provider_error", "planner.parse_error",
                     "planner.stale_response_rejected", "planner.degraded_entered", "planner.reset_requested")


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
        self.session = None  # only after the continue succeeded, so a failure is retried on exit
        return (time.perf_counter() - self.paused_at) * 1000.0


def run_freeze(bridge: Bridge, out_path: Path, duration_s: float, poll_s: float = 0.05,
               recheck_s: float = 1.0) -> dict:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    freezer = Freezer(bridge)
    started = time.perf_counter()
    in_flight: dict[int, float] = {}  # sequenceId -> last full re-read time
    event_seq, latest_call = bridge.latest_ids()
    frozen_ms = 0.0
    intervals = 0
    with open(out_path, "a", encoding="utf-8") as log:
        try:
            while time.perf_counter() - started < duration_s:
                now = time.perf_counter()
                result = bridge.llm_calls_since(latest_call)
                for record in result.get("records") or []:
                    seq = int(record.get("sequenceId", 0))
                    latest_call = max(latest_call or 0, seq)
                    if record.get("requestKind") in GAMEPLAY_KINDS and record.get("status") not in TERMINAL:
                        in_flight[seq] = now
                if in_flight:
                    events = bridge.events_since(event_seq)
                    event_seq = events.get("latestSeqNo", event_seq)
                    if any(e.get("type") in COMPLETION_EVENTS for e in events.get("events") or []):
                        in_flight.clear()
                    else:
                        stale = [seq for seq, checked in in_flight.items() if now - checked >= recheck_s]
                        if stale:  # fallback: re-read in-flight records (these carry the full request body)
                            for record in bridge.llm_calls_since(min(stale) - 1).get("records") or []:
                                seq = int(record.get("sequenceId", 0))
                                if seq in in_flight:
                                    if record.get("status") in TERMINAL:
                                        in_flight.pop(seq)
                                    else:
                                        in_flight[seq] = now
                else:
                    event_seq = bridge.events_since(event_seq).get("latestSeqNo", event_seq)
                if in_flight and freezer.session is None:
                    freezer.pause()
                    log.write(json.dumps({"event": "pause", "wall_ms": round((now - started) * 1000.0, 1),
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
