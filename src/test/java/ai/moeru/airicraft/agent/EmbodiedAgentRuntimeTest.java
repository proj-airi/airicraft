package ai.moeru.airicraft.agent;

import com.google.gson.JsonObject;
import ai.moeru.airicraft.AiricraftConfig;
import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.actions.ActionGoal;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionSnapshot;
import ai.moeru.airicraft.agent.actions.BlockAcquisitionTestFixtures;
import ai.moeru.airicraft.agent.actions.ActionGraphExecutionState;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.debug.AgentDebugTimelineEntry;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.job.ActiveJob;
import ai.moeru.airicraft.agent.job.ActiveJobProposal;
import ai.moeru.airicraft.agent.job.ActiveJobStatus;
import ai.moeru.airicraft.agent.job.ActiveJobType;
import ai.moeru.airicraft.agent.job.ActiveJobRuntime;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexAction;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexCause;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexSnapshot;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexState;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.PlayerLifecycleState;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockUseStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EvidenceKind;
import ai.moeru.airicraft.agent.tasks.EvidenceRequirement;
import ai.moeru.airicraft.agent.tasks.EntityAttackMode;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.tasks.FinishStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
import ai.moeru.airicraft.agent.tasks.SmeltingOption;
import ai.moeru.airicraft.agent.tasks.SmeltingSlotSnapshot;
import ai.moeru.airicraft.agent.tasks.SmeltingStationCandidate;
import ai.moeru.airicraft.agent.tasks.SmeltingStationKey;
import ai.moeru.airicraft.agent.tasks.SmeltingStationKind;
import ai.moeru.airicraft.agent.tasks.SmeltingStationObservation;
import ai.moeru.airicraft.agent.tasks.SmeltingStationSource;
import ai.moeru.airicraft.agent.tasks.SmeltingStationState;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskTerminationCause;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.WorldTaskExecutor;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import ai.moeru.airicraft.agent.tasks.StepExecutionResult;
import ai.moeru.airicraft.agent.tasks.StepExecutionStatus;
import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolCatalog;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbodiedAgentRuntimeTest {
	private static void assertTaskEvidence(EmbodiedAgentRuntime runtime, String type, Map<String, String> expected) {
		var matching = runtime.recentEvents(null).events().stream().filter(event -> type.equals(event.type()))
			.filter(event -> expected.entrySet().stream().allMatch(entry -> entry.getValue().equals(String.valueOf(event.payload().get(entry.getKey())))))
			.toList();
		assertFalse(matching.isEmpty(), "Expected identified task evidence: " + expected);
		String context = ai.moeru.airicraft.agent.llm.PlannerObservation.render(runtime.currentPlannerDecisionContext().observation(0));
		assertTrue(context.contains(type), context);
		for (String value : expected.values()) assertTrue(context.contains(value), context);
	}

	@Test void combatUsesTheCameraControllerTickedByTheClient() throws Exception {
		var config = AgentConfig.defaults();
		var camera = new ai.moeru.airicraft.agent.control.CameraController();
		var runtime = new EmbodiedAgentRuntime(AiricraftConfig.defaults(), config,
			new FirstPersonScreenshotService(), new FakeWorldTaskExecutor(),
			ai.moeru.airicraft.agent.observability.AgentObservability.create(config.observability()),
			new ai.moeru.airicraft.agent.tasks.SmeltingProcessManager(), camera, null);
		try {
			var reflexField = EmbodiedAgentRuntime.class.getDeclaredField("survivalReflexRuntime");
			reflexField.setAccessible(true);
			var cameraField = SurvivalReflexRuntime.class.getDeclaredField("cameraController");
			cameraField.setAccessible(true);
			org.junit.jupiter.api.Assertions.assertSame(camera, cameraField.get(reflexField.get(runtime)));
		} finally { runtime.shutdown(); }
	}

	@Test
	void plannerReceiptAndWorkInspectionShareDirectNavigationIdentityThroughFailure() {
		var executor = new FakeWorldTaskExecutor();
		var runtime = EmbodiedAgentRuntime.createForTests(executor);
		try {
			runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
			String receipt = runtime.executePlannerAction(new PlannerToolCall("nav", "navigate_to",
				JsonParser.parseString("{\"x\":12,\"y\":64,\"z\":8,\"exactY\":true}").getAsJsonObject(), null, null)).join();
			var json = JsonParser.parseString(receipt.substring(receipt.indexOf('{'))).getAsJsonObject();
			assertTrue(json.get("accepted").getAsBoolean(), receipt);
			String workId = json.get("workId").getAsString();
			assertFalse(json.has("work"), "Receipt must not duplicate its work projection");
			assertTrue(workId.startsWith("JOB:"), receipt);
			runtime.onClientTick(null);
			var request = executor.lastActiveTask.orElseThrow();
			executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(request.taskId(), request.goal(), TaskExecutionState.FAILED,
				"CALC_FAILED", TaskTerminationCause.CALCULATION_FAILED));
			runtime.onClientTick(null);
			JsonObject args = new JsonObject(); args.addProperty("workId", workId);
			String inspected = runtime.execute(new PlannerToolCall("inspect", "inspect_work", args, null, null)).join();
			assertTrue(inspected.contains(workId), inspected);
			assertTrue(inspected.contains("FAILED"), inspected);
			assertTrue(inspected.contains("CALC_FAILED"), inspected);
			String listed = runtime.execute(new PlannerToolCall("list", "list_work", new JsonObject(), null, null)).join();
			assertTrue(listed.contains(workId), listed);
		} finally { runtime.shutdown(); }
	}

	@Test
	void rejectedPlannerActionDoesNotInventSuccessfulWork() {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			String result = runtime.executePlannerAction(new PlannerToolCall("bad", "not_a_tool", new JsonObject(), null, null)).join();
			var receipt = JsonParser.parseString(result.substring(result.indexOf('{'))).getAsJsonObject();
			assertFalse(receipt.get("accepted").getAsBoolean(), result);
			assertFalse(receipt.has("work"), result);
		} finally { runtime.shutdown(); }
	}

	@Test
	void reflexPauseKeepsFreshEvidenceInsteadOfTheInterruptedJobsOldSample() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			var fresh = new WorldEvidence(Map.of(), Map.of("minecraft:dirt", 1), Map.of(),
				"minecraft:overworld", -34, 124, -39, "minecraft:dirt", 27024);
			Field field = EmbodiedAgentRuntime.class.getDeclaredField("missionExecutionSnapshot");
			field.setAccessible(true);
			field.set(runtime, new ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot(
				null, null, null, fresh, StepExecutionResult.idle(), TaskExecutionSnapshot.idle()));
			Method pause = EmbodiedAgentRuntime.class.getDeclaredMethod("pauseNormalWorkForReflex", net.minecraft.client.MinecraftClient.class);
			pause.setAccessible(true);
			pause.invoke(runtime, new Object[]{null});
			assertEquals(fresh, runtime.missionExecutionSnapshot().evidence(),
				"A reflex handoff must not present the interrupted job's old position as a fresh update");
		}
		finally { runtime.shutdown(); }
	}

	@Test
	void stalledCombatTriggerReportsUnresolvedThreatAndTacticalHold() {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			var trigger = runtime.createPlannerTriggerForTests(new SemanticEvent(1, 400, 20000, "reflex.resolved",
				Map.of("reason", "combat_approach_stalled", "cause", "MOB_ATTACK", "holdId", "hold-patrol",
					"nextState", "AWAITING_PLANNER", "position", "-35,124,-39", "remainingThreats", "pillager at distance 13.14",
					"noProgressTicks", 400)), new EventRoutingProfile("reflex.resolved", true, PlannerTriggerType.SYSTEM, true));
			assertTrue(trigger.text().contains("Combat is unresolved"));
			assertTrue(trigger.text().contains("hold-patrol"));
			assertTrue(trigger.text().contains("pillager at distance 13.14"));
			assertTrue(trigger.text().contains("-35,124,-39"));
		}
		finally { runtime.shutdown(); }
	}

	@Test
	void plannerCanReadAndReplaceReflexPolicyThroughActionExecutor() {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			var settings = com.google.gson.JsonParser.parseString("""
				{"combatEnabled":false,"drowningEnabled":true,"maxThreatDistance":8,"requireLineOfSight":true}
				""").getAsJsonObject();
			String applied = runtime.execute(PlannerToolCatalog.parseToolCall("configure_reflex", settings,
				ai.moeru.airicraft.agent.llm.PlannerToolRegistry.empty())).join();
			assertTrue(applied.contains("applied ReflexPolicy[combatEnabled=false"));
			String queried = runtime.execute(PlannerToolCatalog.parseToolCall("configure_reflex", new com.google.gson.JsonObject(),
				ai.moeru.airicraft.agent.llm.PlannerToolRegistry.empty())).join();
			assertTrue(queried.contains("maxThreatDistance=8"));
			assertTrue(queried.contains("drowningEnabled=true"));
		}
		finally { runtime.shutdown(); }
	}

	@Test
	void rejectedPolicyDoesNotBecomeATerminalToolReceipt() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			var args = JsonParser.parseString("{\"source\":\"function* main() { return {}; }\",\"input\":{}}").getAsJsonObject();
			String result = runtime.executePlannerAction(new PlannerToolCall("policy-test", "run_policy", args, null, null)).join();
			assertEquals("TOOL_UNAVAILABLE: run_policy disabled", result);
			setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, "policy-hold", null, null));
			String held = runtime.executePlannerAction(new PlannerToolCall("held-policy-test", "run_policy", args, null, null)).join();
			assertEquals("TOOL_UNAVAILABLE: run_policy disabled", held);
		} finally { runtime.shutdown(); }
	}

	@Test
	void policyChildUsesNormalSchedulerAndRootCancellationStopsIt() throws Exception {
		var executor = new FakeWorldTaskExecutor();
		var runtime = EmbodiedAgentRuntime.createForTests(executor);
		try {
			runtime.overrideSessionSnapshotForTests(new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST,
				true, true, "minecraft:overworld", true, 25565, 0));
			var root = new ai.moeru.airicraft.agent.work.WorkHandle("OPERATION:test-policy");
			Field rootField = EmbodiedAgentRuntime.class.getDeclaredField("policyWork"); rootField.setAccessible(true); rootField.set(runtime, root);
			Method dispatch = EmbodiedAgentRuntime.class.getDeclaredMethod("dispatchPolicyTool", String.class, JsonObject.class); dispatch.setAccessible(true);
			var arguments = JsonParser.parseString("{\"x\":10,\"y\":64,\"z\":2,\"exactY\":true}").getAsJsonObject();
			@SuppressWarnings("unchecked")
			var admitted = (CompletableFuture<ai.moeru.airicraft.agent.llm.ExternalPlannerToolResult>) dispatch.invoke(runtime, "navigate_to", arguments);
			assertTrue(admitted.join().text().contains("workId"));
			Method cancel = EmbodiedAgentRuntime.class.getDeclaredMethod("cancelPolicyChildren"); cancel.setAccessible(true);
			var policy = new ai.moeru.airicraft.policy.PolicyRuntime("function* main(p) { yield p.observeContainer(); }", new JsonObject(),
				new ai.moeru.airicraft.policy.PolicyRuntime.Host() {
					public CompletableFuture<com.google.gson.JsonElement> execute(JsonObject effect) { return new CompletableFuture<>(); }
					public void close() { try { cancel.invoke(runtime); } catch (Exception e) { throw new RuntimeException(e); } }
				}, ignored -> {});
			Field policyField = EmbodiedAgentRuntime.class.getDeclaredField("policyRuntime"); policyField.setAccessible(true); policyField.set(runtime, policy);
			runtime.onClientTick(null);
			assertTrue(executor.lastActiveTask.isPresent(), "Policy must not suppress its native child tick");
			assertTrue(policy.active(), "Child admission must not cancel its owner");
			String childId = executor.lastActiveTask.orElseThrow().taskId();
			String inspected = runtime.execute(new PlannerToolCall("inspect", "inspect_work",
				JsonParser.parseString("{\"workId\":\"JOB:" + childId + "\"}").getAsJsonObject(), null, null)).join();
			assertTrue(inspected.contains("OPERATION:test-policy"), inspected);
			String competing = runtime.execute(new PlannerToolCall("competing", "navigate_to", arguments, null, null)).join();
			assertTrue(competing.contains("policy_active"));
			policy.cancel("test_cancel");
			assertEquals(ActiveJobStatus.CANCELLED, runtime.activeJob().status());
			assertTrue(executor.onWorldLeaveCalls > 0, "Root cancellation must stop the native executor");
		} finally { runtime.shutdown(); }
	}

	@Test
	void idlePlannerEvidenceRefreshesWithoutAProjectedSemanticTask() throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			runtime.overrideSessionSnapshotForTests(new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST,
				true, true, "minecraft:overworld", true, 25565, 0));
			var staleEvidence = new WorldEvidence(Map.of(), Map.of("minecraft:spruce_planks", 54), Map.of(),
				"minecraft:overworld", -5, 135, -1, "minecraft:spruce_planks", 0);
			var terminalResult = new StepExecutionResult("navigate_to", StepExecutionStatus.CANCELLED,
				"CANCELED", Map.of(), Map.of("jobType", "NAVIGATE_TO"), 0);
			Field missionField = EmbodiedAgentRuntime.class.getDeclaredField("missionExecutionSnapshot");
			missionField.setAccessible(true);
			missionField.set(runtime, new ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot(
				null, null, null, staleEvidence, terminalResult, TaskExecutionSnapshot.idle()));

			runtime.onClientTick(null);

			var plannerEvidence = runtime.missionExecutionSnapshot().evidence();
			assertEquals(1, plannerEvidence.tick(), "Idle continuation must receive this tick's evidence");
			assertTrue(plannerEvidence.itemCounts().isEmpty(), "Old supplies must not be re-injected as current inventory");
			assertEquals(0, plannerEvidence.x(), "Old position must not overwrite later observations");
			assertEquals(terminalResult, runtime.missionExecutionSnapshot().lastStepResult(), "Retain task outcome history");
		}
		finally { runtime.shutdown(); }
	}

	@Test
	void eatingHoldsGraphAdvancementUntilConsumptionEnds() throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST,
			true, true, "minecraft:overworld", true, 25565, 1));
		runtime.execute(new PlannerToolCall("food-graph", PlannerToolCatalog.START_ACTION_GOAL,
			JsonParser.parseString("{\"kind\":\"resource_collection\",\"resourceKind\":\"WOOD_LOGS\",\"quantity\":1}").getAsJsonObject(), null, null)).join();
		Field controllerField = EmbodiedAgentRuntime.class.getDeclaredField("playerItemUseController");
		controllerField.setAccessible(true);
		Object controller = controllerField.get(runtime);
		Field eatingField = PlayerItemUseController.class.getDeclaredField("eating");
		eatingField.setAccessible(true);
		var constructor = eatingField.getType().getDeclaredConstructors()[0];
		constructor.setAccessible(true);
		eatingField.set(controller, constructor.newInstance("minecraft:bread", 12, 2, Long.MAX_VALUE));
		Method tick = EmbodiedAgentRuntime.class.getDeclaredMethod("tickActionGraph", WorldEvidence.class, boolean.class);
		tick.setAccessible(true);
		var evidence = new WorldEvidence(Map.of(), Map.of(), Map.of(), "minecraft:overworld", 0, 64, 0, "minecraft:bread", 1);
		tick.invoke(runtime, evidence, true);
		assertTrue(runtime.actionGraphExecutionSnapshot().trace().stream().noneMatch(e -> e.eventType().equals("resolution_scheduled")));
		assertEquals(ActionGraphExecutionState.RESOLVING, runtime.actionGraphExecutionSnapshot().state());
		eatingField.set(controller, null);
		tick.invoke(runtime, evidence, true);
		assertTrue(runtime.actionGraphExecutionSnapshot().trace().stream().anyMatch(e -> e.eventType().equals("resolution_scheduled")));
		runtime.shutdown();
	}

	@Test
	void terminalSemanticTaskDoesNotRemainBusyFromStaleReflexExecution() throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		setTaskSnapshot(runtime, new TaskSnapshot(
			TaskState.CANCELLED,
			null, null, null, null, null, null, "planner_tool", "cancelled", "navigate_to",
			LedgerStepKind.NAVIGATE_TO_POSITION, null, 1L, "task-cancelled"
		));
		setTaskExecutionSnapshot(runtime, new TaskExecutionSnapshot(
			TaskExecutionState.PAUSED_BY_REFLEX, "task-cancelled", null, "Custom Goal", "reflex", null, null
		));

		assertFalse(activeTaskInProgress(runtime));
	}

	@Test
	void plannerResponseReplacesIdleAfloatSafetyHold() throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		setReflexSnapshot(runtime, new SurvivalReflexSnapshot(
			SurvivalReflexState.AWAITING_PLANNER,
			SurvivalReflexCause.DROWNING,
			SurvivalReflexAction.STAY_AFLOAT,
			1L,
			"safety-hold-1",
			null,
			null,
			List.of(),
			20.0F,
			20.0F,
			300,
			300,
			1L,
			2L,
			12,
			null
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"I will collect dirt.",
			new DialogueIntent(
				DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 1))
			),
			3L
		));

		assertEquals(SurvivalReflexState.IDLE, runtime.survivalReflexSnapshot().state());
		assertEquals(ActiveJobType.MINE_BLOCKS, runtime.activeJob().type());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event ->
			"reflex.hold_released".equals(event.type())
		));
	}

	@Test
	void continueResumesHeldWorkWithoutReplacingItsIdentity() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			runtime.injectDialogueResponseForTests(new DialogueResponse("", new DialogueIntent(DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"),3))),1));
			String id = runtime.activeJob().jobId();
			activeJobRuntime(runtime).pauseForReflex(2);
			setReflexSnapshot(runtime,reflexSnapshot(SurvivalReflexState.AWAITING_PLANNER,"hold-1",id,null));
			JsonObject args = new JsonObject(); args.addProperty("x",1); args.addProperty("y",64); args.addProperty("z",1);
			String denied = runtime.executePlannerAction(new PlannerToolCall("replace","navigate_to",args,null,null)).join();
			assertTrue(denied.contains("work_in_safety_hold")); assertEquals(id,runtime.activeJob().jobId());
			String resumed = runtime.executePlannerAction(new PlannerToolCall("resume","continue",new JsonObject(),null,null)).join();
			assertTrue(resumed.contains("resumed"),resumed);
			assertEquals(SurvivalReflexState.IDLE,runtime.survivalReflexSnapshot().state());
			assertEquals(id,runtime.activeJob().jobId());
		} finally { runtime.shutdown(); }
	}

	@Test void clearQueueDiscardsEmptySafetyHoldWithoutStoppingActiveReflex() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			for (var state : List.of(SurvivalReflexState.AWAITING_PLANNER, SurvivalReflexState.ACTIVE)) {
				setReflexSnapshot(runtime, reflexSnapshot(state, "orphan-hold", null, null));
				runtime.executePlannerAction(new PlannerToolCall("clear", "clear_queue", new JsonObject(), null, null)).join();
				assertNull(runtime.survivalReflexSnapshot().holdId());
				assertEquals(state == SurvivalReflexState.ACTIVE ? state : SurvivalReflexState.IDLE,
					runtime.survivalReflexSnapshot().state());
			}
		} finally { runtime.shutdown(); }
	}

	@Test void continueCannotOverrideAnActiveReflex() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.ACTIVE, "active-hold", null, null));
			runtime.executePlannerAction(new PlannerToolCall("keep", "continue", new JsonObject(), null, null)).join();
			assertEquals(SurvivalReflexState.ACTIVE, runtime.survivalReflexSnapshot().state());
			assertEquals("active-hold", runtime.survivalReflexSnapshot().holdId());
		} finally { runtime.shutdown(); }
	}

	@Test
	void tacticalHoldWithoutForegroundWorkAdmitsPlannerEscape() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		try {
			runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
			setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, "tactical", null, null));
			Field owner = EmbodiedAgentRuntime.class.getDeclaredField("survivalReflexRuntime");
			owner.setAccessible(true);
			Field window = SurvivalReflexRuntime.class.getDeclaredField("tacticalWindow");
			window.setAccessible(true);
			var constructor = window.getType().getDeclaredConstructor(long.class);
			constructor.setAccessible(true);
			window.set(owner.get(runtime), constructor.newInstance(1200L));
			String receipt = runtime.executePlannerAction(new PlannerToolCall("escape", "navigate_to",
				JsonParser.parseString("{\"x\":12,\"y\":64,\"z\":8,\"exactY\":true}").getAsJsonObject(), null, null)).join();
			assertTrue(receipt.contains("\"accepted\":true"), receipt);
			assertEquals(SurvivalReflexState.IDLE, runtime.survivalReflexSnapshot().state());
			assertTrue(runtime.recentEvents(null).events().stream().anyMatch(e -> "reflex.hold_released".equals(e.type())));
		} finally { runtime.shutdown(); }
	}

	@Test
	void matchingSafetyHoldResumesSamePausedJob() throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Mining dirt.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
			1L
		));
		ActiveJobRuntime activeJobs = activeJobRuntime(runtime);
		String jobId = activeJobs.current().jobId();
		activeJobs.pauseForReflex(2L);
		setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1", jobId, null));

		SurvivalReflexSnapshot resumed = runtime.resumeSafetyHold("hold-1", "test");

		assertEquals(SurvivalReflexState.IDLE, resumed.state());
		assertEquals(jobId, runtime.activeJob().jobId());
		assertEquals(ActiveJobStatus.QUEUED, runtime.activeJob().status());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event ->
			"reflex.task_resumed".equals(event.type())
		));
	}

	@Test
	void resumeRejectsMissingActiveAndStaleSafetyHolds() throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		BridgeUnavailableException missing = assertThrows(BridgeUnavailableException.class,
			() -> runtime.resumeSafetyHold("hold-1", "test"));
		assertEquals("no_safety_hold", missing.code());

		setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.ACTIVE, "hold-1", "job-1", null));
		BridgeUnavailableException active = assertThrows(BridgeUnavailableException.class,
			() -> runtime.resumeSafetyHold("hold-1", "test"));
		assertEquals("reflex_active", active.code());

		setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1", "job-1", null));
		BridgeUnavailableException stale = assertThrows(BridgeUnavailableException.class,
			() -> runtime.resumeSafetyHold("old-hold", "test"));
		assertEquals("stale_safety_hold", stale.code());
	}

	@Test
	void foregroundGraphRemainsBusyAcrossSafetyHoldUntilExplicitCancellation() throws Exception {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		ActionGraphExecutionSnapshot first = runtime.startActionGoal(ActionGoal.inventoryItem("minecraft:bread", 1), "test");
		setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.AWAITING_PLANNER, "hold-1", null, first.executionId()));

		ActionGraphExecutionSnapshot busy = runtime.startActionGoal(
			ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1),
			"test"
		);

		assertEquals(first.executionId(), busy.executionId());
		assertEquals(SurvivalReflexState.AWAITING_PLANNER, runtime.survivalReflexSnapshot().state());

		setReflexSnapshot(runtime, reflexSnapshot(
			SurvivalReflexState.AWAITING_PLANNER, "hold-2", null, first.executionId()
		));
		ActionGraphExecutionSnapshot cancelled = runtime.cancelActionGoal("operator_cancelled");
		assertEquals(ActionGraphExecutionState.CANCELLED, cancelled.state());
		assertEquals(SurvivalReflexState.IDLE, runtime.survivalReflexSnapshot().state());
	}

	@Test
	void suppressesRecentEchoOfAgentOwnPublicChat() {
		assertTrue(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	private static ActiveJobRuntime activeJobRuntime(EmbodiedAgentRuntime runtime) throws Exception {
		Field field = EmbodiedAgentRuntime.class.getDeclaredField("activeJobRuntime");
		field.setAccessible(true);
		return (ActiveJobRuntime) field.get(runtime);
	}

	private static void setReflexSnapshot(EmbodiedAgentRuntime runtime, SurvivalReflexSnapshot snapshot) throws Exception {
		Field runtimeField = EmbodiedAgentRuntime.class.getDeclaredField("survivalReflexRuntime");
		runtimeField.setAccessible(true);
		SurvivalReflexRuntime reflexRuntime = (SurvivalReflexRuntime) runtimeField.get(runtime);
		Field snapshotField = SurvivalReflexRuntime.class.getDeclaredField("snapshot");
		snapshotField.setAccessible(true);
		snapshotField.set(reflexRuntime, snapshot);
	}

	private static void setTaskExecutionSnapshot(EmbodiedAgentRuntime runtime, TaskExecutionSnapshot snapshot) throws Exception {
		Field field = EmbodiedAgentRuntime.class.getDeclaredField("taskExecutionSnapshot");
		field.setAccessible(true);
		field.set(runtime, snapshot);
	}

	private static void setTaskSnapshot(EmbodiedAgentRuntime runtime, TaskSnapshot snapshot) throws Exception {
		Field field = EmbodiedAgentRuntime.class.getDeclaredField("taskSnapshot");
		field.setAccessible(true);
		field.set(runtime, snapshot);
	}

	private static boolean activeTaskInProgress(EmbodiedAgentRuntime runtime) throws Exception {
		Method method = EmbodiedAgentRuntime.class.getDeclaredMethod("activeTaskInProgress");
		method.setAccessible(true);
		return (boolean) method.invoke(runtime);
	}

	private static SurvivalReflexSnapshot reflexSnapshot(
		SurvivalReflexState state,
		String holdId,
		String jobId,
		String actionExecutionId
	) {
		return new SurvivalReflexSnapshot(
			state, SurvivalReflexCause.DROWNING, SurvivalReflexAction.SWIM_TO_AIR, 1L, holdId,
			jobId, actionExecutionId, List.of(), 10.0F, 20.0F, 100, 300, 1L, 2L, 12, null
		);
	}

	@Test
	void doesNotSuppressDifferentOrStaleChat() {
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"follow me",
			"Player918",
			"hello there",
			120L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"Player918",
			"hello there",
			"Player918",
			"hello there",
			200L,
			100L
		));
		assertFalse(EmbodiedAgentRuntime.isAgentChatEcho(
			"magpie",
			"hello there",
			"Player918",
			"hello there",
			120L,
			100L
		));
	}

	@Test
	void detectsLocalControllerMessagesByMatchingClientPlayerName() {
		assertTrue(EmbodiedAgentRuntime.isLocalControllerMessage("Player918", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage("magpie", "Player918"));
		assertFalse(EmbodiedAgentRuntime.isLocalControllerMessage(null, "Player918"));
	}

	@Test
	void idleIdeaSchedulingTreatsTerminalJobsAsIdle() {
		ActiveJob running = new ActiveJob(
			"job-running",
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.RUNNING,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			"test",
			null,
			null,
			1L
		);
		ActiveJob completed = new ActiveJob(
			"job-completed",
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.COMPLETED,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			"test",
			null,
			null,
			2L
		);

		assertFalse(EmbodiedAgentRuntime.isIdleForIdleIdeaScheduling(running));
		assertTrue(EmbodiedAgentRuntime.isIdleForIdleIdeaScheduling(completed));
		assertTrue(EmbodiedAgentRuntime.isIdleForIdleIdeaScheduling(ActiveJob.idle()));
	}

	@Test
	void fallsBackToLastKnownPlayerHealthWhenObservedHealthAlreadyDropped() {
		float effective = EmbodiedAgentRuntime.effectiveHealthBefore(20.0F, 19.0F, 19.0F);

		assertEquals(20.0F, effective);
	}

	@Test
	void keepsObservedHealthBeforeWhenNoHigherBaselineExists() {
		assertEquals(19.0F, EmbodiedAgentRuntime.effectiveHealthBefore(null, 19.0F, 19.0F));
		assertEquals(19.0F, EmbodiedAgentRuntime.effectiveHealthBefore(18.0F, 19.0F, 19.0F));
	}

	@Test
	void taskExecutorPausesWhenSessionDoesNotAllowActuation() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.SINGLEPLAYER_LOCAL,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			10L,
			"test"
		));

		runtime.onClientTick(null);

		assertEquals(TaskExecutionState.PAUSED_BY_SESSION_GATE, runtime.taskExecutionSnapshot().state());
	}

	@Test
	void actionGoalShellStartsAndBlocksWhenWorldIsNotLoaded() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		ActionGraphExecutionSnapshot started = runtime.startActionGoal(ActionGoal.inventoryItem("minecraft:bread", 1), "test");
		runtime.onClientTick(null);
		ActionGraphExecutionSnapshot blocked = runtime.actionGraphExecutionSnapshot();

		assertEquals(ActionGraphExecutionState.RESOLVING, started.state());
		assertEquals(ActionGraphExecutionState.CANCELLED, blocked.state());
		assertEquals("world_left", blocked.message());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event ->
			"action_graph.goal_started".equals(event.type())
				&& started.executionId().equals(event.payload().get("executionId"))
		));
	}

	@Test
	void actionGoalShellCancelsThroughGraphPath() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		ActionGraphExecutionSnapshot started = runtime.startActionGoal(ActionGoal.inventoryItem("minecraft:bread", 1), "test");

		ActionGraphExecutionSnapshot cancelled = runtime.cancelActionGoal("user_cancelled");

		assertEquals(started.executionId(), cancelled.executionId());
		assertEquals(ActionGraphExecutionState.CANCELLED, cancelled.state());
		assertTrue(executor.lastActiveTask.isEmpty());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event ->
			"action_graph.goal_cancelled".equals(event.type())
				&& started.executionId().equals(event.payload().get("executionId"))
				&& "user_cancelled".equals(event.payload().get("reason"))
		));
	}

	@Test
	void startActionGoalPlannerToolStartsInventoryGraphGoal() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		String result = runtime.execute(new PlannerToolCall(
			"call_goal",
			PlannerToolCatalog.START_ACTION_GOAL,
			JsonParser.parseString("""
				{"kind":"inventory_item","itemId":"minecraft:bread","quantity":1}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("Tool result for start_action_goal: state=RESOLVING"));
		assertTrue(result.contains("executionId="));
		assertTrue(result.contains("minecraft:bread"));
		assertEquals(ActionGraphExecutionState.RESOLVING, runtime.actionGraphExecutionSnapshot().state());
	}

	@Test
	void startActionGoalPlannerToolStartsResourceGraphGoal() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		String result = runtime.execute(new PlannerToolCall(
			"call_goal",
			PlannerToolCatalog.START_ACTION_GOAL,
			JsonParser.parseString("""
					{"kind":"resource_collection","resourceKind":"RAW_IRON","quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("Tool result for start_action_goal: state=RESOLVING"));
		assertTrue(result.contains("resourceKind=RAW_IRON"));
		assertEquals(ActionGraphExecutionState.RESOLVING, runtime.actionGraphExecutionSnapshot().state());
	}

	@Test
	void startActionGoalPlannerToolTreatsOutputKindsAsInventoryGraphGoals() {
		EmbodiedAgentRuntime craftingRuntime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		String craftingResult = craftingRuntime.execute(new PlannerToolCall(
			"call_craft_goal",
			PlannerToolCatalog.START_ACTION_GOAL,
			JsonParser.parseString("""
				{"kind":"crafting_output","itemId":"minecraft:crafting_table","quantity":1}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(craftingResult.contains("Tool result for start_action_goal: state=RESOLVING"));
		assertTrue(craftingResult.contains("minecraft:crafting_table"));

		EmbodiedAgentRuntime smeltingRuntime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		String smeltingResult = smeltingRuntime.execute(new PlannerToolCall(
			"call_smelt_goal",
			PlannerToolCatalog.START_ACTION_GOAL,
			JsonParser.parseString("""
				{"kind":"smelting_output","itemId":"minecraft:iron_ingot","quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(smeltingResult.contains("Tool result for start_action_goal: state=RESOLVING"));
		assertTrue(smeltingResult.contains("minecraft:iron_ingot"));
		assertEquals(ActionGraphExecutionState.RESOLVING, smeltingRuntime.actionGraphExecutionSnapshot().state());
	}

	@Test
	void startActionGoalPlannerToolRejectsDifferentGoalWhileForegroundBusy() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		ActionGraphExecutionSnapshot first = runtime.startActionGoal(ActionGoal.inventoryItem("minecraft:bread", 1), "test");

		String result = runtime.execute(new PlannerToolCall(
			"call_goal_again",
			PlannerToolCatalog.START_ACTION_GOAL,
			JsonParser.parseString("""
				{"kind":"inventory_item","itemId":"minecraft:iron_pickaxe","quantity":1}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("Tool result for start_action_goal: state=RESOLVING admission=busy"));
		assertTrue(result.contains("executionId=" + first.executionId()));
		assertTrue(result.contains("minecraft:bread"));
		assertTrue(result.contains("failureCode=foreground_busy"));
		assertEquals(first.executionId(), runtime.actionGraphExecutionSnapshot().executionId());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event ->
			"action_graph.goal_admission".equals(event.type())
				&& "busy".equals(event.payload().get("admission"))
		));
	}

	@Test
	void plannerLegacyToolsCannotPreemptActiveActionGraph() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		ActionGraphExecutionSnapshot started = runtime.startActionGoal(ActionGoal.inventoryItem("minecraft:iron_pickaxe", 1), "test");

		String clear = runtime.execute(new PlannerToolCall(
			"call_clear",
			PlannerToolCatalog.CLEAR_GOAL,
			new com.google.gson.JsonObject(),
			null,
			null
		)).join();
		String collectSmelted = runtime.execute(new PlannerToolCall(
			"call_collect_smelted",
			PlannerToolCatalog.COLLECT_SMELTED_ITEMS,
			new com.google.gson.JsonObject(),
			null,
			null
		)).join();

		assertTrue(clear.contains("TOOL_ERROR: clear_goal denied reason=active_action_graph_in_progress"));
		assertTrue(collectSmelted.contains("TOOL_ERROR: collect_smelted_items denied reason=active_action_graph_in_progress"));
		assertEquals(started.executionId(), runtime.actionGraphExecutionSnapshot().executionId());
		assertEquals(ActionGraphExecutionState.RESOLVING, runtime.actionGraphExecutionSnapshot().state());
	}

	@Test
	void inspectAndCancelActionGoalPlannerToolsUseGraphPath() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		ActionGraphExecutionSnapshot started = runtime.startActionGoal(ActionGoal.inventoryItem("minecraft:bread", 1), "test");
		String selection = """
			{"executionId":"%s"}
			""".formatted(started.executionId());

		String inspect = runtime.execute(new PlannerToolCall(
			"call_inspect",
			PlannerToolCatalog.INSPECT_ACTION_GOAL,
			JsonParser.parseString(selection).getAsJsonObject(),
			null,
			null
		)).join();
		String list = runtime.execute(new PlannerToolCall(
			"call_list",
			PlannerToolCatalog.LIST_ACTION_GOALS,
			new com.google.gson.JsonObject(),
			null,
			null
		)).join();
		String trace = runtime.execute(new PlannerToolCall(
			"call_trace",
			PlannerToolCatalog.INSPECT_ACTION_TRACE,
			JsonParser.parseString(selection).getAsJsonObject(),
			null,
			null
		)).join();
		String cancel = runtime.execute(new PlannerToolCall(
			"call_cancel",
			PlannerToolCatalog.CANCEL_ACTION_GOAL,
			JsonParser.parseString("""
				{"executionId":"%s","reason":"user_changed_task"}
				""".formatted(started.executionId())).getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(inspect.contains("Tool result for inspect_action_goal: state=RESOLVING"));
		assertTrue(inspect.contains("executionPhase=PLANNING"), inspect);
		assertTrue(inspect.contains("activePrimitive=false"), inspect);
		assertTrue(inspect.contains("resolved=false"), inspect);
		assertTrue(inspect.contains("accepted=false"), inspect);
		assertTrue(list.contains("Tool result for list_action_goals: count=1"));
		assertTrue(trace.contains("Tool result for inspect_action_trace: state=RESOLVING"));
		assertTrue(trace.contains("trace="));
		assertTrue(cancel.contains("Tool result for cancel_action_goal: state=CANCELLED"));
		assertEquals(ActionGraphExecutionState.CANCELLED, runtime.actionGraphExecutionSnapshot().state());
	}

	@Test
	void listActionCapabilitiesPlannerToolReportsExecutableInventoryGoal() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		String result = runtime.execute(new PlannerToolCall(
			"call_capabilities",
			PlannerToolCatalog.LIST_ACTION_CAPABILITIES,
			new com.google.gson.JsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("Tool result for list_action_capabilities"));
		assertTrue(result.contains("inventory_item"));
		assertTrue(result.contains("RAW_IRON"));
		assertTrue(result.contains("supported"));
		assertTrue(result.contains("primitiveCount="));
	}

	@Test
	void craftRecipePlannerResponseRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		CraftRecipeStepArgs craftRecipe = new CraftRecipeStepArgs("oak_planks_x2_to_stick", 1);

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Crafting sticks.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.craftRecipe(craftRecipe)),
			20L
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.CRAFT_RECIPE, request.type());
		assertEquals(new WorldTaskRequest.CraftRecipe(craftRecipe), request.task());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void dropItemsPlannerResponseRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		DropItemsStepArgs dropItems = new DropItemsStepArgs("minecraft:oak_log", 2, null);

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Dropping logs.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.dropItems(dropItems)),
			20L
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(new WorldTaskRequest.DropItems(dropItems), request.task());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void dropItemsToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.execute(new PlannerToolCall(
			"call_drop",
			"drop_items",
			JsonParser.parseString("""
				{"itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("terminal result in a later observation"), result);
		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(new WorldTaskRequest.DropItems(new DropItemsStepArgs("minecraft:oak_log", 2, null)), request.task());
	}

	@Test
	void smeltItemsToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.registerSmeltingOptionsForTests(List.of(testSmeltingOption("smelt:iron:nearby-1", 3)));

		String result = runtime.execute(new PlannerToolCall(
				"call_smelt",
				"smelt_items",
				JsonParser.parseString("""
					{"optionId":"smelt:iron:nearby-1","inputQuantity":3,"fuelMode":"manual","fuelItemId":"minecraft:coal","fuelQuantity":1,"confirmationToken":"confirm-1"}
					""").getAsJsonObject(),
				null,
				null
			)).join();
		assertTrue(result.contains("accepted"), result);
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("processId="));
		assertTrue(result.contains("does not mean completed"));
		assertEquals(WorldTaskType.SMELT_ITEMS, request.type());
		assertEquals(new SmeltItemsStepArgs(
			"smelt:iron:nearby-1",
			3,
			SmeltingFuelMode.MANUAL,
			"minecraft:coal",
			1,
			"confirm-1"
		), ((WorldTaskRequest.SmeltItems) request.task()).args());
	}

	@Test
	void mineBlocksToolResultWarnsPlannerToWaitForTerminalResult() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.execute(new PlannerToolCall(
				"call_mine",
				"mine_blocks",
				JsonParser.parseString("""
					{"blockIds":["minecraft:dirt"],"quantity":1}
					""").getAsJsonObject(),
				null,
				null
			)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("terminal result in a later observation"), result);
		assertEquals(WorldTaskType.MINE, request.type());
		assertEquals(new GoalMineSpec(List.of("minecraft:dirt"), 1), request.goal().mineSpec());
	}

	@Test
	void mineBlocksRejectsInventoryItemIdsBeforeStartingTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
			"call_mine",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:raw_iron"],"quantity":1}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		assertTrue(result.contains("TOOL_ERROR: mine_blocks invalid_block_id minecraft:raw_iron"));
		assertTrue(result.contains("item id, not a block id"));
		assertTrue(executor.lastActiveTask.isEmpty());
		assertTrue(runtime.activeGoal().isEmpty());
	}

	@Test
	void mineBlocksIgnoresPickupEventsForCompletion() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.execute(new PlannerToolCall(
			"call_mine",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:dirt"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		runtime.onPlayerPickedUpItem("minecraft:dirt", 3);
		runtime.onPlayerMinedBlock("minecraft:dirt", 0, 64, 0);
		runtime.onPlayerMinedBlock("minecraft:dirt", 1, 64, 0);

		assertTrue(runtime.activeGoal().isPresent());
		assertFalse(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));

		runtime.onPlayerMinedBlock("minecraft:dirt", 2, 64, 0);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(runtime.activeGoal().isPresent());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(), request.goal(), TaskExecutionState.COMPLETED, "Goal reached", TaskTerminationCause.GOAL_REACHED
		));
		runtime.onClientTick(null);

		assertTrue(runtime.activeGoal().isEmpty());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
	}

	@Test
	void mineBlocksPickupDefaultsToSemanticOnlyEventPolicy() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.execute(new PlannerToolCall(
			"call_mine",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:cobblestone"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		runtime.onPlayerPickedUpItem("minecraft:cobblestone", 1);

		assertEquals(EventPolicyEffect.SEMANTIC_ONLY, runtime.lastEventPolicyDecision().effect());
		assertEquals("default-mining-pickup-semantic-only", runtime.lastEventPolicyDecision().matchedRuleId());
		assertEquals(1, runtime.recentEventPolicyInterventionCount());
	}

	@Test
	void ensureBlocksInInventoryPickupDefaultsToSemanticOnlyEventPolicy() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.execute(new PlannerToolCall(
			"call_ensure_blocks",
			"ensure_blocks_in_inventory",
			JsonParser.parseString("""
				{"blockIds":["minecraft:iron_ore"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		runtime.onPlayerPickedUpItem("minecraft:raw_iron", 1);

		assertEquals(EventPolicyEffect.SEMANTIC_ONLY, runtime.lastEventPolicyDecision().effect());
		assertEquals("default-mining-pickup-semantic-only", runtime.lastEventPolicyDecision().matchedRuleId());
		assertEquals(1, runtime.recentEventPolicyInterventionCount());
	}

	@Test
	void mineBlocksEarlyTerminalMismatchWarnsAndKeepsMining() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.execute(new PlannerToolCall(
			"call_mine",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:dirt"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);
		WorldTaskRequest firstAttempt = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			firstAttempt.taskId(),
			firstAttempt.goal(),
			TaskExecutionState.COMPLETED,
			"Goal reached",
			TaskTerminationCause.GOAL_REACHED
		));

		runtime.onClientTick(null);

		assertFalse(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK WARNING: mine_blocks broken_block_count_mismatch")
				&& turn.text().contains("brokenBlocks=0")
				&& turn.text().contains("requestedBlocks=3")
		));

		runtime.onClientTick(null);
		WorldTaskRequest secondAttempt = executor.lastActiveTask.orElseThrow();

		assertTrue(runtime.activeGoal().isPresent());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(new GoalMineSpec(List.of("minecraft:dirt"), 3), secondAttempt.goal().mineSpec());
	}

	@Test
	void ensureBlocksInInventoryToolRoutesAbsoluteMineGoal() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
				"call_ensure_blocks",
				"ensure_blocks_in_inventory",
				JsonParser.parseString("""
					{"blockIds":["minecraft:dirt"],"quantity":3}
					""").getAsJsonObject(),
				null,
				null
			)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("terminal result in a later observation"), result);
		assertEquals(WorldTaskType.MINE, request.type());
		assertEquals(new GoalMineSpec(List.of("minecraft:dirt"), 3), request.goal().mineSpec());
	}

	@Test
	void returnToSurfaceToolRoutesWorldTaskRequestWithDefaultFillerAndTowering() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("{}").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("useTowering=true"));
		assertEquals(WorldTaskType.RETURN_TO_SURFACE, request.type());
		ReturnToSurfaceStepArgs returnArgs = ((WorldTaskRequest.ReturnToSurface) request.task()).args();
		assertTrue(returnArgs.useTowering());
		assertEquals("none", returnArgs.targetKind());
		assertEquals(ReturnToSurfaceStepArgs.DEFAULT_FILLER_BLOCK_IDS, returnArgs.fillerBlockIds());
	}

	@Test
	void returnToSurfaceToolHonorsExplicitToweringFalse() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("""
				{"useTowering":false}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		assertTrue(result.contains("TOOL_ERROR"));
		assertTrue(result.contains("surface_target_unavailable"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void directPlannerJobUpdateDoesNotPreemptRunningReturnToSurface() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("{}").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);
		assertTrue(result.contains("useTowering=true"));
		assertEquals(WorldTaskType.RETURN_TO_SURFACE, executor.lastActiveTask.orElseThrow().type());

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.placeBlock(new BlockPlacementStepArgs(
					"minecraft:crafting_table",
					new GoalPosition(1, 64, 2, true),
					"auto",
					"air_or_replaceable"
				))
			),
			1L
		));
		runtime.onClientTick(null);

		assertEquals(ActiveJobType.RETURN_TO_SURFACE, runtime.activeJob().type());
		assertEquals(WorldTaskType.RETURN_TO_SURFACE, executor.lastActiveTask.orElseThrow().type());
	}

	@Test
	void plannerToolDoesNotPreemptRunningTaskExecutionWhenSemanticJobIsIdle() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"return-task",
			null,
			"ReturnToSurface",
			"towering:support_unavailable",
			null,
			null
		);
		runtime.onClientTick(null);
		runtime.recordWorldReadForTests(new BlockPos(1, 64, 2));

		String result = runtime.execute(new PlannerToolCall(
			"call_place",
			"place_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:crafting_table","targets":[{"targetPosition":{"x":1,"y":64,"z":2,"exactY":true},"placementMode":"air_or_replaceable"}]}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("TOOL_ERROR"));
		assertTrue(result.contains("active_task_in_progress"));
		assertTrue(result.contains("taskExecutionState=RUNNING"));
		assertEquals(ActiveJobType.IDLE, runtime.activeJob().type());
	}

	@Test
	void craftRecipeToolDoesNotPreemptRunningTaskExecutionWhenSemanticJobIsIdle() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			"return-task",
			null,
			"ReturnToSurface",
			"towering:support_unavailable",
			null,
			null
		);
		runtime.onClientTick(null);

		String result = runtime.execute(craftRecipeToolCall()).join();

		assertTrue(result.contains("TOOL_ERROR"));
		assertTrue(result.contains("active_task_in_progress"));
		assertTrue(result.contains("taskExecutionState=RUNNING"));
		assertEquals(ActiveJobType.IDLE, runtime.activeJob().type());
	}

	@Test
	void ensureBlocksInInventoryPrimitiveCompletionWarnsUntilInventorySatisfied() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.execute(new PlannerToolCall(
			"call_ensure_blocks",
			"ensure_blocks_in_inventory",
			JsonParser.parseString("""
				{"blockIds":["minecraft:dirt"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		runtime.onPlayerMinedBlock("minecraft:dirt", 0, 64, 0);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.COMPLETED,
			"Goal reached",
			TaskTerminationCause.GOAL_REACHED
		));

		runtime.onClientTick(null);

		assertTrue(runtime.dialogueSnapshot().recentTurns().stream().anyMatch(turn ->
			"system".equals(turn.speaker())
				&& turn.text().contains("TASK WARNING: ensure_blocks_in_inventory inventory_target_not_satisfied")
				&& turn.text().contains("brokenBlocks=1")
				&& turn.text().contains("itemCount=0")
				&& turn.text().contains("requestedItemCount=3")
		));
		assertFalse(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));

		runtime.onClientTick(null);
		WorldTaskRequest secondAttempt = executor.lastActiveTask.orElseThrow();

		assertTrue(runtime.activeGoal().isPresent());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"), secondAttempt.taskId());
	}

	@Test
	void mineBlocksExactBreakCountReleasesNextPlannerActionAfterCancellation() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		runtime.execute(new PlannerToolCall(
			"call_mine_blocks",
			"mine_blocks",
			JsonParser.parseString("""
				{"blockIds":["minecraft:stone"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		runtime.onPlayerMinedBlock("minecraft:stone", 0, 64, 0);
		runtime.onPlayerMinedBlock("minecraft:stone", 1, 64, 0);
		runtime.onPlayerMinedBlock("minecraft:stone", 2, 64, 0);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.CANCELLED,
			"Task cancelled",
			TaskTerminationCause.BARITONE_CANCELLED
		));

		runtime.onClientTick(null);

		assertEquals(ActiveJobStatus.COMPLETED, runtime.activeJob().status());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
		String result = runtime.execute(new PlannerToolCall(
			"call_navigate",
			"navigate_to",
			JsonParser.parseString("""
				{"x":1,"y":64,"z":1,"exactY":true}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("accepted queued"), result);
		assertFalse(result.contains("active_task_in_progress"), result);
		assertEquals(ActiveJobType.NAVIGATE_TO, runtime.activeJob().type());
	}

	@Test
	void collectSmeltedItemsToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.registerSmeltingOptionsForTests(List.of(testSmeltingOption("smelt:iron:nearby-1", 3)));
		String startResult = runtime.execute(new PlannerToolCall(
			"call_smelt",
			"smelt_items",
			JsonParser.parseString("""
				{"optionId":"smelt:iron:nearby-1","inputQuantity":1}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		String processId = extractProcessId(startResult);

		String result = runtime.execute(new PlannerToolCall(
			"call_collect_smelted",
			"collect_smelted_items",
			JsonParser.parseString("""
				{"processId":"%s","confirmationToken":"confirm-2"}
				""".formatted(processId)).getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertEquals(WorldTaskType.COLLECT_SMELTED_ITEMS, request.type());
		assertEquals(new WorldTaskRequest.CollectSmeltedItems(new CollectSmeltedItemsStepArgs(processId, "confirm-2")), request.task());
	}

	@Test
	void collectSmeltedItemsWithoutProcessUsesTrackedSmeltingProcess() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.registerSmeltingOptionsForTests(List.of(testSmeltingOption("smelt:iron:nearby-1", 3)));
		String startResult = runtime.execute(new PlannerToolCall(
			"call_smelt",
			"smelt_items",
			JsonParser.parseString("""
				{"optionId":"smelt:iron:nearby-1","inputQuantity":1}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		String processId = extractProcessId(startResult);

		String result = runtime.execute(new PlannerToolCall(
			"call_collect_smelted",
			"collect_smelted_items",
			JsonParser.parseString("""
				{}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("processId=" + processId));
		assertEquals(new WorldTaskRequest.CollectSmeltedItems(new CollectSmeltedItemsStepArgs(processId, null)), request.task());
	}

	@Test
	void physicalEpisodesDeliverObservedPositionAndTaskContextToPlanner() throws Exception {
		var runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		var payload = Map.<String,Object>of("kind","fall","phase","ended","position",Map.of("x",318,"y",-12,"z",279),
			"startContext",Map.of("taskId","nav-1"),"currentContext",Map.of("taskStatus","COMPLETED"));
		Field field = EmbodiedAgentRuntime.class.getDeclaredField("eventPipeline");
		field.setAccessible(true);
		var pipeline = (ai.moeru.airicraft.agent.events.AgentEventPipeline) field.get(runtime);
		pipeline.appendRaw(20, "player.physical", payload);
		var triggers = pipeline.drain(runtime::createPlannerTriggerForTests);
		assertEquals(1, triggers.size());
		assertEquals(payload, pipeline.plannerEventBuffer().query(null).events().getFirst().payload());
		var trigger = triggers.getFirst();
		assertEquals(PlannerTriggerType.SYSTEM,trigger.type());
		assertEquals("physical:fall",trigger.coalescingKey());
		assertTrue(trigger.text().contains("nav-1"));
		assertTrue(trigger.text().contains("-12"));
		assertTrue(trigger.text().contains("not proof of an involuntary cause"));
		setReflexSnapshot(runtime, reflexSnapshot(SurvivalReflexState.ACTIVE, "hold-1", "nav-1", null));
		pipeline.appendRaw(21, "player.physical", Map.of("kind", "burning", "phase", "started"));
		assertTrue(pipeline.drain(runtime::createPlannerTriggerForTests).isEmpty());
		assertEquals(2, pipeline.plannerEventBuffer().query(null).events().size());
		setReflexSnapshot(runtime, SurvivalReflexSnapshot.idle());
		pipeline.appendRaw(22, "player.physical", Map.of("kind", "burning", "phase", "ended"));
		assertEquals(1, pipeline.drain(runtime::createPlannerTriggerForTests).size());
	}

	@Test
	void smeltingOutputReadyEventCreatesSystemPlannerTrigger() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		PlannerTrigger trigger = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"smelting.output_ready",
			Map.of(
				"processId", "smelt-process-1",
				"optionId", "smelt:iron:nearby-1",
				"station", "minecraft:overworld@1,64,1",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1,
				"inputQuantity", 1
			)
		), new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true));

		assertEquals(PlannerTriggerType.SYSTEM, trigger.type());
		assertEquals("runtime", trigger.speaker());
		assertEquals(
			"Smelting output ready: processId=smelt-process-1 output=minecraft:iron_ingotx1 station=minecraft:overworld@1,64,1. Output still needs collection: call collect_smelted_items with this processId, then verify inventory.",
			trigger.text()
		);
	}

	@Test
	void actionGraphSuspensionTriggerPermitsUsefulWorkChatOrNoActionWithoutFiller() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		PlannerTrigger trigger = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"action_graph.goal_suspended",
			Map.of(
				"executionId", "action-graph-wheat",
				"pendingWatch", "wait_for_wheat_maturity"
			)
		), new EventRoutingProfile("action_graph.goal_suspended", true, PlannerTriggerType.SYSTEM, true));

		assertEquals(PlannerTriggerType.SYSTEM, trigger.type());
		assertEquals("action_graph", trigger.speaker());
		assertTrue(trigger.text().contains("one short chat message"));
		assertTrue(trigger.text().contains("start at most one useful new high-level goal"));
		assertTrue(trigger.text().contains("simply acknowledge without taking action"));
		assertTrue(trigger.text().contains("Do not invent filler work"));
		assertEquals("action_graph_suspended:action-graph-wheat", trigger.coalescingKey());
	}

	@Test
	void unknownActionGraphAcquisitionIdentifiesCapabilityFailureWithoutEndingObjective() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		PlannerTrigger trigger = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"action_graph.goal_terminal",
			Map.of(
				"executionId", "action-graph-seagrass",
				"state", "FAILED",
				"goal", "inventory.item|itemId=minecraft:seagrass|countAtLeast>=20",
				"failureCode", "unknown_acquisition_method",
				"message", "no registered acquisition method for inventory item minecraft:seagrass; no target search was started"
			)
		), new EventRoutingProfile("action_graph.goal_terminal", true, PlannerTriggerType.SYSTEM, true));

		assertEquals(PlannerTriggerType.SYSTEM, trigger.type());
		assertEquals("action_graph", trigger.speaker());
		assertTrue(trigger.text().contains("unknown_acquisition_method"));
		assertTrue(trigger.text().contains("Another observed supported action may still serve the objective"));
		assertTrue(trigger.text().contains("requested capability is unsupported"));
		assertEquals("action_graph_terminal:action-graph-seagrass", trigger.coalescingKey());
	}

	@Test
	void actionGraphFailureTriggerNamesFailedPrerequisiteInsteadOfOnlyTopLevelGoal() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		PlannerTrigger trigger = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"action_graph.goal_terminal",
			Map.of(
				"executionId", "action-graph-charcoal",
				"state", "FAILED",
				"goal", "inventory.item|itemId=minecraft:charcoal|countAtLeast>=1",
				"failureCode", "missing_fact",
				"message", "insufficient_illumination reason=loaded_target_unilluminated torchCount=0",
				"failedPrimitive", "mine_block",
				"failedTarget", "minecraft:cobblestone",
				"failedArgs", Map.of("itemId", "minecraft:cobblestone", "quantity", 8)
			)
		), new EventRoutingProfile("action_graph.goal_terminal", true, PlannerTriggerType.SYSTEM, true));

		assertTrue(trigger.text().contains("failedPrimitive=mine_block"), trigger.text());
		assertTrue(trigger.text().contains("failedTarget=minecraft:cobblestone"), trigger.text());
		assertTrue(trigger.text().contains("failedArgs={itemId=minecraft:cobblestone, quantity=8")
			|| trigger.text().contains("failedArgs={quantity=8, itemId=minecraft:cobblestone"), trigger.text());
	}

	@Test
	void successfulActionGraphTerminalDoesNotRetriggerPlanner() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		PlannerTrigger trigger = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"action_graph.goal_terminal",
			Map.of("executionId", "action-graph-dirt", "state", "SUCCEEDED")
		), new EventRoutingProfile("action_graph.goal_terminal", true, PlannerTriggerType.SYSTEM, true));

		assertEquals(null, trigger);
	}

	@Test
	void evaluationPreparationKeepsAutonomousTriggersSuppressedUntilGoalAdmission() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());

		runtime.finishEvaluation();
		PlannerTrigger suppressed = runtime.createPlannerTriggerForTests(new SemanticEvent(
			1L,
			20L,
			1000L,
			"smelting.output_ready",
			Map.of(
				"processId", "smelt-process-1",
				"station", "minecraft:overworld@1,64,1",
				"outputItemId", "minecraft:iron_ingot",
				"outputCount", 1
			)
		), new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true));
		PlannerTrigger explicitChat = runtime.createPlannerTriggerForTests(new SemanticEvent(
			2L,
			21L,
			1001L,
			"social.player_addressed_agent",
			Map.of(
				"player", "Player",
				"message", "@agent are you there?"
			)
		), new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true));

		assertNull(suppressed);
		assertEquals(PlannerTriggerType.CHAT, explicitChat.type());

		runtime.prepareForEvaluation();
		PlannerTrigger beforeGoalAdmission = runtime.createPlannerTriggerForTests(new SemanticEvent(
			3L,
			22L,
			1002L,
			"smelting.output_ready",
			Map.of(
				"processId", "smelt-process-2",
				"station", "minecraft:overworld@1,64,1",
				"outputItemId", "minecraft:gold_ingot",
				"outputCount", 1
			)
		), new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true));

		assertNull(beforeGoalAdmission, "Preparation must not let the planner run before the scenario goal exists");
	}

	@Test
	void attackEntityToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.execute(new PlannerToolCall(
			"call_attack",
			"attack_entity",
			JsonParser.parseString("""
				{"entityTypeId":"minecraft:sheep","mode":"hit_once"}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("terminal result in a later observation"), result);
		assertEquals(WorldTaskType.ATTACK_ENTITY, request.type());
		assertEquals(new EntityInteractionStepArgs(
			new EntitySelector(null, null, "minecraft:sheep"),
			null,
			EntityAttackMode.HIT_ONCE
		), ((WorldTaskRequest.AttackEntity) request.task()).args());
	}

	@Test
	void attackEntityToolResultUsesShortUuidToken() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.execute(new PlannerToolCall(
			"call_attack",
			"attack_entity",
			JsonParser.parseString("""
				{"uuid":"12345678-aaaa-4d9d-8b9d-fb24b98cb81d"}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("uuid=12345678"));
		assertFalse(result.contains("uuid=12345678-aaaa-4d9d-8b9d-fb24b98cb81d"));
		assertEquals(new EntityInteractionStepArgs(
			new EntitySelector("12345678-aaaa-4d9d-8b9d-fb24b98cb81d", null, null),
			null
		), ((WorldTaskRequest.AttackEntity) request.task()).args());
	}

	@Test
	void useEntityToolRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		String result = runtime.execute(new PlannerToolCall(
			"call_use",
			"use_entity",
			JsonParser.parseString("""
				{"name":"Dinner","itemId":"minecraft:shears"}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("terminal result in a later observation"), result);
		assertEquals(WorldTaskType.USE_ENTITY, request.type());
		assertEquals(new EntityInteractionStepArgs(
			new EntitySelector(null, "Dinner", null),
			"minecraft:shears"
		), ((WorldTaskRequest.UseEntity) request.task()).args());
	}

	@Test
	void blockModificationToolInspectsInsteadOfQueuingUnreadTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
			"call_place",
			"place_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:dirt","x":1,"y":64,"z":2}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		assertTrue(result.contains("blocked reason=target_not_inspected"));
		assertTrue(result.contains("Runtime converted this request to inspect_world first"));
		assertTrue(result.contains("Call place_block again"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void modificationFallbackInspectsAllMissingTargetsInOneResponse() {
		var first = new BlockPos(1, 64, 2);
		var second = new BlockPos(7, 64, 2);
		var known = new BlockPos(8, 64, 2);
		var ledger = new ai.moeru.airicraft.agent.llm.WorldReadLedger();
		ledger.recordObserved(List.of(known));
		var queried = new java.util.ArrayList<BlockPos>();
		String result = EmbodiedAgentRuntime.inspectMissingModificationTargets("place_block",
			List.of(first, second, known), ledger, args -> {
				BlockPos pos = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
				queried.add(pos);
				return new ai.moeru.airicraft.agent.llm.CurrentWorldQueryService.WorldQueryResult("observed=" + pos, List.of(pos));
			});
		assertEquals(List.of(first, second), queried);
		assertTrue(ledger.isFresh(first));
		assertTrue(ledger.isFresh(second));
		assertTrue(result.contains("blocked reason=target_not_inspected"));
		assertTrue(result.contains("Call place_block again"));
	}

	@Test
	void failedFallbackReadDoesNotAuthorizeOrClaimInspection() {
		var target = new BlockPos(1, 64, 2);
		var ledger = new ai.moeru.airicraft.agent.llm.WorldReadLedger();
		String result = EmbodiedAgentRuntime.inspectMissingModificationTargets("use_block", List.of(target), ledger,
			args -> new ai.moeru.airicraft.agent.llm.CurrentWorldQueryService.WorldQueryResult("WORLD_UNAVAILABLE", List.of()));
		assertFalse(ledger.isFresh(target));
		assertTrue(result.contains("uninspectedTargets=1"));
		assertFalse(result.contains("The target has now been inspected"));
	}

	@Test
	void blockModificationToolQueuesAfterFreshWorldRead() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 65, 2));

		CompletableFuture<String> resultFuture = runtime.execute(new PlannerToolCall(
			"call_use_block",
			"use_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:wheat_seeds","x":1,"y":65,"z":2,"expectedSupportBlockIds":["minecraft:farmland"],"expectedTargetMaterial":"air"}
				""").getAsJsonObject(),
			null,
			null
		));

		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, request.type());
		BlockUseStepArgs useArgs = ((WorldTaskRequest.UseBlock) request.task()).args();
		assertEquals("minecraft:wheat_seeds", useArgs.itemId());
		assertEquals(new GoalPosition(1, 65, 2, true), useArgs.targetPosition());
		assertEquals(List.of("minecraft:farmland"), useArgs.expectedSupportBlockIds());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"used block",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertTrue(result.contains("state=COMPLETED"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void blockModificationToolFailureWaitsForTerminalFeedback() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 65, 2));

		CompletableFuture<String> resultFuture = runtime.execute(new PlannerToolCall(
			"call_use_block",
			"use_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:stone_hoe","x":1,"y":65,"z":2,"expectedSupportBlockIds":["minecraft:dirt"],"expectedTargetMaterial":"air"}
				""").getAsJsonObject(),
			null,
			null
		));

		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.FAILED,
			"interaction_failed itemRaycastMatches=false",
			null
		));
		runtime.onClientTick(null);
		String result = resultFuture.join();

		assertTrue(result.contains("failed"));
		assertTrue(result.contains("state=FAILED"));
		assertTrue(result.contains("interaction_failed itemRaycastMatches=false"));
		assertFalse(result.contains("accepted queued"));
		runtime.onClientTick(null);
		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertTaskEvidence(runtime, "task.failed", Map.of("activeStepKind", "USE_BLOCK", "failure", "interaction_failed itemRaycastMatches=false"));
	}

	@Test
	void worldLeaveCompletesPendingBlockModificationToolResult() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		runtime.onWorldLeave();
		String result = resultFuture.join();

		assertTrue(result.contains("cancelled reason=world_left"), result);
		runtime.onWorldLeave();
		assertEquals(result, resultFuture.join());
	}

	@Test
	void shutdownCompletesPendingBlockModificationToolResult() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		runtime.shutdown();

		assertTrue(resultFuture.join().contains("cancelled reason=runtime_shutdown"));
	}

	@Test
	void finishEvaluationCompletesPendingBlockModificationToolResult() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		runtime.finishEvaluation();

		assertTrue(resultFuture.join().contains("cancelled reason=evaluation_finished"));
	}

	@Test
	void plannerResetCompletesPendingBlockModificationToolResult() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		runtime.onChatReceived("Alice", "@agent reset");

		assertTrue(resultFuture.join().contains("cancelled reason=planner_reset"));
	}

	@Test
	void replacementCompletesOnlyTheOldBlockModificationToolResult() throws Exception {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> firstResult = startPendingUseBlock(runtime, executor, 1);
		WorldTaskRequest firstRequest = executor.lastActiveTask.orElseThrow();
		activeJobRuntime(runtime).clear();
		setTaskSnapshot(runtime, TaskSnapshot.idle());
		setTaskExecutionSnapshot(runtime, TaskExecutionSnapshot.idle());
		runtime.recordWorldReadForTests(new BlockPos(2, 65, 2));
		CompletableFuture<String> secondResult = assertTimeoutPreemptively(
			Duration.ofSeconds(1),
			() -> runtime.execute(useBlockToolCall("call_use_block_2", 2))
		);

		assertTrue(firstResult.isDone(), "secondResult=" + (secondResult.isDone() ? secondResult.getNow("<missing>") : "pending"));
		String firstToolResult = firstResult.getNow("<missing>");
		assertTrue(firstToolResult.contains("cancelled reason=superseded"), firstToolResult);
		assertFalse(secondResult.isDone());
		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> runtime.onClientTick(null));

		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			firstRequest.taskId(),
			firstRequest.goal(),
			TaskExecutionState.COMPLETED,
			"stale first result",
			null
		));
		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> runtime.onClientTick(null));
		assertFalse(secondResult.isDone());

		WorldTaskRequest currentRequestAfterStaleEvent = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			currentRequestAfterStaleEvent.taskId(),
			currentRequestAfterStaleEvent.goal(),
			TaskExecutionState.COMPLETED,
			"second result",
			null
		));
		assertTimeoutPreemptively(Duration.ofSeconds(1), () -> runtime.onClientTick(null));
		assertTrue(secondResult.get(1, TimeUnit.SECONDS).contains("second result"));
	}

	@Test
	void batchedBlockModificationToolInspectsInsteadOfQueuingUnreadTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 65, 2));

		String result = runtime.execute(new PlannerToolCall(
			"call_use_block",
			"use_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:wheat_seeds","targets":[{"x":1,"y":65,"z":2},{"x":2,"y":65,"z":2}]}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		assertTrue(result.contains("blocked reason=target_not_inspected"));
		assertTrue(result.contains("Runtime converted this request to inspect_world first"));
		assertTrue(result.contains("Call use_block again"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void batchedBlockModificationToolQueuesAfterFreshWorldReads() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 64, 2));
		runtime.recordWorldReadForTests(new BlockPos(2, 64, 2));

		CompletableFuture<String> resultFuture = runtime.execute(new PlannerToolCall(
			"call_place",
			"place_block",
			JsonParser.parseString("""
				{
				  "itemId":"minecraft:dirt",
				  "facePreference":"down",
				  "targets":[
				    {"x":1,"y":64,"z":2},
				    {"x":2,"y":64,"z":2,"facePreference":"north","requireCurrentTargetMaterial":"air"}
				  ]
				}
				""").getAsJsonObject(),
			null,
			null
		));
		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.PLACE_BLOCK, request.type());
		BlockPlacementStepArgs placementArgs = ((WorldTaskRequest.PlaceBlock) request.task()).args();
		assertEquals("minecraft:dirt", placementArgs.itemId());
		assertEquals(2, placementArgs.targets().size());
		assertEquals(new GoalPosition(1, 64, 2, true), placementArgs.targets().get(0).targetPosition());
		assertEquals("down", placementArgs.targets().get(0).facePreference());
		assertEquals(new GoalPosition(2, 64, 2, true), placementArgs.targets().get(1).targetPosition());
		assertEquals("north", placementArgs.targets().get(1).facePreference());
		assertEquals("air", placementArgs.targets().get(1).requiredTargetMaterial());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"placed blocks",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertTrue(result.contains("targets=2"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void placeBlockDispatchesAfterUnrelatedCraftCompletionSnapshot() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			"previous-craft-task",
			null,
			"Crafting",
			"crafted",
			null,
			null
		);
		runtime.onClientTick(null);
		executor.forcedSnapshot = null;
		runtime.recordWorldReadForTests(new BlockPos(1, 64, 2));

		CompletableFuture<String> resultFuture = runtime.execute(new PlannerToolCall(
			"call_place_after_craft",
			"place_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:crafting_table","x":1,"y":64,"z":2,"requireCurrentTargetMaterial":"air"}
				""").getAsJsonObject(),
			null,
			null
		));
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.PLACE_BLOCK, request.type());
		assertFalse(resultFuture.isDone());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"placed crafting table",
			TaskTerminationCause.GOAL_REACHED
		));
		runtime.onClientTick(null);

		assertTrue(resultFuture.join().contains("completed"));
		assertEquals(request.taskId(), runtime.taskSnapshot().taskId());
	}

	@Test
	void blockModificationFallbackAcceptsMatchingTerminalSnapshotWhenEventIsMissing() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			request.taskId(),
			null,
			"BlockInteraction",
			"placed without event",
			null,
			null
		);

		runtime.onClientTick(null);
		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);

		assertTrue(resultFuture.join().contains("completed"));
	}

	@Test
	void blockModificationFallbackRejectsWrongTaskWithTheSameStepKind() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			"different-use-block-task",
			null,
			"BlockInteraction",
			"stale completion",
			null,
			null
		);

		runtime.onClientTick(null);
		runtime.onClientTick(null);

		assertFalse(resultFuture.isDone());
		assertEquals(request.taskId(), executor.lastActiveTask.orElseThrow().taskId());
	}

	@Test
	void blockModificationFallbackRejectsAStalePriorTaskSnapshotAfterReplacement() throws Exception {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> firstResult = startPendingUseBlock(runtime, executor, 1);
		activeJobRuntime(runtime).clear();
		setTaskSnapshot(runtime, TaskSnapshot.idle());
		setTaskExecutionSnapshot(runtime, TaskExecutionSnapshot.idle());
		runtime.recordWorldReadForTests(new BlockPos(2, 65, 2));
		CompletableFuture<String> secondResult = runtime.execute(useBlockToolCall("call_use_block_2", 2));
		runtime.onClientTick(null);
		WorldTaskRequest secondRequest = executor.lastActiveTask.orElseThrow();

		executor.forcedSnapshot = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			"stale-first-task",
			null,
			"BlockInteraction",
			"stale completion",
			null,
			null
		);
		assertTrue(firstResult.isDone());
		runtime.onClientTick(null);
		runtime.onClientTick(null);

		assertFalse(secondResult.isDone());
	}

	@Test
	void blockModificationTerminalEventAndSnapshotCompleteOnlyOnce() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"event completion",
			null
		));
		runtime.onClientTick(null);
		String result = resultFuture.join();
		runtime.onClientTick(null);

		assertTrue(result.contains("event completion"));
		assertEquals(result, resultFuture.join());
	}

	@Test
	void blockModificationToolTimesOutWithoutTerminalEvidence() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = startPendingUseBlock(runtime, executor, 1);
		for (int tick = 0; tick < EmbodiedAgentRuntime.BLOCK_MODIFICATION_TOOL_RESULT_TIMEOUT_TICKS; tick++) {
			runtime.onClientTick(null);
		}

		assertTrue(resultFuture.join().contains("pending_timeout"));
	}

	@Test
	void breakBlocksToolInspectsInsteadOfQueuingUnreadTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
			"call_break",
			"break_blocks",
			JsonParser.parseString("""
				{"targets":[{"x":1,"y":64,"z":2,"expectedBlockIds":["minecraft:grass_block"]}]}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		assertTrue(result.contains("blocked reason=target_not_inspected"));
		assertTrue(result.contains("Runtime converted this request to inspect_world first"));
		assertTrue(result.contains("Call break_blocks again"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void breakBlocksToolQueuesAfterFreshWorldRead() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.recordWorldReadForTests(new BlockPos(1, 64, 2));

		CompletableFuture<String> resultFuture = runtime.execute(new PlannerToolCall(
			"call_break",
			"break_blocks",
			JsonParser.parseString("""
				{"targets":[{"x":1,"y":64,"z":2,"expectedBlockIds":["minecraft:grass_block","minecraft:dirt"]}]}
				""").getAsJsonObject(),
			null,
			null
		));
		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.BREAK_BLOCKS, request.type());
		BlockBreakStepArgs breakArgs = ((WorldTaskRequest.BreakBlocks) request.task()).args();
		assertEquals(new GoalPosition(1, 64, 2, true), breakArgs.targets().getFirst().position());
		assertEquals(List.of("minecraft:grass_block", "minecraft:dirt"), breakArgs.targets().getFirst().expectedBlockIds());
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"broke blocks",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void manualAttackEntitySubmitRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.submitAttackEntity(
			new EntityInteractionStepArgs(new EntitySelector(null, null, "minecraft:sheep"), null),
			"bridge_debug"
		);
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.ATTACK_ENTITY, request.type());
		assertEquals(new WorldTaskRequest.AttackEntity(new EntityInteractionStepArgs(new EntitySelector(null, null, "minecraft:sheep"), null)), request.task());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void manualUseEntitySubmitRoutesWorldTaskRequest() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.submitUseEntity(
			new EntityInteractionStepArgs(new EntitySelector(null, "Dinner", null), "minecraft:shears"),
			"bridge_debug"
		);
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertEquals(WorldTaskType.USE_ENTITY, request.type());
		assertEquals(new WorldTaskRequest.UseEntity(new EntityInteractionStepArgs(new EntitySelector(null, "Dinner", null), "minecraft:shears")), request.task());
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
	}

	@Test
	void commonCraftReceiptPublishesTerminalWorkBeforeCompletingFuture() {
		for (TaskExecutionState state : List.of(TaskExecutionState.COMPLETED, TaskExecutionState.FAILED)) {
			var executor = new FakeWorldTaskExecutor();
			var runtime = EmbodiedAgentRuntime.createForTests(executor);
			runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
			try {
				var future = runtime.executePlannerAction(craftRecipeToolCall());
				runtime.onClientTick(null);
				var request = executor.lastActiveTask.orElseThrow();
				executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(request.taskId(), null, state, "observed result", null));
				runtime.onClientTick(null);
				var receipt = JsonParser.parseString(future.join().substring(future.join().indexOf('{'))).getAsJsonObject();
				assertEquals(state == TaskExecutionState.COMPLETED ? "SUCCEEDED" : "FAILED", receipt.get("state").getAsString());
				assertTrue(receipt.get("accepted").getAsBoolean());
				assertEquals(state.name(), receipt.get("phase").getAsString());
			} finally { runtime.shutdown(); }
		}
	}

	@Test
	void craftRecipeToolResultWaitsForTerminalFeedback() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());

		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"crafted",
			null
		));
		runtime.onClientTick(null);

		String result = resultFuture.join();
		assertTrue(result.contains("completed"));
		assertTrue(result.contains("state=COMPLETED"));
		assertFalse(result.contains("accepted queued"));
	}

	@Test
	void craftSnapshotFallbackAcceptsMatchingTaskIdentity() throws Exception {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());
		WorldTaskRequest request = runtimeTaskRequest(runtime, executor);

		invokeCraftSnapshotFallback(runtime, terminalCraftSnapshot(request.taskId()));

		assertTrue(resultFuture.join().contains("completed"));
	}

	@Test
	void craftSnapshotFallbackRejectsWrongTaskWithTheSameStepKind() throws Exception {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());
		WorldTaskRequest request = runtimeTaskRequest(runtime, executor);

		invokeCraftSnapshotFallback(runtime, terminalCraftSnapshot("different-craft-task"));

		assertFalse(resultFuture.isDone());
		assertEquals(request.taskId(), executor.lastActiveTask.orElseThrow().taskId());
	}

	@Test
	void craftSnapshotFallbackRejectsCompatibilitySnapshotWithoutTaskIdentity() throws Exception {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());
		runtimeTaskRequest(runtime, executor);

		invokeCraftSnapshotFallback(runtime, new TaskSnapshot(
			TaskState.COMPLETED,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			LedgerStepKind.CRAFT_RECIPE,
			null,
			1L
		));

		assertFalse(resultFuture.isDone());
	}

	@Test
	void craftSnapshotFallbackRejectsStalePriorTaskAfterReplacement() throws Exception {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> firstResult = runtime.execute(craftRecipeToolCall());
		WorldTaskRequest firstRequest = runtimeTaskRequest(runtime, executor);
		activeJobRuntime(runtime).clear();
		setTaskSnapshot(runtime, TaskSnapshot.idle());
		setTaskExecutionSnapshot(runtime, TaskExecutionSnapshot.idle());

		CompletableFuture<String> secondResult = runtime.execute(craftRecipeToolCall());
		WorldTaskRequest secondRequest = runtimeTaskRequest(runtime, executor);

		assertTrue(firstResult.join().contains("superseded"));
		invokeCraftSnapshotFallback(runtime, terminalCraftSnapshot(firstRequest.taskId()));
		assertFalse(secondResult.isDone());
		invokeCraftSnapshotFallback(runtime, terminalCraftSnapshot(secondRequest.taskId()));
		assertTrue(secondResult.join().contains("completed"));
	}

	@Test
	void craftTerminalEventAndSnapshotCompleteOnlyOnce() throws Exception {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());
		WorldTaskRequest request = runtimeTaskRequest(runtime, executor);
		invokeCraftTerminalEvent(runtime, new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.COMPLETED,
			"terminal event",
			null
		));
		invokeCraftSnapshotFallback(runtime, terminalCraftSnapshot(request.taskId()));

		assertTrue(resultFuture.join().contains("message=terminal event"));
	}

	@Test
	void plannerResetCancelsPendingCraftToolResult() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());
		runtime.onChatReceived("Alice", "@agent reset");

		assertTrue(resultFuture.join().contains("cancelled reason=planner_reset"));
	}

	@Test
	void inventoryTaskUpdateSnapshotFormatsPlannerVisibleInventoryEvidence() {
		String result = EmbodiedAgentRuntime.formatInventorySnapshotForTaskUpdate(new WorldEvidence(
			Map.of(),
			Map.of("minecraft:stone_pickaxe", 1, "minecraft:stick", 4),
			Map.of(),
			"minecraft:overworld",
			0,
			64,
			0,
			"minecraft:stone_pickaxe",
			2,
			List.of("0=minecraft:stickx4", "1=empty", "2=minecraft:stone_pickaxex1"),
			10L
		));

		assertTrue(result.contains("Carrying 4 stick, stone_pickaxe."));
		assertTrue(result.contains("stone_pickaxe in third slot (selected)"));
		assertTrue(result.contains("Main hand: stone_pickaxe."));
		assertTrue(result.contains("4 stick in first slot"));
		assertTrue(result.contains("Unobserved slots unknown."));
	}

	@Test
	void craftingProgressDoesNotWakePlannerWhileCraftToolResultPending() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());
		runtime.onPlayerCraftedItem("minecraft:stick", 4);

		AgentDebugTimelineEntry route = runtime.debugTimeline(null).entries().stream()
			.filter(entry -> "event_pipeline".equals(entry.domain()))
			.filter(entry -> "crafting.item_crafted".equals(entry.payload().get("eventType")))
			.findFirst()
			.orElseThrow();

		assertFalse(resultFuture.isDone());
		assertEquals(true, route.payload().get("emitSemantic"));
		assertEquals(false, route.payload().get("emitTrigger"));
	}

	@Test
	void craftRecipeToolResultTimesOutWhileLeavingTaskRunning() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());

		for (int i = 0; i <= EmbodiedAgentRuntime.CRAFT_TOOL_RESULT_TIMEOUT_TICKS; i++) {
			runtime.onClientTick(null);
		}

		String result = resultFuture.join();
		assertTrue(result.contains("pending_timeout"));
		assertFalse(result.contains("accepted queued"));
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
		assertTrue(executor.lastActiveTask.isPresent());
	}

	@Test
	void craftRecipePrimitiveFailureEmitsFailureReasonToPlanner() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		CompletableFuture<String> resultFuture = runtime.execute(craftRecipeToolCall());
		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();

		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			null,
			TaskExecutionState.FAILED,
			"missing_ingredients",
			null
		));
		runtime.onClientTick(null);
		String result = resultFuture.join();
		runtime.onClientTick(null);

		assertTrue(result.contains("failed"));
		assertTrue(result.contains("missing_ingredients"));
		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertEquals("missing_ingredients", runtime.taskSnapshot().lastFailure());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTaskEvidence(runtime, "task.failed", Map.of("activeStepKind", "CRAFT_RECIPE", "failure", "missing_ingredients"));
	}

	@Test
	void directGoalFailureEmitsFailureReasonToPlanner() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());

		String result = runtime.execute(new PlannerToolCall(
			"call_nav",
			"navigate_to",
			JsonParser.parseString("""
				{"x":-30,"y":115,"z":-55,"exactY":false}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		assertTrue(result.contains("accepted"));

		runtime.onClientTick(null);
		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			"CALC_FAILED",
			TaskTerminationCause.CALCULATION_FAILED
		));
		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTaskEvidence(runtime, "task.failed", Map.of("goalType", "NAVIGATE_TO", "message", "CALC_FAILED", "terminationCause", "CALCULATION_FAILED"));
	}

	@Test
	void givePlayerToolRejectsMissingNearbyTargetBeforeQueuingTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		String result = runtime.execute(new PlannerToolCall(
			"call_give",
			"give_player",
			JsonParser.parseString("""
				{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("target_not_nearby"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void followGoalRemainsActiveAfterNearbyTargetIsLost() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.injectNearbyPlayerForTests("Alice", new Vec3d(8.0D, 64.0D, 0.0D));
		runtime.injectGoalForTests(new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alice", 10L, "test"));
		runtime.onClientTick(null);

		runtime.disconnectNearbyPlayerForTests("Alice");
		runtime.onClientTick(null);

		assertTrue(runtime.activeGoal().isPresent());
		assertEquals(GoalType.FOLLOW_PLAYER, runtime.activeGoal().orElseThrow().type());
		assertEquals("Alice", runtime.activeGoal().orElseThrow().targetPlayer());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "follow.target_lost".equals(event.type())));
	}

	@Test
	void givePlayerToolRejectsFarTargetBeforeQueuingTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.injectNearbyPlayerForTests("Alice", new Vec3d(5.0D, 0.0D, 0.0D));

		String result = runtime.execute(new PlannerToolCall(
			"call_give",
			"give_player",
			JsonParser.parseString("""
				{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("target_not_nearby"));
		assertTrue(executor.lastActiveTask.isEmpty());
	}

	@Test
	void givePlayerToolRoutesDropTaskForNearbyTarget() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectNearbyPlayerForTests("Alice", new Vec3d(2.0D, 0.0D, 0.0D));

		String result = runtime.execute(new PlannerToolCall(
			"call_give",
			"give_player",
			JsonParser.parseString("""
				{"targetPlayer":"Alice","itemId":"minecraft:oak_log","quantity":2}
				""").getAsJsonObject(),
			null,
			null
		)).join();
		runtime.onClientTick(null);

		WorldTaskRequest request = executor.lastActiveTask.orElseThrow();
		assertTrue(result.contains("accepted"));
		assertTrue(result.contains("queued"));
		assertTrue(result.contains("does not mean completed"));
		assertTrue(result.contains("terminal result in a later observation"), result);
		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(new WorldTaskRequest.DropItems(new DropItemsStepArgs("minecraft:oak_log", 2, "Alice")), request.task());
	}

	@Test
	void terminalTaskEventCreatesSemanticEventAndDialogueTaskUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 8),
			20L,
			"test"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			"mine-task",
			goal,
			TaskExecutionState.COMPLETED,
			"Goal reached",
			TaskTerminationCause.GOAL_REACHED
		));

		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(goal);

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.completed".equals(event.type())));
		assertTaskEvidence(runtime, "task.completed", Map.of());
	}

	@Test
	void directGoalTerminalFailureCreatesDialogueTaskUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		GoalSnapshot goal = new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(12, 64, -8, true),
			null,
			20L,
			"test"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			"navigate-task",
			goal,
			TaskExecutionState.FAILED,
			"Path calculation failed",
			TaskTerminationCause.CALCULATION_FAILED
		));

		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectGoalForTests(goal);

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTaskEvidence(runtime, "task.failed", Map.of("goalType", "NAVIGATE_TO", "message", "Path calculation failed"));
	}

	@Test
	void submitTaskPlannerResponseThreadsTaskSnapshotIntoRuntimeSnapshot() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"On it.",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertEquals(TaskType.COLLECT_RESOURCE, runtime.snapshot().task().spec().type());
		assertTrue(runtime.snapshot().taskExecution().state() == TaskExecutionState.IDLE
			|| runtime.snapshot().taskExecution().state() == TaskExecutionState.RUNNING);
	}

	@Test
	void submitTaskPopulatesCollectResourceDebugProbe() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), "bridge_debug");

		assertTrue(runtime.debugCollectResourceState().active());
		assertEquals("WOOD_LOGS", runtime.debugCollectResourceState().resourceKind());
		assertEquals(16, runtime.debugCollectResourceState().targetQuantity());
		assertFalse(runtime.debugTimeline(null).entries().isEmpty());
	}

	@Test
	void missionUpdatePlannerResponseThreadsLedgerIntoRuntimeSnapshot() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
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
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Starting the mission.",
			new DialogueIntent(
				DialogueIntentType.MISSION_UPDATE,
				null,
				null,
				null,
				null,
				null,
				ledger
			),
			20L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertEquals("mission-wood-1", runtime.snapshot().task().mission().missionId());
		assertEquals("collect_logs", runtime.snapshot().task().activeStepId());
		assertEquals(ledger, runtime.snapshot().task().ledger());
	}

	@Test
	void directGoalSubmissionDoesNotPreemptActiveTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SET_GOAL,
				GoalType.NAVIGATE_TO,
				null,
				new GoalPosition(1, 64, 1, true),
				null,
				null
			),
			30L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertTrue(runtime.activeGoal().isEmpty());
	}

	@Test
	void directJobUpdateDoesNotPreemptActiveTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.navigateTo(new GoalPosition(1, 64, 1, true))
			),
			30L
		));

		assertEquals(TaskState.QUEUED, runtime.snapshot().task().state());
		assertTrue(runtime.activeGoal().isEmpty());
	}

	@Test
	void plannerDirectGoalToolDoesNotPreemptActiveCollectResourceTask() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideBlockAcquisitionsForTests(BlockAcquisitionTestFixtures.survival());
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), "planner_tool");
		runtime.onClientTick(null);
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());

		String result = runtime.execute(new PlannerToolCall(
			"call_nav",
			"navigate_to",
			JsonParser.parseString("""
				{"x":71,"y":70,"z":-299,"exactY":false}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(result.contains("TOOL_ERROR: navigate_to denied"));
		assertTrue(result.contains("active_task_in_progress"));
		String ensureResult = runtime.execute(new PlannerToolCall(
			"call_ensure",
			"ensure_blocks_in_inventory",
			JsonParser.parseString("""
				{"blockIds":["minecraft:oak_log"],"quantity":3}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(ensureResult.contains("TOOL_ERROR: ensure_blocks_in_inventory denied"));
		assertTrue(ensureResult.contains("active_task_in_progress"));
		String returnResult = runtime.execute(new PlannerToolCall(
			"call_return",
			"return_to_surface",
			JsonParser.parseString("""
				{"useTowering":true}
				""").getAsJsonObject(),
			null,
			null
		)).join();

		assertTrue(returnResult.contains("TOOL_ERROR: return_to_surface denied"));
		assertTrue(returnResult.contains("active_task_in_progress"));
		assertEquals(TaskState.RUNNING, runtime.taskSnapshot().state());
		assertEquals(TaskType.COLLECT_RESOURCE, runtime.taskSnapshot().spec().type());
		assertEquals(GoalType.MINE_BLOCKS, runtime.activeGoal().orElseThrow().type());
	}

	@Test
	void submitTaskClearsPreviouslyActiveDirectGoal() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			10L,
			"test"
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		assertTrue(runtime.activeGoal().isEmpty());
		assertEquals(TaskState.QUEUED, runtime.taskSnapshot().state());
	}

	@Test
	void semanticTaskFailureEmitsTaskEventAndInternalUpdate() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideBlockAcquisitionsForTests(BlockAcquisitionTestFixtures.survival());
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"",
			new DialogueIntent(
				DialogueIntentType.SUBMIT_TASK,
				null,
				null,
				null,
				null,
				new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4)
			),
			20L
		));

		GoalSnapshot goal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(List.of("minecraft:oak_log"), 4),
			21L,
			"task_runtime"
		);
		executor.nextTerminalEvent = Optional.of(new TaskTerminalEvent(
			"mine-task",
			goal,
			TaskExecutionState.FAILED,
			"Path calculation failed",
			TaskTerminationCause.CALCULATION_FAILED
		));

		for (int tick = 0; tick < 24; tick++) {
			runtime.onClientTick(null);
		}

		assertEquals(TaskState.FAILED, runtime.taskSnapshot().state());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.failed".equals(event.type())));
		assertTaskEvidence(runtime, "task.failed", Map.of("state", "FAILED"));
	}

	@Test
	void collectResourceLeavesTargetAndDropDiscoveryToItsExecutor() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideBlockAcquisitionsForTests(BlockAcquisitionTestFixtures.survival());
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Got it, collecting 5 logs now.",
			new DialogueIntent(
				DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.collectResource(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 5))
			),
			20L
		));

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().noneMatch(event -> "task.blocked".equals(event.type())));
		assertEquals(ActiveJobStatus.RUNNING, runtime.activeJob().status());
	}

	@Test
	void directGoalEventsAreNotSuppressedAfterTaskHasAlreadyTerminated() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		));
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 4), "bridge_debug");
		runtime.cancelTask("user_cancelled");
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.NAVIGATE_TO,
			null,
			new GoalPosition(1, 64, 1, true),
			null,
			30L,
			"test"
		));

		runtime.onClientTick(null);

		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.started".equals(event.type())));
	}

	@Test
	void clearGoalEventEmitsWhenGoalAlreadyClearedByTaskRuntime() {
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(new FakeWorldTaskExecutor());
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"Alice",
			null,
			null,
			10L,
			"test"
		));
		runtime.injectGoalForTests(null);

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Stopping.",
			new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
			20L
		));

		SemanticEvent event = runtime.recentEvents(null).events().stream()
			.filter(current -> "planner.goal_cleared".equals(current.type()))
			.findFirst()
			.orElseThrow();
		assertEquals("planner_response", event.payload().get("source"));
	}

	@Test
	void cancelTaskRecordsCancelledEventImmediately() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);

		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), "bridge_debug");
		runtime.cancelTask("user_cancelled");

		assertEquals(TaskState.CANCELLED, runtime.taskSnapshot().state());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "task.cancelled".equals(event.type())));
	}

	@Test
	void clearGoalResponseRecordsEventWhenGoalAlreadyClearedByRuntime() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.injectGoalForTests(new GoalSnapshot(
			GoalType.FOLLOW_PLAYER,
			"ObserveAlice",
			null,
			null,
			10L,
			"test"
		));
		assertTrue(runtime.activeGoal().isPresent());

		runtime.cancelTask("target_lost");
		assertTrue(runtime.activeGoal().isEmpty());
		long baselineSeqNo = runtime.latestEventSeqNo();

		runtime.injectDialogueResponseForTests(new DialogueResponse(
			"Stopping.",
			new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
			20L
		));

		SemanticEvent event = runtime.recentEvents(baselineSeqNo).events().stream()
			.filter(candidate -> "planner.goal_cleared".equals(candidate.type()))
			.findFirst()
			.orElseThrow();
		assertEquals(Boolean.TRUE, event.payload().get("alreadyClear"));
		assertEquals("planner_response", event.payload().get("source"));
	}

	@Test
	void deathIsAHardCancellationBoundaryAndRejectsNewActions() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideBlockAcquisitionsForTests(BlockAcquisitionTestFixtures.survival());
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 2), "test");
		runtime.onClientTick(null);
		ActionGraphExecutionSnapshot graph = runtime.startActionGoal(ActionGoal.inventoryItem("minecraft:bread", 1), "test");

		runtime.overrideSessionSnapshotForTests(deadRemoteSession());
		runtime.onClientTick(null);

		assertEquals(TaskState.CANCELLED, runtime.taskSnapshot().state());
		assertEquals(ActionGraphExecutionState.CANCELLED, runtime.actionGraphExecution(graph.executionId()).execution().state());
		assertEquals(1, executor.onWorldLeaveCalls);
		assertEquals(List.of("Root", "WaitForRespawn"), runtime.behaviorTreeSnapshot().activeNodePath());
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "player.actions_cancelled".equals(event.type())));

		BridgeUnavailableException exception = assertThrows(
			BridgeUnavailableException.class,
			() -> runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), "test")
		);
		assertEquals("player_dead", exception.code());
	}

	@Test
	void fatalHealthPacketClosesActuationGateBeforeNextTick() {
		FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
		EmbodiedAgentRuntime runtime = EmbodiedAgentRuntime.createForTests(executor);
		runtime.overrideSessionSnapshotForTests(loadedRemoteSession());
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 2), "test");

		runtime.onPlayerHealthUpdated(true, 10.0F, 0.0F);

		assertTrue(runtime.sessionSnapshot().requiresRespawn());
		assertFalse(runtime.sessionSnapshot().companionActuationAllowed());
		assertEquals(TaskState.CANCELLED, runtime.taskSnapshot().state());
		assertEquals(1, executor.onWorldLeaveCalls);
		assertTrue(runtime.recentEvents(null).events().stream().anyMatch(event -> "player.died".equals(event.type())));
		assertThrows(
			BridgeUnavailableException.class,
			() -> runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), "test")
		);
	}

	private static final class FakeWorldTaskExecutor implements WorldTaskExecutor {
		private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
		private TaskExecutionSnapshot forcedSnapshot;
		private Optional<TaskTerminalEvent> nextTerminalEvent = Optional.empty();
		private Optional<WorldTaskRequest> lastActiveTask = Optional.empty();
		private int onWorldLeaveCalls;

		@Override
		public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
			lastActiveTask = activeTask;
			if (forcedSnapshot != null) {
				snapshot = forcedSnapshot;
			}
			else if (!sessionSnapshot.companionActuationAllowed()) {
				snapshot = new TaskExecutionSnapshot(
					TaskExecutionState.PAUSED_BY_SESSION_GATE,
					activeTask.map(WorldTaskRequest::taskId).orElse(null),
					activeTask.map(WorldTaskRequest::goal).orElse(null),
					null,
					null,
					null,
					null
				);
			}
			else if (nextTerminalEvent.isPresent()) {
				TaskTerminalEvent terminalEvent = nextTerminalEvent.get();
				snapshot = new TaskExecutionSnapshot(
					terminalEvent.terminalState(),
					terminalEvent.taskId(),
					terminalEvent.goal(),
					null,
					terminalEvent.message(),
					null,
					terminalEvent.terminationCause()
				);
			}
			else {
				snapshot = new TaskExecutionSnapshot(
					activeTask.isPresent() ? TaskExecutionState.RUNNING : TaskExecutionState.IDLE,
					activeTask.map(WorldTaskRequest::taskId).orElse(null),
					activeTask.map(WorldTaskRequest::goal).orElse(null),
					null,
					null,
					null,
					null
				);
			}

			Optional<TaskTerminalEvent> result = nextTerminalEvent;
			nextTerminalEvent = Optional.empty();
			return result;
		}

		@Override
		public TaskExecutionSnapshot snapshot() {
			return snapshot;
		}

		@Override
		public void onWorldLeave() {
			onWorldLeaveCalls++;
			snapshot = TaskExecutionSnapshot.idle();
		}

		@Override
		public void shutdown() {
			snapshot = TaskExecutionSnapshot.idle();
		}
	}

	private static WorldTaskRequest runtimeTaskRequest(EmbodiedAgentRuntime runtime, FakeWorldTaskExecutor executor) {
		runtime.onClientTick(null);
		return executor.lastActiveTask.orElseThrow();
	}

	private static TaskSnapshot terminalCraftSnapshot(String taskId) {
		return new TaskSnapshot(
			TaskState.COMPLETED,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			LedgerStepKind.CRAFT_RECIPE,
			null,
			1L,
			taskId
		);
	}

	private static void invokeCraftSnapshotFallback(EmbodiedAgentRuntime runtime, TaskSnapshot snapshot) throws Exception {
		var method = EmbodiedAgentRuntime.class.getDeclaredMethod("completePendingCraftToolResultFromTaskSnapshot", TaskSnapshot.class);
		method.setAccessible(true);
		method.invoke(runtime, snapshot);
	}

	private static void invokeCraftTerminalEvent(EmbodiedAgentRuntime runtime, TaskTerminalEvent event) throws Exception {
		var method = EmbodiedAgentRuntime.class.getDeclaredMethod("completePendingCraftToolResult", TaskTerminalEvent.class);
		method.setAccessible(true);
		method.invoke(runtime, event);
	}

	private static PlannerToolCall craftRecipeToolCall() {
		return new PlannerToolCall(
			"call_craft",
			"craft_recipe",
			JsonParser.parseString("""
				{"recipeId":"oak_planks_x2_to_stick","times":1}
				""").getAsJsonObject(),
			null,
			null
		);
	}

	private static CompletableFuture<String> startPendingUseBlock(
		EmbodiedAgentRuntime runtime,
		FakeWorldTaskExecutor executor,
		int x
	) {
		runtime.recordWorldReadForTests(new BlockPos(x, 65, 2));
		CompletableFuture<String> resultFuture = runtime.execute(useBlockToolCall("call_use_block_" + x, x));
		assertFalse(resultFuture.isDone());
		runtime.onClientTick(null);
		assertEquals(WorldTaskType.USE_BLOCK, executor.lastActiveTask.orElseThrow().type());
		return resultFuture;
	}

	private static PlannerToolCall useBlockToolCall(String callId, int x) {
		return new PlannerToolCall(
			callId,
			"use_block",
			JsonParser.parseString("""
				{"itemId":"minecraft:wheat_seeds","x":%d,"y":65,"z":2,"expectedSupportBlockIds":["minecraft:farmland"],"expectedTargetMaterial":"air"}
				""".formatted(x)).getAsJsonObject(),
			null,
			null
		);
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

	private static SmeltingOption testSmeltingOption(String optionId, int maxInputQuantity) {
		SmeltingStationKey key = new SmeltingStationKey("minecraft:overworld", 1, 64, 1);
		SmeltingStationObservation observation = new SmeltingStationObservation(
			key,
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, false),
			false,
			1.0D
		);
		return new SmeltingOption(
			optionId,
			"minecraft:raw_iron",
			"minecraft:iron_ingot",
			1,
			maxInputQuantity,
			200,
			new SmeltingStationCandidate(
				SmeltingStationSource.NEARBY_EXISTING,
				SmeltingStationState.EMPTY,
				SmeltingStationKind.FURNACE,
				key,
				1.0D,
				false
			),
			observation
		);
	}

	private static String extractProcessId(String toolResult) {
		int start = toolResult.indexOf("processId=");
		assertTrue(start >= 0);
		int valueStart = start + "processId=".length();
		int valueEnd = toolResult.indexOf(' ', valueStart);
		return valueEnd < 0 ? toolResult.substring(valueStart) : toolResult.substring(valueStart, valueEnd);
	}

	private static SessionSnapshot loadedRemoteSession() {
		return new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L
		);
	}

	private static SessionSnapshot deadRemoteSession() {
		return new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			0L,
			PlayerLifecycleState.DEAD
		);
	}
}
