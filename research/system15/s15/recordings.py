"""Readers for Airicraft flight recordings.

Sources, in order of preference (formats documented in docs/live-playtest-recording.md and
docs/automatic-playtest.md; record types from DashboardObservationStore / RuntimeFlightRecorder):

* live-recording.jsonl[.gz]  -> observation records; `conversation_sources` carries the *canonical*
  planner conversation, whose "DECISION CONTEXT: {json}" messages hold the full structured state.
* llm-calls.jsonl[.gz]       -> {"collectedAt", "record": LlmFlightRecord}; a call can appear twice
  (non-terminal, then terminal). `parsedResponse` holds System 2's decision (PlannerResponse).
* events.jsonl[.gz]          -> {"collectedAt", "event": SemanticEvent}.

Provider request bodies are rendered prose with state deltas (PlannerSnapshotPresentation +
PlannerInputText), so structured state is taken from the canonical conversation when available.
"""
from __future__ import annotations

import gzip
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

DECISION_PREFIX = "DECISION CONTEXT: "
RUN_FILE_STEMS = ("live-recording", "llm-calls", "events")
# FlightRecordingObservability.requestKind: planner, follow_up, compaction, micro_compaction, vision.
GAMEPLAY_REQUEST_KINDS = ("planner", "follow_up", "")


def open_text(path: Path):
    path = Path(path)
    if path.suffix == ".gz":
        return gzip.open(path, "rt", encoding="utf-8")
    return open(path, "r", encoding="utf-8")


def iter_jsonl(path: Path) -> Iterator[dict]:
    with open_text(path) as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue  # an interrupted writer can leave a partial last line
            if isinstance(value, dict):
                yield value


def _sniff(path: Path) -> str | None:
    """Stream kind of an arbitrarily named JSONL file (e.g. a dashboard or CLI recording export)."""
    for record in iter_jsonl(path):
        if record.get("recordType") in ("observation", "manifest", "export_complete"):
            return "live-recording"
        if "record" in record:
            return "llm-calls"
        if "event" in record:
            return "events"
        return None
    return None


def find_run_files(root: Path) -> dict[str, Path]:
    """Locate recording streams under a run directory, a Play directory, or a single (possibly renamed) file."""
    root = Path(root)
    found: dict[str, Path] = {}
    if root.is_file():
        kind = next((stem for stem in RUN_FILE_STEMS if root.name in (f"{stem}.jsonl", f"{stem}.jsonl.gz")), None)
        kind = kind or _sniff(root)
        return {kind: root} if kind else {}
    for path in sorted(root.rglob("*.jsonl*")):
        name = path.name
        for stem in RUN_FILE_STEMS:
            if name in (f"{stem}.jsonl", f"{stem}.jsonl.gz") and stem not in found:
                found[stem] = path
    return found


@dataclass
class SemanticEvent:
    seq: int
    tick: int
    type: str
    payload: dict
    timestamp_ms: int = 0


@dataclass
class DecisionContext:
    """One System 2 decision boundary: the structured state it saw and the events it incorporated."""
    world: str
    tick: int
    server_tick: int
    decision_owner: str
    actuator_owner: str
    current: dict | None
    events: list[SemanticEvent]
    after_seq: int | None = None
    through_seq: int | None = None
    rendered_text: str = ""  # used when only rendered prose is available
    source: str = ""

    @property
    def key(self) -> tuple:
        return (self.world, self.tick, self.through_seq)


