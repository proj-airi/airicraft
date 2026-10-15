"""READING form v0: the typed, fixed-order slots that System 1.5 keeps current.

The form is serialized as one compact JSON object with a fixed key order so that
(a) a diffusion canvas can be warm-started from the previous reading and
(b) every slot value has a known character span for clamping, renoising and scoring.
See docs/research/system-1.5/state-document.md for the semantics of each slot.
"""
from __future__ import annotations

import json
import re
from dataclasses import dataclass, field

SCHEMA_VERSION = "reading-v0"  # bump when slots, values or serialization change; labels are per version
SECTION_NAMES = ("SELF", "OBJECTIVE", "PLAN", "NOW", "RECENT")


@dataclass(frozen=True)
class Slot:
    name: str  # dotted path, e.g. "chat.intent" or "threats.0.stance"
    kind: str  # "enum" | "text" | "ref"
    values: tuple[str, ...] = ()
    default: str = ""
    max_words: int = 0
    depends: tuple[str, ...] = ()
    actuates: str = ""  # existing runtime surface this slot may drive after Gate B; empty = advisory only


CHAT_INTENTS = ("none", "stop", "wait", "follow", "come_here", "go_home", "protect", "dont_attack",
                "give_item", "question", "smalltalk", "complex")
STANCES = ("ignore", "fight", "avoid", "protect")
ANOMALIES = ("none", "wrong_tool", "stuck", "no_progress", "hazard", "low_food", "low_health", "drowning",
             "lost", "inventory_full", "other")
FIX_ACTIONS = ("none", "equip", "eat", "retreat", "pause_work", "resume_work", "look_at", "ask_s2")
ESCALATE_LEVELS = ("no", "soon", "now")
ESCALATE_REASONS = ("none", "new_instruction", "plan_invalid", "threat", "anomaly", "goal_done", "uncertain")
THREAT_SLOTS = 2

SLOTS: tuple[Slot, ...] = (
    Slot("situation", "text", default="", max_words=20, depends=("OBJECTIVE", "PLAN", "NOW", "RECENT")),
    Slot("chat.intent", "enum", CHAT_INTENTS, "none", depends=("RECENT",)),
    Slot("chat.target", "ref", default="none", max_words=3, depends=("RECENT", "NOW")),
    *(s for i in range(THREAT_SLOTS) for s in (
        Slot(f"threats.{i}.ref", "ref", default="none", max_words=2, depends=("NOW", "RECENT")),
        Slot(f"threats.{i}.stance", "enum", STANCES, "ignore", depends=("NOW", "RECENT", "OBJECTIVE"),
             actuates="per-entity reflex target filter (needs ReflexPolicy extension)"),
    )),
    Slot("reflex.combat", "enum", ("on", "off"), "on", depends=("NOW", "RECENT", "OBJECTIVE"),
         actuates="configure_reflex.combatEnabled"),
    Slot("reflex.max_threat_distance", "enum", ("4", "8", "12", "16", "24", "32"), "16",
         depends=("NOW", "RECENT", "OBJECTIVE"), actuates="configure_reflex.maxThreatDistance"),
    Slot("reflex.require_los", "enum", ("yes", "no"), "yes", depends=("NOW", "RECENT"),
         actuates="configure_reflex.requireLineOfSight"),
    Slot("anomaly.kind", "enum", ANOMALIES, "none", depends=("NOW", "RECENT")),
    Slot("anomaly.detail", "text", default="", max_words=12, depends=("NOW", "RECENT")),
    Slot("fix.action", "enum", FIX_ACTIONS, "none", depends=("NOW", "RECENT"),
         actuates="equip_item | hold/resume through existing holds | look-at (reversible only)"),
    Slot("fix.arg", "text", default="none", max_words=6, depends=("NOW", "RECENT")),
    Slot("say", "text", default="", max_words=16, depends=("RECENT",), actuates="chat acknowledgement"),
    Slot("escalate.level", "enum", ESCALATE_LEVELS, "no", depends=("OBJECTIVE", "PLAN", "NOW", "RECENT"),
         actuates="System 2 wake (coalesced SYSTEM trigger)"),
    Slot("escalate.reason", "enum", ESCALATE_REASONS, "none", depends=("OBJECTIVE", "PLAN", "NOW", "RECENT")),
)
SLOT_BY_NAME = {slot.name: slot for slot in SLOTS}
SLOT_NAMES = tuple(slot.name for slot in SLOTS)


