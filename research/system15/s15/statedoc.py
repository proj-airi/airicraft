"""State document: one structured text the fast layer reads each refresh.

Sections are ordered from least to most volatile so that prompt/KV caches reuse the stable prefix:
SELF (config) -> OBJECTIVE (System 2) -> PLAN (System 2) -> NOW (System 1) -> RECENT (System 1).
The READING form (reading.py) is appended by the filler, never rendered here.
"""
from __future__ import annotations

import hashlib
import json
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path

from .recordings import DecisionContext, LlmCall, Run, SemanticEvent

SECTION_ORDER = ("SELF", "OBJECTIVE", "PLAN", "NOW", "RECENT")
SECTION_OWNERS = {"SELF": "config", "OBJECTIVE": "System 2", "PLAN": "System 2", "NOW": "System 1",
                  "RECENT": "System 1", "READING": "System 1.5"}

DEFAULT_SELF = (
    "You are System 1.5 of an embodied Minecraft agent (Airicraft). You do not act directly: you keep the "
    "READING form current so that fast reflexes (System 1) and the slow planner (System 2) can use it.\n"
    "Hard rules: never attack players or tamed animals; the latest player safety instruction wins; prefer "
    "reversible fixes; set escalate when System 2 must decide. Copy @r references exactly as written."
)

# Event types that open an extra state document between System 2 decisions (--granularity event).
SALIENT_EVENT_PREFIXES = ("social.player_", "social.local_controller", "combat.", "reflex.started", "reflex.threat_detected",
                          "reflex.resolved", "task.notice", "task.failed", "task.completed", "task.cancelled",
                          "task.blocked", "player.physical", "player.died", "work.travel_restriction")
MAX_EVENT_CHARS = 220
RECENT_EVENT_LIMIT = 16


@dataclass
class StateDoc:
    doc_id: str
    run_id: str
    tick: int
    kind: str  # "decision" (a System 2 boundary) or "event" (between boundaries)
    sections: dict[str, str]
    meta: dict = field(default_factory=dict)

    def render(self, sections: tuple[str, ...] = SECTION_ORDER, headers: bool = True) -> str:
        parts = []
        for name in sections:
            body = self.sections.get(name, "").strip()
            if headers:
                parts.append(f"## {name} ({SECTION_OWNERS[name]})\n{body or '(empty)'}")
            else:
                parts.append(body)
        return "\n\n".join(parts)

    def to_json(self) -> dict:
        return asdict(self)

    @staticmethod
    def from_json(obj: dict) -> "StateDoc":
        return StateDoc(obj["doc_id"], obj["run_id"], int(obj["tick"]), obj["kind"], dict(obj["sections"]),
                        dict(obj.get("meta") or {}))


def compact(value, limit: int = 0) -> str:
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False, separators=(",", ":"),
                                                           sort_keys=False)
    if limit and len(text) > limit:
        return text[: limit - 1] + "…"
    return text


def _num(value) -> str:
    if isinstance(value, float):
        return f"{value:.1f}".rstrip("0").rstrip(".") if abs(value - round(value)) > 1e-9 else str(int(round(value)))
    return str(value)


def render_objective(current: dict) -> str:
    lines = []
    objective = current.get("objective")
    if isinstance(objective, dict) and objective:
        lines.append(f"Objective {objective.get('id', '?')} [{objective.get('status', '?')}]: "
                     f"{objective.get('objective', '')}".strip())
        for key, label in (("constraints", "Constraints"), ("completionCriteria", "Completion"),
                           ("outcome", "Outcome")):
            if objective.get(key):
                lines.append(f"{label}: {compact(objective[key], 400)}")
        if objective.get("decisions"):
            lines.append(f"Named decisions: {compact(objective['decisions'], 400)}")
    elif objective:
        lines.append(f"Objective: {compact(objective, 400)}")
    else:
        lines.append("No active objective.")
    travel = current.get("travelRestrictions")
    if isinstance(travel, dict):
        rest = {k: v for k, v in travel.items() if k not in ("coordinateMeaning",) and v not in ("", None, [], {})}
        if rest:
            lines.append(f"Travel restrictions: {compact(rest, 300)}")
    return "\n".join(lines)


def _render_work(work) -> list[str]:
    items = work if isinstance(work, list) else [work] if isinstance(work, dict) else []
    lines = []
    for item in items:
        if not isinstance(item, dict):
            continue
        head = f"Work {item.get('workId', '?')} {item.get('label', '')} state {item.get('state', '?')}"
        if item.get("phase") and item.get("phase") != item.get("state"):
            head += f" phase {item['phase']}"
        if item.get("message"):
            head += f": {compact(item['message'], 200)}"
        lines.append(head)
    return lines or ["No current work."]