@dataclass
class LlmCall:
    sequence_id: int
    status: str
    request_kind: str
    thread_id: str
    model: str
    requested_at_ms: int
    completed_at_ms: int
    dispatch_tick: int
    dispatch_server_tick: int
    decision_context: dict
    tool_calls: list[dict] = field(default_factory=list)  # [{"name", "arguments"}]
    reply_text: str = ""
    usage: dict = field(default_factory=dict)
    request_messages: list[dict] = field(default_factory=list)

    @property
    def latency_ms(self) -> int | None:
        if self.completed_at_ms and self.requested_at_ms:
            return self.completed_at_ms - self.requested_at_ms
        return None

    def known_at_tick(self, ms_per_tick: float = 50.0) -> int | None:
        """Agent tick by which the response had arrived (dispatch + wall latency at nominal 20 TPS).

        Approximate: tick-debug pauses and server lag stretch wall time. Unfinished calls are never known.
        """
        if self.dispatch_tick < 0 or self.latency_ms is None or self.status != "COMPLETED":
            return None
        return self.dispatch_tick + -(-self.latency_ms // int(ms_per_tick))


def _event_from(obj: dict) -> SemanticEvent | None:
    if not isinstance(obj, dict) or "type" not in obj:
        return None
    return SemanticEvent(
        seq=int(obj.get("seqNo", obj.get("seq", -1)) or -1),
        tick=int(obj.get("tick", -1) or -1),
        type=str(obj.get("type", "")),
        payload=obj.get("payload") if isinstance(obj.get("payload"), dict) else {},
        timestamp_ms=int(obj.get("timestampMs", 0) or 0),
    )


def _as_int(value, default=-1) -> int:
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def parse_decision_context(text: str, source: str = "") -> DecisionContext | None:
    """Parse one canonical "DECISION CONTEXT: {json}" paragraph (full state, not a presentation delta)."""
    for paragraph in text.split("\n\n"):
        if not paragraph.startswith(DECISION_PREFIX + "{"):
            continue
        try:
            payload = json.loads(paragraph[len(DECISION_PREFIX):])
        except json.JSONDecodeError:
            continue
        current = payload.get("current") if isinstance(payload.get("current"), dict) else None
        events = [e for e in (_event_from(item) for item in payload.get("events") or []) if e]
        return DecisionContext(
            world=str(payload.get("worldSessionId", "")),
            tick=_as_int(payload.get("tick")),
            server_tick=_as_int(payload.get("serverTick")),
            decision_owner=str(payload.get("decisionOwner", "")),
            actuator_owner=str(payload.get("actuatorOwner", "")),
            current=current,
            events=events,
            after_seq=payload.get("afterEventSequence"),
            through_seq=payload.get("throughEventSequence"),
            source=source,
        )
    return None


def _message_text(message: dict) -> str:
    content = message.get("content", message.get("text", ""))
    if isinstance(content, list):
        return "\n\n".join(part.get("text", "") for part in content if isinstance(part, dict))
    return content if isinstance(content, str) else ""


def load_events(path: Path) -> list[SemanticEvent]:
    events: dict[int, SemanticEvent] = {}
    for record in iter_jsonl(path):
        obj = record.get("event")
        if obj is None and record.get("recordType") == "observation" and record.get("type") == "semantic_event":
            obj = record.get("payload")
        event = _event_from(obj) if obj else None
        if event:
            events[event.seq] = event
    return [events[k] for k in sorted(events)]


def _tool_calls(parsed: dict) -> list[dict]:
    calls = []
    for call in (parsed.get("toolCalls") or []) or ([parsed["toolCall"]] if parsed.get("toolCall") else []):
        if isinstance(call, dict) and call.get("name"):
            arguments = call.get("arguments")
            calls.append({"name": call["name"], "arguments": arguments if isinstance(arguments, dict) else {}})
    return calls


def load_llm_calls(path: Path) -> list[LlmCall]:
    """Terminal version of each call (the recorder appends a non-terminal copy first)."""
    latest: dict[int, dict] = {}
    for record in iter_jsonl(path):
        obj = record.get("record")
        if obj is None and record.get("recordType") == "observation" and record.get("type") == "llm_call":
            obj = record.get("payload")
        if not isinstance(obj, dict) or "sequenceId" not in obj:
            continue
        seq = _as_int(obj["sequenceId"])
        previous = latest.get(seq)
        terminal = obj.get("status") in ("COMPLETED", "FAILED")
        if previous is None or terminal or previous.get("status") not in ("COMPLETED", "FAILED"):
            latest[seq] = obj
    calls = []
    for seq in sorted(latest):
        obj = latest[seq]
        parsed = obj.get("parsedResponse") if isinstance(obj.get("parsedResponse"), dict) else {}
        messages: list[dict] = []
        body = obj.get("requestBody")
        if isinstance(body, str) and body.startswith("{"):
            try:
                messages = json.loads(body).get("messages") or []
            except json.JSONDecodeError:
                messages = []
        usage = obj.get("usage") if isinstance(obj.get("usage"), dict) else {}
        calls.append(LlmCall(
            sequence_id=seq,
            status=str(obj.get("status", "")),
            request_kind=str(obj.get("requestKind", "")),
            thread_id=str(obj.get("threadId", "")),
            model=str(obj.get("model", "")),
            requested_at_ms=_as_int(obj.get("requestedAtMs"), 0),
            completed_at_ms=_as_int(obj.get("completedAtMs"), 0),
            dispatch_tick=_as_int(obj.get("dispatchTick")),
            dispatch_server_tick=_as_int(obj.get("dispatchServerTick")),
            decision_context=obj.get("decisionContext") if isinstance(obj.get("decisionContext"), dict) else {},
            tool_calls=_tool_calls(parsed),
            reply_text=str(parsed.get("replyText") or ""),
            usage=usage,
            request_messages=messages,
        ))
    return calls


def contexts_from_live_recording(path: Path) -> tuple[list[DecisionContext], list[SemanticEvent]]:
    contexts: dict[tuple, DecisionContext] = {}
    events: dict[int, SemanticEvent] = {}
    for record in iter_jsonl(path):
        if record.get("recordType") != "observation":
            continue
        kind = record.get("type")
        payload = record.get("payload")
        if kind == "semantic_event":
            event = _event_from(payload)
            if event:
                events[event.seq] = event
        elif kind == "conversation_sources" and isinstance(payload, dict):
            canonical = payload.get("canonicalConversation") or {}
            for message in canonical.get("messages") or []:
                if not isinstance(message, dict) or message.get("role") != "user":
                    continue
                context = parse_decision_context(_message_text(message), "live-recording:canonical")
                if context and context.current is not None:
                    contexts.setdefault(context.key, context)
    ordered = sorted(contexts.values(), key=lambda c: (c.tick, c.through_seq or -1))
    return ordered, [events[k] for k in sorted(events)]


def contexts_from_llm_calls(calls: list[LlmCall]) -> list[DecisionContext]:
    """Fallback when no live recording exists.

    Older/raw request bodies may still contain canonical JSON; rendered bodies only yield prose, kept in
    `rendered_text` so an LLM filler can still read the state (structured features are then unavailable).
    """
    contexts: dict[tuple, DecisionContext] = {}
    for call in calls:
        if call.request_kind not in GAMEPLAY_REQUEST_KINDS:
            continue
        users = [m for m in call.request_messages if m.get("role") == "user"]
        if not users:
            continue
        text = _message_text(users[-1])
        context = parse_decision_context(text, "llm-calls:canonical")
        if context is None and "DECISION CONTEXT" in text:
            meta = call.decision_context
            context = DecisionContext(
                world=str(meta.get("worldSessionId", "")),
                tick=_as_int(meta.get("tick"), call.dispatch_tick),
                server_tick=_as_int(meta.get("serverTick"), call.dispatch_server_tick),
                decision_owner=str(meta.get("decisionOwner", "")),
                actuator_owner=str(meta.get("actuatorOwner", "")),
                current=None,
                events=[],
                after_seq=meta.get("afterEventSequence"),
                through_seq=meta.get("throughEventSequence"),
                rendered_text=text[text.index("DECISION CONTEXT"):],
                source="llm-calls:rendered",
            )
        if context:
            contexts.setdefault(context.key, context)
    return sorted(contexts.values(), key=lambda c: (c.tick, c.through_seq or -1))


GENERIC_DIR_NAMES = {"airicraft.playtest", "extensions", "recorder", "capture", "flight", "staging"}


def default_run_id(root: Path) -> str:
    """A readable id that stays unique across typical layouts.

    Play extension directories share the name `airicraft.playtest`, so walk up to the Play directory; evaluator
    scenario directories (`01-iron-pickaxe`) repeat across evaluation runs, so prefix the run directory. No ':'
    (document ids use it as a separator).
    """
    root = Path(root).resolve()
    node = root.parent if root.is_file() else root
    while node.name in GENERIC_DIR_NAMES and node.parent != node:
        node = node.parent
    name = node.name
    if len(name) > 3 and name[:2].isdigit() and name[2] == "-":
        name = f"{node.parent.name}__{name}"
    if root.is_file() and root.stem.split(".")[0] not in RUN_FILE_STEMS:
        name = f"{name}__{root.stem.split('.')[0]}"
    return name.replace(":", "_")


@dataclass
class Run:
    run_id: str
    contexts: list[DecisionContext]
    events: list[SemanticEvent]
    calls: list[LlmCall]
    files: dict[str, Path]


def load_run(root: Path, run_id: str | None = None) -> Run:
    files = find_run_files(root)
    contexts: list[DecisionContext] = []
    events: list[SemanticEvent] = []
    calls: list[LlmCall] = []
    if "llm-calls" in files:
        calls = load_llm_calls(files["llm-calls"])
    if "live-recording" in files:
        contexts, events = contexts_from_live_recording(files["live-recording"])
    if "events" in files:
        merged = {e.seq: e for e in events}
        merged.update({e.seq: e for e in load_events(files["events"])})
        events = [merged[k] for k in sorted(merged)]
    if not contexts and calls:
        contexts = contexts_from_llm_calls(calls)
    if not events:
        merged = {}
        for context in contexts:
            for event in context.events:
                merged[event.seq] = event
        events = [merged[k] for k in sorted(merged)]
    return Run(run_id or default_run_id(root), contexts, events, calls, files)
