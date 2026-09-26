#!/usr/bin/env python3
"""Build an auditable wake ledger from a RuntimeFlightRecorder directory."""

import argparse
from collections import Counter
import json
import math
from pathlib import Path
import re
import sys

SCHEMA = "airicraft.wake-ledger.v1"
INITIAL_PHASES = {"INITIAL", "PLANNER_REQUEST"}


def read_jsonl(path):
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as stream:
        return [json.loads(line) for line in stream if line.strip()]


def number(value):
    return int(value) if value is not None else None


def observation(messages):
    """Read the canonical observe JSON, including legacy DECISION CONTEXT text."""
    observe_ids = {call.get("id") for message in messages if message.get("role") == "assistant"
                   for call in message.get("tool_calls", [])
                   if call.get("function", {}).get("name") == "observe"}
    for message in reversed(messages):
        if message.get("role") != "tool" or message.get("tool_call_id") not in observe_ids:
            continue
        try:
            value = json.loads(message.get("content") or "")
        except (TypeError, json.JSONDecodeError):
            continue
        if isinstance(value, dict) and {"tick", "serverTick", "afterEventSequence", "throughEventSequence", "events"} <= value.keys():
            return value, None
    for message in reversed(messages):
        content = message.get("content")
        if not isinstance(content, str):
            continue
        for marker in ("DECISION CONTEXT:", "Tool result for observe:"):
            offset = content.rfind(marker)
            if offset < 0:
                continue
            start = content.find("{", offset + len(marker))
            if start < 0:
                continue
            try:
                value, _ = json.JSONDecoder().raw_decode(content[start:])
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict) and {"tick", "serverTick", "throughEventSequence"} <= value.keys():
                return value, content[:offset].strip()
    return None, None


def latest_user_delta(messages, previous_messages):
    previous = Counter(message.get("content") for message in previous_messages
                       if message.get("role") == "user" and isinstance(message.get("content"), str))
    for message in messages:
        content = message.get("content")
        if message.get("role") != "user" or not isinstance(content, str):
            continue
        if previous[content]:
            previous[content] -= 1
            continue
        if not content.startswith(("Context update:", "Tool result", "Goal:", "DECISION CONTEXT:")) \
                and "DECISION CONTEXT:" not in content and not content.lower().startswith(("trigger:", "manual trigger:")):
            return True
    return False


def trigger_hint(prefix):
    if not prefix:
        return None
    match = re.search(r"(?:^|\n)((?:manual )?trigger[^\n]*)", prefix, re.IGNORECASE)
    return match.group(1).strip() if match else prefix.splitlines()[0][:160]


def has_gap(numbers):
    ordered = sorted(set(numbers))
    return bool(ordered and (ordered[0] > 1 or any(b != a + 1 for a, b in zip(ordered, ordered[1:]))))


def percentile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        return None
    return ordered[math.ceil(fraction * len(ordered)) - 1]


def distribution(values):
    return {"p50": percentile(values, .5), "p90": percentile(values, .9),
            "max": max(values) if values else None, "count": len(values)}


def rate(count, span, scale):
    return round(count * scale / span, 6) if span > 0 else None


