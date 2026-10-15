"""E0b reaction probe: inject a chat into a running client and time the existing system's response.

No Java changes. Uses the debug chat route (the same path as `airicraft agent debug chat`) and polls events,
dialogue state and LLM flight records. Latencies are wall-clock milliseconds from injection and agent ticks
from the injected chat event. Without a sender the message comes from the local player, i.e. the operator
(`social.local_controller_spoke`); another sender name is a player, who must start with "@agent" to address the agent.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

from .bridge import Bridge

RESPONSE_EVENTS = ("planner.response_applied", "work.changed", "task.cancelled", "task.started", "task.submitted",
                   "task.paused_by_reflex", "player.actions_cancelled")


def _dialogue_marker(goals: dict):
    response = goals.get("lastDialogueResponse")
    return json.dumps(response, sort_keys=True) if response is not None else None


def run_probe(bridge: Bridge, message: str, sender: str | None, watch_s: float, poll_s: float = 0.2) -> dict:
    seq, call_seq = bridge.latest_ids()
    baseline_dialogue = _dialogue_marker(bridge.goals())

    injected_at = time.perf_counter()
    injection = bridge.inject_chat(message, sender)
    first: dict[str, dict] = {}
    chat_tick = None
    deadline = injected_at + watch_s
    while time.perf_counter() < deadline:
        now_ms = (time.perf_counter() - injected_at) * 1000.0
        batch = bridge.events_since(seq)
        seq = batch.get("latestSeqNo", seq)
        for event in batch.get("events") or []:
            chat_types = ("social.player_spoke", "social.local_controller_spoke")
            if event.get("type") in chat_types and chat_tick is None and event.get("payload", {}).get("message") == message:
                chat_tick = event.get("tick")
            if event.get("type") in RESPONSE_EVENTS and event["type"] not in first:
                first[event["type"]] = {"ms": round(now_ms, 1), "tick": event.get("tick"), "payload": event.get("payload")}
        records = bridge.llm_calls_since(call_seq)
        for record in records.get("records") or []:
            call_seq = max(call_seq or 0, record.get("sequenceId", 0))
            if record.get("requestKind") in ("planner", "follow_up", "") and "llm_dispatch" not in first:
                first["llm_dispatch"] = {"ms": round(now_ms, 1), "tick": record.get("dispatchTick")}
        if "dialogue_reply" not in first and _dialogue_marker(bridge.goals()) != baseline_dialogue:
            first["dialogue_reply"] = {"ms": round(now_ms, 1), "tick": None}
        time.sleep(poll_s)
    for value in first.values():
        if chat_tick is not None and value.get("tick") is not None:
            value["ticks_after_chat"] = value["tick"] - chat_tick
    return {"message": message, "sender": injection.get("senderName"), "watch_s": watch_s,
            "chat_tick": chat_tick, "first": first,
            "session_mode": injection.get("sessionMode")}


def append_result(result: dict, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "a", encoding="utf-8") as handle:
        handle.write(json.dumps(result, ensure_ascii=False) + "\n")
