"""E4a shadow mode: run a filler against a live client without changing Java or behaviour.

Each refresh reads the events since the last refresh, builds a state document from the latest canonical decision
context plus those events, fills READING (warm-started from the previous refresh), and appends one JSONL row. Nothing
is sent back to the game. Analyse with `s15 shadow-report`.

Bridge routes run on the Minecraft client thread, so polling is kept light: events and new LLM records are read every
refresh (only new items are transferred), the reflex/task snapshot comes from `/v1/agent/goals`, and the heavy
`/v1/agent/context` (whole conversations) is read only at start and when a new System 2 call appears.
"""
from __future__ import annotations

import json
import time
from pathlib import Path

from . import reading
from .backends.base import FillRequest, Filler
from .bridge import Bridge, BridgeError
from .recordings import SemanticEvent, parse_decision_context
from .statedoc import compact, dirty_sections, make_doc

GAMEPLAY_KINDS = ("planner", "follow_up", "")


def latest_context(context_payload: dict):
    canonical = context_payload.get("canonicalConversation") or {}
    for message in reversed(canonical.get("messages") or []):
        if message.get("role") != "user":
            continue
        parsed = parse_decision_context(message.get("text", ""), "bridge:canonical")
        if parsed is not None and parsed.current is not None:
            return parsed
    return None


def live_runtime_line(goals_payload: dict) -> str:
    """Fresher than the last decision context: reflex and task snapshots polled this refresh."""
    parts = []
    reflex = goals_payload.get("reflex")
    if isinstance(reflex, dict):
        parts.append("Live reflex: " + compact({k: reflex[k] for k in ("state", "safetyEpoch", "cause") if k in reflex}, 200))
    task = goals_payload.get("task")
    if isinstance(task, dict) and task:
        parts.append("Live task: " + compact({k: task[k] for k in ("state", "taskType", "activeStepKind", "message")
                                              if k in task}, 240))
    return "\n".join(parts)


def run_shadow(bridge: Bridge, filler: Filler, out_path: Path, duration_s: float, period_s: float = 0.25,
               run_id: str = "live") -> int:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    events: list[SemanticEvent] = []
    event_seq, call_seq = bridge.latest_ids()
    previous_doc = previous_reading = None
    context = latest_context(bridge.context())
    refreshes = 0
    with open(out_path, "a", encoding="utf-8") as handle:
        while time.perf_counter() - started < duration_s:
            tick_started = time.perf_counter()
            try:
                batch = bridge.events_since(event_seq)
                calls = bridge.llm_calls_since(call_seq)
                if any(r.get("requestKind") in GAMEPLAY_KINDS for r in calls.get("records") or []) or context is None:
                    fresh = latest_context(bridge.context())
                    if fresh is not None:
                        context = fresh
                goals = bridge.goals()
            except BridgeError as error:
                handle.write(json.dumps({"wall_s": round(time.perf_counter() - started, 3), "error": str(error)}) + "\n")
                time.sleep(1.0)
                continue
            event_seq = batch.get("latestSeqNo", event_seq)
            call_seq = calls.get("latestSequenceId", call_seq)
            for event in batch.get("events") or []:
                events.append(SemanticEvent(int(event.get("seqNo", -1)), int(event.get("tick", -1)),
                                            str(event.get("type", "")), event.get("payload") or {}))
            events = events[-512:]
            if context is None:
                time.sleep(period_s)
                continue
            since = [e for e in events if context.through_seq is None or e.seq > context.through_seq][-64:]
            tick = max([context.tick] + [e.tick for e in since])
            doc = make_doc(run_id, context, since, [], tick=tick, kind="live", extra_now=live_runtime_line(goals),
                           doc_id=f"{run_id}:{tick}:live:{refreshes}")
            dirty = dirty_sections(previous_doc, doc)
            request = FillRequest(doc, previous_reading, reading.dirty_slots(dirty), dirty)
            result = filler.fill(request)
            handle.write(json.dumps({
                "wall_s": round(time.perf_counter() - started, 3), "tick": tick, "doc_id": doc.doc_id,
                "context_tick": context.tick, "dirty_sections": sorted(dirty), "result": result.to_json(),
            }, ensure_ascii=False) + "\n")
            handle.flush()
            previous_doc, previous_reading = doc, result.reading
            refreshes += 1
            time.sleep(max(0.0, period_s - (time.perf_counter() - tick_started)))
    return refreshes
