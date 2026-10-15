"""Settledness signals: can the fast layer tell when it does not know (and System 2 should decide)?

Each function maps one filler result (and optionally the previous result of the same run) to a score where
higher = less settled. They operationalise, for READING slots, signals from the dLLM uncertainty literature:
final entropy; flip count across denoising steps (temporal oscillation, Wang et al. 2025 "Time Is a Feature");
temporal semantic entropy over intermediate values; intermediate-vs-final disagreement (UQ for large
language diffusion models, 2026); late-commit weighting (TRE, 2026); and cross-refresh flicker.
"""
from __future__ import annotations

import math
from collections import Counter

from . import reading

ESCALATION_SLOTS = ("chat.intent", "anomaly.kind", "fix.action", "escalate.level", "threats.0.stance", "reflex.combat")


def _slot_values(trajectory: list[dict], slot: str) -> list[str | None]:
    return [(step.get("slots", {}).get(slot) or {}).get("value") for step in trajectory]


def flip_count(result: dict, slot: str) -> float:
    values = [v for v in _slot_values(result.get("trajectory") or [], slot) if v is not None]
    return float(sum(1 for a, b in zip(values, values[1:]) if a != b))


def temporal_entropy(result: dict, slot: str) -> float:
    values = [v for v in _slot_values(result.get("trajectory") or [], slot) if v is not None]
    if not values:
        return 0.0
    counts = Counter(values)
    return -sum((c / len(values)) * math.log(c / len(values)) for c in counts.values())


def final_disagreement(result: dict, slot: str) -> float:
    values = [v for v in _slot_values(result.get("trajectory") or [], slot) if v is not None]
    if not values:
        return 0.0
    final = values[-1]
    return sum(1 for v in values if v != final) / len(values)


def late_entropy(result: dict, slot: str) -> float:
    """Entropy weighted towards later steps (uncertainty that survives to the end matters most)."""
    trajectory = result.get("trajectory") or []
    total = weight_sum = 0.0
    for index, step in enumerate(trajectory, start=1):
        entropy = ((step.get("slots") or {}).get(slot) or {}).get("entropy")
        if entropy is None:
            continue
        total += index * entropy
        weight_sum += index
    return total / weight_sum if weight_sum else 0.0


def low_confidence(result: dict, slot: str) -> float:
    confidence = (result.get("slot_confidence") or {}).get(slot)
    return 1.0 - confidence if confidence is not None else 0.0


def cross_refresh_flicker(result: dict, previous: dict | None, slot: str, inputs_changed: bool) -> float:
    """1 when a slot changes between refreshes although none of its declared inputs changed."""
    if previous is None or inputs_changed:
        return 0.0
    return 1.0 if result["reading"].get(slot) != previous["reading"].get(slot) else 0.0


SIGNALS = {
    "flip_count": flip_count,
    "temporal_entropy": temporal_entropy,
    "final_disagreement": final_disagreement,
    "late_entropy": late_entropy,
    "low_confidence": low_confidence,
}


def doc_signals(result: dict, previous: dict | None = None, dirty_slots: set[str] | None = None,
                slots: tuple[str, ...] = ESCALATION_SLOTS) -> dict[str, float]:
    """Max over escalation-relevant slots for each signal, plus the filler's own escalate verdict."""
    out: dict[str, float] = {}
    for name, function in SIGNALS.items():
        out[name] = max((function(result, slot) for slot in slots), default=0.0)
    out["flicker"] = max((cross_refresh_flicker(result, previous, slot, slot in (dirty_slots or set()))
                          for slot in slots), default=0.0)
    level = result["reading"].get("escalate.level", "no")
    out["self_report"] = {"no": 0.0, "soon": 0.5, "now": 1.0}.get(level, 0.0)
    return out


def slot_names() -> tuple[str, ...]:
    return reading.SLOT_NAMES
