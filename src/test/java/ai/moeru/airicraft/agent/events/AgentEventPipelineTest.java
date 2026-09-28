package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentEventPipelineTest {
	@Test void delayedInterventionRetainsSourceTimestamp() {
		var clock = new java.util.concurrent.atomic.AtomicLong(1000L);
		var raw = new AgentEventLog(16);
		var bus = new AgentEventBus(EventCatalog.defaults(), raw, clock::get, true);
		var pipeline = new AgentEventPipeline(raw, bus, new SemanticEventBuffer(16), new EventPolicyState(),
			Map.of("task.notice", new EventRoutingProfile("task.notice", true, null, false)),
			new ai.moeru.airicraft.agent.debug.AgentDebugRecorder(),
			(event, profile) -> new EventPolicyDecision(EventPolicyEffect.IGNORE, "quiet", "test policy", false));
		var source = bus.from("DialogueRuntime").publish(10, "task.notice", Map.of("message", "progress"));
		clock.set(2500L);

		pipeline.drain((event, profile) -> null);

		var intervention = raw.query(source.seqNo()).events().getFirst();
		assertEquals(1000L, intervention.timestampMs());
		assertEquals(10L, intervention.tick());
		assertEquals(2L, intervention.seqNo());
		assertEquals("policy.event_intervened", intervention.type());
		assertEquals("AgentEventPipeline", intervention.source());
		assertEquals(EventCause.event(source.seqNo()), intervention.cause());
		assertEquals(Map.of("sourceEventSeqNo", 1L, "sourceEventType", "task.notice", "effect", "IGNORE",
			"matchedRuleId", "quiet", "reason", "test policy"), intervention.payload());
		assertEquals(2500L, bus.from("DialogueRuntime").publish(11, "task.notice", Map.of()).timestampMs());
	}

	@Test void plannerCopyRetainsProvenance() {
		var raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		var planner = new SemanticEventBuffer(16);
		var pipeline = new AgentEventPipeline(raw, publisher, planner, new EventPolicyState(), Map.of(
			"task.notice", new EventRoutingProfile("task.notice", true, null, false)));
		publisher.publish(10, "task.notice", Map.of("message", "progress"), "DialogueRuntime", EventCause.event(7));
		pipeline.drain((event, profile) -> null);
		var copied = planner.query(null).events().getFirst();
		assertEquals("DialogueRuntime", copied.source());
		assertEquals(EventCause.event(7), copied.cause());
		assertEquals(1000, copied.timestampMs());
	}

	@Test
	void plannerOffStillProducesTriggersButDoesNotRetainSemanticInput() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, new EventPolicyState(), Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false)
		));
		pipeline.setPlannerEnabled(false);

		publisher.from("test").publish(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(0, planner.size());

		pipeline.setPlannerEnabled(true);
		assertEquals(0, planner.size());
	}

	@Test
	void reenabledPlannerFeedContinuesAfterThePreviousSequenceWatermark() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, new EventPolicyState(), Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false)
		));

		publisher.from("test").publish(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple"));
		pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);
		long previousPlannerSeqNo = planner.latestSeqNo();

		pipeline.setPlannerEnabled(false);
		pipeline.setPlannerEnabled(true);
		publisher.from("test").publish(11L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:stick"));
		pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		SemanticEventQueryResult resumed = planner.query(previousPlannerSeqNo);
		assertEquals(1, resumed.events().size());
		assertEquals("minecraft:stick", resumed.events().getFirst().payload().get("itemId"));
	}

	@Test
	void ignoreKeepsRawEventButSuppressesSemanticAndTrigger() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"mute-system",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("social.system_message", null, "server", null, null, null, null),
			"mute system spam",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, policyState, Map.of(
			"social.system_message", new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		publisher.from("test").publish(10L, "social.system_message", Map.of("message", "hello"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "server", "hello", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals(0, planner.size());
		assertEquals(2, raw.size());
		assertTrue(raw.containsType("social.system_message"));
		assertTrue(raw.containsType("policy.event_intervened"));
		var intervention = raw.query(null).events().getLast();
		assertEquals("AgentEventPipeline", intervention.source());
		assertEquals(EventCause.event(1), intervention.cause());
	}

	@Test
	void semanticOnlyKeepsPlannerSemanticFeedButSuppressesTrigger() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"pickup-semantic",
			EventPolicyEffect.SEMANTIC_ONLY,
			new EventPolicyMatch("pickup.item_picked_up", null, null, "self", "minecraft:apple", null, null),
			"keep notice only",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, policyState, Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		publisher.from("test").publish(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals(1, planner.size());
		assertTrue(planner.containsType("pickup.item_picked_up"));
	}

	@Test
	void defaultSemanticOnlyPolicySuppressesTriggerWithoutDroppingContext() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		AgentEventPipeline pipeline = new AgentEventPipeline(
			raw,
			publisher,
			planner,
			policyState,
			Map.of(
				"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
				"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
			),
			new ai.moeru.airicraft.agent.debug.AgentDebugRecorder(),
			(event, profile) -> new EventPolicyDecision(
				EventPolicyEffect.SEMANTIC_ONLY,
				"default-mining-pickup-semantic-only",
				"mining owns pickup progress",
				false
			)
		);

		publisher.from("test").publish(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:cobblestone", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals(1, planner.size());
		assertTrue(planner.containsType("pickup.item_picked_up"));
		assertEquals("default-mining-pickup-semantic-only", policyState.lastDecision().orElseThrow().matchedRuleId());
	}

	@Test
	void explicitAllowRuleOverridesDefaultSemanticOnlyPolicy() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"allow-pickups",
			EventPolicyEffect.ALLOW,
			new EventPolicyMatch("pickup.item_picked_up", null, null, "self", null, null, null),
			"wake on pickups",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(
			raw,
			publisher,
			planner,
			policyState,
			Map.of(
				"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
				"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
			),
			new ai.moeru.airicraft.agent.debug.AgentDebugRecorder(),
			(event, profile) -> new EventPolicyDecision(
				EventPolicyEffect.SEMANTIC_ONLY,
				"default-mining-pickup-semantic-only",
				"mining owns pickup progress",
				false
			)
		);

		publisher.from("test").publish(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:cobblestone", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(1, planner.size());
		assertEquals("allow-pickups", policyState.lastDecision().orElseThrow().matchedRuleId());
	}

	@Test
	void triggerOnlyWakesPlannerWithoutSemanticProjectionInput() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"pickup-trigger",
			EventPolicyEffect.TRIGGER_ONLY,
			new EventPolicyMatch("pickup.item_picked_up", null, null, null, null, null, null),
			"wake only",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, policyState, Map.of(
			"pickup.item_picked_up", new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		publisher.from("test").publish(10L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:apple", "count", 1));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "self", "picked up", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(0, planner.size());
	}

	@Test
	void newestMatchingRuleWins() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"allow-system",
			EventPolicyEffect.ALLOW,
			new EventPolicyMatch("social.system_message", null, null, null, null, null, null),
			"allow",
			1000L,
			null,
			0L,
			"planner"
		));
		policyState.upsert(new EventPolicyRule(
			"ignore-system",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("social.system_message", null, null, null, null, null, null),
			"ignore latest",
			1001L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, policyState, Map.of(
			"social.system_message", new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		publisher.from("test").publish(10L, "social.system_message", Map.of("message", "hello"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "server", "hello", event.tick(), event.timestampMs())
		);

		assertEquals(0, triggers.size());
		assertEquals("ignore-system", policyState.lastDecision().orElseThrow().matchedRuleId());
	}

	@Test
	void bypassEventsIgnoreMatchingPolicyRules() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"ignore-addressed",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("social.player_addressed_agent", "Alice", null, null, null, null, null),
			"should be bypassed",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, policyState, Map.of(
			"social.player_addressed_agent", new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		publisher.from("test").publish(10L, "social.player_addressed_agent", Map.of("player", "Alice", "message", "@agent hi"));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(profile.triggerType(), "Alice", "@agent hi", event.tick(), event.timestampMs())
		);

		assertEquals(1, triggers.size());
		assertEquals(0, policyState.recentInterventionCount());
		assertEquals(1, raw.size());
	}

	@Test
	void taskBlockedBypassesPolicyAndEmitsSemanticAndTrigger() {
		AgentEventLog raw = new AgentEventLog(16);
		var publisher = new AgentEventBus(EventCatalog.defaults(), raw, () -> 1000L, true);
		SemanticEventBuffer planner = new SemanticEventBuffer(16, () -> 1000L);
		EventPolicyState policyState = new EventPolicyState();
		policyState.upsert(new EventPolicyRule(
			"ignore-task-blocked",
			EventPolicyEffect.IGNORE,
			new EventPolicyMatch("task.blocked", null, null, null, null, null, null),
			"should be bypassed",
			1000L,
			null,
			0L,
			"planner"
		));
		AgentEventPipeline pipeline = new AgentEventPipeline(raw, publisher, planner, policyState, Map.of(
			"task.blocked", new EventRoutingProfile("task.blocked", true, PlannerTriggerType.SYSTEM, true),
			"policy.event_intervened", EventRoutingProfile.rawOnly("policy.event_intervened")
		));

		publisher.from("test").publish(10L, "task.blocked", Map.of(
			"taskType", "COLLECT_RESOURCE",
			"resourceKind", "WOOD_LOGS",
			"blockedReason", "target_missing",
			"collected", 0,
			"remaining", 5
		));
		List<PlannerTrigger> triggers = pipeline.drain((event, profile) ->
			PlannerTrigger.pending(
				profile.triggerType(),
				"runtime",
				"Task blocked: taskType=COLLECT_RESOURCE resourceKind=WOOD_LOGS reason=target_missing collected=0 remaining=5.",
				event.tick(),
				event.timestampMs()
			)
		);

		assertEquals(1, triggers.size());
		assertEquals(PlannerTriggerType.SYSTEM, triggers.getFirst().type());
		assertEquals("runtime", triggers.getFirst().speaker());
		assertEquals(
			"Task blocked: taskType=COLLECT_RESOURCE resourceKind=WOOD_LOGS reason=target_missing collected=0 remaining=5.",
			triggers.getFirst().text()
		);
		assertEquals(1, planner.size());
		assertTrue(planner.containsType("task.blocked"));
		assertEquals(0, policyState.recentInterventionCount());
		assertEquals(1, raw.size());
		assertTrue(policyState.lastDecision().orElseThrow().bypassed());
	}
}