def build_ledger(run_dir):
    run_dir = Path(run_dir)
    calls = sorted(read_jsonl(run_dir / "planner-calls.jsonl"), key=lambda x: number(x["sequence"]))
    events = [row["event"] for row in read_jsonl(run_dir / "events.jsonl")]
    event_by_seq = {number(event["seqNo"]): event for event in events}
    timeline = [row["entry"] for row in read_jsonl(run_dir / "debug-timeline.jsonl")]
    timeline.sort(key=lambda x: number(x["entryId"]))
    wakes = [entry for entry in timeline if entry.get("domain") == "planner_wake"]
    drops = []
    for entry in wakes:
        if entry.get("action") != "dropped":
            continue
        fields = entry.get("payload") or {}
        drops.append({"entryId": entry["entryId"], "agentTick": entry.get("tick"),
                      "serverTick": fields.get("serverTick"), "path": fields.get("path"),
                      "owner": fields.get("owner"), "gate": fields.get("gate"),
                      "eventSequence": fields.get("eventSequence"),
                      "retainedPending": fields.get("gate") in {"G5.run_policy", "G5.queued_tool_work"}})

    requests = []
    previous_initial_server = None
    previous_initial_agent = None
    previous_messages = []
    consumed_wake_ids = set()
    for call in calls:
        phase = call.get("plannerAttempt", {}).get("phase")
        if phase not in INITIAL_PHASES | {"TOOL_FOLLOW_UP"}:
            continue
        messages = call.get("request", {}).get("messages", [])
        obs, prefix = observation(messages)
        dispatch_server = number(call["timeline"]["submitted"]["serverTick"])
        agent_tick = number(obs.get("tick")) if obs else None
        server_tick = number(obs.get("serverTick")) if obs else None
        visible = obs.get("events", []) if obs else []
        attributed = []
        if phase in INITIAL_PHASES:
            for entry in wakes:
                if entry.get("action") != "submitted" or entry["entryId"] in consumed_wake_ids:
                    continue
                fields = entry.get("payload") or {}
                wake_server = number(fields.get("serverTick"))
                if wake_server is not None and wake_server < 0:
                    wake_server = None
                wake_agent = number(entry.get("tick"))
                if wake_server is not None:
                    in_window = (previous_initial_server is None or wake_server >= previous_initial_server) and wake_server <= dispatch_server
                else:
                    in_window = (previous_initial_agent is None or wake_agent > previous_initial_agent) and (agent_tick is None or wake_agent <= agent_tick)
                if in_window:
                    attributed.append({"entryId": entry["entryId"], "path": fields.get("path", "unknown"),
                                       "submittedAttempt": True,
                                       "owner": fields.get("owner"), "agentTick": wake_agent,
                                       "serverTick": wake_server, "triggerTypes": fields.get("triggerTypes", []),
                                       "origins": fields.get("origins", []), "coalescingKeys": fields.get("coalescingKeys", []),
                                       "speakers": fields.get("speakers", []), "eventSequence": fields.get("eventSequence")})
                    consumed_wake_ids.add(entry["entryId"])
            if not attributed:
                attributed = [{"path": "unknown", "reason": "no submitted audit in attribution window"}]
            previous_initial_server, previous_initial_agent = dispatch_server, agent_tick
        direct_guidance = any("DIRECT_GUIDANCE" in wake.get("origins", []) for wake in attributed)
        user_delta = latest_user_delta(messages, previous_messages)
        visible_chat = any(event.get("type") == "social.player_addressed_agent" for event in visible)
        user_turn = direct_guidance or user_delta or visible_chat
        user_turn_source = "direct_guidance_audit" if direct_guidance else "request_user_delta" if user_delta else "planner_visible_chat" if visible_chat else None
        previous_messages = messages
        visible_events = []
        evidence_gaps = bool(obs and obs.get("missingEventRange"))
        for event in visible:
            raw_seq = number(event.get("seqNo"))
            raw = event_by_seq.get(raw_seq)
            recorded = raw is not None and raw.get("type") == event.get("type") and number(raw.get("tick")) == number(event.get("tick"))
            evidence_gaps = evidence_gaps or not recorded
            visible_events.append({"seqNo": raw_seq, "rawSeqNo": number(raw["seqNo"]) if recorded else None,
                                   "tick": number(event.get("tick")), "type": event.get("type"), "recorded": recorded})
        requests.append({"seq": number(call["sequence"]), "turnId": call.get("turnId"),
                         "phase": phase, "dispatchAgentTick": agent_tick,
                         "dispatchServerTick": dispatch_server, "observeServerTick": server_tick,
                         "owner": obs.get("decisionOwner") if obs else None,
                         "afterEventSequence": number(obs.get("afterEventSequence")) if obs else None,
                         "throughEventSequence": number(obs.get("throughEventSequence")) if obs else None,
                         "newEvents": visible_events,
                         "userTurn": user_turn, "userTurnSource": user_turn_source,
                         "baselineRefresh": bool(obs and obs.get("stateBaseline")),
                         "wakes": attributed, "triggerHint": trigger_hint(prefix) if attributed and attributed[0]["path"] == "unknown" else None,
                         "appliedServerTick": number((call["timeline"].get("applied") or {}).get("serverTick")),
                         "clockAlignment": {"agentTick": agent_tick, "serverTick": server_tick},
                         "evidenceGap": evidence_gaps,
                         "uncertainty": [reason for condition, reason in
                                         ((obs is None, "observation_missing"),
                                          (bool(attributed) and attributed[0]["path"] == "unknown" and phase in INITIAL_PHASES, "wake_audit_missing"),
                                          (user_turn_source == "request_user_delta", "user_turn_inferred_from_request"),
                                          (evidence_gaps, "event_evidence_incomplete")) if condition]})

    initial = [r for r in requests if r["phase"] in INITIAL_PHASES]
    followups = [r for r in requests if r["phase"] == "TOOL_FOLLOW_UP"]
    ticks = [r["dispatchServerTick"] for r in requests]
    span = max(ticks) - min(ticks) if len(ticks) > 1 else (1 if ticks else 0)
    per_owner, per_path, empty = Counter(), Counter(), Counter()
    for request in initial:
        paths = {wake["path"] for wake in request["wakes"]}
        per_owner[request["owner"] or "unknown"] += 1
        for path in paths:
            per_path[path] += 1
            if not request["newEvents"] and not request["userTurn"] and not request["baselineRefresh"]:
                empty[path] += 1

    outcome_latency, chat_request_latency, chat_applied_latency = [], [], []
    for event in events:
        kind = event.get("type")
        terminal = kind == "work.changed" and str(event.get("payload", {}).get("state", "")).upper() in {"SUCCEEDED", "FAILED", "CANCELLED"}
        if not terminal and kind != "social.player_addressed_agent":
            continue
        raw_seq = number(event["seqNo"])
        if terminal:
            first = next((r for r in requests if r["throughEventSequence"] is not None
                          and r["throughEventSequence"] >= raw_seq), None)
        else:
            speaker = event.get("payload", {}).get("player") or event.get("payload", {}).get("speaker")
            first = next((r for r in requests if any(
                "DIRECT_GUIDANCE" in wake.get("origins", []) and "CHAT" in wake.get("triggerTypes", [])
                and wake.get("agentTick") is not None and wake["agentTick"] >= number(event["tick"])
                and (not speaker or speaker in wake.get("speakers", [])) for wake in r["wakes"])), None)
            if first is None:
                first = next((r for r in requests if any(
                    visible["seqNo"] == raw_seq and visible["type"] == kind for visible in r["newEvents"])), None)
        if first is None or first["dispatchAgentTick"] is None:
            continue
        agent_delta = first["dispatchAgentTick"] - number(event["tick"])
        if agent_delta < 0:
            continue
        if terminal:
            outcome_latency.append(agent_delta)
        else:
            chat_request_latency.append(agent_delta)
            if first["appliedServerTick"] is not None and first["observeServerTick"] is not None:
                chat_applied_latency.append(first["appliedServerTick"] - (first["observeServerTick"] - agent_delta))

    llm_latest = {}
    for row in read_jsonl(run_dir / "llm-calls.jsonl"):
        record = row["record"]
        if record.get("requestKind", "").lower() == "planner":
            llm_latest[record["sequenceId"]] = record
    summary_path = run_dir / "summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}
    llm_gaps = not (run_dir / "llm-calls.jsonl").exists() or bool(summary.get("llmCallsTruncated"))
    window_start, window_end = (min(ticks), max(ticks)) if ticks else (None, None)
    in_window = [record for record in llm_latest.values()
                 if window_start is not None and number(record.get("dispatchServerTick")) is not None
                 and window_start <= number(record["dispatchServerTick"]) <= window_end]
    tokens_total = sum(number(record.get("usage", {}).get("totalTokens")) or 0 for record in llm_latest.values())
    tokens = sum(number(record.get("usage", {}).get("totalTokens")) or 0 for record in in_window)
    token_unknown = sum(record.get("usage", {}).get("totalTokens") is None for record in in_window)
    window_unplaced = sum(record.get("dispatchServerTick") is None for record in llm_latest.values())
    tokens_complete = bool(in_window) and not token_unknown and not llm_gaps and not window_unplaced
    metrics = {"requestsPerMinute": {"overall": rate(len(initial), span, 1200),
                                     "byOwner": {key: rate(count, span, 1200) for key, count in sorted(per_owner.items())},
                                     "byPath": {key: rate(count, span, 1200) for key, count in sorted(per_path.items())}},
               "followUpsPerTurn": len(followups) / len(initial) if initial else None,
               "emptyWakes": dict(sorted(empty.items())),
               "outcomeLatencyTicks": distribution(outcome_latency),
               "chatReplyLatencyTicks": {"toRequest": distribution(chat_request_latency),
                                         "toApplied": distribution(chat_applied_latency)},
               "droppedWakes": dict(sorted(Counter(drop["gate"] or "unknown" for drop in drops).items())),
               "tokensPerHour": rate(tokens, span, 72000) if tokens_complete else None,
               "tokensComplete": tokens_complete,
               "tokensTotalRecorded": tokens_total, "tokensInWindow": tokens,
               "tokenWindow": {"startServerTick": window_start, "endServerTick": window_end, "durationTicks": span},
               "tokensExcludedOutsideWindow": len(llm_latest) - len(in_window) - window_unplaced,
               "tokensUnknown": token_unknown,
               "llmGaps": llm_gaps, "tokensUnplaced": window_unplaced,
               "timelineGaps": not (run_dir / "debug-timeline.jsonl").exists() or bool(summary.get("debugTimelineTruncated")) or has_gap([entry["entryId"] for entry in timeline]),
               "eventGaps": not (run_dir / "events.jsonl").exists() or bool(summary.get("eventsTruncated")) or has_gap([event["seqNo"] for event in events]) or any(r["evidenceGap"] for r in requests),
               "byPathAttribution": "multi_attributed_submitted_attempts",
               "chatAppliedClockEstimate": True,
               "observedServerTickSpan": span}
    return {"schema": SCHEMA, "runDir": str(run_dir), "requests": requests, "drops": drops, "metrics": metrics}


