"""E4a analysis: what would System 1.5 have done, and how does that compare with what System 2 did?

Joins a shadow log (`s15 shadow`) with the same session's recording (events + llm-calls) on the agent tick clock.
Tick-based latencies assume 20 TPS.
"""
from __future__ import annotations

from .labels import PLAN_CHANGING_TOOLS, GAMEPLAY_KINDS
from .metrics import latency_summary
from .reading import SLOTS
from .recordings import Run

CHAT_TYPES = ("social.player_addressed_agent", "social.local_controller_spoke")
ENUM_SLOTS = tuple(slot.name for slot in SLOTS if slot.kind == "enum")


def _rows(shadow: list[dict]) -> list[dict]:
    return sorted((row for row in shadow if "result" in row), key=lambda row: (row["tick"], row["wall_s"]))


def flicker_rate(rows: list[dict]) -> dict:
    """Enum slot changes between consecutive refreshes whose declared inputs did not change."""
    from .reading import SLOT_BY_NAME

    changes = opportunities = 0
    for previous, current in zip(rows, rows[1:]):
        dirty = set(current.get("dirty_sections") or [])
        for name in ENUM_SLOTS:
            if dirty.intersection(SLOT_BY_NAME[name].depends):
                continue
            opportunities += 1
            changes += previous["result"]["reading"].get(name) != current["result"]["reading"].get(name)
    return {"flicker_rate": changes / opportunities if opportunities else None, "opportunities": opportunities}


def reaction_times(rows: list[dict], run: Run, horizon_ticks: int = 1200) -> list[dict]:
    """Per addressed chat: ticks until the first READING with a chat intent, and until System 2's response arrived."""
    out = []
    for event in run.events:
        if event.type not in CHAT_TYPES:
            continue
        fast = next((row for row in rows if event.tick <= row["tick"] <= event.tick + horizon_ticks
                     and row["result"]["reading"].get("chat.intent", "none") != "none"), None)
        slow = next((call for call in run.calls if call.request_kind in GAMEPLAY_KINDS
                     and call.dispatch_tick >= event.tick and call.known_at_tick() is not None), None)
        out.append({"tick": event.tick, "message": event.payload.get("message"),
                    "fast_intent": fast["result"]["reading"]["chat.intent"] if fast else None,
                    "fast_ticks": fast["tick"] - event.tick if fast else None,
                    "system2_ticks": slow.known_at_tick() - event.tick if slow else None,
                    "system2_tools": [t["name"] for t in slow.tool_calls] if slow else None})
    return out


def escalation_agreement(rows: list[dict], run: Run) -> dict:
    """Last READING before each System 2 decision: did `escalate` anticipate a change of course?"""
    counts = {"tp": 0, "fp": 0, "fn": 0, "tn": 0}
    for call in run.calls:
        if call.request_kind not in GAMEPLAY_KINDS or call.dispatch_tick < 0:
            continue
        before = [row for row in rows if row["tick"] <= call.dispatch_tick]
        if not before:
            continue
        predicted = before[-1]["result"]["reading"].get("escalate.level", "no") != "no"
        actual = any(tool["name"] in PLAN_CHANGING_TOOLS for tool in call.tool_calls)
        counts[("t" if predicted == actual else "f") + ("p" if predicted else "n")] += 1
    decided = sum(counts.values())
    counts["agreement"] = (counts["tp"] + counts["tn"]) / decided if decided else None
    return counts


def now_escalations_per_hour(rows: list[dict]) -> float | None:
    if len(rows) < 2:
        return None
    transitions = sum(1 for a, b in zip(rows, rows[1:])
                      if b["result"]["reading"].get("escalate.level") == "now"
                      and a["result"]["reading"].get("escalate.level") != "now")
    hours = (rows[-1]["wall_s"] - rows[0]["wall_s"]) / 3600.0
    return transitions / hours if hours > 0 else None


def report(shadow: list[dict], run: Run | None) -> dict:
    rows = _rows(shadow)
    result = {
        "refreshes": len(rows),
        "errors": sum(1 for row in shadow if "error" in row),
        "refresh_latency_ms": latency_summary([row["result"]["latency_ms"] for row in rows]),
        "refresh_interval_s": latency_summary([b["wall_s"] - a["wall_s"] for a, b in zip(rows, rows[1:])]),
        "now_escalations_per_hour": now_escalations_per_hour(rows),
        **flicker_rate(rows),
    }
    if run is not None:
        reactions = reaction_times(rows, run)
        result["chat_reactions"] = reactions
        result["fast_reaction_ticks"] = latency_summary([float(r["fast_ticks"]) for r in reactions
                                                         if r["fast_ticks"] is not None])
        result["system2_reaction_ticks"] = latency_summary([float(r["system2_ticks"]) for r in reactions
                                                            if r["system2_ticks"] is not None])
        result["escalation_vs_system2"] = escalation_agreement(rows, run)
    return result