def _render_inventory(inventory) -> str:
    if not inventory:
        return "Inventory empty."
    if isinstance(inventory, dict):
        items = inventory.get("items") if isinstance(inventory.get("items"), (list, dict)) else inventory
        if isinstance(items, dict):
            return "Inventory: " + ", ".join(f"{_num(v)} {k.replace('minecraft:', '')}" for k, v in items.items())
    return "Inventory: " + compact(inventory, 400)


def render_now(context: DecisionContext) -> str:
    current = context.current or {}
    lines = [f"As of tick {context.tick}; decisions: {context.decision_owner or '?'}; "
             f"actuation: {context.actuator_owner or '?'}."]
    if current.get("dimension"):
        lines.append(f"Dimension: {str(current['dimension']).replace('minecraft:', '')}")
    vitals = current.get("vitals")
    if isinstance(vitals, dict):
        lines.append("Vitals: " + ", ".join(f"{k} {_num(v)}" for k, v in vitals.items()))
    physical = current.get("physical")
    if isinstance(physical, dict):
        pos = physical.get("position") or {}
        where = ", ".join(_num(pos.get(axis, "?")) for axis in ("x", "y", "z")) if isinstance(pos, dict) else "?"
        flags = [name for name in ("grounded", "touchingWater", "climbing") if physical.get(name)]
        lines.append(f"Position ({where}); {' '.join(flags) or 'airborne'}")
    reflex = current.get("reflex")
    if isinstance(reflex, dict):
        threats = reflex.get("threats") or []
        lines.append(f"Reflex {reflex.get('state', '?')} epoch {reflex.get('safetyEpoch', '?')}; "
                     f"threats: {compact(threats, 300) if threats else 'none'}")
    lines.extend(_render_work(current.get("work", current.get("job"))))
    lines.append(_render_inventory(current.get("inventory")))
    skipped = {"objective", "travelRestrictions", "dimension", "vitals", "physical", "reflex", "work", "job",
               "inventory"}
    for key, value in current.items():
        if key not in skipped and value not in (None, "", [], {}):
            lines.append(f"{key}: {compact(value, 300)}")
    return "\n".join(lines)


def _item(value) -> str:
    return str(value or "?").replace("minecraft:", "")


def describe_event(event: SemanticEvent) -> str:
    """Semantic one-liners for the event types the fast layer cares about; compact JSON for the rest."""
    p = event.payload
    if event.type in ("social.player_spoke", "social.player_addressed_agent", "social.local_controller_spoke"):
        to_agent = {"social.player_addressed_agent": " (to agent)",
                    "social.local_controller_spoke": " (operator, to agent)"}.get(event.type, "")
        return f"{p.get('player', '?')} said{to_agent}: {compact(str(p.get('message', '')), 200)}"
    if event.type == "task.notice" and p.get("reason") == "slow_mining":
        best = p.get("bestCarriedToolByBaseSpeed") or {}
        return (f"slow mining: breaking {_item(p.get('block'))} holding {_item(p.get('heldItem'))} for "
                f"{p.get('elapsedTicks', '?')}/{p.get('estimatedBreakTicks', '?')} ticks; best carried tool "
                f"{_item(best.get('item'))} (slot {best.get('slot', '?')})")
    if event.type == "combat.damage_taken":
        return (f"took {_num(p.get('amount', '?'))} damage from {p.get('attackerName') or '?'} "
                f"({_item(p.get('damageTypeId'))})")
    if event.type == "work.changed":
        return (f"work {p.get('workId', '?')} {p.get('label', '')} {p.get('state', '?')}"
                + (f"/{p['phase']}" if p.get("phase") and p.get("phase") != p.get("state") else ""))
    return f"{event.type} {compact(p, MAX_EVENT_CHARS)}".rstrip()


def render_event(event: SemanticEvent) -> str:
    return f"[t{event.tick} #{event.seq}] {describe_event(event)}"


def chat_lines(events: list[SemanticEvent]) -> list[str]:
    lines = []
    for event in events:
        if event.type in ("social.player_spoke", "social.local_controller_spoke"):
            lines.append(f"[t{event.tick}] {event.payload.get('player', '?')}: "
                         f"{compact(str(event.payload.get('message', '')), 200)}")
    return lines


def render_recent(events: list[SemanticEvent]) -> str:
    """Most recent events, always keeping the last few chat lines even when routine events crowd them out."""
    if not events:
        return "No new events."
    social = [e for e in events if e.type.startswith("social.")][-4:]
    routine = [e for e in events if not e.type.startswith("social.")][-(RECENT_EVENT_LIMIT - len(social)):]
    keep = {id(e) for e in social + routine}
    shown = [e for e in events if id(e) in keep]
    lines = []
    if len(events) > len(shown):
        lines.append(f"({len(events) - len(shown)} earlier events omitted)")
    lines.extend(render_event(event) for event in shown)
    return "\n".join(lines)


