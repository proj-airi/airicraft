package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionSpec;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.StepExecutionResult;
import ai.moeru.airicraft.agent.tasks.StepExecutionStatus;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskOwnership;
import ai.moeru.airicraft.agent.tasks.TaskProgressSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskStep;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerContextAggregatorTest {
	@Test void retainedDelegationTriggerKeepsItsStructuredFields() {
		var aggregator = new PlannerContextAggregator(Clock.systemUTC(), 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		String id = "1e5e7000-0000-4000-8000-000000000000";
		var fields = JsonParser.parseString("{\"delegationId\":\"" + id + "\",\"task\":\"mine iron\"}").getAsJsonObject();
		var trigger = PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self",
			"DELEGATED TASK CONTINUATION: " + id + "; task=mine iron", 1, 1_000, "planner_goal", fields);
		var request = requestAt(1_000L, "Alice", "placeholder").withTriggerBatch(PlannerTriggerBatch.of(List.of(trigger)));
		var snapshot = freezeSnapshot(aggregator, request);
		aggregator.commitAcceptedTriggerBatch(snapshot);
		var retained = aggregator.currentRetainedConversation(1_000L);
		assertTrue(retained.messages().stream().anyMatch(message -> message.fields() != null
			&& id.equals(message.fields().getAsJsonObject().get("delegationId").getAsString())));
	}
	@Test
	void completedJobContextDoesNotClaimWorkOrPlannerGoalIsActive() {
		var aggregator = new PlannerContextAggregator(Clock.systemUTC(), 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		var task = new TaskSnapshot(TaskState.COMPLETED,
			new MissionSpec("finished-job", MissionType.COLLECT_RESOURCE, "Collect logs"), null, null,
			new TaskProgressSnapshot(2, 0), TaskStep.NONE, TaskOwnership.NONE, "action_graph", null,
			"collect_logs", LedgerStepKind.COLLECT_RESOURCE, StepExecutionResult.idle(), 200L);
		var request = new PlannerRequest(200L, 10_000L, SessionMode.SINGLEPLAYER_LAN_HOST,
			"Alice", null, task, null, "Alice", "continue", null);
		String context = aggregator.buildPlannerConversation(request).messages().toString();
		assertTrue(context.contains("Job state=COMPLETED COLLECT_RESOURCE"));
		assertTrue(context.contains("No direct action goal is active"));
		assertFalse(context.contains("There is no active goal right now"));
		assertFalse(context.contains("Active job"));
	}

	@Test
	void fixedRolePrefixSurvivesGoalUpdates() {
		var data = new java.util.concurrent.atomic.AtomicReference<>("goal=prepare shelter");
		var provider = new PlannerToolProvider() {
			public String id() { return "test_context"; }
			public List<Map<String, Object>> openAiTools() { return List.of(); }
			public boolean handles(String name) { return false; }
			public String promptInstructions() { return "Act as the controller."; }
			public String contextSnapshot() { return data.get(); }
			public java.util.concurrent.CompletableFuture<String> execute(PlannerToolCall call) { throw new UnsupportedOperationException(); }
		};
		var registry = PlannerToolRegistry.of(provider);
		registry.freezeToolPrefix();
		var tools = registry.openAiTools();
		var aggregator = new PlannerContextAggregator(Clock.systemUTC(), 65_536, 128,
			PlannerVisionMode.EXTERNAL_SUMMARY, registry);
		var first = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "start"));
		aggregator.commitAcceptedTriggerBatch(first);
		data.set("goal=build shelter");
		var second = freezeSnapshot(aggregator, requestAt(2_000L, "Alice", "continue"));
		assertEquals(tools, registry.openAiTools());
		var a = first.plannerConversation().messages();
		var b = second.plannerConversation().messages();
		assertEquals(a, b.subList(0, a.size()));
		assertFalse(b.getFirst().content().contains("goal="));
		assertTrue(b.stream().anyMatch(m -> m.content().equals("goal=build shelter")));
	}

	@Test
	void discardedSnapshotConsumesInputWithoutAddingItToHistory() {
		PlannerContextAggregator aggregator = new PlannerContextAggregator(
			Clock.systemUTC(),
			10_000,
			PlannerVisionMode.EXTERNAL_SUMMARY
		);
		PlannerRequest discardedRequest = requestAt(10_000L, "Alice", "discard me");
		aggregator.enqueueTrigger(discardedRequest.triggerBatch().triggers().getFirst());
		PlannerContextSnapshot discarded = aggregator.freezePlannerSnapshot(discardedRequest);

		aggregator.discardSnapshot(discarded);

		assertEquals(0, aggregator.queuedTriggerCount());
		PlannerRequest nextRequest = requestAt(11_000L, "Alice", "keep me");
		aggregator.enqueueTrigger(nextRequest.triggerBatch().triggers().getFirst());
		String nextConversation = aggregator.freezePlannerSnapshot(nextRequest).plannerConversation().messages().toString();
		assertFalse(nextConversation.contains("discard me"));
		assertTrue(nextConversation.contains("keep me"));
	}

	@Test
	void injectsSingleTimeBeaconPerThirtyMinuteWindow() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot firstSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent hi"));
		LlmConversation first = firstSnapshot.plannerConversation();
		assertEquals(6, first.messages().size());
		assertEquals(LlmMessageKind.NOTICE, first.messages().get(1).kind());
		assertTrue(first.messages().get(1).content().contains("local time"));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("Session mode is currently out of world.")));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("There is no primary interaction player right now.")));
		assertTrue(first.messages().stream().anyMatch(message -> message.content().contains("No direct action goal is active")));
		aggregator.commitAcceptedTriggerBatch(firstSnapshot);

		PlannerContextSnapshot secondSnapshot = freezeSnapshot(aggregator, requestAt(10 * 60_000L, "Alice", "@agent follow me"));
		LlmConversation second = secondSnapshot.plannerConversation();
		long noticeCount = second.messages().stream().filter(message -> message.kind() == LlmMessageKind.NOTICE).count();
		assertEquals(0L, noticeCount);
		aggregator.commitAcceptedTriggerBatch(secondSnapshot);

		PlannerContextSnapshot thirdSnapshot = freezeSnapshot(aggregator, requestAt(31 * 60_000L, "Alice", "@agent stop"));
		LlmConversation third = thirdSnapshot.plannerConversation();
		long updatedNoticeCount = third.messages().stream().filter(message -> message.kind() == LlmMessageKind.NOTICE).count();
		assertEquals(1L, updatedNoticeCount);
	}

	@Test
	void recordsAmbientContextAndSemanticEventsAsFrozenNotices() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(1L, 100L, 8_000L, "follow.target_acquired", Map.of("player", "Alice")),
			new SemanticEvent(2L, 101L, 9_000L, "planner.goal_set", Map.of("goalType", "FOLLOW_PLAYER", "targetPlayer", "Alice"))
		));

		PlannerContextSnapshot snapshot = freezeSnapshot(aggregator, new PlannerRequest(
			200L,
			10_000L,
			SessionMode.REMOTE_MULTIPLAYER,
			"Alice",
			new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alice", 200L, "planner"),
			null,
			null,
			"Bob",
			"status?",
			null
		));
		LlmConversation conversation = snapshot.plannerConversation();

		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Primary interaction player is Alice.")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Direct action goal: Follow Alice.")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Started following Alice")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("The planner set goal FOLLOW_PLAYER for Alice")));
	}

	@Test
	void recordsProjectedMixedEventBatchAsCoalescedNoticesInFirstSeenOrder() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(1L, 100L, 8_000L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 1)),
			new SemanticEvent(2L, 101L, 8_100L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:cobblestone", "count", 1)),
			new SemanticEvent(3L, 102L, 8_200L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:dirt", "count", 2)),
			new SemanticEvent(4L, 103L, 8_300L, "follow.target_acquired", Map.of("player", "Alice"))
		));

		LlmConversation conversation = freezeSnapshot(aggregator, requestAt(10_000L, "Alice", "@agent hi")).plannerConversation();
		List<LlmChatMessage> notices = conversation.messages().stream()
			.filter(message -> message.kind() == LlmMessageKind.NOTICE)
			.toList();

		assertTrue(notices.stream().anyMatch(message -> message.content().contains("3x minecraft:dirt")));
		assertTrue(notices.stream().anyMatch(message -> message.content().contains("1x minecraft:cobblestone")));
		assertTrue(notices.stream().anyMatch(message -> message.content().contains("Started following Alice")));

		int dirtIndex = indexContaining(notices, "3x minecraft:dirt");
		int cobbleIndex = indexContaining(notices, "1x minecraft:cobblestone");
		int followIndex = indexContaining(notices, "Started following Alice");
		assertTrue(dirtIndex < cobbleIndex);
		assertTrue(cobbleIndex < followIndex);
	}

	@Test
	void coalescesMatchingSemanticUpdatesAcrossMultipleRecordBatchesBeforeFreeze() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(1L, 100L, 8_000L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1))
		));
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(2L, 101L, 8_100L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1))
		));
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(3L, 102L, 8_200L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:wheat_seeds", "count", 1))
		));
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(4L, 103L, 8_300L, "pickup.item_picked_up", Map.of("actor", "self", "itemId", "minecraft:sunflower", "count", 1))
		));

		LlmConversation conversation = freezeSnapshot(aggregator, requestAt(10_000L, "Alice", "@agent hi")).plannerConversation();
		List<LlmChatMessage> notices = conversation.messages().stream()
			.filter(message -> message.kind() == LlmMessageKind.NOTICE)
			.filter(message -> message.content().contains("picked up"))
			.toList();

		assertEquals(2, notices.size());
		assertTrue(notices.get(0).content().contains("3x minecraft:sunflower"));
		assertTrue(notices.get(1).content().contains("1x minecraft:wheat_seeds"));
	}

	@Test
	void coalescesMatchingDamageUpdatesAcrossMultipleRecordBatchesBeforeFreeze() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		recordEvents(aggregator, 10_000L, List.of(
			damageEvent(1L, 100L, 8_000L, 2.0F, 18.0F)
		));
		recordEvents(aggregator, 10_000L, List.of(
			damageEvent(2L, 101L, 8_100L, 1.5F, 16.5F)
		));
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(3L, 102L, 8_200L, "combat.damage_taken", Map.of(
				"actor", "self",
				"amount", 1.0F,
				"healthBefore", 16.5F,
				"healthAfter", 15.5F,
				"fatal", false,
				"damageTypeId", "minecraft:fall"
			))
		));

		LlmConversation conversation = freezeSnapshot(aggregator, requestAt(10_000L, "Alice", "@agent hi")).plannerConversation();
		List<LlmChatMessage> notices = conversation.messages().stream()
			.filter(message -> message.kind() == LlmMessageKind.NOTICE)
			.filter(message -> message.content().contains("took"))
			.toList();

		assertEquals(2, notices.size());
		assertTrue(notices.get(0).content().contains("3.5 damage from Zombie"));
		assertTrue(notices.get(0).content().contains("16.5 health"));
		assertTrue(notices.get(1).content().contains("1 damage from minecraft:fall"));
	}

	@Test
	void includesMissionAndEvidenceNoticesWhenPresent() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);
		TaskLedger ledger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 4 wood logs",
			List.of(new LedgerStep(
				"collect_logs",
				LedgerStepKind.COLLECT_RESOURCE,
				new LedgerStepPayload(
					new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
					null, null, null, null, null, null, null, null, null, null
				),
				List.of(),
				LedgerStepStatus.ACTIVE,
				List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
				1,
				"Collect logs"
			)),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			"user_request",
			"Keep it simple"
		);

		LlmConversation conversation = aggregator.buildPlannerConversation(new PlannerRequest(
			200L,
			10_000L,
			SessionMode.REMOTE_MULTIPLAYER,
			"Alice",
			null,
			new TaskSnapshot(
				TaskState.RUNNING,
				new MissionSpec("mission-wood-1", MissionType.COLLECT_RESOURCE, "Collect 4 wood logs"),
				ledger,
				null,
				new TaskProgressSnapshot(2, 2),
				TaskStep.MINE_TARGET,
				TaskOwnership.TASK_RUNTIME,
				"planner_response",
				null,
				"collect_logs",
				LedgerStepKind.COLLECT_RESOURCE,
				StepExecutionResult.idle(),
				200L
			),
			new MissionExecutionSnapshot(
				new MissionSpec("mission-wood-1", MissionType.COLLECT_RESOURCE, "Collect 4 wood logs"),
				ledger,
				null,
				new WorldEvidence(
					Map.of(ai.moeru.airicraft.agent.tasks.TaskResourceKind.WOOD_LOGS, 2),
					Map.of("minecraft:oak_log", 3),
					Map.of(),
					List.of(new ai.moeru.airicraft.agent.tasks.CraftingOpportunity("oak_log_to_oak_planks", "minecraft:oak_planks", 4, List.of("minecraft:oak_log"))),
					"minecraft:overworld",
					0,
					64,
					0,
					null,
					200L
				),
				new StepExecutionResult("collect_logs", StepExecutionStatus.RUNNING, null, Map.of(), Map.of(), 200L),
				TaskExecutionSnapshot.idle()
			),
			"Bob",
			"status?",
			null
		));

		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Job state=RUNNING COLLECT_RESOURCE")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Job progress: collected=2, remaining=2.")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("World evidence snapshot:")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Compatibility ledger snapshot:")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Last step result:")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("Compatibility history summary:")));
		assertTrue(conversation.messages().stream().anyMatch(message -> message.content().contains("[From {1*oak_log} to 4*oak_planks]: oak_log_to_oak_planks")));
	}

	@Test
	void compactionConversationAppendsTaskInstructionAtTail() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot initialSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent hi"));
		aggregator.commitAcceptedTriggerBatch(initialSnapshot);
		aggregator.recordUsage(new LlmUsageSnapshot(70_000, 200, 70_200));

		assertTrue(aggregator.compactionPending());
		LlmConversation compactionConversation = aggregator.buildCompactionConversation();
		LlmChatMessage lastMessage = compactionConversation.messages().get(compactionConversation.messages().size() - 1);
		assertEquals(LlmMessageKind.TASK, lastMessage.kind());
		assertTrue(lastMessage.content().startsWith("COMPACTION TASK:"));

		aggregator.applyCheckpoint(new CompactionCheckpoint(
			"Tuesday afternoon",
			"in world",
			"follow Alice",
			java.util.List.of("follow Alice"),
			java.util.List.of("Alice is nearby"),
			java.util.List.of("Alice"),
			java.util.List.of("keep following"),
			java.util.List.of("Alice asked for follow"),
			java.util.List.of()
		));

		assertFalse(aggregator.compactionPending());
		LlmConversation afterCheckpoint = freezeSnapshot(aggregator, requestAt(32 * 60_000L, "Alice", "@agent status")).plannerConversation();
		assertEquals(LlmMessageKind.CHECKPOINT, afterCheckpoint.messages().get(1).kind());
	}

	@Test
	void backendManagedHistoryKeepsEventTransactionsWithoutLocalTranscriptOrCompaction() {
		Clock clock = Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(
			clock,
			10,
			128,
			PlannerVisionMode.NATIVE_TOOL_IMAGE,
			PlannerToolRegistry.empty(),
			true
		);
		recordEvents(aggregator, 10_000L, List.of(
			new SemanticEvent(7L, 199L, 9_500L, "follow.target_acquired", Map.of("player", "Alice"))
		));

		PlannerContextSnapshot first = freezeSnapshot(aggregator, requestAt(10_000L, "Alice", "@agent hi"));
		assertTrue(first.plannerConversation().messages().stream().anyMatch(message -> message.content().contains("Started following Alice")));
		aggregator.commitAcceptedTriggerBatch(first);
		aggregator.recordAgentTurn(new ai.moeru.airicraft.agent.dialogue.DialogueTurn("agent", "Hello.", 200L, 10_000L));
		aggregator.recordAcceptedToolExchange(JsonParser.parseString("{\"tool\":\"ignored\"}"), "ignored", 200L, 10_000L);
		aggregator.recordUsage(new LlmUsageSnapshot(100_000, 100, 100_100));

		PlannerContextDebugSnapshot debug = aggregator.debugSnapshot();
		assertEquals(0, debug.acceptedTurnCount());
		assertEquals(0, debug.pendingSemanticEventCount());
		assertEquals(0, debug.queuedTriggerCount());
		assertEquals(7L, debug.lastObservedEventSeqNo());
		assertFalse(aggregator.compactionPending());

		PlannerContextSnapshot second = freezeSnapshot(aggregator, requestAt(11_000L, "Alice", "@agent status"));
		assertFalse(second.plannerConversation().messages().stream().anyMatch(message -> message.content().contains("@agent hi")));
		assertFalse(second.plannerConversation().messages().stream().anyMatch(message -> "assistant".equals(message.role())));

		LlmConversation followUp = aggregator.buildPlannerFollowUpConversation(first, (com.google.gson.JsonElement) null, "inventory count=3");
		assertEquals(2, followUp.messages().size());
		assertEquals(LlmMessageKind.TOOL_RESULT, followUp.messages().getLast().kind());
		assertTrue(followUp.messages().getLast().content().contains("inventory count=3"));
	}

	@Test
	void acceptedAssistantHistoryIsRenderedWithoutFrozenRelativeTimeText() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot firstSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent hi"));
		aggregator.commitAcceptedTriggerBatch(firstSnapshot);
		aggregator.recordAgentTurn(new ai.moeru.airicraft.agent.dialogue.DialogueTurn("agent", "On it.", 20L, 1_000L));

		clock.advanceMillis(120_000L);
		LlmConversation laterConversation = freezeSnapshot(aggregator, requestAt(clock.millis(), "Alice", "@agent status")).plannerConversation();

		assertTrue(laterConversation.messages().stream().anyMatch(message ->
			"assistant".equals(message.role()) && "On it.".equals(message.content())
		));
		assertFalse(laterConversation.messages().stream().anyMatch(message -> message.content().contains("Agent replied just now")));
	}

	@Test
	void currentRetainedConversationIncludesAcceptedReplyAndCheckpoint() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot snapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "Gather iron"));
		aggregator.commitAcceptedTriggerBatch(snapshot);
		aggregator.recordAgentTurn(new ai.moeru.airicraft.agent.dialogue.DialogueTurn("agent", "I found ore", 20L, 1_000L));

		LlmConversation retained = aggregator.currentRetainedConversation(clock.millis());
		assertTrue(retained.messages().stream().anyMatch(message -> message.content().contains("Gather iron")));
		assertTrue(retained.messages().stream().anyMatch(message ->
			"assistant".equals(message.role()) && "I found ore".equals(message.content())),
			"Idle context must include the accepted reply, not just the last submitted request");

		var checkpoint = new CompactionCheckpoint("today", "cave", "smelt iron", List.of(), List.of("Three raw iron gathered"), List.of(), List.of(), List.of(), List.of());
		aggregator.applyCheckpoint(checkpoint);

		retained = aggregator.currentRetainedConversation(clock.millis());
		assertTrue(retained.messages().stream().anyMatch(message -> message.content().contains("Three raw iron gathered")),
			"Idle context must show the post-compaction checkpoint");
		assertTrue(retained.messages().stream().anyMatch(message ->
			message.kind() == LlmMessageKind.CHECKPOINT),
			"Idle context must carry the checkpoint message kind");
	}

	@Test
	void acceptedAssistantHistoryRetainsRawAssistantContentOverride() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot firstSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent hi"));
		aggregator.commitAcceptedTriggerBatch(firstSnapshot);
		aggregator.recordAgentTurn(
			new ai.moeru.airicraft.agent.dialogue.DialogueTurn("agent", "On it.", 20L, 1_000L),
			JsonParser.parseString("""
				[
				  {
				    "type": "reasoning",
				    "text": "Think before responding.",
				    "thought": true,
				    "thought_signature": "sig-123"
				  },
				  {
				    "type": "text",
				    "text": "{\\"replyText\\":\\"On it.\\",\\"intent\\":{\\"type\\":\\"reply_only\\"},\\"toolRequest\\":null}"
				  }
				]
				""")
		);

		LlmConversation laterConversation = freezeSnapshot(aggregator, requestAt(clock.millis() + 1_000L, "Alice", "@agent status")).plannerConversation();
		LlmChatMessage assistantMessage = laterConversation.messages().stream()
			.filter(message -> "assistant".equals(message.role()))
			.findFirst()
			.orElseThrow();

		assertEquals("On it.", assistantMessage.content());
		assertTrue(assistantMessage.rawContentOverride().isJsonArray());
	}

	@Test
	void acceptedToolExchangeRehydratesIntoLaterPlannerHistory() {
		MutableClock clock = new MutableClock(Instant.ofEpochMilli(1_000L), ZoneId.of("Asia/Taipei"));
		PlannerContextAggregator aggregator = new PlannerContextAggregator(clock, 65_536, PlannerVisionMode.EXTERNAL_SUMMARY);

		PlannerContextSnapshot firstSnapshot = freezeSnapshot(aggregator, requestAt(1_000L, "Alice", "@agent craft 4 planks"));
		aggregator.commitAcceptedTriggerBatch(firstSnapshot);
		aggregator.recordAcceptedToolExchange(
			JsonParser.parseString("""
				[
				  {
				    "type": "text",
				    "text": "{\\"replyText\\":\\"\\",\\"intent\\":{\\"type\\":\\"none\\"},\\"toolRequest\\":{\\"type\\":\\"check_craftables\\"}}"
				  }
				]
				"""),
			"Tool result for check_craftables: availableCrafts=Available 2x2 crafts: [From {1*birch_wood} to 4*birch_planks]: birch_wood_to_birch_planks",
			20L,
			1_000L
		);
		aggregator.recordAgentTurn(
			new ai.moeru.airicraft.agent.dialogue.DialogueTurn("agent", "I can craft birch planks.", 21L, 1_500L),
			null
		);

		LlmConversation laterConversation = freezeSnapshot(aggregator, requestAt(clock.millis() + 5_000L, "Alice", "@agent craft them"))
			.plannerConversation();

		LlmChatMessage toolRequest = laterConversation.messages().stream()
			.filter(message -> "assistant".equals(message.role()) && message.rawContentOverride() != null)
			.findFirst()
			.orElseThrow();
		assertTrue(toolRequest.rawContentOverride().toString().contains("check_craftables"));

		LlmChatMessage toolResult = laterConversation.messages().stream()
			.filter(message -> message.kind() == LlmMessageKind.TOOL_RESULT)
			.findFirst()
			.orElseThrow();
		assertTrue(toolResult.content().contains("birch_wood_to_birch_planks"));
	}

	private static PlannerRequest requestAt(long timestampMs, String sender, String message) {
		return new PlannerRequest(
			timestampMs / 50L,
			timestampMs,
			SessionMode.OUT_OF_WORLD,
			null,
			null,
			null,
			null,
			sender,
			message,
			null
		);
	}

	private static void recordEvents(PlannerContextAggregator aggregator, long anchorTimeMs, List<SemanticEvent> events) {
		aggregator.recordObservedEvents(
			new SemanticEventQueryResult(
				events.isEmpty() ? 0L : events.getFirst().seqNo(),
				events.isEmpty() ? 0L : events.getLast().seqNo(),
				false,
				events
			)
		);
	}

	private static SemanticEvent damageEvent(long seqNo, long tick, long timestampMs, float amount, float healthAfter) {
		return new SemanticEvent(seqNo, tick, timestampMs, "combat.damage_taken", Map.of(
			"actor", "self",
			"amount", amount,
			"healthBefore", healthAfter + amount,
			"healthAfter", healthAfter,
			"fatal", false,
			"damageTypeId", "minecraft:mob_attack",
			"attackerName", "Zombie",
			"attackerEntityTypeId", "minecraft:zombie",
			"directSourceEntityTypeId", "minecraft:zombie"
		));
	}

	private static PlannerContextSnapshot freezeSnapshot(PlannerContextAggregator aggregator, PlannerRequest request) {
		if (request.triggerBatch() != null) {
			for (PlannerTrigger trigger : request.triggerBatch().triggers()) {
				aggregator.enqueueTrigger(trigger);
			}
		}
		return aggregator.freezePlannerSnapshot(request);
	}

	private static int indexContaining(List<LlmChatMessage> messages, String fragment) {
		for (int index = 0; index < messages.size(); index++) {
			if (messages.get(index).content().contains(fragment)) {
				return index;
			}
		}
		return -1;
	}

	private static final class MutableClock extends Clock {
		private Instant instant;
		private final ZoneId zoneId;

		private MutableClock(Instant instant, ZoneId zoneId) {
			this.instant = instant;
			this.zoneId = zoneId;
		}

		@Override
		public ZoneId getZone() {
			return zoneId;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return new MutableClock(instant, zone);
		}

		@Override
		public Instant instant() {
			return instant;
		}

		private void advanceMillis(long millis) {
			instant = instant.plusMillis(millis);
		}

		@Override
		public long millis() {
			return instant.toEpochMilli();
		}
	}
}
