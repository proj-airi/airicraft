"""Regenerate tests/fixtures/run-a from the repo's captured decision contexts plus synthetic events.

The four DECISION CONTEXT payloads come from src/test/resources/planner/semantic-followups.json (captured
from the iron-pickaxe run). Chat, slow-mining and damage events and the extra System 2 calls are synthetic,
shaped like ChatIngestService / EmbodiedAgentRuntime / LlmFlightRecord output. Run from the repo root:

    python3 research/system15/tests/build_fixture.py
"""
from __future__ import annotations

import json
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
SOURCE = REPO / "src/test/resources/planner/semantic-followups.json"
OUT = Path(__file__).resolve().parent / "fixtures" / "run-a"
PREFIX = "DECISION CONTEXT: "

SYNTHETIC_EVENTS = [
    {"seqNo": 40, "tick": 1100, "timestampMs": 0, "type": "social.player_spoke",
     "payload": {"player": "Alex", "message": "@agent stop and come here", "normalizedMessage": "@agent stop and come here"}},
    {"seqNo": 41, "tick": 1100, "timestampMs": 0, "type": "social.player_addressed_agent",
     "payload": {"player": "Alex", "message": "@agent stop and come here", "normalizedMessage": "@agent stop and come here"}},
    {"seqNo": 167, "tick": 3600, "timestampMs": 0, "type": "task.notice",
     "payload": {"reason": "slow_mining", "block": "minecraft:stone", "heldItem": "minecraft:furnace",
                 "elapsedTicks": 120, "estimatedBreakTicks": 480,
                 "bestCarriedToolByBaseSpeed": {"item": "minecraft:stone_pickaxe", "slot": 34},
                 "message": "Slow mining observed: minecraft:stone with minecraft:furnace"}},
    {"seqNo": 168, "tick": 3650, "timestampMs": 0, "type": "combat.damage_taken",
     "payload": {"amount": 3.0, "damageTypeId": "minecraft:mob_attack", "attackerName": "Zombie"}},
    {"seqNo": 169, "tick": 3651, "timestampMs": 0, "type": "reflex.started",
     "payload": {"cause": "MOB", "safetyEpoch": 1}},
]

# (dispatchTick, tool calls, reply) — the four real boundaries plus two synthetic follow-ups.
CALLS = [
    (143, [{"name": "wait_for_work", "arguments": {}}], ""),
    (1057, [{"name": "collect_resource", "arguments": {"resourceKind": "WOOD_LOGS", "quantity": 4}}], ""),
    (1110, [{"name": "cancel_work", "arguments": {"workId": "@r9"}}], "Stopping. Coming to you, Alex."),
    (3502, [{"name": "wait_for_work", "arguments": {}}], ""),
    (3660, [{"name": "equip_item", "arguments": {"itemId": "minecraft:stone_pickaxe"}}], ""),
    (4216, [{"name": "inspect_inventory", "arguments": {}}], ""),
]


# Hand-labelled READING for each fixture doc (overrides on the defaults). Doubles as a worked example of the
# labelling rubric in s15/prompts.py and as the reference for scoring tests.
GOLD = {
    "run-a:143:decision": {"situation": "collecting four spruce logs toward the iron pickaxe"},
    "run-a:1057:decision": {"situation": "idle with crafting table and planks; choosing next step"},
    "run-a:1100:event:40": {"situation": "Alex asked me to stop and come to him", "chat.intent": "come_here",
                            "chat.target": "Alex", "fix.action": "pause_work", "say": "Coming, Alex.",
                            "escalate.level": "now", "escalate.reason": "new_instruction"},
    "run-a:1100:event:41": {"situation": "Alex asked me to stop and come to him", "chat.intent": "come_here",
                            "chat.target": "Alex", "fix.action": "pause_work", "say": "Coming, Alex.",
                            "escalate.level": "now", "escalate.reason": "new_instruction"},
    "run-a:3502:decision": {"situation": "smelting in the furnace; waiting for output"},
    "run-a:3600:event:167": {"situation": "mining stone while holding a furnace", "anomaly.kind": "wrong_tool",
                             "anomaly.detail": "holding furnace, stone_pickaxe carried in slot 34",
                             "fix.action": "equip", "fix.arg": "stone_pickaxe",
                             "escalate.level": "soon", "escalate.reason": "anomaly"},
    "run-a:3650:event:168": {"situation": "zombie hit me while mining with the wrong tool",
                             "threats.0.ref": "Zombie", "threats.0.stance": "fight", "anomaly.kind": "wrong_tool",
                             "anomaly.detail": "holding furnace, stone_pickaxe carried in slot 34",
                             "fix.action": "equip", "fix.arg": "stone_pickaxe",
                             "escalate.level": "soon", "escalate.reason": "threat"},
    "run-a:3651:event:169": {"situation": "combat reflex engaged against a zombie",
                             "threats.0.ref": "Zombie", "threats.0.stance": "fight", "anomaly.kind": "wrong_tool",
                             "anomaly.detail": "holding furnace, stone_pickaxe carried in slot 34",
                             "fix.action": "equip", "fix.arg": "stone_pickaxe",
                             "escalate.level": "soon", "escalate.reason": "threat"},
    "run-a:4216:decision": {"situation": "collecting smelted output from the furnace"},
}


