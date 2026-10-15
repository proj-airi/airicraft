"""Automatic (hindsight and imitation) labels derived from a recording.

These need no model: they come from what System 2 actually did next and from runtime notices.
Teacher labels for every READING slot come from teacher.py.
"""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .recordings import LlmCall, Run
from .statedoc import StateDoc

# Tools whose use means System 2 changed course (as opposed to waiting or inspecting).
PLAN_CHANGING_TOOLS = frozenset({
    "cancel_work", "clear_queue", "change_planner_goal", "set_planner_goal", "finish_planner_goal",
    "delegate_task", "configure_reflex", "update_event_policy", "resume_work", "equip_item",
})
PASSIVE_TOOLS = frozenset({"wait_for_work", "continue", "inspect_work", "list_work", "inspect_inventory",
                           "inspect_planner_goal"})
SALIENT_INPUT_PREFIXES = ("social.player_addressed_agent", "social.local_controller_spoke", "combat.damage_taken", "reflex.started", "task.failed",
                          "task.notice", "player.died", "task.blocked")
GAMEPLAY_KINDS = ("planner", "follow_up", "")


@dataclass
class HindsightLabel:
    doc_id: str
    tick: int
    s2_next_tick: int | None
    s2_next_tools: list[str]
    s2_changed_plan: bool  # System 2's next decision within the window changed course
    salient_input: bool  # the doc's incorporated events contain a rule-triggering event
    wrong_tool: bool | None  # a slow_mining notice with a better carried tool, None when not mining-related
    imitation: dict[str, str] = field(default_factory=dict)  # READING slots implied by System 2's next decision


def _next_calls(calls: list[LlmCall], tick: int, window: int) -> list[LlmCall]:
    return [c for c in calls if c.request_kind in GAMEPLAY_KINDS and tick <= c.dispatch_tick <= tick + window]


def _imitation(call: LlmCall | None) -> dict[str, str]:
    if call is None:
        return {}
    labels: dict[str, str] = {}
    for tool in call.tool_calls:
        args = tool.get("arguments") or {}
        if tool["name"] == "configure_reflex":
            if "combatEnabled" in args:
                labels["reflex.combat"] = "on" if args["combatEnabled"] else "off"
            if "maxThreatDistance" in args:
                distance = int(args["maxThreatDistance"])
                labels["reflex.max_threat_distance"] = str(min((4, 8, 12, 16, 24, 32), key=lambda d: abs(d - distance)))
            if "requireLineOfSight" in args:
                labels["reflex.require_los"] = "yes" if args["requireLineOfSight"] else "no"
        elif tool["name"] == "equip_item":
            labels["fix.action"] = "equip"
            labels["fix.arg"] = str(args.get("itemId", "none")).replace("minecraft:", "")
        elif tool["name"] == "cancel_work":
            labels["escalate.level"] = "now"
    if call.reply_text:
        labels["say"] = " ".join(call.reply_text.split()[:16])
    return labels


def hindsight_labels(run: Run, docs: list[StateDoc], window_ticks: int = 400) -> list[HindsightLabel]:
    by_seq = {event.seq: event for event in run.events}
    labels = []
    for doc in docs:
        upcoming = _next_calls(run.calls, doc.tick, window_ticks)
        first = upcoming[0] if upcoming else None
        # Only the next decision counts: later ones may be reacting to events this doc could not contain.
        changed = first is not None and any(tool["name"] in PLAN_CHANGING_TOOLS for tool in first.tool_calls)
        doc_events = [by_seq[row["seq"]] for row in doc.meta.get("events", []) if row["seq"] in by_seq]
        salient = any(e.type.startswith(SALIENT_INPUT_PREFIXES) for e in doc_events)
        wrong_tool = None
        for event in doc_events:
            if event.type == "task.notice" and event.payload.get("reason") == "slow_mining":
                best = (event.payload.get("bestCarriedToolByBaseSpeed") or {}).get("item")
                wrong_tool = bool(best) and best != event.payload.get("heldItem")
        labels.append(HindsightLabel(
            doc_id=doc.doc_id, tick=doc.tick,
            s2_next_tick=first.dispatch_tick if first else None,
            s2_next_tools=[t["name"] for t in first.tool_calls] if first else [],
            s2_changed_plan=changed, salient_input=salient, wrong_tool=wrong_tool,
            imitation=_imitation(first),
        ))
    return labels


def write_labels(labels: list[HindsightLabel], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        for label in labels:
            handle.write(json.dumps(asdict(label), ensure_ascii=False) + "\n")


def read_labels(path: Path) -> dict[str, dict]:
    with open(path, "r", encoding="utf-8") as handle:
        return {row["doc_id"]: row for row in (json.loads(line) for line in handle if line.strip())}