def diff_ledgers(before, after):
    left = {r["seq"]: r for r in before["requests"]}
    right = {r["seq"]: r for r in after["requests"]}
    changed = []
    for seq in sorted(left.keys() | right.keys()):
        a, b = left.get(seq), right.get(seq)
        differences = {}
        if a is None or b is None:
            differences["presence"] = {"before": a is not None, "after": b is not None}
        else:
            for label, getter in (("wakePaths", lambda r: [w["path"] for w in r["wakes"]]),
                                  ("evidence", lambda r: {k: r[k] for k in ("afterEventSequence", "throughEventSequence", "newEvents", "userTurn", "baselineRefresh", "evidenceGap")})):
                old, new = getter(a), getter(b)
                if old != new:
                    differences[label] = {"before": old, "after": new}
        if differences:
            changed.append({"seq": seq, "differences": differences})
    return {"schema": "airicraft.wake-ledger-diff.v1", "changedRequests": changed}


def summary_table(ledgers):
    rows = ["| Run | INITIAL | Requests/min | Follow-ups/turn | Empty | Drops | Tokens/hour | Gaps |",
            "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
    rates, token_rates = [], []
    for ledger in ledgers:
        m = ledger["metrics"]
        rpm = m["requestsPerMinute"]["overall"]
        if rpm is not None:
            rates.append(rpm)
        if m["tokensPerHour"] is not None:
            token_rates.append(m["tokensPerHour"])
        rows.append(f"| {Path(ledger['runDir']).name} | {sum(r['phase'] in INITIAL_PHASES for r in ledger['requests'])} | {rpm if rpm is not None else 'n/a'} | {m['followUpsPerTurn'] if m['followUpsPerTurn'] is not None else 'n/a'} | {sum(m['emptyWakes'].values())} | {sum(m['droppedWakes'].values())} | {m['tokensPerHour'] if m['tokensPerHour'] is not None else 'n/a'} | {'yes' if m['timelineGaps'] or m['eventGaps'] or m['llmGaps'] else 'no'} |")
    rows.append(f"\nRequests/min spread: {min(rates)}–{max(rates)}" if rates else "\nRequests/min spread: n/a")
    rows.append(f"Tokens/hour spread: {min(token_rates)}–{max(token_rates)}" if token_rates else "Tokens/hour spread: n/a")
    return "\n".join(rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    ledger = commands.add_parser("ledger")
    ledger.add_argument("run_dir", type=Path)
    ledger.add_argument("-o", "--output", type=Path)
    summary = commands.add_parser("summarize")
    summary.add_argument("run_dirs", nargs="+", type=Path)
    diff = commands.add_parser("diff")
    diff.add_argument("before", type=Path)
    diff.add_argument("after", type=Path)
    args = parser.parse_args(argv)
    if args.command == "ledger":
        result = json.dumps(build_ledger(args.run_dir), indent=2, ensure_ascii=False) + "\n"
        if args.output:
            args.output.write_text(result, encoding="utf-8")
        else:
            sys.stdout.write(result)
    elif args.command == "summarize":
        print(summary_table([build_ledger(path) for path in args.run_dirs]))
    else:
        print(json.dumps(diff_ledgers(json.loads(args.before.read_text()), json.loads(args.after.read_text())), indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