def event_rows(events: list[SemanticEvent]) -> list[dict]:
    return [{"seq": e.seq, "tick": e.tick, "type": e.type, "payload": e.payload} for e in events]


def render_plan(calls: list[LlmCall], tick: int, limit: int = 2) -> str:
    """System 2's latest decisions whose responses had arrived by `tick` (no leakage of later decisions)."""
    previous = [c for c in calls if c.request_kind in ("planner", "follow_up", "")
                and (known := c.known_at_tick()) is not None and known <= tick]
    if not previous:
        return "No System 2 decision yet."
    lines = []
    for call in previous[-limit:]:
        tools = "; ".join(f"{t['name']}({compact(t['arguments'], 160)})" for t in call.tool_calls) or "no tool calls"
        reply = f" — said: {compact(call.reply_text, 160)}" if call.reply_text else ""
        lines.append(f"Decision at tick {call.dispatch_tick}: {tools}{reply}")
    return "\n".join(lines)


def fingerprint(section: str, text: str) -> str:
    """Hash ignoring tick counters and ages, so a section is dirty only when its content changed."""
    if section in ("NOW", "RECENT"):
        text = re.sub(r"\b(t|tick )\d+\b|\d+ ticks old", "", text)
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:16]


def dirty_sections(previous: StateDoc | None, current: StateDoc) -> set[str]:
    if previous is None or previous.run_id != current.run_id:
        return set(SECTION_ORDER)
    return {name for name in SECTION_ORDER
            if fingerprint(name, previous.sections.get(name, "")) != fingerprint(name, current.sections.get(name, ""))}


def make_doc(run_id: str, context: DecisionContext, events: list[SemanticEvent], calls: list[LlmCall],
             tick: int | None = None, kind: str = "decision", self_text: str = DEFAULT_SELF, extra_now: str = "",
             doc_id: str | None = None) -> StateDoc:
    """State document from the latest structured context plus the events seen since it."""
    tick = context.tick if tick is None else tick
    if context.current is not None:
        objective, now = render_objective(context.current), render_now(context)
    else:  # rendered-prose fallback: the whole context is the NOW section
        objective, now = "(see NOW)", context.rendered_text
    if tick > context.tick:
        now += f"\n(NOW is {tick - context.tick} ticks old; see RECENT.)"
    if extra_now:
        now += "\n" + extra_now
    return StateDoc(
        doc_id=doc_id or f"{run_id}:{tick}:{kind}", run_id=run_id, tick=tick, kind=kind,
        sections={"SELF": self_text, "OBJECTIVE": objective, "PLAN": render_plan(calls, tick), "NOW": now,
                  "RECENT": render_recent(events)},
        meta={"world": context.world, "decision_owner": context.decision_owner,
              "actuator_owner": context.actuator_owner, "now_tick": context.tick, "source": context.source,
              "structured": context.current is not None, "through_seq": context.through_seq,
              "current": context.current, "events": event_rows(events)},
    )


def build_docs(run: Run, granularity: str = "decision", self_text: str = DEFAULT_SELF) -> list[StateDoc]:
    """One doc per System 2 decision boundary, plus (granularity="event") one per salient event between them."""
    docs: list[StateDoc] = []
    contexts = sorted(run.contexts, key=lambda c: c.tick)
    for index, context in enumerate(contexts):
        incorporated = context.events or [e for e in run.events
                                          if context.after_seq is not None and context.through_seq is not None
                                          and context.after_seq < e.seq <= context.through_seq]
        docs.append(make_doc(run.run_id, context, incorporated, run.calls, self_text=self_text))
        if granularity != "event":
            continue
        horizon = contexts[index + 1].tick if index + 1 < len(contexts) else None
        start_seq = context.through_seq if context.through_seq is not None else -1
        pending: list[SemanticEvent] = []
        for event in run.events:
            if event.seq <= start_seq or event.tick < context.tick:
                continue
            if horizon is not None and event.tick >= horizon:
                break
            pending.append(event)
            if event.type.startswith(SALIENT_EVENT_PREFIXES):
                doc = make_doc(run.run_id, context, list(pending), run.calls, tick=event.tick, kind="event",
                               self_text=self_text, doc_id=f"{run.run_id}:{event.tick}:event:{event.seq}")
                doc.meta["trigger_event"] = event.type
                docs.append(doc)
    return docs


def write_docs(docs: list[StateDoc], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as handle:
        for doc in docs:
            handle.write(json.dumps(doc.to_json(), ensure_ascii=False) + "\n")


def read_docs(path: Path) -> list[StateDoc]:
    with open(path, "r", encoding="utf-8") as handle:
        return [StateDoc.from_json(json.loads(line)) for line in handle if line.strip()]
