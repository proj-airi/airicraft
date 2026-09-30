"""Prompts shared by the teacher, the autoregressive baseline and the diffusion filler.

Stable text comes first so provider prompt caches and the diffusion encoder's KV cache can reuse it;
NOW/RECENT (and the previous READING in update mode) come last.
"""
from __future__ import annotations

from . import reading
from .statedoc import StateDoc

RUBRIC = """How to fill each READING slot:
- situation: one short sentence on what matters right now.
- chat.intent: the latest player chat addressed to the agent that the PLAN has not already handled; none if there is none.
  stop = halt current activity; wait = stay put; come_here/follow/go_home = movement requests; protect/dont_attack = combat
  constraints; give_item = hand something over; question/smalltalk = no action needed; complex = needs System 2 planning.
- chat.target: the player or @r entity the instruction refers to (the speaker for come_here/follow), else none.
- threats: up to two entities that matter for combat, most urgent first. stance fight = defend against it, avoid = keep
  away, protect = must not be harmed (pets, villagers, players), ignore = irrelevant. Unused entries: ref none, stance ignore.
- reflex.*: what the combat reflex should use now, given the objective's constraints and recent chat.
- anomaly: whether current execution looks wrong (wrong_tool = working with an unsuitable held item while a better one is
  carried; stuck/no_progress = work not advancing; hazard = lava, falling, fire; lost = far from where the plan expects).
- fix: the smallest reversible correction System 1 could apply immediately (equip, eat, retreat, pause_work, resume_work,
  look_at). Use ask_s2 when a real decision is needed; none otherwise. fix.arg names the item/slot/ref.
- say: a short acknowledgement to a player who just spoke to the agent; empty otherwise.
- escalate: now = System 2 must decide immediately (new instruction, current plan invalid, goal done, unhandled danger);
  soon = at the next natural boundary; no = nothing System 2 needs to know."""

CONTRACT = ("Output only the READING JSON object, on one line, with exactly the keys and order shown in the example. "
            "Every value is a string.")


def system_prompt(doc: StateDoc) -> str:
    return "\n\n".join([
        doc.sections.get("SELF", "").strip(),
        reading.schema_text(),
        RUBRIC,
        "Example (all defaults):\n" + reading.example_json(),
        CONTRACT,
    ])


def filler_messages(doc: StateDoc, previous: dict[str, str] | None = None, dirty: set[str] | None = None) -> list[dict]:
    """Messages for a filler. With `previous`, the model updates the prior READING instead of starting fresh."""
    body = doc.render(sections=("OBJECTIVE", "PLAN", "NOW", "RECENT"))
    if previous is not None:
        prior = reading.serialize(previous)[0]
        hint = f" Inputs changed for: {', '.join(sorted(dirty))}." if dirty else " No declared input changed."
        body += ("\n\n## PREVIOUS READING (System 1.5)\n" + prior +
                 "\nUpdate it: keep values that are still right, change the ones the new state invalidates." + hint)
    return [{"role": "system", "content": system_prompt(doc)}, {"role": "user", "content": body}]


def teacher_messages(doc: StateDoc, hindsight: str | None = None) -> list[dict]:
    body = doc.render(sections=("OBJECTIVE", "PLAN", "NOW", "RECENT"))
    if hindsight:
        body += ("\n\n## WHAT HAPPENED NEXT (privileged; use it only to resolve ambiguity about the present, "
                 "never to predict the future)\n" + hindsight)
    return [
        {"role": "system", "content": system_prompt(doc) +
         "\n\nYou are the labeling teacher. Think carefully, then output only the JSON."},
        {"role": "user", "content": body},
    ]
