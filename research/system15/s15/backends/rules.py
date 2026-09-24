"""Hand-written System 1.5 baseline (B1).

This is what the codebase does today in spirit: SlowMiningObserver-style notices and keyword triggers,
generalised to the READING form. Any learned filler has to beat it. It is also the test double.
"""
from __future__ import annotations

import re
import time

from .. import reading
from .base import FillRequest, FillResult, Filler

INTENT_PATTERNS = (  # first match wins, so the most safety-relevant intent comes first
    ("stop", r"\b(stop|halt|freeze|cut it out|enough)\b"),
    ("dont_attack", r"\b(don'?t|do not|never) (attack|hit|kill|hurt)\b|\bleave (it|him|her|them) alone\b"),
    ("wait", r"\b(wait|hold on|stay( here)?|stand still)\b"),
    ("come_here", r"\b(come (here|to me|over)|over here)\b"),
    ("follow", r"\bfollow( me)?\b"),
    ("go_home", r"\b(go|head|return) (home|back to base)\b"),
    ("protect", r"\b(protect|guard|defend)\b"),
    ("give_item", r"\b(give|bring|hand|toss) me\b"),
    ("question", r"\?\s*$|^(what|where|why|how|can|do|are|is)\b"),
    ("smalltalk", r"\b(hi|hello|hey|thanks|thank you|lol|nice|good job)\b"),
)
ACK = {"stop": "Stopping.", "wait": "Waiting here.", "come_here": "Coming.", "follow": "Following you.",
       "go_home": "Heading home.", "dont_attack": "Understood, I won't attack it.", "protect": "I'll keep it safe."}


def classify_chat(message: str) -> str:
    text = message.strip().lower()
    for intent, pattern in INTENT_PATTERNS:
        if re.search(pattern, text):
            return intent
    return "complex" if text else "none"


class RulesFiller(Filler):
    name = "rules"

    def fill(self, request: FillRequest) -> FillResult:
        started = time.perf_counter()
        doc = request.doc
        current = doc.meta.get("current") or {}
        events = doc.meta.get("events") or []
        out = reading.default_reading()

        addressed = [e for e in events if e["type"] == "social.player_addressed_agent"]
        if addressed:
            last = addressed[-1]["payload"]
            intent = classify_chat(str(last.get("message", "")))
            out["chat.intent"] = intent
            if intent in ("come_here", "follow", "protect", "give_item"):
                out["chat.target"] = str(last.get("player", "none"))
            out["say"] = ACK.get(intent, "")

        reflex_state = current.get("reflex") if isinstance(current.get("reflex"), dict) else {}
        for index, threat in enumerate((reflex_state.get("threats") or [])[: reading.THREAT_SLOTS]):
            ref = threat.get("entityRef") or threat.get("ref") or threat.get("id") if isinstance(threat, dict) else threat
            out[f"threats.{index}.ref"] = str(ref or "none")
            out[f"threats.{index}.stance"] = "fight"

        vitals = current.get("vitals") if isinstance(current.get("vitals"), dict) else {}
        for event in events:
            payload = event["payload"]
            if event["type"] == "task.notice" and payload.get("reason") == "slow_mining":
                best = payload.get("bestCarriedToolByBaseSpeed") or {}
                if best.get("item") and best.get("item") != payload.get("heldItem"):
                    out["anomaly.kind"] = "wrong_tool"
                    out["anomaly.detail"] = (f"holding {str(payload.get('heldItem')).replace('minecraft:', '')}, "
                                             f"{str(best['item']).replace('minecraft:', '')} carried")
                    out["fix.action"] = "equip"
                    out["fix.arg"] = str(best["item"]).replace("minecraft:", "")
        if out["anomaly.kind"] == "none":
            if isinstance(vitals.get("health"), (int, float)) and vitals["health"] <= 6:
                out["anomaly.kind"] = "low_health"
            elif isinstance(vitals.get("food"), (int, float)) and vitals["food"] <= 6:
                out["anomaly.kind"], out["fix.action"] = "low_food", "eat"
        if reflex_state.get("state") == "ACTIVE" and str(reflex_state.get("cause", "")).upper().startswith("DROWN"):
            out["anomaly.kind"] = "drowning"

        if out["chat.intent"] == "stop":
            out["fix.action"] = "pause_work"
        if out["chat.intent"] not in ("none", "smalltalk"):
            out["escalate.level"], out["escalate.reason"] = "now", "new_instruction"
        elif out["anomaly.kind"] in ("hazard", "low_health", "drowning"):
            out["escalate.level"], out["escalate.reason"] = "now", "threat"
        elif any(e["type"] in ("task.failed", "task.blocked") for e in events):
            out["escalate.level"], out["escalate.reason"] = "now", "plan_invalid"
        elif out["anomaly.kind"] != "none":
            out["escalate.level"], out["escalate.reason"] = "soon", "anomaly"

        work = current.get("work") if isinstance(current.get("work"), list) else []
        doing = ", ".join(f"{w.get('label', '?')} {w.get('state', '?')}".strip() for w in work[:2] if isinstance(w, dict))
        out["situation"] = " ".join(f"{doing or 'idle'}; {out['anomaly.kind'] if out['anomaly.kind'] != 'none' else 'nominal'}"
                                    .split()[: reading.SLOT_BY_NAME['situation'].max_words])
        text, _ = reading.serialize(out)
        return FillResult(out, True, {name: True for name in reading.SLOT_NAMES},
                          (time.perf_counter() - started) * 1000.0,
                          slot_confidence={name: 1.0 for name in reading.SLOT_NAMES}, raw_text=text)