def default_reading() -> dict[str, str]:
    """Flat reading (dotted slot name -> string value) holding every default."""
    return {slot.name: slot.default for slot in SLOTS}


def unflatten(flat: dict[str, str]) -> dict:
    """Dotted slot names -> the nested JSON object in canonical key order."""
    out: dict = {}
    for slot in SLOTS:
        value = flat.get(slot.name, slot.default)
        parts = slot.name.split(".")
        node = out
        i = 0
        while i < len(parts) - 1:
            if parts[i + 1].isdigit():  # "threats.0.ref": list element, then its key
                items = node.setdefault(parts[i], [])
                index = int(parts[i + 1])
                while len(items) <= index:
                    items.append({})
                node = items[index]
                i += 2
            else:
                node = node.setdefault(parts[i], {})
                i += 1
        node[parts[-1]] = value
    return out


def flatten(obj) -> dict[str, str]:
    """Nested JSON (possibly partial or malformed) -> flat dict of raw string values for known slots."""
    flat: dict[str, str] = {}
    for slot in SLOTS:
        node = obj
        ok = True
        for part in slot.name.split("."):
            if isinstance(node, list) and part.isdigit() and int(part) < len(node):
                node = node[int(part)]
            elif isinstance(node, dict) and part in node:
                node = node[part]
            else:
                ok = False
                break
        if ok and node is not None and not isinstance(node, (dict, list)):
            flat[slot.name] = str(node)
    return flat


def serialize(flat: dict[str, str]) -> tuple[str, dict[str, tuple[int, int]]]:
    """Canonical one-line JSON plus the character span of every slot *value* (inside the quotes)."""
    spans: dict[str, tuple[int, int]] = {}
    pieces: list[str] = []
    length = 0

    def emit(text: str) -> None:
        nonlocal length
        pieces.append(text)
        length += len(text)

    def value(slot_name: str) -> None:
        raw = flat.get(slot_name, SLOT_BY_NAME[slot_name].default)
        encoded = json.dumps(str(raw), ensure_ascii=False)
        emit('"')
        start = length
        emit(encoded[1:-1])
        spans[slot_name] = (start, length)
        emit('"')

    def emit_node(node, prefix: str) -> None:
        if isinstance(node, dict):
            emit("{")
            for index, (key, child) in enumerate(node.items()):
                if index:
                    emit(", ")
                emit(json.dumps(key) + ": ")
                emit_node(child, f"{prefix}.{key}" if prefix else key)
            emit("}")
        elif isinstance(node, list):
            emit("[")
            for index, child in enumerate(node):
                if index:
                    emit(", ")
                emit_node(child, f"{prefix}.{index}")
            emit("]")
        else:
            value(prefix)

    emit_node(unflatten(flat), "")
    return "".join(pieces), spans


def _snap_enum(raw: str, allowed: tuple[str, ...]) -> tuple[str, bool]:
    token = raw.strip().lower().replace("-", "_").replace(" ", "_")
    if token in allowed:
        return token, True
    prefixed = [value for value in allowed if value.startswith(token) or token.startswith(value)]
    if len(prefixed) == 1 and token:
        return prefixed[0], False
    return "", False


def _clip_words(raw: str, max_words: int) -> tuple[str, bool]:
    words = raw.strip().split()
    if max_words and len(words) > max_words:
        return " ".join(words[:max_words]), False
    return " ".join(words), True


@dataclass
class ParsedReading:
    reading: dict[str, str]
    parse_ok: bool
    exact: dict[str, bool] = field(default_factory=dict)  # value was present and valid as written
    present: dict[str, bool] = field(default_factory=dict)
    error: str = ""


def _first_object(text: str) -> tuple[str, list[str], bool] | None:
    """The first top-level JSON object (ignoring canvas padding after it), its open-bracket stack, in-string flag."""
    start = text.find("{")
    if start < 0:
        return None
    stack: list[str] = []
    in_string = escaped = False
    out: list[str] = []
    for ch in text[start:]:
        out.append(ch)
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
            continue
        if ch == '"':
            in_string = True
        elif ch in "{[":
            stack.append(ch)
        elif ch in "}]":
            if stack:
                stack.pop()
            if not stack:
                break
    return "".join(out), stack, in_string


