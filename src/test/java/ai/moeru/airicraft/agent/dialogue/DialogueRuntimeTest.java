package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.llm.CurrentInventoryTool;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionTool;
import ai.moeru.airicraft.agent.llm.LlmBackend;
import ai.moeru.airicraft.agent.llm.LlmBackendException;
import ai.moeru.airicraft.agent.llm.LlmCallResult;
import ai.moeru.airicraft.agent.llm.LlmConversation;
import ai.moeru.airicraft.agent.llm.LlmFailureType;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleLlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.PlannerChatMessage;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerConversationDebugKind;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerIntent;
import ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor;
import ai.moeru.airicraft.agent.llm.PlannerLifecycleListener;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerResponse;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerToolExecutionObserver;
import ai.moeru.airicraft.agent.llm.PlannerToolNarrationSink;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.agent.llm.PlannerVisionMode;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftingOpportunity;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionSpec;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.StepExecutionResult;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskOwnership;
import ai.moeru.airicraft.agent.tasks.TaskProgressSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.StepExecutionStatus;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskStep;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialogueRuntimeTest {
	@org.junit.jupiter.params.ParameterizedTest
	@org.junit.jupiter.params.provider.ValueSource(strings = {"run_policy", "mine_blocks"})
	void routineProgressDoesNotWakeAcceptedWorkButDamageStillDoes(String label) {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer events = new SemanticEventBuffer(32);
		runtime.observeAcceptedWork(new ai.moeru.airicraft.agent.work.WorkSnapshot(
			ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.OPERATION, "policy"),
			"", ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING, label, "POLICY", true, 10, java.util.Map.of()));
		for (var type : List.of(PlannerTriggerType.CRAFT, PlannerTriggerType.PICKUP, PlannerTriggerType.IDLE_THINK)) {
			runtime.onPlannerTrigger(PlannerTrigger.autonomous(type, "self", "progress", 11, 11, "progress"),
				SessionSnapshot.initial(), null, Optional.empty(), null, null, events);
			assertFalse(runtime.plannerDebugSnapshot().inFlight(), type.name());
		}
		runtime.onPlannerTrigger(PlannerTrigger.autonomous(PlannerTriggerType.DAMAGE, "self", "hurt", 12, 12, "damage"),
			SessionSnapshot.initial(), null, Optional.empty(), null, null, events);
		assertTrue(runtime.plannerDebugSnapshot().inFlight());
		runtime.shutdown();
	}

	@Test
	void continuationRunsDuringPolicyAndDispatchesOnceWithoutNormalPlannerCall() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer events = new SemanticEventBuffer(64);
		var response = new CompletableFuture<PlannerResponse>();
		var requests = new java.util.ArrayList<LlmConversation>();
		var executed = new java.util.ArrayList<PlannerToolCall>();
		runtime.configurePolicyContinuation(new ai.moeru.airicraft.agent.llm.PolicyContinuationPlanner(c -> {
			requests.add(c); return response;
		}, () -> {}), b -> true, call -> {
			executed.add(call); return CompletableFuture.completedFuture("Tool result for run_policy: accepted");
		});
		runtime.configureDecisionContext(() -> new ai.moeru.airicraft.agent.llm.PlannerDecisionContext(
			"world", 10, 10, "controller", executed.isEmpty() ? "idle" : "policy",
			java.util.Map.of("objective", "craft", "inventory", java.util.Map.of(), "vitals", java.util.Map.of("health", 20)), events.query(null)));
		var handle = ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.OPERATION, "parent");
		var parent = new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, "",
			ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING, "run_policy", "POLICY", true, 10, java.util.Map.of());
		runtime.observeAcceptedWork(parent);
		runtime.poll(10, events);
		assertEquals(1, requests.size());
		response.complete(new PlannerResponse("", new PlannerToolCall("next", "run_policy", com.google.gson.JsonParser.parseString("""
			{"source":"function* main(p) { return {crafted:true}; }", "input":{},
			 "guard":{"parentResult":{"gathered":true},"inventoryMin":{},"blocks":[]}}
			""").getAsJsonObject(), null, null), null));
		runtime.poll(11, events);
		assertTrue(executed.isEmpty());
		assertFalse(runtime.plannerConversationDebugSnapshot().messages().stream().anyMatch(m -> m.text().contains("crafted:true")));
		runtime.observeWork(List.of(new ai.moeru.airicraft.agent.work.WorkSnapshot(handle, "",
			ai.moeru.airicraft.agent.work.WorkSnapshot.State.SUCCEEDED, "run_policy", "FINISHED", false, 12,
			java.util.Map.of("result", java.util.Map.of("gathered", true)))));
		runtime.queueTaskWakeup(null, 12, events.append(12, "work.changed", java.util.Map.of("workId", "parent")).seqNo());
		runtime.poll(12, events);
		runtime.poll(13, events);
		assertEquals(1, executed.size());
		assertEquals(0, backend.conversationCount());
		assertTrue(events.containsType("policy.continuation.accepted"));
		runtime.shutdown();
	}

	@Test
	void childCompletionDoesNotWakeNormalPlannerWhilePolicyOwnsWork() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer events = new SemanticEventBuffer(32);
		var policy = new ai.moeru.airicraft.agent.work.WorkSnapshot(
			ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.OPERATION, "policy"),
			"", ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING, "run_policy", "POLICY", true, 10, java.util.Map.of());
		runtime.observeAcceptedWork(policy);
		runtime.queueTaskWakeup(null, 11, events.append(11, "work.changed", java.util.Map.of("workId", "child")).seqNo());
		runtime.poll(12, events);
		assertFalse(runtime.plannerDebugSnapshot().inFlight());
		runtime.shutdown();
	}

	@Test
	void slowMiningNoticeWakesPlannerDuringPolicy() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer events = new SemanticEventBuffer(32);
		var policy = new ai.moeru.airicraft.agent.work.WorkSnapshot(
			ai.moeru.airicraft.agent.work.WorkHandle.of(ai.moeru.airicraft.agent.work.WorkHandle.Kind.OPERATION, "policy"),
			"", ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING, "run_policy", "POLICY", true, 10, java.util.Map.of());
		runtime.observeAcceptedWork(policy);
		runtime.queueTaskWakeup(null, 11, events.append(11, "work.changed", java.util.Map.of("workId", "child")).seqNo());
		runtime.queueTaskAttention(12, events.append(12, "task.notice", java.util.Map.of("reason", "slow_mining", "message", "Slow mining stone with furnace")).seqNo());
		runtime.poll(12, events);
		assertTrue(runtime.plannerDebugSnapshot().inFlight());
		runtime.shutdown();
	}

	@Test
	void attentionWaitsForBusyPlannerWithoutReplacingItsRequest() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer events = new SemanticEventBuffer(32);
		var response = backend.enqueueResponse();
		runtime.onPlayerChat("Alice", "Inspect the cave", 10L,
			SessionSnapshot.initial(), "Alice", Optional.empty(), events);
		backend.awaitConversationCount(1);
		runtime.queueTaskAttention(11, events.append(11, "task.notice",
			java.util.Map.of("reason", "slow_mining", "message", "Slow mining stone")).seqNo());
		runtime.poll(12, events);
		assertEquals(1, backend.conversationCount());
		response.complete(new PlannerResponse("Inspecting.", new PlannerIntent("none", null, null)));
		awaitResponse(runtime, events, Duration.ofSeconds(1));
		for (int tick = 13; tick < 20; tick++) runtime.poll(tick, events);
		backend.awaitConversationCount(2);
		assertEquals(2, backend.conversationCount());
		runtime.shutdown();
	}

	@Test
	void plainReplyDoesNotContinueUnfinishedWorkWithoutAnotherTrigger() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer events = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse("I will inspect the cave next.", new PlannerIntent("none", null, null)));
		runtime.onPlayerChat("Alice", "Craft torches then scout a cave", 10L,
			SessionSnapshot.initial(), "Alice", Optional.empty(), events);
		awaitResponse(runtime, events, Duration.ofSeconds(1));
		for (long tick = 20; tick < 220; tick++) runtime.poll(tick, events);
		assertFalse(runtime.plannerDebugSnapshot().inFlight());
		assertEquals(1, backend.conversationCount());
		runtime.shutdown();
	}

	@Test
	void activePlannerGoalContinuesAfterPlainReplyAndStopsWhenFinished(@org.junit.jupiter.api.io.TempDir java.nio.file.Path world) throws Exception {
		var goal = new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore(() -> world);

		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY, goal);
		runtime.startEvaluationGoal("Craft torches then scout a cave");
		var active = goal.snapshot();
		assertTrue(java.nio.file.Files.exists(world.resolve("airicraft/planner-goal.json")));
		SemanticEventBuffer events = new SemanticEventBuffer(32);
		var session = new SessionSnapshot(ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST,
			true, true, "minecraft:overworld", true, 25565, 10);
		backend.injectMockResponse(new PlannerResponse("I will inspect the cave next.", new PlannerIntent("none", null, null)));
		runtime.continuePlannerGoal(10L, true, session, "Alice", Optional.empty(), null, null, events);
		awaitResponse(runtime, events, Duration.ofSeconds(1));
		assertEquals(1, backend.conversationCount());
		// An executor still owns the action: no self-polling model loop.
		runtime.continuePlannerGoal(100, false, session, "Alice", Optional.empty(), null, null, events);
		assertFalse(runtime.plannerDebugSnapshot().inFlight());
		// No new chat, idle timer, or external event is supplied.
		backend.injectMockResponse(new PlannerResponse("The next step is underway.", new PlannerIntent("none", null, null)));
		runtime.continuePlannerGoal(121, true, session, "Alice", Optional.empty(), null, null, events);
		awaitResponse(runtime, events, Duration.ofSeconds(1));
		assertEquals(2, backend.conversationCount());
		assertEquals(2, runtime.gameplayDecisionCount());
		assertTrue(backend.conversation(1).messages().stream().anyMatch(m -> m.content() != null && m.content().contains("GOAL CONTINUATION")));
		goal.finish(active.id(), ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore.Status.GIVEN_UP, "Cave scouting needs unavailable evidence");
		assertFalse(runtime.continuePlannerGoal(200, true, session, "Alice", Optional.empty(), null, null, events));
		assertFalse(runtime.plannerDebugSnapshot().inFlight());
		assertEquals(2, backend.conversationCount());
		assertEquals(2, runtime.gameplayDecisionCount());
		runtime.shutdown();
	}

	@Test
	void evaluationGoalWaitsForWorkWithoutRepeatedRequests(@org.junit.jupiter.api.io.TempDir java.nio.file.Path world) throws Exception {
		var goal = new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore(() -> world);
		var backend = new BlockingLlmBackend();
		var runtime = newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY, goal);
		try {
			runtime.startEvaluationGoal("Obtain iron pickaxe");
			var events = new SemanticEventBuffer(32);
			var session = new SessionSnapshot(ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST,
				true, true, "minecraft:overworld", true, 25565, 1);
			var work = new ai.moeru.airicraft.agent.work.WorkSnapshot(new ai.moeru.airicraft.agent.work.WorkHandle("JOB:iron"), "",
				ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING, "Mine iron", "BREAK", true, 1, java.util.Map.of());
			runtime.observeAcceptedWork(work);
			for (int tick = 1; tick < 500; tick++) {
				runtime.observeWork(List.of(new ai.moeru.airicraft.agent.work.WorkSnapshot(work.handle(), "",
					ai.moeru.airicraft.agent.work.WorkSnapshot.State.RUNNING, "Mine iron", tick % 2 == 0 ? "NAVIGATE" : "BREAK", true, tick, java.util.Map.of())));
				runtime.continuePlannerGoal(tick, true, session, null, Optional.empty(), null, null, events);
			}
			assertEquals(0, backend.conversationCount());
			assertEquals(0, runtime.gameplayDecisionCount());
			runtime.observeWork(List.of(new ai.moeru.airicraft.agent.work.WorkSnapshot(work.handle(), "",
				ai.moeru.airicraft.agent.work.WorkSnapshot.State.SUCCEEDED, "Mine iron", "DONE", true, 500, java.util.Map.of())));
			backend.injectMockResponse(new PlannerResponse("Time to smelt.", new PlannerIntent("none", null, null)));
			runtime.continuePlannerGoal(501, true, session, null, Optional.empty(), null, null, events);
			awaitResponse(runtime, events, Duration.ofSeconds(1));
			assertEquals(1, backend.conversationCount());
			assertTrue(goal.active());
		} finally { runtime.shutdown(); }
	}

	@Test
	void blockedGoalOnlyReassessesOnItsNamedEvents(@org.junit.jupiter.api.io.TempDir java.nio.file.Path world) throws Exception {
		var goal = new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore(() -> world);
		var objective = goal.set("Build chest");
		goal.block(objective.id(),"no wood","JOB:one exhausted search","wood becomes available",java.util.List.of("interaction.container_take"));
		var backend = new BlockingLlmBackend();
		var runtime = newDialogueRuntime(backend,CurrentViewVisionTool.disabled(),PlannerVisionMode.EXTERNAL_SUMMARY,goal);
		var events = new SemanticEventBuffer(32);
		var session = new SessionSnapshot(ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST,true,true,"minecraft:overworld",true,25565,1);
		try {
			runtime.poll(1,events,session,Optional.empty(),null,null);
			events.append(2,"work.changed",java.util.Map.of("workId","unrelated"));
			for (int tick=2;tick<50;tick++) {
				runtime.continuePlannerGoal(tick,true,session,null,Optional.empty(),null,null,events);
				runtime.poll(tick,events,session,Optional.empty(),null,null);
			}
			assertEquals(0,backend.conversationCount());
			backend.injectMockResponse(new PlannerResponse("I will inspect what was retrieved.",new PlannerIntent("none",null,null)));
			events.append(51,"interaction.container_take",java.util.Map.of("itemId","minecraft:spruce_log"));
			runtime.poll(51,events,session,Optional.empty(),null,null);
			awaitResponse(runtime,events,Duration.ofSeconds(1));
			assertEquals(1,backend.conversationCount());
			assertTrue(goal.blocked(),"Reassessment does not silently resume the objective");
			for (int tick=52;tick<80;tick++) runtime.continuePlannerGoal(tick,true,session,null,Optional.empty(),null,null,events);
			assertEquals(1,backend.conversationCount());
		} finally { runtime.shutdown(); }
	}

	@Test
	void blockedObjectiveStillAllowsOneReflexSupervisionDecision(@org.junit.jupiter.api.io.TempDir java.nio.file.Path world) throws Exception {
		var goal = new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore(() -> world);
		var objective = goal.set("Build chest");
		goal.block(objective.id(), "no wood", "search exhausted", "wood available", java.util.List.of("interaction.container_take"));
		var backend = new BlockingLlmBackend();
		var runtime = newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY, goal);
		var events = new SemanticEventBuffer(32);
		var session = new SessionSnapshot(ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, 1);
		try {
			runtime.poll(1, events, session, Optional.empty(), null, null);
			runtime.updateSafetyContext(1, "hold-defend", true);
			backend.injectMockResponse(new PlannerResponse("I will leave defense active.", new PlannerIntent("none", null, null)));
			var event = events.append(2, "reflex.started", java.util.Map.of("holdId", "hold-defend"));
			runtime.queueTaskWakeup(null, 2, event.seqNo());
			runtime.poll(2, events, session, Optional.empty(), null, null);
			awaitResponse(runtime, events, Duration.ofSeconds(1));
			assertEquals(1, backend.conversationCount());
			assertTrue(goal.blocked(), "Supervision does not resume the objective");
			for (int tick = 3; tick < 80; tick++) {
				runtime.continuePlannerGoal(tick, false, session, null, Optional.empty(), null, null, events);
				runtime.poll(tick, events, session, Optional.empty(), null, null);
			}
			assertEquals(1, backend.conversationCount());
		} finally { runtime.shutdown(); }
	}

	@Test
	void unchangedSafetyHoldDoesNotRepeatPlaintextDecisions(@org.junit.jupiter.api.io.TempDir java.nio.file.Path world) throws Exception {
		var goal = new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore(() -> world);
		goal.set("Gather logs and finish shelter");
		var backend = new BlockingLlmBackend();
		var runtime = newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY, goal);
		try {
			var events = new SemanticEventBuffer(32);
			var session = new SessionSnapshot(ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST,
				true, true, "minecraft:overworld", true, 25565, 10);
			runtime.updateSafetyContext(3, "hold-patrol", false);
			backend.injectMockResponse(new PlannerResponse("I will deal with the closer pillager.", new PlannerIntent("none", null, null)));
			runtime.onPlayerChat("Alice", "Combat approach stalled; choose a tactic", 10, session, "Alice", Optional.empty(), events);
			awaitResponse(runtime, events, Duration.ofSeconds(1));
			runtime.continuePlannerGoal(100, false, session, "Alice", Optional.empty(), null, null, events);
			assertFalse(runtime.plannerDebugSnapshot().inFlight(), "An unchanged hold must not generate a plaintext continuation loop");
			assertEquals(1, backend.conversationCount());
			assertFalse(runtime.delegationWorkIdle(), "Paused work is still unfinished for delegation completion");
			runtime.updateSafetyContext(4, "hold-new-danger", true);
			runtime.continuePlannerGoal(200, false, session, "Alice", Optional.empty(), null, null, events);
			assertFalse(runtime.plannerDebugSnapshot().inFlight());
			assertEquals(1, backend.conversationCount());
		}
		finally { runtime.shutdown(); }
	}

	@Test
	void plannerGoalDoesNotRunWhenDisabledOrExternallyDriven(@org.junit.jupiter.api.io.TempDir java.nio.file.Path world) throws Exception {
		var goal = new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore(() -> world);
		goal.set("Scout a cave");
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY, goal);
		var session = new SessionSnapshot(ai.moeru.airicraft.agent.session.SessionMode.SINGLEPLAYER_LAN_HOST,
			true, true, "minecraft:overworld", true, 25565, 10);
		var events = new SemanticEventBuffer(32);
		runtime.setPlannerEnabled(false);
		runtime.continuePlannerGoal(100, true, session, null, Optional.empty(), null, null, events);
		assertFalse(runtime.plannerDebugSnapshot().inFlight());
		runtime.setPlannerEnabled(true);
		runtime.enableExternalDriver();
		runtime.continuePlannerGoal(200, true, session, null, Optional.empty(), null, null, events);
		assertFalse(runtime.plannerDebugSnapshot().inFlight());
		assertEquals(0, backend.conversationCount());
		runtime.shutdown();
	}

	@Test
	void externalDriverSuppressesPlannerSubmission() throws Exception {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		runtime.enableExternalDriver();

		runtime.onPlayerChat("Alice", "@agent follow me", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		Thread.sleep(50L);

		assertTrue(runtime.externalDriverActive());
		assertEquals(0, backend.conversationCount());
		assertFalse(runtime.plannerDebugSnapshot().inFlight());
		assertEquals(null, runtime.poll(11L, eventBuffer));
		runtime.shutdown();
	}

	@Test
	void mockPlannerResponseProducesDialogueResponse() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Sure, I'll follow you!",
			new PlannerIntent("set_goal", GoalType.FOLLOW_PLAYER, "Alice")
		));

		runtime.onPlayerChat("Alice", "@agent follow me", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Sure, I'll follow you!", response.text());
		assertEquals(DialogueIntentType.SET_GOAL, response.intent().type());
		assertFalse(runtime.isDegraded());
		runtime.shutdown();
	}

	@Test
	void plannerChatMessagesAreQueuedWithDelays() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			List.of(
				new PlannerChatMessage("I found the cave.", 0),
				new PlannerChatMessage("I will head back now.", 30)
			),
			new PlannerIntent("reply_only", null, null),
			null
		));

		runtime.onPlayerChat("Alice", "@agent report", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("I will head back now.", response.text());
		assertEquals("I found the cave.", runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow().response().text());
		PendingDialogueReply firstReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();
		assertTrue(runtime.recordSentReply(firstReply, true));
		assertTrue(runtime.pendingReplyReady(0L).isEmpty());
		assertEquals("I will head back now.", runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow().response().text());
		runtime.shutdown();
	}

	@Test
	void successfulSendCommitsPlannerHistoryOnce() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse("Visible reply.", new PlannerIntent("reply_only", null, null)));

		runtime.onPlayerChat("Alice", "@agent reply", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		PendingDialogueReply pendingReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();

		assertFalse(hasAgentTurn(runtime, "Visible reply."));
		assertTrue(runtime.recordSentReply(pendingReply, true));
		assertTrue(hasAgentTurn(runtime, "Visible reply."));
		assertFalse(runtime.hasPendingReply());
		runtime.shutdown();
	}

	@Test
	void successfulSendCommitsReplyToTheNextPlannerConversation() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse("Visible reply.", new PlannerIntent("reply_only", null, null)));

		runtime.onPlayerChat("Alice", "@agent reply", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		recordPendingReply(runtime);

		backend.injectMockResponse(new PlannerResponse("Follow-up reply.", new PlannerIntent("reply_only", null, null)));
		runtime.onPlayerChat("Alice", "@agent follow up", 11L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		backend.awaitConversationCount(2);

		assertTrue(backend.conversation(1).messages().stream().anyMatch(message ->
			"assistant".equals(message.role()) && message.content().contains("Visible reply.")
		));
		runtime.shutdown();
	}

	@Test
	void failedSendDoesNotCommitPlannerHistoryOrRemoveReply() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse("Unsent reply.", new PlannerIntent("reply_only", null, null)));

		runtime.onPlayerChat("Alice", "@agent reply", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		PendingDialogueReply pendingReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();

		assertFalse(runtime.recordSentReply(pendingReply, false));
		assertFalse(hasAgentTurn(runtime, "Unsent reply."));
		assertTrue(runtime.hasPendingReply());
		runtime.shutdown();
	}

	@Test
	void newerResponsePreservesUnsentRepliesInOrder() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse("Replaced reply.", new PlannerIntent("reply_only", null, null)));
		backend.injectMockResponse(new PlannerResponse("Current reply.", new PlannerIntent("reply_only", null, null)));

		runtime.onPlayerChat("Alice", "@agent first", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		PendingDialogueReply replacedReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();

		runtime.onPlayerChat("Alice", "@agent second", 11L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		PendingDialogueReply currentReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();

		assertEquals(replacedReply.id(), currentReply.id());
		assertTrue(runtime.recordSentReply(replacedReply, true));
		assertTrue(hasAgentTurn(runtime, "Replaced reply."));
		currentReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();
		assertEquals("Current reply.", currentReply.response().text());
		assertTrue(runtime.recordSentReply(currentReply, true));
		assertTrue(hasAgentTurn(runtime, "Current reply."));
		runtime.shutdown();
	}

	@Test
	void resetDropsUnsentReplyWithoutCommittingPlannerHistory() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse("Cleared reply.", new PlannerIntent("reply_only", null, null)));

		runtime.onPlayerChat("Alice", "@agent reply", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		PendingDialogueReply unsentReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();

		assertTrue(runtime.handleResetCommand("Alice", "@agent reset", 11L, eventBuffer));
		assertFalse(runtime.pendingReplyReady(Long.MAX_VALUE).stream().anyMatch(reply -> "Cleared reply.".equals(reply.response().text())));
		assertFalse(runtime.recordSentReply(unsentReply, true));
		assertFalse(hasAgentTurn(runtime, "Cleared reply."));
		runtime.shutdown();
	}

	@Test
	void duplicateSendCallbackCommitsReplyExactlyOnce() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse("One visible reply.", new PlannerIntent("reply_only", null, null)));

		runtime.onPlayerChat("Alice", "@agent reply", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		PendingDialogueReply pendingReply = runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow();

		assertTrue(runtime.recordSentReply(pendingReply, true));
		assertFalse(runtime.recordSentReply(pendingReply, true));
		assertEquals(1, agentTurnCount(runtime, "One visible reply."));
		runtime.shutdown();
	}

	@Test
	void structuredPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Heading there.",
			new PlannerIntent(
				"set_goal",
				GoalType.NAVIGATE_TO,
				null,
				new GoalPosition(12, 64, -8, true),
				new GoalMineSpec(List.of("minecraft:oak_log"), 16)
			)
		));

		runtime.onPlayerChat("Alice", "@agent head there", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Heading there.", response.text());
		assertEquals(DialogueIntentType.SET_GOAL, response.intent().type());
		assertEquals(GoalType.NAVIGATE_TO, response.intent().goalType());
		assertEquals(new GoalPosition(12, 64, -8, true), response.intent().position());
		assertEquals(new GoalMineSpec(List.of("minecraft:oak_log"), 16), response.intent().mineSpec());
		runtime.shutdown();
	}

	@Test
	void submitTaskPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"On it.",
			new PlannerIntent(
				"submit_task",
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16)
			)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("On it.", response.text());
		assertEquals(DialogueIntentType.SUBMIT_TASK, response.intent().type());
		assertEquals(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), response.intent().taskSpec());
		runtime.shutdown();
	}

	@Test
	void jobUpdatePlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		ActiveJobProposal proposal = ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16));
		backend.injectMockResponse(new PlannerResponse(
			"On it.",
			new PlannerIntent("job_update", proposal)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("On it.", response.text());
		assertEquals(DialogueIntentType.JOB_UPDATE, response.intent().type());
		assertEquals(proposal, response.intent().activeJob());
		runtime.shutdown();
	}

	@Test
	void cancelTaskPlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Stopping the task.",
			new PlannerIntent(
				"cancel_task",
				null,
				null,
				null,
				null,
				null
			)
		));

		runtime.onPlayerChat("Alice", "@agent stop the task", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Stopping the task.", response.text());
		assertEquals(DialogueIntentType.CANCEL_TASK, response.intent().type());
		assertEquals(null, response.intent().taskSpec());
		runtime.shutdown();
	}

	@Test
	void missionUpdatePlannerIntentSurvivesDialogueRuntimeMapping() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		TaskLedger ledger = new TaskLedger(
			"mission-wood-1",
			MissionType.COLLECT_RESOURCE,
			"Collect 4 wood logs",
			List.of(
				new LedgerStep(
					"collect_logs",
					LedgerStepKind.COLLECT_RESOURCE,
					new LedgerStepPayload(
						new CollectResourceStepArgs(TaskResourceKind.WOOD_LOGS, 4, "KEEP"),
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null
					),
					List.of(),
					LedgerStepStatus.ACTIVE,
					List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
					2,
					"Collect logs"
				),
				new LedgerStep(
					"finish",
					LedgerStepKind.FINISH,
					new LedgerStepPayload(
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						null,
						new FinishStepArgs("Mission complete")
					),
					List.of("collect_logs"),
					LedgerStepStatus.PENDING,
					List.of(new EvidenceRequirement(EvidenceKind.STEP_COMPLETED, null, null, "collect_logs", null)),
					0,
					"Finish"
				)
			),
			"collect_logs",
			List.of(new EvidenceRequirement(EvidenceKind.INVENTORY_DELTA_AT_LEAST, TaskResourceKind.WOOD_LOGS, 4, null, null)),
			"user_request",
			"Keep it simple"
		);
		backend.injectMockResponse(new PlannerResponse(
			"Starting the mission.",
			new PlannerIntent(
				"mission_update",
				null,
				null,
				null,
				null,
				null,
				ledger
			)
		));

		runtime.onPlayerChat("Alice", "@agent get wood", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Starting the mission.", response.text());
		assertEquals(DialogueIntentType.MISSION_UPDATE, response.intent().type());
		assertEquals(ledger, response.intent().taskLedger());
		runtime.shutdown();
	}

	@Test
	void threeTimeoutsEnterDegradedAndResetCommandClearsIt() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		for (long tick = 1L; tick <= 3L; tick++) {
			backend.injectTimeout();
			runtime.onPlayerChat("Alice", "@agent follow me", tick, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
			awaitFailureProcessed(runtime, eventBuffer, tick, Duration.ofSeconds(1));
		}

		assertTrue(runtime.isDegraded());
		assertTrue(eventBuffer.containsType("planner.degraded_entered"));
		assertTrue(runtime.lastResponse().orElseThrow().text().contains("@agent reset"));

		assertTrue(runtime.handleResetCommand("Alice", "@agent reset", 50L, eventBuffer));
		assertFalse(runtime.isDegraded());
		assertTrue(eventBuffer.containsType("planner.degraded_cleared"));
		assertTrue(eventBuffer.containsType("planner.reset_requested"));
		assertEquals("Planner state reset.", runtime.lastResponse().orElseThrow().text());
		runtime.shutdown();
	}

	@Test
	void degradedDirectChatEmitsBlockedEventAndVisibleResetReminder() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		for (long tick = 1L; tick <= 3L; tick++) {
			backend.injectTimeout();
			runtime.onPlayerChat("Alice", "@agent follow me", tick, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
			awaitFailureProcessed(runtime, eventBuffer, tick, Duration.ofSeconds(1));
		}

		long sinceSeqNo = eventBuffer.latestSeqNo();
		while (runtime.hasPendingReply()) recordPendingReply(runtime);
		runtime.onPlayerChat("Alice", "@agent are you alive?", 50L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);

		assertTrue(eventBuffer.containsTypeSince(sinceSeqNo, "planner.degraded_blocked"));
		assertTrue(runtime.lastResponse().orElseThrow().text().contains("@agent reset"));
		assertEquals("planner_degraded_visible_reply", runtime.pendingReplyReason());
		runtime.shutdown();
	}

	@Test
	void plannerOffSilentlyDiscardsDirectChatEvenWhileDegraded() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		for (long tick = 1L; tick <= 3L; tick++) {
			backend.injectTimeout();
			runtime.onPlayerChat("Alice", "@agent follow me", tick, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
			awaitFailureProcessed(runtime, eventBuffer, tick, Duration.ofSeconds(1));
		}
		recordPendingReply(runtime);
		long sinceSeqNo = eventBuffer.latestSeqNo();

		runtime.setPlannerEnabled(false);
		runtime.onPlayerChat("Alice", "@agent are you alive?", 50L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);

		assertTrue(runtime.isDegraded());
		assertFalse(runtime.hasPendingReply());
		assertFalse(eventBuffer.containsTypeSince(sinceSeqNo, "planner.degraded_blocked"));
		runtime.shutdown();
	}

	@Test
	void plannerOffDoesNotQueueInjectedResponsesOrTimeoutsForReenable() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		runtime.setPlannerEnabled(false);

		runtime.injectMockResponse(new PlannerResponse("stale reply", new PlannerIntent("reply_only", null, null)));
		runtime.injectTimeout();
		runtime.poll(1L, eventBuffer);

		runtime.setPlannerEnabled(true);
		runtime.injectMockResponse(new PlannerResponse("fresh reply", new PlannerIntent("reply_only", null, null)));
		runtime.onPlayerChat("Alice", "@agent hello", 2L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("fresh reply", response.text());
		assertFalse(runtime.isDegraded());
		runtime.shutdown();
	}

	@Test
	void timeoutEmitsFreshVisibleReplyForDirectChat() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Still working on it.",
			new PlannerIntent("reply_only", null, null)
		));

		runtime.onPlayerChat("Alice", "@agent status", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		assertEquals("Still working on it.", response.text());
		recordPendingReply(runtime);

		backend.injectTimeout();
		runtime.onPlayerChat("Alice", "@agent status?", 11L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		awaitFailureProcessed(runtime, eventBuffer, 1L, Duration.ofSeconds(1));

		assertFalse("failure_reused_last_response".equals(runtime.pendingReplyReason()));
		assertFalse(runtime.lastResponse().filter(last -> "Still working on it.".equals(last.text())).isPresent());
		runtime.shutdown();
	}

	@Test
	void timeoutDuringBackgroundTaskUpdateStaysSilent() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		backend.injectTimeout();
		runtime.onContextTrigger(
			PlannerTriggerType.SYSTEM,
			"server",
			"Background update",
			12L,
			SessionSnapshot.initial(),
			null,
			Optional.empty(),
			eventBuffer
		);
		awaitFailureProcessed(runtime, eventBuffer, 1L, Duration.ofSeconds(1));

		assertFalse(runtime.hasPendingReply());
		assertTrue(runtime.lastResponse().isEmpty());
		runtime.shutdown();
	}

	@Test
	void internalTaskUpdateDuringInFlightPlannerRequestIsSubmittedAfterResult() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		CompletableFuture<PlannerResponse> firstResponse = backend.enqueueResponse();
		CompletableFuture<PlannerResponse> taskUpdateResponse = backend.enqueueResponse();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:dirt"), 1),
			10L,
			"planner_tool"
		);

		runtime.onPlayerChat(
			"Alice",
			"@agent dig down",
			10L,
			SessionSnapshot.initial(),
			"Alice",
			Optional.of(goal),
			eventBuffer
		);
		backend.awaitConversationCount(1);
		runtime.onInternalTaskUpdate(
			"TASK UPDATE: state=CANCELLED taskId=mine-task goalType=MINE_BLOCKS message=Task cancelled terminationCause=BARITONE_CANCELLED",
			11L,
			SessionSnapshot.initial(),
			Optional.of(goal),
			TaskSnapshot.idle(),
			MissionExecutionSnapshot.idle(),
			eventBuffer
		);

		assertEquals(1, backend.conversationCount());

		firstResponse.complete(new PlannerResponse("Starting.", new PlannerIntent("reply_only", null, null)));
		assertEquals("Starting.", awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1)).text());
		backend.awaitConversationCount(2);
		assertTrue(backend.conversation(1).messages().stream().anyMatch(message ->
			message.content().contains("TASK UPDATE: state=CANCELLED")
				&& message.content().contains("goalType=MINE_BLOCKS")
		));

		taskUpdateResponse.complete(new PlannerResponse("The mining task cancelled.", new PlannerIntent("reply_only", null, null)));
		assertEquals("The mining task cancelled.", awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1)).text());
		runtime.shutdown();
	}

	@Test
	void queuedIdleThinkIsInvalidatedWhenActionStartsDuringInFlightPlannerRequest() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		CompletableFuture<PlannerResponse> firstResponse = backend.enqueueResponse();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		runtime.onPlayerChat(
			"Alice",
			"@agent make charcoal",
			10L,
			SessionSnapshot.initial(),
			"Alice",
			Optional.empty(),
			eventBuffer
		);
		backend.awaitConversationCount(1);
		runtime.onPlannerTrigger(
			PlannerTrigger.pending(PlannerTriggerType.IDLE_THINK, "self", "IDLE THINK: You currently have no active task.", 11L, 1_100L),
			SessionSnapshot.initial(),
			null,
			Optional.empty(),
			TaskSnapshot.idle(),
			MissionExecutionSnapshot.idle(),
			eventBuffer
		);

		runtime.invalidateIdleThinkTriggers();
		firstResponse.complete(new PlannerResponse("Planning charcoal.", new PlannerIntent("reply_only", null, null)));

		assertEquals("Planning charcoal.", awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1)).text());
		backend.assertConversationCountRemains(1, Duration.ofMillis(100));
		runtime.shutdown();
	}

	@Test
	void directPlayerGuidanceSupersedesQueuedInternalTaskUpdate() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		CompletableFuture<PlannerResponse> firstResponse = backend.enqueueResponse();
		CompletableFuture<PlannerResponse> latestResponse = backend.enqueueResponse();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);

		runtime.onPlannerTrigger(
			PlannerTrigger.pending(PlannerTriggerType.CHAT, "Alice", "@agent gather wood", 10L, 1000L),
			SessionSnapshot.initial(),
			"Alice",
			Optional.empty(),
			TaskSnapshot.idle(),
			MissionExecutionSnapshot.idle(),
			eventBuffer
		);
		backend.awaitConversationCount(1);
		runtime.onInternalTaskUpdate(
			"TASK UPDATE: state=COMPLETED missionId=old-mission",
			11L,
			SessionSnapshot.initial(),
			Optional.empty(),
			activeTask("old-mission", MissionType.COLLECT_RESOURCE, "Gather wood", "collect", LedgerStepKind.COLLECT_RESOURCE),
			MissionExecutionSnapshot.idle(),
			eventBuffer
		);
		runtime.onPlannerTrigger(
			PlannerTrigger.pending(PlannerTriggerType.CHAT, "Alice", "@agent stop, come back", 12L, 1200L),
			SessionSnapshot.initial(),
			"Alice",
			Optional.empty(),
			TaskSnapshot.idle(),
			MissionExecutionSnapshot.idle(),
			eventBuffer
		);

		assertTrue(eventBuffer.containsType("planner.internal_task_update_superseded"));
		latestResponse.complete(new PlannerResponse("Coming back.", new PlannerIntent("reply_only", null, null)));
		firstResponse.complete(new PlannerResponse("Gathering wood.", new PlannerIntent("reply_only", null, null)));

		assertEquals("Coming back.", awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1)).text());
		backend.awaitConversationCount(2);
		String latestPrompt = backend.conversation(1).messages().stream()
			.map(message -> message.content() == null ? "" : message.content())
			.reduce("", (left, right) -> left + "\n" + right);
		assertTrue(latestPrompt.contains("@agent stop, come back"));
		assertFalse(latestPrompt.contains("TASK UPDATE: state=COMPLETED missionId=old-mission"));
		runtime.shutdown();
	}

	@Test
	void queuedInternalTaskUpdateIsDroppedWhenMissionChangedBeforeReplay() {
		BlockingLlmBackend backend = new BlockingLlmBackend();
		CompletableFuture<PlannerResponse> firstResponse = backend.enqueueResponse();
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		TaskSnapshot staleTask = activeTask("stale-mission", MissionType.CRAFT_ITEM, "Stale craft planks", "stale-step", LedgerStepKind.CRAFT_RECIPE);
		MissionExecutionSnapshot staleExecution = missionExecution(staleTask.mission(), StepExecutionStatus.COMPLETED);
		TaskSnapshot currentTask = activeTask("current-mission", MissionType.COLLECT_RESOURCE, "Fresh mine stone", "current-step", LedgerStepKind.MINE_BLOCKS);
		MissionExecutionSnapshot currentExecution = missionExecution(currentTask.mission(), StepExecutionStatus.RUNNING);

		runtime.onPlayerChat(
			"Alice",
			"@agent continue",
			10L,
			SessionSnapshot.initial(),
			"Alice",
			Optional.empty(),
			eventBuffer
		);
		backend.awaitConversationCount(1);
		runtime.onInternalTaskUpdate(
			"TASK UPDATE: state=COMPLETED activeStepKind=CRAFT_RECIPE",
			11L,
			SessionSnapshot.initial(),
			Optional.empty(),
			staleTask,
			staleExecution,
			eventBuffer
		);

		firstResponse.complete(new PlannerResponse("Working.", new PlannerIntent("reply_only", null, null)));
		assertEquals(
			"Working.",
			awaitResponse(
				runtime,
				eventBuffer,
				Duration.ofSeconds(1),
				SessionSnapshot.initial(),
				Optional.empty(),
				currentTask,
				currentExecution
			).text()
		);
		backend.assertConversationCountRemains(1, Duration.ofMillis(100));
		assertTrue(eventBuffer.query(null).events().stream().anyMatch(event ->
			"planner.internal_task_update_superseded".equals(event.type())
				&& "mission_changed".equals(event.payload().get("reason"))
				&& "stale-mission".equals(event.payload().get("updateMissionId"))
				&& "current-mission".equals(event.payload().get("currentMissionId"))
		));
		runtime.shutdown();
	}

	@Test
	void parseFailureRetryCanRecoverWithoutVisibleFailure() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		JsonObject navigateArgs = new JsonObject();
		navigateArgs.addProperty("x", 1);
		navigateArgs.addProperty("y", 64);
		navigateArgs.addProperty("z", 2);
		navigateArgs.addProperty("exactY", false);
		JsonObject craftArgs = new JsonObject();
		craftArgs.addProperty("recipeId", "minecraft:oak_planks");
		craftArgs.addProperty("times", 1);
		backend.injectMockResponse(PlannerResponse.toolCalls(List.of(
			new PlannerToolCall("call_nav", PlannerToolCatalog.NAVIGATE_TO, navigateArgs, null, null),
			new PlannerToolCall("call_craft", PlannerToolCatalog.CRAFT_RECIPE, craftArgs, null, null)
		), null));
		backend.injectMockResponse(new PlannerResponse(
			"I will do one step at a time.",
			new PlannerIntent("reply_only", null, null)
		));

		runtime.onPlayerChat("Alice", "@agent move and craft", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("I will do one step at a time.", response.text());
		assertEquals(0, runtime.consecutiveFailureCount());
		assertFalse(runtime.isDegraded());
		assertFalse(eventBuffer.containsType("planner.parse_error"));
		runtime.shutdown();
	}

	@Test
	void plannerTriggerPreservesOriginalTriggerType() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"I will pick one step.",
			new PlannerIntent("reply_only", null, null)
		));

		runtime.onPlannerTrigger(
			PlannerTrigger.pending(PlannerTriggerType.IDLE_THINK, "self", "IDLE THINK: choose a useful step.", 12L, 1000L),
			SessionSnapshot.initial(),
			null,
			Optional.empty(),
			TaskSnapshot.idle(),
			MissionExecutionSnapshot.idle(),
			eventBuffer
		);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertTrue(runtime.plannerConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.text().contains("[idle_think][self] IDLE THINK: choose a useful step.")
		));
		assertFalse(runtime.plannerConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.text().contains("[chat][self] IDLE THINK")
		));
		runtime.shutdown();
	}

	@Test
	void internalTaskUpdateDuringInFlightPlannerQueuesFollowUp() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(configuredLlmConfig());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"Working on it.",
			new PlannerIntent("reply_only", null, null)
		));
		backend.injectMockResponse(new PlannerResponse(
			"Completion noted.",
			new PlannerIntent("reply_only", null, null)
		));

		runtime.onPlayerChat("Alice", "@agent craft planks", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		runtime.onInternalTaskUpdate(
			"TASK UPDATE: state=COMPLETED activeStepKind=CRAFT_RECIPE",
			11L,
			SessionSnapshot.initial(),
			Optional.empty(),
			TaskSnapshot.idle(),
			MissionExecutionSnapshot.idle(),
			eventBuffer
		);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));
		DialogueResponse followUp = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("Working on it.", response.text());
		assertEquals("Completion noted.", followUp.text());
		assertTrue(runtime.plannerConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.text().contains("[system][runtime] TASK UPDATE: state=COMPLETED activeStepKind=CRAFT_RECIPE")
		));
		runtime.shutdown();
	}

	@Test
	void plannerConversationDebugSnapshotShowsAcceptedNativeVisionReply() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(
			backend,
			new CurrentViewVisionTool() {
				@Override
				public boolean isConfigured() {
					return true;
				}

				@Override
				public CompletableFuture<ai.moeru.airicraft.FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
					return CompletableFuture.completedFuture(
						new ai.moeru.airicraft.FirstPersonScreenshotService.CapturedScreenshot("png", 854, 480, 1920, 1080, 1L, new byte[]{1, 2, 3})
					);
				}

				@Override
				public CompletableFuture<ai.moeru.airicraft.agent.llm.VisionDescription> requestDescription(
					ai.moeru.airicraft.FirstPersonScreenshotService.CapturedScreenshot screenshot,
					String prompt
				) {
					return CompletableFuture.failedFuture(new AssertionError("Native tool image flow should not request external description"));
				}
			},
			PlannerVisionMode.NATIVE_TOOL_IMAGE
		);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"",
			new PlannerIntent("none", null, null),
			new ai.moeru.airicraft.agent.llm.PlannerToolRequest("take_a_look", null)
		));
		backend.injectMockResponse(new PlannerResponse(
			"I see snow.",
			new PlannerIntent("reply_only", null, null)
		));

		runtime.onPlayerChat("Alice", "@agent take a look", 10L, SessionSnapshot.initial(), "Alice", Optional.empty(), eventBuffer);
		DialogueResponse response = awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertEquals("I see snow.", response.text());
		assertTrue(runtime.plannerProjectedConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.kind() == PlannerConversationDebugKind.ASSISTANT_TURN && message.text().contains("I see snow.")
		));
		assertFalse(runtime.plannerCanonicalConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.kind() == PlannerConversationDebugKind.ASSISTANT_TURN && message.text().contains("I see snow.")
		));
		runtime.shutdown();
	}

	@Test
	void playerChatPlannerRequestIncludesMissionEvidence() {
		OpenAiCompatibleLlmBackend backend = new OpenAiCompatibleLlmBackend(AgentConfig.LlmConfig.defaults());
		DialogueRuntime runtime = newDialogueRuntime(backend);
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(32);
		backend.injectMockResponse(new PlannerResponse(
			"I can craft jungle planks.",
			new PlannerIntent("reply_only", null, null)
		));
		MissionSpec mission = new MissionSpec("mission-craft", MissionType.CRAFT_ITEM, "Craft from inventory");
		MissionExecutionSnapshot missionExecution = new MissionExecutionSnapshot(
			mission,
			null,
			null,
			new WorldEvidence(
				java.util.Map.of(),
				java.util.Map.of("minecraft:jungle_log", 7),
				java.util.Map.of(),
				List.of(new CraftingOpportunity("jungle_log_to_jungle_planks", "minecraft:jungle_planks", 4, List.of("minecraft:jungle_log"))),
				"minecraft:overworld",
				0,
				64,
				0,
				null,
				10L
			),
			StepExecutionResult.idle(),
			TaskExecutionSnapshot.idle()
		);
		TaskSnapshot activeTask = new TaskSnapshot(
			TaskState.RUNNING,
			mission,
			null,
			null,
			new TaskProgressSnapshot(0, 0),
			TaskStep.NONE,
			TaskOwnership.NONE,
			"test",
			null,
			null,
			null,
			StepExecutionResult.idle(),
			10L
		);

		runtime.onPlayerChat(
			"Alice",
			"@agent what can you craft?",
			10L,
			SessionSnapshot.initial(),
			"Alice",
			Optional.empty(),
			activeTask,
			missionExecution,
			eventBuffer
		);
		awaitResponse(runtime, eventBuffer, Duration.ofSeconds(1));

		assertTrue(runtime.plannerConversationDebugSnapshot().messages().stream().anyMatch(message ->
			message.text().contains("[From {1*jungle_log} to 4*jungle_planks]: jungle_log_to_jungle_planks")
		));
		runtime.shutdown();
	}

	private static void recordPendingReply(DialogueRuntime runtime) {
		assertTrue(runtime.recordSentReply(runtime.pendingReplyReady(Long.MAX_VALUE).orElseThrow(), true));
	}

	private static boolean hasAgentTurn(DialogueRuntime runtime, String text) {
		return agentTurnCount(runtime, text) > 0;
	}

	private static int agentTurnCount(DialogueRuntime runtime, String text) {
		return (int) runtime.snapshot().recentTurns().stream()
			.filter(turn -> DialogueSpeakerLabels.AGENT.equals(turn.speaker()))
			.filter(turn -> text.equals(turn.text()))
			.count();
	}

	private static DialogueResponse awaitResponse(DialogueRuntime runtime, SemanticEventBuffer eventBuffer, Duration timeout) {
		return awaitResponse(runtime, eventBuffer, timeout, null, Optional.empty(), null, null);
	}

	private static DialogueResponse awaitResponse(
		DialogueRuntime runtime,
		SemanticEventBuffer eventBuffer,
		Duration timeout,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		TaskSnapshot activeTask,
		MissionExecutionSnapshot missionExecution
	) {
		Instant deadline = Instant.now().plus(timeout);
		long pollTick = 100L;
		while (Instant.now().isBefore(deadline)) {
			DialogueResponse response = runtime.poll(pollTick++, eventBuffer, sessionSnapshot, activeGoal, activeTask, missionExecution);
			if (response != null) {
				return response;
			}
			sleepBriefly();
		}
		throw new AssertionError("Timed out waiting for dialogue response");
	}

	private static void awaitFailureProcessed(DialogueRuntime runtime, SemanticEventBuffer eventBuffer, long tick, Duration timeout) {
		Instant deadline = Instant.now().plus(timeout);
		long pollTick = tick + 100L;
		while (Instant.now().isBefore(deadline)) {
			runtime.poll(pollTick++, eventBuffer);
			if (runtime.consecutiveFailureCount() >= tick) {
				return;
			}
			sleepBriefly();
		}
		throw new AssertionError("Timed out waiting for planner failure");
	}

	private static void sleepBriefly() {
		try {
			Thread.sleep(10L);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting", exception);
		}
	}

	private static AgentConfig.LlmConfig configuredLlmConfig() {
		AgentConfig.LlmConfig defaults = AgentConfig.LlmConfig.defaults();
		return new AgentConfig.LlmConfig(
			defaults.providerBaseUrl(),
			"test-key",
			"test-model",
			defaults.visionProviderBaseUrl(),
			defaults.visionApiKey(),
			defaults.visionModel(),
			defaults.requestTimeoutMillis(),
			defaults.visionRequestTimeoutMillis(),
			defaults.maxRecentConversationTurns(),
			defaults.plannerCompactionTriggerTokens(),
			defaults.plannerPendingSemanticEventCap(),
			defaults.plannerSessionMaxConcurrentAttempts(),
			defaults.plannerSessionCoalesceStepMillis(),
			defaults.plannerSessionCoalesceMinMillis(),
			defaults.plannerSessionCoalesceMaxMillis(),
			defaults.visionImageDetail(),
			defaults.plannerNativeVisionEnabled(),
			defaults.plannerUseJsonObjectResponseFormat()
		);
	}

	private static DialogueRuntime newDialogueRuntime(OpenAiCompatibleLlmBackend backend) {
		return newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);
	}

	private static DialogueRuntime newDialogueRuntime(LlmBackend backend) {
		return newDialogueRuntime(backend, CurrentViewVisionTool.disabled(), PlannerVisionMode.EXTERNAL_SUMMARY);
	}

	private static DialogueRuntime newDialogueRuntime(
		OpenAiCompatibleLlmBackend backend,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode
	) {
		return newDialogueRuntime((LlmBackend) backend, visionTool, visionMode);
	}

	private static DialogueRuntime newDialogueRuntime(
		LlmBackend backend,
		CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode
	) {
		return newDialogueRuntime(backend, visionTool, visionMode, null);
	}

	private static DialogueRuntime newDialogueRuntime(LlmBackend backend, CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode, ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore goal) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		Clock clock = Clock.systemDefaultZone();
		PlannerOrchestrator orchestrator = new PlannerOrchestrator(
			new PlannerExecutor(backend),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config)),
			new PlannerContextAggregator(
				clock,
				config.plannerCompactionTriggerTokens(),
				config.plannerPendingSemanticEventCap(),
				visionMode
			),
			visionTool,
			CurrentInventoryTool.disabled(),
			visionMode,
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder(),
			PlannerActionToolExecutor.DISABLED,
			PlannerToolNarrationSink.NO_OP,
			PlannerToolRegistry.empty(),
			PlannerToolExecutionObserver.NO_OP
		);
		return new DialogueRuntime(orchestrator, 8, clock, goal);
	}

	private static TaskSnapshot activeTask(
		String missionId,
		MissionType missionType,
		String goalText,
		String activeStepId,
		LedgerStepKind activeStepKind
	) {
		MissionSpec mission = new MissionSpec(missionId, missionType, goalText);
		return new TaskSnapshot(
			TaskState.RUNNING,
			mission,
			null,
			null,
			new TaskProgressSnapshot(0, 1),
			TaskStep.NONE,
			TaskOwnership.NONE,
			"test",
			null,
			activeStepId,
			activeStepKind,
			StepExecutionResult.idle(),
			10L
		);
	}

	private static MissionExecutionSnapshot missionExecution(MissionSpec mission, StepExecutionStatus status) {
		return new MissionExecutionSnapshot(
			mission,
			null,
			null,
			null,
			new StepExecutionResult(null, status, null, java.util.Map.of(), java.util.Map.of(), 10L),
			TaskExecutionSnapshot.idle()
		);
	}

	private static final class BlockingLlmBackend implements LlmBackend {
		private final CopyOnWriteArrayList<LlmConversation> conversations = new CopyOnWriteArrayList<>();
		private final LinkedBlockingQueue<CompletableFuture<PlannerResponse>> responses = new LinkedBlockingQueue<>();

		@Override
		public LlmCallResult<PlannerResponse> generate(LlmConversation conversation) throws LlmBackendException {
			conversations.add(conversation);
			try {
				CompletableFuture<PlannerResponse> response = responses.take();
				return LlmCallResult.of(response.get(2, TimeUnit.SECONDS), null);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "Interrupted while waiting for test response", exception);
			}
			catch (ExecutionException exception) {
				throw new LlmBackendException(LlmFailureType.PROVIDER_ERROR, "Failed test response", exception);
			}
			catch (TimeoutException exception) {
				throw new LlmBackendException(LlmFailureType.TIMEOUT, "Timed out waiting for test response", exception);
			}
		}

		@Override
		public void injectMockResponse(PlannerResponse response) {
			CompletableFuture<PlannerResponse> future = enqueueResponse();
			future.complete(response);
		}

		@Override
		public void injectTimeout() {
			CompletableFuture<PlannerResponse> future = enqueueResponse();
			future.completeExceptionally(new TimeoutException("Injected LLM timeout"));
		}

		@Override
		public boolean isConfigured() {
			return true;
		}

		private CompletableFuture<PlannerResponse> enqueueResponse() {
			CompletableFuture<PlannerResponse> response = new CompletableFuture<>();
			responses.add(response);
			return response;
		}

		private int conversationCount() {
			return conversations.size();
		}

		private LlmConversation conversation(int index) {
			return conversations.get(index);
		}

		private void awaitConversationCount(int expectedCount) {
			long deadlineNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
			while (System.nanoTime() < deadlineNanos) {
				if (conversationCount() >= expectedCount) {
					return;
				}
				sleepBriefly();
			}
			throw new AssertionError("Timed out waiting for conversation count " + expectedCount + ", got " + conversationCount());
		}

		private void assertConversationCountRemains(int expectedCount, Duration duration) {
			long deadlineNanos = System.nanoTime() + duration.toNanos();
			while (System.nanoTime() < deadlineNanos) {
				if (conversationCount() != expectedCount) {
					throw new AssertionError("Expected conversation count to remain " + expectedCount + ", got " + conversationCount());
				}
				sleepBriefly();
			}
		}
	}
}