def write_gold() -> None:
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from s15 import reading

    with open(OUT / "teacher-gold.jsonl", "w", encoding="utf-8") as handle:
        for doc_id, overrides in GOLD.items():
            value = dict(reading.default_reading(), **overrides)
            handle.write(json.dumps({"doc_id": doc_id, "reading": value, "parse_ok": True, "raw": "hand-labelled",
                                     "model": "human"}) + "\n")


def main() -> None:
    turns = json.loads(SOURCE.read_text(encoding="utf-8"))
    contexts = []
    for turn in turns:
        for message in turn["messages"]:
            if message["role"] == "user":
                for paragraph in message["content"].split("\n\n"):
                    if paragraph.startswith(PREFIX + "{"):
                        contexts.append((turn, json.loads(paragraph[len(PREFIX):])))
    OUT.mkdir(parents=True, exist_ok=True)

    events = {}
    for _, context in contexts:
        for event in context.get("events", []):
            events[event["seqNo"]] = {"seqNo": event["seqNo"], "tick": event["tick"], "timestampMs": 0,
                                      "type": event["type"], "payload": event.get("payload", {})}
    for event in SYNTHETIC_EVENTS:
        events[event["seqNo"]] = event

    with open(OUT / "events.jsonl", "w", encoding="utf-8") as handle:
        for seq in sorted(events):
            handle.write(json.dumps({"collectedAt": "fixture", "event": events[seq]}) + "\n")

    sequence = 0
    canonical_messages = []
    with open(OUT / "live-recording.jsonl", "w", encoding="utf-8") as handle:
        handle.write(json.dumps({"recordType": "manifest", "fixture": True}) + "\n")
        for seq in sorted(events):
            sequence += 1
            event = events[seq]
            handle.write(json.dumps({"recordType": "observation", "sequence": sequence, "sessionId": "fixture",
                                     "tick": event["tick"], "serverTickId": event["tick"],
                                     "throughServerTickId": event["tick"], "capturedAtMs": 0,
                                     "type": "semantic_event", "payload": event}) + "\n")
        for turn, context in contexts:
            for message in turn["messages"]:
                canonical_messages.append({"role": message["role"], "kind": "NOTICE" if message["role"] == "user" else "TOOL",
                                           "text": message["content"], "generation": 1, "phase": "RUNNING",
                                           "attempt": 0, "hasImageAttachment": False})
            sequence += 1
            payload = {"canonicalConversation": {"generation": 1, "phase": "RUNNING", "attempt": 0,
                                                 "messages": list(canonical_messages)},
                       "projectedConversation": {"generation": 1, "phase": "RUNNING", "attempt": 0, "messages": []},
                       "canonicalMessageCount": len(canonical_messages), "projectedMessageCount": 0,
                       "canonicalUserTurnCount": 0, "projectedUserTurnCount": 0, "hiddenKinds": []}
            handle.write(json.dumps({"recordType": "observation", "sequence": sequence, "sessionId": "fixture",
                                     "tick": context["tick"], "serverTickId": context["serverTick"],
                                     "throughServerTickId": context["serverTick"], "capturedAtMs": 0,
                                     "type": "conversation_sources", "payload": payload}) + "\n")
        handle.write(json.dumps({"recordType": "export_complete", "observations": sequence, "truncated": False}) + "\n")

    by_tick = {context["tick"]: (turn, context) for turn, context in contexts}
    with open(OUT / "llm-calls.jsonl", "w", encoding="utf-8") as handle:
        for index, (tick, tools, reply) in enumerate(CALLS, start=1):
            turn_context = by_tick.get(tick)
            messages = [{"role": "system", "content": "fixture system prompt"}]
            meta = {"tick": tick}
            if turn_context:
                turn, context = turn_context
                messages += [{"role": m["role"], "content": m["content"]} for m in turn["messages"]]
                meta = {k: context[k] for k in ("worldSessionId", "tick", "serverTick", "decisionOwner", "actuatorOwner",
                                                "afterEventSequence", "throughEventSequence") if k in context}
            record = {"sequenceId": index, "requestedAtMs": 1_000_000 + tick * 50, "completedAtMs": 1_000_000 + tick * 50 + 4200,
                      "status": "COMPLETED", "requestKind": "planner", "threadId": "controller", "javaThreadId": 1,
                      "providerName": "fixture", "endpoint": "http://fixture/v1", "model": "fixture-model",
                      "timeoutMillis": 30000, "messageCount": len(messages), "imageAttached": False,
                      "requestBody": json.dumps({"model": "fixture-model", "messages": messages}),
                      "statusCode": 200, "responseModel": "fixture-model",
                      "usage": {"promptTokens": 12000, "completionTokens": 80, "totalTokens": 12080},
                      "rawResponseBody": "", "parsedResponseKind": "planner",
                      "parsedResponse": {"replyText": reply, "toolCalls": [dict(t, id=f"call_{index}") for t in tools]},
                      "failureType": "", "failureMessage": "", "dispatchTick": tick, "dispatchServerTick": tick,
                      "decisionContext": meta}
            requested = dict(record, status="REQUESTED", completedAtMs=0, parsedResponse=None)
            handle.write(json.dumps({"collectedAt": "fixture", "record": requested}) + "\n")
            handle.write(json.dumps({"collectedAt": "fixture", "record": record}) + "\n")
    write_gold()
    print(f"wrote {OUT}")


if __name__ == "__main__":
    main()
