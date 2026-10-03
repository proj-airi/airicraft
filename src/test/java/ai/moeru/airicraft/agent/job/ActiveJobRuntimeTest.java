package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.actions.BlockAcquisitionTestFixtures;
import ai.moeru.airicraft.agent.dialogue.DialogueIntent;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockUseStepArgs;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.EntitySelector;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.LedgerStepPayload;
import ai.moeru.airicraft.agent.tasks.LedgerStepStatus;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltingFuelMode;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.TaskTerminationCause;
import ai.moeru.airicraft.agent.tasks.TaskType;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActiveJobRuntimeTest {
	@Test
	void reflexPausePreservesJobIdentityAndProgressUntilExplicitResume() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		runtime.recordMinedBlock("minecraft:dirt", 2L);
		String jobId = runtime.current().jobId();

		runtime.pauseForReflex(3L);

		assertEquals(jobId, runtime.current().jobId());
		assertEquals(1, runtime.current().collectedCount());
		assertEquals(ActiveJobStatus.BLOCKED, runtime.current().status());
		assertEquals("reflex", runtime.current().blockedReason());
		assertEquals(TaskState.PAUSED_BY_REFLEX, runtime.taskSnapshot().state());
		assertEquals(TaskExecutionState.PAUSED_BY_REFLEX, runtime.missionExecutionSnapshot().primitiveExecution().state());

		runtime.resumeAfterReflex(4L);

		assertEquals(jobId, runtime.current().jobId());
		assertEquals(1, runtime.current().collectedCount());
		assertEquals(ActiveJobStatus.QUEUED, runtime.current().status());
		assertNull(runtime.current().blockedReason());
	}

	@Test
	void minedBlockEventWithoutActiveMineJobIsIgnored() {
		ActiveJobRuntime runtime = runtime();

		assertTrue(runtime.recordMinedBlock("minecraft:dirt", 1L).isEmpty());
		assertEquals(ActiveJobStatus.IDLE, runtime.current().status());
	}

	@Test
	void mineBlocksProgressCountsOnlyMatchingBreakEvents() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 2))),
				1L
			),
			0,
			"test",
			1L
		);

		assertTrue(runtime.recordMinedBlock("minecraft:stone", 2L).isEmpty());
		assertEquals(0, runtime.current().collectedCount());
		GoalPosition firstBrokenBlock = new GoalPosition(0, 64, 0, true);
		GoalPosition secondBrokenBlock = new GoalPosition(1, 64, 0, true);
		assertTrue(runtime.recordMinedBlock("minecraft:dirt", firstBrokenBlock, 3L).isEmpty());
		assertEquals(1, runtime.current().collectedCount());
		assertTrue(runtime.recordMinedBlock("minecraft:dirt", secondBrokenBlock, 4L).isEmpty());
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		WorldTaskRequest.Mine mine = (WorldTaskRequest.Mine) request.task();

		assertEquals(List.of(firstBrokenBlock, secondBrokenBlock), mine.pickupSweepPositions());
		assertEquals(secondBrokenBlock, mine.pickupSweepPositions().getLast());
		assertEquals(ActiveJobStatus.RUNNING, runtime.current().status());
		runtime.tick(new TaskExecutionSnapshot(TaskExecutionState.RUNNING, request.taskId(), request.goal(), null, null, null, null), evidence(Map.of("minecraft:dirt", 0), 5L), true, true, 5L);
		assertEquals(request.taskId(), runtime.activeTaskRequest().orElseThrow().taskId());

		ActiveJobRuntime.TerminalTaskReport completed = runtime.reportTerminalTaskEvent(
			new TaskTerminalEvent(request.taskId(), request.goal(), TaskExecutionState.COMPLETED, "Goal reached", TaskTerminationCause.GOAL_REACHED),
			Optional.of(request)
		);

		assertEquals("Goal reached brokenBlocks=2 requestedBlocks=2", completed.event().orElseThrow().message());
		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void mineBlocksExactBreakCountNormalizesCancelledTerminalToCompletion() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		runtime.recordMinedBlock("minecraft:dirt", 3L);
		runtime.recordMinedBlock("minecraft:dirt", 4L);
		runtime.recordMinedBlock("minecraft:dirt", 5L);

		ActiveJobRuntime.TerminalTaskReport report = runtime.reportTerminalTaskEvent(
			new TaskTerminalEvent(
				request.taskId(),
				request.goal(),
				TaskExecutionState.CANCELLED,
				"Task cancelled",
				TaskTerminationCause.NAVIGATION_CANCELLED
			),
			Optional.of(request)
		);

		TaskTerminalEvent completed = report.event().orElseThrow();
		assertEquals(TaskExecutionState.COMPLETED, completed.terminalState());
		assertEquals(TaskTerminationCause.GOAL_REACHED, completed.terminationCause());
		assertEquals("Goal reached brokenBlocks=3 requestedBlocks=3", completed.message());
		assertTrue(report.warning().isEmpty());
		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void mineBlocksAtOrAboveRequestedCountCompletesParentJob() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 1))),
				1L
			),
			0,
			"test",
			1L
		);
		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		runtime.recordMinedBlock("minecraft:dirt", new GoalPosition(1, 64, 0, true), 3L);
		runtime.recordMinedBlock("minecraft:dirt", new GoalPosition(2, 64, 0, true), 4L);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertTrue(((WorldTaskRequest.Mine) request.task()).mineGoalSatisfied());
		ActiveJobRuntime.TerminalTaskReport report = runtime.reportTerminalTaskEvent(
			new TaskTerminalEvent(request.taskId(), request.goal(), TaskExecutionState.COMPLETED, "Goal reached", TaskTerminationCause.GOAL_REACHED),
			Optional.of(request)
		);

		assertEquals(TaskExecutionState.COMPLETED, report.event().orElseThrow().terminalState());
		assertTrue(report.warning().isEmpty());
		assertEquals("Goal reached brokenBlocks=2 requestedBlocks=1", report.event().orElseThrow().message());
		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void collectResourceForwardsLastMatchingBrokenBlockToMinePickupSweep() {
		ActiveJobRuntime runtime = runtime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.COBBLESTONE, 3), 0, "test", 1L);
		runtime.tick(TaskExecutionSnapshot.idle(), resourceEvidence(TaskResourceKind.COBBLESTONE, 0, 2L), true, true, 2L);

		runtime.recordMinedBlock("minecraft:stone", new GoalPosition(4, 63, -2, true), 3L);

		assertEquals(new GoalPosition(4, 63, -2, true), ((WorldTaskRequest.Mine) runtime.activeTaskRequest().orElseThrow().task()).pickupSweepPositions().getLast());
	}

	@Test
	void mineBlocksIgnoresInventoryIncreaseAndRestartsAfterEarlyNavigationCompletion() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot completedBeforeBreaks = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			"AT_GOAL",
			null,
			TaskTerminationCause.GOAL_REACHED
		);

		runtime.tick(completedBeforeBreaks, evidence(Map.of("minecraft:dirt", 3), 3L), true, true, 3L);
		WorldTaskRequest secondAttempt = runtime.activeTaskRequest().orElseThrow();

		assertEquals(ActiveJobStatus.RUNNING, runtime.current().status());
		assertEquals(0, runtime.current().collectedCount());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(6, secondAttempt.goal().mineSpec().quantity());
	}

	@Test
	void mineBlocksRestartsAfterPartialBreakCountAndEarlyNavigationCompletion() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();

		runtime.recordMinedBlock("minecraft:dirt", 3L);
		TaskExecutionSnapshot completedAfterOneBreak = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			"AT_GOAL",
			null,
			TaskTerminationCause.GOAL_REACHED
		);
		runtime.tick(completedAfterOneBreak, evidence(Map.of("minecraft:dirt", 1), 4L), true, true, 4L);
		WorldTaskRequest secondAttempt = runtime.activeTaskRequest().orElseThrow();

		assertEquals(1, runtime.current().collectedCount());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(3, secondAttempt.goal().mineSpec().quantity());
	}

	@Test
	void ensureBlocksInInventoryUsesAbsoluteInventoryTarget() {
		ActiveJobRuntime runtime = runtime();
		GoalMineSpec mineSpec = new GoalMineSpec(List.of("minecraft:dirt"), 3);
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Ensuring dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.ensureBlocksInInventory(mineSpec)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY, runtime.current().type());
		assertEquals(GoalType.MINE_BLOCKS, request.goal().type());
		assertEquals(mineSpec, request.goal().mineSpec());
		assertTrue(runtime.recordMinedBlock("minecraft:dirt", 2L).isEmpty());
		assertTrue(runtime.recordMinedBlock("minecraft:stone", 3L).isEmpty());
		assertEquals(1, runtime.current().collectedCount());
		assertNotEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
	}

	@Test
	void ensureBlocksInInventoryProjectsNavigationMineTaskLifecycle() {
		ActiveJobRuntime runtime = runtime();
		GoalMineSpec mineSpec = new GoalMineSpec(
			List.of("minecraft:seagrass"),
			20,
			List.of("minecraft:seagrass"),
			List.of("minecraft:shears")
		);
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Harvesting nearby seagrass.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.ensureBlocksInInventory(mineSpec)),
				1L
			),
			0,
			"action_graph",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.MINE, request.type());
		assertEquals(mineSpec, request.goal().mineSpec());

		runtime.tick(
			new TaskExecutionSnapshot(TaskExecutionState.FAILED, request.taskId(), request.goal(), "Navigation", "resource_not_found_nearby", null, null),
			evidence(Map.of("minecraft:seagrass", 0), 2L),
			true,
			false,
			2L
		);
		assertEquals(ActiveJobStatus.FAILED, runtime.current().status());
		assertTrue(runtime.current().lastError().contains("resource_not_found_nearby"));
	}

	@Test
	void ensureBlocksInInventoryCompletesFromMappedDropInventory() {
		ActiveJobRuntime runtime = runtime();
		GoalMineSpec mineSpec = new GoalMineSpec(
			List.of("minecraft:iron_ore"),
			3,
			List.of("minecraft:raw_iron"),
			List.of("minecraft:stone_pickaxe")
		);
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Ensuring iron.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.ensureBlocksInInventory(mineSpec)),
				1L
			),
			0,
			"test",
			1L
		);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:raw_iron", 3), 2L), true, true, 2L);

		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void ensureBlocksInInventoryTerminalReportIncludesBrokenBlockCount() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Ensuring dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.ensureBlocksInInventory(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		runtime.recordMinedBlock("minecraft:dirt", 2L);

		ActiveJobRuntime.TerminalTaskReport report = runtime.reportTerminalTaskEvent(
			new TaskTerminalEvent(
				request.taskId(),
				request.goal(),
				TaskExecutionState.COMPLETED,
				"Goal reached",
				TaskTerminationCause.GOAL_REACHED
			),
			Optional.of(request)
		);

		assertTrue(report.event().isEmpty());
		assertTrue(report.warning().orElseThrow().contains("inventory_target_not_satisfied"));
		assertTrue(report.warning().orElseThrow().contains("brokenBlocks=1"));
		assertTrue(report.warning().orElseThrow().contains("itemCount=0"));
		assertTrue(report.warning().orElseThrow().contains("requestedItemCount=3"));
	}

	@Test
	void mineBlocksCompletedTerminalMismatchReportsWarningOnly() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Mining dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.mineBlocks(new GoalMineSpec(List.of("minecraft:dirt"), 3))),
				1L
			),
			0,
			"test",
			1L
		);
		runtime.tick(TaskExecutionSnapshot.idle(), evidence(Map.of("minecraft:dirt", 0), 2L), true, true, 2L);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		ActiveJobRuntime.TerminalTaskReport report = runtime.reportTerminalTaskEvent(
			new TaskTerminalEvent(
				request.taskId(),
				request.goal(),
				TaskExecutionState.COMPLETED,
				"Goal reached",
				TaskTerminationCause.GOAL_REACHED
			),
			Optional.of(request)
		);

		assertTrue(report.event().isEmpty());
		assertTrue(report.warning().orElseThrow().contains("broken_block_count_mismatch"));
		assertTrue(report.warning().orElseThrow().contains("brokenBlocks=0"));
		assertTrue(report.warning().orElseThrow().contains("requestedBlocks=3"));
	}

	@Test
	void collectProgressDoesNotReplaceRunningPrimitiveMineTask() {
		ActiveJobRuntime runtime = runtime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), 4, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(4, 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot running = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			null,
			null,
			null
		);

		runtime.tick(running, evidence(5, 3L), true, true, 3L);
		WorldTaskRequest afterPickup = runtime.activeTaskRequest().orElseThrow();

		assertEquals(firstAttempt.taskId(), afterPickup.taskId());
		assertEquals(20, afterPickup.goal().mineSpec().quantity());
		assertEquals(TaskExecutionState.RUNNING, runtime.missionExecutionSnapshot().primitiveExecution().state());
		assertEquals(1, runtime.taskSnapshot().progress().collected());
		assertEquals(15, runtime.taskSnapshot().progress().remaining());
	}

	@Test
	void collectShortfallStartsNextPrimitiveAttemptAfterCompletion() {
		ActiveJobRuntime runtime = runtime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 16), 4, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(4, 2L), true, true, 2L);
		WorldTaskRequest firstAttempt = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot completed = new TaskExecutionSnapshot(
			TaskExecutionState.COMPLETED,
			firstAttempt.taskId(),
			firstAttempt.goal(),
			null,
			"AT_GOAL",
			null,
			TaskTerminationCause.GOAL_REACHED
		);

		runtime.tick(completed, evidence(5, 3L), true, true, 3L);
		WorldTaskRequest secondAttempt = runtime.activeTaskRequest().orElseThrow();

		assertNotEquals(firstAttempt.taskId(), secondAttempt.taskId());
		assertTrue(secondAttempt.taskId().endsWith(":mine:2"));
		assertEquals(20, secondAttempt.goal().mineSpec().quantity());
	}

	@Test
	void collectAttemptUsesAbsoluteInventoryTargetWhenBaselineAlreadyHasLogs() {
		ActiveJobRuntime runtime = runtime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 5), 5, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(5, 2L), true, true, 2L);
		WorldTaskRequest attempt = runtime.activeTaskRequest().orElseThrow();

		assertEquals(10, attempt.goal().mineSpec().quantity());
	}

	@Test
	void actionGraphCollectStartsWithoutNearbyEvidence() {
		ActiveJobRuntime runtime = runtime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 1), 0, "action_graph", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(0, 2L), true, false, 2L);

		assertEquals(ActiveJobStatus.RUNNING, runtime.current().status());
		assertNull(runtime.current().blockedReason());
		assertEquals(GoalType.MINE_BLOCKS, runtime.activeTaskRequest().orElseThrow().goal().type());
	}

	@Test
	void clearGoalDoesNotCancelCompletedCollectJob() {
		ActiveJobRuntime runtime = runtime();
		runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 5), 3, "test", 1L);

		runtime.tick(TaskExecutionSnapshot.idle(), evidence(8, 2L), true, true, 2L);
		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Done.",
				new DialogueIntent(DialogueIntentType.CLEAR_GOAL, null, null),
				3L
			),
			8,
			"planner_response",
			3L
		);

		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
		assertNull(runtime.current().lastError());
	}

	@Test
	void craftRecipeActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		CraftRecipeStepArgs craftRecipe = new CraftRecipeStepArgs("oak_planks_x2_to_stick", 1);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Crafting sticks.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.craftRecipe(craftRecipe)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.CRAFT_RECIPE, request.type());
		assertEquals(new WorldTaskRequest.CraftRecipe(craftRecipe), request.task());
		assertNull(request.goal());
		assertEquals(ActiveJobType.CRAFT_RECIPE, runtime.current().type());
		assertEquals(craftRecipe, runtime.current().craftRecipe());
	}

	@Test
	void dropItemsActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		DropItemsStepArgs dropItems = new DropItemsStepArgs("minecraft:oak_log", 2, "Alice");

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Dropping logs.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.dropItems(dropItems)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.DROP_ITEMS, request.type());
		assertEquals(new WorldTaskRequest.DropItems(dropItems), request.task());
		assertNull(request.goal());
		assertEquals(ActiveJobType.DROP_ITEMS, runtime.current().type());
		assertEquals(dropItems, runtime.current().dropItems());
	}

	@Test
	void smeltItemsActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		SmeltItemsStepArgs smeltItems = new SmeltItemsStepArgs(
			"smelt:iron:nearby-1",
			3,
			SmeltingFuelMode.MANUAL,
			"minecraft:coal",
			1,
			"confirm-1"
		);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Starting iron smelting.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.smeltItems(smeltItems)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.SMELT_ITEMS, request.type());
		assertEquals(new WorldTaskRequest.SmeltItems(smeltItems), request.task());
		assertNull(request.goal());
		assertEquals(ActiveJobType.SMELT_ITEMS, runtime.current().type());
		assertEquals(smeltItems, runtime.current().smeltItems());
	}

	@Test
	void collectSmeltedItemsActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		CollectSmeltedItemsStepArgs collect = new CollectSmeltedItemsStepArgs("smelt-process-1", "confirm-2");

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Collecting furnace output.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.collectSmeltedItems(collect)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.COLLECT_SMELTED_ITEMS, request.type());
		assertEquals(new WorldTaskRequest.CollectSmeltedItems(collect), request.task());
		assertNull(request.goal());
		assertEquals(ActiveJobType.COLLECT_SMELTED_ITEMS, runtime.current().type());
		assertEquals(collect, runtime.current().collectSmeltedItems());
	}

	@Test
	void returnToSurfaceActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		ReturnToSurfaceStepArgs returnToSurface = new ReturnToSurfaceStepArgs(
			new GoalPosition(12, 70, -8, false),
			"nearest_surface",
			true,
			List.of("minecraft:dirt")
		);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Returning to surface.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.returnToSurface(returnToSurface)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.RETURN_TO_SURFACE, request.type());
		assertEquals(new WorldTaskRequest.ReturnToSurface(returnToSurface), request.task());
		assertNull(request.goal());
		assertEquals(ActiveJobType.RETURN_TO_SURFACE, runtime.current().type());
		assertEquals(returnToSurface, runtime.current().returnToSurface());
	}

	@Test
	void blockInteractionActiveJobsProjectWorldTaskRequests() {
		ActiveJobRuntime placeRuntime = runtime();
		BlockPlacementStepArgs place = new BlockPlacementStepArgs(
			"minecraft:dirt",
			new GoalPosition(1, 64, 2, true),
			"down",
			"air_or_replaceable"
		);

		placeRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Placing dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.placeBlock(place)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest placeRequest = placeRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.PLACE_BLOCK, placeRequest.type());
		assertEquals(new WorldTaskRequest.PlaceBlock(place), placeRequest.task());
		assertEquals(ActiveJobType.PLACE_BLOCK, placeRuntime.current().type());

		ActiveJobRuntime useRuntime = runtime();
		BlockUseStepArgs use = new BlockUseStepArgs(
			"minecraft:wheat_seeds",
			new GoalPosition(1, 65, 2, true),
			"down",
			List.of("minecraft:farmland"),
			"air"
		);

		useRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Planting seeds.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.useBlock(use)),
				2L
			),
			0,
			"test",
			2L
		);

		WorldTaskRequest useRequest = useRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, useRequest.type());
		assertEquals(new WorldTaskRequest.UseBlock(use), useRequest.task());
		assertEquals(ActiveJobType.USE_BLOCK, useRuntime.current().type());
	}

	@Test
	void cropTendingPayloadSurvivesPauseResumeAndCancellation() {
		ActiveJobRuntime runtime = runtime();
		var args = new ai.moeru.airicraft.agent.tasks.CropTendingStepArgs("minecraft:wheat_seeds", 1, 63, 2, 3, 4);
		runtime.applyPlannerResponse(new DialogueResponse("Tending crops.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.tendCrops(args)), 1L), 0, "test", 1L);
		assertEquals(WorldTaskType.TEND_CROPS, runtime.activeTaskRequest().orElseThrow().type());
		assertEquals(new WorldTaskRequest.TendCrops(args), runtime.activeTaskRequest().orElseThrow().task());
		String id = runtime.current().jobId();
		runtime.pauseForReflex(2L);
		assertEquals(args, runtime.current().cropTending());
		runtime.resumeAfterReflex(3L);
		assertEquals(id, runtime.current().jobId());
		assertEquals(new WorldTaskRequest.TendCrops(args), runtime.activeTaskRequest().orElseThrow().task());
		runtime.cancel("test_cancel", 4L);
		assertEquals(ActiveJobStatus.CANCELLED, runtime.current().status());
		assertEquals(args, runtime.current().cropTending());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void lurePayloadSurvivesPauseResumeAndCancellation() {
		ActiveJobRuntime runtime = runtime();
		var args = new ai.moeru.airicraft.agent.tasks.LureEntitiesStepArgs(java.util.List.of("aaaaaaaa"), "minecraft:wheat_seeds", 1, 63, 2, 3, 64, 4);
		runtime.applyPlannerResponse(new DialogueResponse("Luring animals.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.lureEntities(args)), 1L), 0, "test", 1L);
		assertEquals(WorldTaskType.LURE_ENTITIES, runtime.activeTaskRequest().orElseThrow().type());
		assertEquals(new WorldTaskRequest.LureEntities(args), runtime.activeTaskRequest().orElseThrow().task());
		String id = runtime.current().jobId();
		runtime.pauseForReflex(2L);
		assertEquals(args, runtime.current().lureEntities());
		runtime.resumeAfterReflex(3L);
		assertEquals(id, runtime.current().jobId());
		assertEquals(new WorldTaskRequest.LureEntities(args), runtime.activeTaskRequest().orElseThrow().task());
		runtime.cancel("test_cancel", 4L);
		assertEquals(ActiveJobStatus.CANCELLED, runtime.current().status());
		assertEquals(args, runtime.current().lureEntities());
		assertTrue(runtime.activeTaskRequest().isEmpty());
	}

	@Test
	void navigationIgnoresPreviousPlacementFailure() {
		ActiveJobRuntime runtime = runtime();
		runtime.applyPlannerResponse(new DialogueResponse("Climb the scaffold.",
			new DialogueIntent(DialogueIntentType.JOB_UPDATE,
				ActiveJobProposal.navigateTo(new GoalPosition(104, -57, 31, true))), 1L), 0, "test", 1L);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		runtime.tick(new TaskExecutionSnapshot(TaskExecutionState.FAILED, "previous-placement",
			null, "BlockInteraction", "target_not_visible", null, null), evidence(0, 2L), true, false, 2L);
		assertEquals(ActiveJobStatus.QUEUED, runtime.current().status());
		assertEquals(request.taskId(), runtime.activeTaskRequest().orElseThrow().taskId());
		runtime.tick(new TaskExecutionSnapshot(TaskExecutionState.COMPLETED, request.taskId(),
			null, "Navigation", "arrived", null, null), evidence(0, 3L), true, false, 3L);
		assertEquals(ActiveJobStatus.COMPLETED, runtime.current().status());
	}

	@Test
	void primitiveJobAppliesOnlyMatchingTaskSnapshot() {
		Map<TaskExecutionState, ActiveJobStatus> taskStatuses = Map.of(
			TaskExecutionState.RUNNING, ActiveJobStatus.RUNNING,
			TaskExecutionState.COMPLETED, ActiveJobStatus.COMPLETED,
			TaskExecutionState.FAILED, ActiveJobStatus.FAILED,
			TaskExecutionState.CANCELLED, ActiveJobStatus.CANCELLED,
			TaskExecutionState.PAUSED_BY_SESSION_GATE, ActiveJobStatus.BLOCKED,
			TaskExecutionState.PAUSED_BY_REFLEX, ActiveJobStatus.BLOCKED
		);
		for (Map.Entry<TaskExecutionState, ActiveJobStatus> taskStatus : taskStatuses.entrySet()) {
			ActiveJobRuntime runtime = runtime();
			runtime.applyPlannerResponse(
				new DialogueResponse(
					"Placing the crafting table.",
					new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.placeBlock(new BlockPlacementStepArgs(
						"minecraft:crafting_table",
						new GoalPosition(1, 64, 2, true),
						"down",
						"air"
					))),
					1L
				),
				0,
				"test",
				1L
			);
			WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

			runtime.tick(
				new TaskExecutionSnapshot(taskStatus.getKey(), "previous-craft-task", null, "Crafting", "crafted", null, null),
				evidence(0, 2L),
				true,
				false,
				2L
			);

			assertEquals(ActiveJobStatus.QUEUED, runtime.current().status(), taskStatus.getKey().name());
			assertEquals(request.taskId(), runtime.activeTaskRequest().orElseThrow().taskId(), taskStatus.getKey().name());

			runtime.tick(
				new TaskExecutionSnapshot(taskStatus.getKey(), request.taskId(), null, "BlockInteraction", "matching", null, null),
				evidence(0, 3L),
				true,
				false,
				3L
			);

			assertEquals(taskStatus.getValue(), runtime.current().status(), taskStatus.getKey().name());
			assertEquals(taskStatus.getValue().terminal(), runtime.activeTaskRequest().isEmpty(), taskStatus.getKey().name());
		}
	}

	@Test
	void useBlockLedgerStepProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		BlockUseStepArgs use = new BlockUseStepArgs(
			"minecraft:water_bucket",
			new GoalPosition(-15, 63, -40, true),
			"up",
			List.of(),
			"air"
		);
		TaskLedger ledger = new TaskLedger(
			"water-hole",
			MissionType.CRAFT_ITEM,
			"Place water in the irrigation hole.",
			List.of(new LedgerStep(
				"place-water",
				LedgerStepKind.USE_BLOCK,
				new LedgerStepPayload(null, null, null, null, null, null, null, null, use, null, null, null, null, null),
				List.of(),
				LedgerStepStatus.PENDING,
				List.of(),
				0,
				null
			)),
			"place-water",
			List.of(),
			null,
			null
		);

		runtime.submitMissionLedger(ledger, 0, "test", 1L);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, request.type());
		assertEquals(new WorldTaskRequest.UseBlock(use), request.task());
		assertEquals(ActiveJobType.USE_BLOCK, runtime.current().type());
	}

	@Test
	void batchedBlockInteractionActiveJobsProjectWorldTaskRequests() {
		ActiveJobRuntime placeRuntime = runtime();
		BlockPlacementStepArgs place = new BlockPlacementStepArgs(
			"minecraft:dirt",
			List.of(
				new BlockPlacementStepArgs.Target(new GoalPosition(1, 64, 2, true), "down", "air_or_replaceable"),
				new BlockPlacementStepArgs.Target(new GoalPosition(2, 64, 2, true), "north", "air")
			)
		);

		placeRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Placing dirt.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.placeBlock(place)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest placeRequest = placeRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.PLACE_BLOCK, placeRequest.type());
		assertEquals(new WorldTaskRequest.PlaceBlock(place), placeRequest.task());
		assertEquals(2, ((WorldTaskRequest.PlaceBlock) placeRequest.task()).args().targets().size());
		assertEquals(ActiveJobType.PLACE_BLOCK, placeRuntime.current().type());

		ActiveJobRuntime useRuntime = runtime();
		BlockUseStepArgs use = new BlockUseStepArgs(
			"minecraft:wheat_seeds",
			List.of(
				new BlockUseStepArgs.Target(new GoalPosition(1, 65, 2, true), "down", List.of("minecraft:farmland"), "air"),
				new BlockUseStepArgs.Target(new GoalPosition(2, 65, 2, true), "down", List.of("minecraft:farmland"), "air")
			)
		);

		useRuntime.applyPlannerResponse(
			new DialogueResponse(
				"Planting seeds.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.useBlock(use)),
				2L
			),
			0,
			"test",
			2L
		);

		WorldTaskRequest useRequest = useRuntime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.USE_BLOCK, useRequest.type());
		assertEquals(new WorldTaskRequest.UseBlock(use), useRequest.task());
		assertEquals(2, ((WorldTaskRequest.UseBlock) useRequest.task()).args().targets().size());
		assertEquals(ActiveJobType.USE_BLOCK, useRuntime.current().type());
	}

	@Test
	void breakBlocksLedgerStepProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		BlockBreakStepArgs blockBreak = new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(
			new GoalPosition(-15, 63, -40, true),
			List.of("minecraft:grass_block")
		)));
		TaskLedger ledger = new TaskLedger(
			"break-center",
			MissionType.CRAFT_ITEM,
			"Break the irrigation hole.",
			List.of(new LedgerStep(
				"break-hole",
				LedgerStepKind.BREAK_BLOCKS,
				new LedgerStepPayload(null, null, null, null, null, null, null, null, null, null, blockBreak, null, null, null),
				List.of(),
				LedgerStepStatus.PENDING,
				List.of(),
				0,
				null
			)),
			"break-hole",
			List.of(),
			null,
			null
		);

		runtime.submitMissionLedger(ledger, 0, "test", 1L);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		assertEquals(WorldTaskType.BREAK_BLOCKS, request.type());
		assertEquals(new WorldTaskRequest.BreakBlocks(blockBreak), request.task());
		assertEquals(ActiveJobType.BREAK_BLOCKS, runtime.current().type());
	}

	@Test
	void attackEntityActiveJobProjectsWorldTaskRequest() {
		ActiveJobRuntime runtime = runtime();
		EntityInteractionStepArgs attack = new EntityInteractionStepArgs(
			new EntitySelector(null, null, "minecraft:sheep"),
			null
		);

		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Attack the sheep.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.attackEntity(attack)),
				1L
			),
			0,
			"test",
			1L
		);

		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();

		assertEquals(WorldTaskType.ATTACK_ENTITY, request.type());
		assertEquals(new WorldTaskRequest.AttackEntity(attack), request.task());
		assertNull(request.goal());
		assertEquals(ActiveJobType.ATTACK_ENTITY, runtime.current().type());
		assertEquals(attack, runtime.current().entityInteraction());
	}

	@Test
	void dropItemsPrimitiveJobIgnoresCompanionSessionGate() {
		ActiveJobRuntime runtime = runtime();
		DropItemsStepArgs dropItems = new DropItemsStepArgs("minecraft:oak_log", 2, null);
		runtime.applyPlannerResponse(
			new DialogueResponse(
				"Dropping logs.",
				new DialogueIntent(DialogueIntentType.JOB_UPDATE, ActiveJobProposal.dropItems(dropItems)),
				1L
			),
			0,
			"test",
			1L
		);
		WorldTaskRequest request = runtime.activeTaskRequest().orElseThrow();
		TaskExecutionSnapshot runningDrop = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			request.taskId(),
			null,
			null,
			"inventory_screen_dismissed",
			null,
			null
		);

		runtime.tick(runningDrop, evidence(4, 2L), false, false, 2L);

		assertEquals(ActiveJobStatus.RUNNING, runtime.current().status());
		assertNull(runtime.current().blockedReason());
		assertEquals("inventory_screen_dismissed", runtime.missionExecutionSnapshot().primitiveExecution().lastPathEvent());
	}

	private static WorldEvidence evidence(int woodLogs, long tick) {
		return new WorldEvidence(
			Map.of(TaskResourceKind.WOOD_LOGS, woodLogs),
			Map.of("minecraft:oak_log", 4),
			"minecraft:overworld",
			0,
			64,
			0,
			null,
			tick
		);
	}

	private static WorldEvidence evidence(Map<String, Integer> itemCounts, long tick) {
		return new WorldEvidence(
			Map.of(),
			itemCounts,
			Map.of(),
			"minecraft:overworld",
			0,
			64,
			0,
			null,
			tick
		);
	}

	private static WorldEvidence resourceEvidence(TaskResourceKind resourceKind, int count, long tick) {
		return new WorldEvidence(
			Map.of(resourceKind, count),
			Map.of(),
			Map.of(),
			"minecraft:overworld",
			0,
			64,
			0,
			null,
			tick
		);
	}

	private static ActiveJobRuntime runtime() {
		ActiveJobRuntime runtime = new ActiveJobRuntime();
		runtime.updateBlockAcquisitions(BlockAcquisitionTestFixtures.survival());
		return runtime;
	}
}