def _loads_lenient(text: str):
    found = _first_object(text)
    if found is None:
        return None
    candidate, stack, in_string = found
    try:
        return json.loads(candidate)
    except json.JSONDecodeError:
        pass
    # Repair a truncated or slightly malformed canvas: close the open string, drop a dangling key,
    # close open containers, remove trailing commas.
    repaired = candidate + ('"' if in_string else "")
    repaired = repaired.rstrip()
    repaired = re.sub(r',\s*"[^"]*"\s*:?\s*$', "", repaired)
    repaired = re.sub(r'([{\[])\s*"[^"]*"\s*:\s*$', r"\1", repaired)
    repaired = re.sub(r"[,:]\s*$", "", repaired)
    repaired += "".join("}" if opener == "{" else "]" for opener in reversed(stack))
    repaired = re.sub(r",\s*([}\]])", r"\1", repaired)
    try:
        return json.loads(repaired)
    except json.JSONDecodeError:
        return None


def parse(text: str) -> ParsedReading:
    """Tolerant parse: snap enums, clip text, fall back to defaults. Never raises."""
    obj = _loads_lenient(text or "")
    reading = default_reading()
    if not isinstance(obj, dict):
        return ParsedReading(reading, False, {n: False for n in SLOT_NAMES}, {n: False for n in SLOT_NAMES},
                             "no JSON object")
    raw = flatten(obj)
    exact: dict[str, bool] = {}
    present: dict[str, bool] = {}
    for slot in SLOTS:
        if slot.name not in raw:
            exact[slot.name] = False
            present[slot.name] = False
            continue
        present[slot.name] = True
        value = raw[slot.name]
        if slot.kind == "enum":
            snapped, is_exact = _snap_enum(value, slot.values)
            reading[slot.name] = snapped or slot.default
            exact[slot.name] = is_exact
        else:
            clipped, within = _clip_words(value, slot.max_words)
            reading[slot.name] = clipped if clipped else slot.default
            exact[slot.name] = within
    return ParsedReading(reading, True, exact, present)


def value_spans(text: str) -> dict[str, tuple[int, int]]:
    """Character span of each slot value in model-written JSON, found by scanning leaf keys in canonical order.

    Works on partially formed canvases; slots that cannot be located are omitted. For an empty value the
    span is empty (start == end) and points at the closing quote.
    """
    spans: dict[str, tuple[int, int]] = {}
    cursor = 0
    for slot in SLOTS:
        key = '"' + slot.name.split(".")[-1] + '"'
        found = text.find(key, cursor)
        if found < 0:
            continue
        colon = text.find(":", found + len(key))
        start = text.find('"', colon + 1) if colon >= 0 else -1
        if start < 0 or text[colon + 1:start].strip():
            continue
        end = start + 1
        while end < len(text) and not (text[end] == '"' and text[end - 1] != "\\"):
            end += 1
        spans[slot.name] = (start + 1, min(end, len(text)))
        cursor = end + 1
    return spans


def dirty_slots(dirty_sections: set[str]) -> set[str]:
    """Slots whose declared inputs changed. v0 uses this fixed table; E2 compares it with model-chosen renoising."""
    return {slot.name for slot in SLOTS if dirty_sections.intersection(slot.depends)}


def schema_text() -> str:
    """Human/model-readable schema used by prompts (teacher, AR baseline, diffusion prompt)."""
    lines = ["READING slots (JSON, keep this exact key order; every value is a string):"]
    for slot in SLOTS:
        if slot.kind == "enum":
            detail = "one of " + "|".join(slot.values)
        elif slot.kind == "ref":
            detail = f"an @r reference, player name, or none (<= {slot.max_words} words)"
        else:
            detail = f"free text <= {slot.max_words} words" + (" (empty allowed)" if slot.default == "" else "")
        lines.append(f"- {slot.name}: {detail}; default {slot.default!r}")
    return "\n".join(lines)


def example_json() -> str:
    return serialize(default_reading())[0]
