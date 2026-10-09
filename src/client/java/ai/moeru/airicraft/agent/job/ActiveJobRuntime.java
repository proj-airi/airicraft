package ai.moeru.airicraft.agent.job;

import ai.moeru.airicraft.agent.debug.CollectResourceTaskDebugSnapshot;
import ai.moeru.airicraft.agent.actions.BlockAcquisitionIndex;
import ai.moeru.airicraft.agent.dialogue.DialogueIntentType;
import ai.moeru.airicraft.agent.dialogue.DialogueResponse;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.tasks.AskUserStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockBreakStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockPlacementStepArgs;
import ai.moeru.airicraft.agent.tasks.BlockUseStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectSmeltedItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectResourceStepArgs;
import ai.moeru.airicraft.agent.tasks.CollectResourceTaskHandler;
import ai.moeru.airicraft.agent.tasks.CraftRecipeStepArgs;
import ai.moeru.airicraft.agent.tasks.DropItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.EntityInteractionStepArgs;
import ai.moeru.airicraft.agent.tasks.LedgerStep;
import ai.moeru.airicraft.agent.tasks.LedgerStepKind;
import ai.moeru.airicraft.agent.tasks.MissionExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.MissionSpec;
import ai.moeru.airicraft.agent.tasks.MissionType;
import ai.moeru.airicraft.agent.tasks.ReturnToSurfaceStepArgs;
import ai.moeru.airicraft.agent.tasks.SmeltItemsStepArgs;
import ai.moeru.airicraft.agent.tasks.StepExecutionResult;
import ai.moeru.airicraft.agent.tasks.StepExecutionStatus;
import ai.moeru.airicraft.agent.tasks.TaskLedger;
import ai.moeru.airicraft.agent.tasks.TaskExecutionSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskExecutionState;
import ai.moeru.airicraft.agent.tasks.TaskOwnership;
import ai.moeru.airicraft.agent.tasks.TaskProgressSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSnapshot;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskState;
import ai.moeru.airicraft.agent.tasks.TaskStep;
import ai.moeru.airicraft.agent.tasks.TaskTerminalEvent;
import ai.moeru.airicraft.agent.tasks.TaskTerminationCause;
import ai.moeru.airicraft.agent.tasks.WorldTaskRequest;
import ai.moeru.airicraft.agent.tasks.WorldEvidence;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ActiveJobRuntime {
	private final CollectResourceTaskHandler collectResourceTaskHandler = new CollectResourceTaskHandler();

	private ActiveJob activeJob = ActiveJob.idle();
	private TaskExecutionSnapshot lastPrimitiveExecution = TaskExecutionSnapshot.idle();
	private WorldEvidence lastEvidence = new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, -1L);
	private MissionSpec compatibilityMission;
	private TaskLedger compatibilityLedger;
	private CollectResourceTaskDebugSnapshot collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
	private WorldTaskRequest desiredPrimitiveTask;
	private String collectAttemptJobId;
	private int collectAttemptSequence;
	private String mineAttemptJobId;
	private int mineAttemptSequence;
	private String minePickupSweepJobId;
	private final LinkedHashSet<GoalPosition> minePickupSweepPositions = new LinkedHashSet<>();

	public void clear() {
		activeJob = ActiveJob.idle();
		lastPrimitiveExecution = TaskExecutionSnapshot.idle();
		lastEvidence = new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, -1L);
		compatibilityMission = null;
		compatibilityLedger = null;
		collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
		desiredPrimitiveTask = null;
		collectAttemptJobId = null;
		collectAttemptSequence = 0;
		mineAttemptJobId = null;
		mineAttemptSequence = 0;
		minePickupSweepJobId = null;
		minePickupSweepPositions.clear();
	}

	public ActiveJob current() {
		return activeJob;
	}

	/**
	 * Block and item ids the running job is working on (mined block ids, and a collect job's accepted items), so
	 * attention can leave a percept about exactly that work to the job. Empty when idle or terminal.
	 */
	public List<String> activeTargetIds() {
		if (activeJob == null || activeJob.status().terminal()) return List.of();
		var ids = new java.util.LinkedHashSet<String>();
		GoalSnapshot goal = activeJob.directGoal();
		GoalMineSpec spec = goal == null ? null : goal.mineSpec();
		switch (activeJob.type()) {
			case MINE_BLOCKS, ENSURE_BLOCKS_IN_INVENTORY -> { if (spec != null) ids.addAll(spec.blockIds()); }
			case COLLECT_RESOURCE -> {
				if (activeJob.taskSpec() != null) {
					ids.addAll(collectResourceTaskHandler.targetBlockIds(activeJob.taskSpec()));
					ai.moeru.airicraft.agent.tasks.ResourceGatheringCatalog.entry(activeJob.taskSpec().resourceKind()).ifPresent(entry -> ids.addAll(entry.acceptedItemIds()));
				}
			}
			default -> { }
		}
		return List.copyOf(ids);
	}

	public void updateBlockAcquisitions(BlockAcquisitionIndex blockAcquisitions) {
		collectResourceTaskHandler.updateBlockAcquisitions(blockAcquisitions);
	}

	public Optional<GoalSnapshot> activeGoal(long tick) {
		if (activeJob.isIdle() || activeJob.status().terminal()) {
			return Optional.empty();
		}
		if (activeJob.type() == ActiveJobType.MINE_BLOCKS && activeJob.directGoal() != null) {
			return Optional.of(activeJob.directGoal());
		}
		if (desiredPrimitiveTask != null
			&& desiredPrimitiveTask.goal() != null
			&& Objects.equals(desiredPrimitiveTask.sourceJobId(), activeJob.jobId())) {
			return Optional.of(desiredPrimitiveTask.goal());
		}
		if (activeJob.directGoal() != null) {
			return Optional.of(activeJob.directGoal());
		}
		if (activeJob.type() == ActiveJobType.COLLECT_RESOURCE && activeJob.taskSpec() != null) {
			if (activeJob.status() != ActiveJobStatus.RUNNING) {
				return Optional.empty();
			}
			int remaining = Math.max(1, activeJob.taskSpec().quantity() - activeJob.collectedCount());
			return Optional.of(collectResourceTaskHandler.start(activeJob.taskSpec(), remaining, tick));
		}
		return Optional.empty();
	}

	public Optional<WorldTaskRequest> activeTaskRequest() {
		return Optional.ofNullable(desiredPrimitiveTask);
	}

	public CollectResourceTaskDebugSnapshot collectResourceDebugSnapshot() {
		return collectResourceDebugSnapshot;
	}

	public Optional<TaskTerminalEvent> recordMinedBlock(String blockId, GoalPosition position, long tick) {
		if (blockId == null || blockId.isBlank()) {
			return Optional.empty();
		}
		if (
			(activeJob.type() != ActiveJobType.MINE_BLOCKS && activeJob.type() != ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY && activeJob.type() != ActiveJobType.COLLECT_RESOURCE)
				|| activeJob.status().terminal()
		) {
			return Optional.empty();
		}
		GoalSnapshot goal = activeJob.directGoal();
		GoalMineSpec spec = goal == null ? null : goal.mineSpec();
		List<String> targetBlockIds = activeJob.type() == ActiveJobType.COLLECT_RESOURCE
			? collectResourceTaskHandler.targetBlockIds(activeJob.taskSpec())
			: spec == null ? List.of() : spec.blockIds();
		if (!targetBlockIds.contains(blockId)) {
			return Optional.empty();
		}
		if (!Objects.equals(minePickupSweepJobId, activeJob.jobId())) {
			minePickupSweepPositions.clear();
		}
		minePickupSweepJobId = activeJob.jobId();
		if (position != null) {
			minePickupSweepPositions.add(position);
		}
		if (activeJob.type() == ActiveJobType.COLLECT_RESOURCE) {
			refreshDesiredTask(tick);
			return Optional.empty();
		}

		int minedCount = activeJob.collectedCount() + 1;
		ActiveJobStatus nextStatus = activeMiningStatus(activeJob.status());
		activeJob = updated(activeJob, nextStatus, null, null, minedCount, tick);
		refreshDesiredTask(tick);
		if (activeJob.type() == ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY) {
			return Optional.empty();
		}
		return Optional.empty();
	}

	public Optional<TaskTerminalEvent> recordMinedBlock(String blockId, long tick) {
		return recordMinedBlock(blockId, null, tick);
	}

	public TerminalTaskReport reportTerminalTaskEvent(TaskTerminalEvent event, Optional<WorldTaskRequest> activeRequest) {
		if (event == null) {
			return TerminalTaskReport.empty();
		}
		if (!isActiveMiningInventoryTask(activeJob.type()) || activeJob.status().terminal()) {
			return TerminalTaskReport.emit(event);
		}
		if (activeRequest.isEmpty() || !Objects.equals(activeRequest.get().sourceJobId(), activeJob.jobId())) {
			return TerminalTaskReport.emit(event);
		}
		if (!Objects.equals(activeRequest.get().taskId(), event.taskId())) {
			return TerminalTaskReport.emit(event);
		}
		GoalSnapshot goal = activeJob.directGoal();
		GoalMineSpec spec = goal == null ? null : goal.mineSpec();
		if (spec == null) {
			return TerminalTaskReport.emit(event);
		}

		int brokenBlocks = activeJob.collectedCount();
		if (activeJob.type() == ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY) {
			int itemCount = matchingItemCount(lastEvidence.itemCounts(), spec.matchingItemIds());
			if (event.terminalState() == TaskExecutionState.COMPLETED && itemCount < spec.quantity()) {
				return TerminalTaskReport.warnOnly(ensureBlocksInventoryShortfallWarning(
					event.taskId(),
					brokenBlocks,
					itemCount,
					spec
				));
			}
			return TerminalTaskReport.emit(withTerminalMessage(
				event,
				appendBrokenBlocks(event.message(), brokenBlocks)
					+ " itemCount=" + itemCount
					+ " requestedItemCount=" + spec.quantity()
			));
		}

		boolean mismatch = brokenBlocks < spec.quantity();
		String warning = mismatch
			? mineBlocksMismatchWarning(event.taskId(), brokenBlocks, spec.quantity())
			: null;
		if (mismatch && event.terminalState() == TaskExecutionState.COMPLETED) {
			return TerminalTaskReport.warnOnly(warning);
		}
		TaskTerminalEvent reportedEvent = event;
		if (!mismatch && activeJob.type() == ActiveJobType.MINE_BLOCKS) {
			activeJob = updated(activeJob, ActiveJobStatus.COMPLETED, null, null, brokenBlocks, lastEvidence.tick());
			refreshDesiredTask(lastEvidence.tick());
			reportedEvent = completedMineBlocksTerminalEvent(event);
		}
		String message = mineBlocksTerminalMessage(reportedEvent.message(), brokenBlocks, spec.quantity(), mismatch);
		return TerminalTaskReport.of(withTerminalMessage(reportedEvent, message), warning);
	}

	public void submitTask(TaskSpec spec, int currentResourceCount, String source, long tick) {
		Objects.requireNonNull(spec, "spec");
		activeJob = new ActiveJob(
			newJobId(),
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.QUEUED,
			null,
			spec,
			null,
			null,
			-1L,
			currentResourceCount,
			0,
			normalizeSource(source),
			null,
			null,
			tick
		);
		compatibilityMission = new MissionSpec(activeJob.jobId(), MissionType.COLLECT_RESOURCE, goalText());
		compatibilityLedger = null;
		collectResourceDebugSnapshot = collectResourceProbe(
			activeJob,
			currentResourceCount,
			false,
			lastPrimitiveExecution.state(),
			null,
			tick
		);
		refreshDesiredTask(tick);
	}

	public void submitMissionLedger(TaskLedger ledger, int currentResourceCount, String source, long tick) {
		Objects.requireNonNull(ledger, "ledger");
		ActiveJob next = fromLedger(ledger, currentResourceCount, normalizeSource(source), tick);
		activeJob = preserveProgressIfSame(next, tick);
		compatibilityMission = missionFromLedger(ledger);
		compatibilityLedger = ledger;
		collectResourceDebugSnapshot = activeJob.type() == ActiveJobType.COLLECT_RESOURCE
			? collectResourceProbe(activeJob, currentResourceCount, false, lastPrimitiveExecution.state(), null, tick)
			: CollectResourceTaskDebugSnapshot.empty();
		refreshDesiredTask(tick);
	}

	public void applyPlannerResponse(DialogueResponse response, int currentResourceCount, String source, long tick) {
		if (response == null || response.intent() == null || response.intent().type() == null) {
			return;
		}

		if (response.intent().type() == DialogueIntentType.MISSION_UPDATE && response.intent().taskLedger() != null) {
			submitMissionLedger(response.intent().taskLedger(), currentResourceCount, source, tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.JOB_UPDATE && response.intent().activeJob() != null) {
			activeJob = preserveProgressIfSame(fromProposal(response.intent().activeJob(), currentResourceCount, normalizeSource(source), tick), tick);
			compatibilityMission = missionSpec();
			compatibilityLedger = null;
			refreshDesiredTask(tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.SUBMIT_TASK && response.intent().taskSpec() != null) {
			submitTask(response.intent().taskSpec(), currentResourceCount, source, tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.CANCEL_TASK) {
			cancel("planner_cancelled", tick);
			return;
		}
		if (response.intent().type() == DialogueIntentType.CLEAR_GOAL) {
			if (!activeJob.status().terminal()) {
				cancel("planner_cancelled", tick);
			}
			return;
		}
		if (response.intent().type() == DialogueIntentType.SET_GOAL && response.intent().goalType() != null) {
			ActiveJob next = fromGoalResponse(response, normalizeSource(source));
			activeJob = preserveProgressIfSame(next, tick);
			refreshDesiredTask(tick);
		}
	}

	public void cancel(String reason, long tick) {
		if (activeJob.isIdle()) {
			return;
		}
		activeJob = new ActiveJob(
			activeJob.jobId(),
			activeJob.type(),
			ActiveJobStatus.CANCELLED,
			activeJob.directGoal(),
			activeJob.taskSpec(),
			activeJob.craftRecipe(),
			activeJob.dropItems(),
			activeJob.entityInteraction(),
			activeJob.smeltItems(),
			activeJob.collectSmeltedItems(),
			activeJob.returnToSurface(),
			activeJob.blockPlacement(),
			activeJob.blockUse(),
			activeJob.blockBreak(),
			activeJob.askPrompt(),
			activeJob.waitUntilTick(),
			activeJob.baselineResourceCount(),
			activeJob.collectedCount(),
			activeJob.source(),
			null,
			reason,
			tick,
			activeJob.cropTending(),
			activeJob.lureEntities()
		);
		collectResourceDebugSnapshot = activeJob.type() == ActiveJobType.COLLECT_RESOURCE
			? collectResourceProbe(activeJob, activeJob.baselineResourceCount() + activeJob.collectedCount(), false, lastPrimitiveExecution.state(), reason, tick)
			: CollectResourceTaskDebugSnapshot.empty();
		refreshDesiredTask(tick);
	}

	public void pauseForReflex(long tick) {
		if (activeJob.isIdle() || activeJob.status().terminal()) {
			return;
		}
		activeJob = updated(activeJob, ActiveJobStatus.BLOCKED, "reflex", null, activeJob.collectedCount(), tick);
		lastPrimitiveExecution = pausedPrimitive(lastPrimitiveExecution);
	}

	public void resumeAfterReflex(long tick) {
		if (activeJob.status() != ActiveJobStatus.BLOCKED || !"reflex".equals(activeJob.blockedReason())) {
			return;
		}
		activeJob = updated(activeJob, ActiveJobStatus.QUEUED, null, null, activeJob.collectedCount(), tick);
		lastPrimitiveExecution = TaskExecutionSnapshot.idle();
		refreshDesiredTask(tick);
	}

	private static TaskExecutionSnapshot pausedPrimitive(TaskExecutionSnapshot snapshot) {
		TaskExecutionSnapshot source = snapshot == null ? TaskExecutionSnapshot.idle() : snapshot;
		return new TaskExecutionSnapshot(
			TaskExecutionState.PAUSED_BY_REFLEX,
			source.taskId(),
			source.activeGoal(),
			source.processName(),
			"reflex",
			source.estimatedTicksToGoal(),
			source.terminationCause()
		);
	}

	public void tick(
		TaskExecutionSnapshot primitiveExecution,
		WorldEvidence evidence,
		boolean actuationAllowed,
		boolean nearbyResourceTargetAvailable,
		long tick
	) {
		lastPrimitiveExecution = primitiveExecution == null ? TaskExecutionSnapshot.idle() : primitiveExecution;
		lastEvidence = evidence == null ? new WorldEvidence(Map.of(), Map.of(), Map.of(), null, 0, 0, 0, null, tick) : evidence;

		if (activeJob.isIdle() || activeJob.status().terminal()) {
			collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
			return;
		}

		activeJob = switch (activeJob.type()) {
			case COLLECT_RESOURCE -> tickCollectResource(activeJob, lastPrimitiveExecution, lastEvidence, actuationAllowed, nearbyResourceTargetAvailable, tick);
			case CRAFT_RECIPE -> tickPrimitiveJob(activeJob, desiredPrimitiveTask, lastPrimitiveExecution, actuationAllowed, tick);
			case DROP_ITEMS -> tickPrimitiveJob(activeJob, desiredPrimitiveTask, lastPrimitiveExecution, true, tick);
			case SMELT_ITEMS, COLLECT_SMELTED_ITEMS, RETURN_TO_SURFACE, PLACE_BLOCK, USE_BLOCK, BREAK_BLOCKS, TEND_CROPS, LURE_ENTITIES -> tickPrimitiveJob(activeJob, desiredPrimitiveTask, lastPrimitiveExecution, actuationAllowed, tick);
			case ATTACK_ENTITY, USE_ENTITY -> tickPrimitiveJob(activeJob, desiredPrimitiveTask, lastPrimitiveExecution, actuationAllowed, tick);
			case ASK_USER -> tickAskUser(activeJob, tick);
			case MINE_BLOCKS -> tickMineBlocks(activeJob, lastPrimitiveExecution, actuationAllowed, tick);
			case ENSURE_BLOCKS_IN_INVENTORY -> tickEnsureBlocksInInventory(activeJob, lastPrimitiveExecution, lastEvidence, actuationAllowed, tick);
			case FOLLOW_PLAYER, NAVIGATE_TO -> tickGoalJob(activeJob, desiredPrimitiveTask, lastPrimitiveExecution, actuationAllowed, tick);
			case IDLE -> ActiveJob.idle();
		};
		if (activeJob.type() != ActiveJobType.COLLECT_RESOURCE) {
			collectResourceDebugSnapshot = CollectResourceTaskDebugSnapshot.empty();
		}
		refreshDesiredTask(tick);
	}

	private void refreshDesiredTask(long tick) {
		if (activeJob.isIdle() || activeJob.status().terminal()) {
			clearDesiredTaskState();
			return;
		}
		if (
			(activeJob.type() == ActiveJobType.MINE_BLOCKS || activeJob.type() == ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY)
				&& activeJob.directGoal() != null
		) {
			refreshMineBlocksAttempt(tick);
			return;
		}
		if (activeJob.directGoal() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.direct(activeJob.jobId(), activeJob.directGoal());
			return;
		}
		if (activeJob.type() == ActiveJobType.CRAFT_RECIPE && activeJob.craftRecipe() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.craftRecipe(activeJob.jobId(), activeJob.jobId(), activeJob.craftRecipe());
			return;
		}
		if (activeJob.type() == ActiveJobType.DROP_ITEMS && activeJob.dropItems() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.dropItems(activeJob.jobId(), activeJob.jobId(), activeJob.dropItems());
			return;
		}
		if (activeJob.type() == ActiveJobType.SMELT_ITEMS && activeJob.smeltItems() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.smeltItems(activeJob.jobId(), activeJob.jobId(), activeJob.smeltItems());
			return;
		}
		if (activeJob.type() == ActiveJobType.COLLECT_SMELTED_ITEMS && activeJob.collectSmeltedItems() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.collectSmeltedItems(activeJob.jobId(), activeJob.jobId(), activeJob.collectSmeltedItems());
			return;
		}
		if (activeJob.type() == ActiveJobType.RETURN_TO_SURFACE && activeJob.returnToSurface() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.returnToSurface(activeJob.jobId(), activeJob.jobId(), activeJob.returnToSurface());
			return;
		}
		if (activeJob.type() == ActiveJobType.PLACE_BLOCK && activeJob.blockPlacement() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.placeBlock(activeJob.jobId(), activeJob.jobId(), activeJob.blockPlacement());
			return;
		}
		if (activeJob.type() == ActiveJobType.USE_BLOCK && activeJob.blockUse() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.useBlock(activeJob.jobId(), activeJob.jobId(), activeJob.blockUse());
			return;
		}
		if (activeJob.type() == ActiveJobType.LURE_ENTITIES && activeJob.lureEntities() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.lureEntities(activeJob.jobId(), activeJob.jobId(), activeJob.lureEntities());
			return;
		}
		if (activeJob.type() == ActiveJobType.TEND_CROPS && activeJob.cropTending() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.tendCrops(activeJob.jobId(), activeJob.jobId(), activeJob.cropTending());
			return;
		}
		if (activeJob.type() == ActiveJobType.BREAK_BLOCKS && activeJob.blockBreak() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.breakBlocks(activeJob.jobId(), activeJob.jobId(), activeJob.blockBreak());
			return;
		}
		if (activeJob.type() == ActiveJobType.ATTACK_ENTITY && activeJob.entityInteraction() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.attackEntity(activeJob.jobId(), activeJob.jobId(), activeJob.entityInteraction());
			return;
		}
		if (activeJob.type() == ActiveJobType.USE_ENTITY && activeJob.entityInteraction() != null) {
			clearAttemptState();
			desiredPrimitiveTask = WorldTaskRequest.useEntity(activeJob.jobId(), activeJob.jobId(), activeJob.entityInteraction());
			return;
		}
		if (activeJob.type() != ActiveJobType.COLLECT_RESOURCE || activeJob.taskSpec() == null) {
			clearDesiredTaskState();
			return;
		}
		if (activeJob.status() != ActiveJobStatus.RUNNING) {
			clearDesiredTaskState();
			return;
		}
		int remaining = Math.max(1, activeJob.taskSpec().quantity() - activeJob.collectedCount());
		boolean collectTaskChanged = !Objects.equals(collectAttemptJobId, activeJob.jobId());
		boolean collectTaskMissing = desiredPrimitiveTask == null || !Objects.equals(desiredPrimitiveTask.sourceJobId(), activeJob.jobId());
		boolean primitiveCompleted = lastPrimitiveExecution.state() == TaskExecutionState.COMPLETED
			&& desiredPrimitiveTask != null
			&& Objects.equals(lastPrimitiveExecution.taskId(), desiredPrimitiveTask.taskId());
		if (collectTaskChanged || collectTaskMissing || primitiveCompleted) {
			startCollectAttempt(remaining, tick);
			return;
		}
		applyMinePickupSweepPositions();
	}

	private void refreshMineBlocksAttempt(long tick) {
		GoalMineSpec spec = activeJob.directGoal().mineSpec();
		int satisfiedCount = activeJob.type() == ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY
			? matchingItemCount(lastEvidence.itemCounts(), spec == null ? null : spec.matchingItemIds())
			: activeJob.collectedCount();
		if (spec == null) {
			clearDesiredTaskState();
			return;
		}
		if (satisfiedCount >= spec.quantity()) {
			applyMinePickupSweepPositions();
			return;
		}
		boolean mineTaskChanged = !Objects.equals(mineAttemptJobId, activeJob.jobId());
		boolean mineTaskMissing = desiredPrimitiveTask == null || !Objects.equals(desiredPrimitiveTask.sourceJobId(), activeJob.jobId());
		boolean primitiveCompleted = lastPrimitiveExecution.state() == TaskExecutionState.COMPLETED
			&& desiredPrimitiveTask != null
			&& Objects.equals(lastPrimitiveExecution.taskId(), desiredPrimitiveTask.taskId());
		if (mineTaskChanged || mineTaskMissing || primitiveCompleted) {
			startMineBlocksAttempt(spec, tick, satisfiedCount);
			return;
		}
		applyMinePickupSweepPositions();
	}

	private void startMineBlocksAttempt(GoalMineSpec requestedSpec, long tick, int satisfiedCount) {
		if (!Objects.equals(mineAttemptJobId, activeJob.jobId())) {
			mineAttemptJobId = activeJob.jobId();
			mineAttemptSequence = 0;
		}
		mineAttemptSequence++;
		int remainingToMine = Math.max(1, requestedSpec.quantity() - satisfiedCount);
		int absoluteInventoryTarget = activeJob.type() == ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY
			? requestedSpec.quantity()
			: matchingItemCount(lastEvidence.itemCounts(), requestedSpec.matchingItemIds()) + remainingToMine;
		GoalSnapshot executionGoal = new GoalSnapshot(
			GoalType.MINE_BLOCKS,
			null,
			null,
			new GoalMineSpec(
				requestedSpec.blockIds(),
				absoluteInventoryTarget,
				requestedSpec.matchingItemIds(),
				requestedSpec.requiredToolItemIds(),
				requestedSpec.constraints()
			),
			tick,
			activeJob.directGoal().source()
		);
		desiredPrimitiveTask = WorldTaskRequest.collectMine(
			activeJob.jobId() + ":mine:" + mineAttemptSequence,
			activeJob.jobId(),
			executionGoal,
			minePickupSweepPositions()
		);
	}

	private void startCollectAttempt(int remainingQuantity, long tick) {
		if (activeJob.taskSpec() == null) {
			clearDesiredTaskState();
			return;
		}
		if (!Objects.equals(collectAttemptJobId, activeJob.jobId())) {
			collectAttemptJobId = activeJob.jobId();
			collectAttemptSequence = 0;
		}
		collectAttemptSequence++;
		int absoluteInventoryTarget = activeJob.baselineResourceCount() + activeJob.taskSpec().quantity();
		GoalSnapshot goal = collectResourceTaskHandler.start(activeJob.taskSpec(), absoluteInventoryTarget, tick);
		desiredPrimitiveTask = WorldTaskRequest.collectMine(
			activeJob.jobId() + ":mine:" + collectAttemptSequence,
			activeJob.jobId(),
			goal,
			minePickupSweepPositions()
		);
	}

	private void applyMinePickupSweepPositions() {
		if (desiredPrimitiveTask != null && !minePickupSweepPositions().isEmpty()) {
			desiredPrimitiveTask = desiredPrimitiveTask.withPickupSweepPositions(minePickupSweepPositions());
		}
		if (desiredPrimitiveTask != null && activeJob.type() == ActiveJobType.MINE_BLOCKS && activeJob.directGoal() != null && activeJob.directGoal().mineSpec() != null) {
			desiredPrimitiveTask = desiredPrimitiveTask.withMineGoalSatisfied(
				activeJob.collectedCount() >= activeJob.directGoal().mineSpec().quantity()
			);
		}
	}

	private List<GoalPosition> minePickupSweepPositions() {
		return Objects.equals(minePickupSweepJobId, activeJob.jobId()) ? List.copyOf(minePickupSweepPositions) : List.of();
	}

	private void clearDesiredTaskState() {
		desiredPrimitiveTask = null;
		clearAttemptState();
		minePickupSweepJobId = null;
		minePickupSweepPositions.clear();
	}

	private void clearCollectAttemptState() {
		collectAttemptJobId = null;
		collectAttemptSequence = 0;
	}

	private void clearMineAttemptState() {
		mineAttemptJobId = null;
		mineAttemptSequence = 0;
	}

	private void clearAttemptState() {
		clearCollectAttemptState();
		clearMineAttemptState();
	}

	public TaskSnapshot taskSnapshot() {
		TaskSpec spec = activeJob.taskSpec();
		TaskProgressSnapshot progress = spec == null
			? new TaskProgressSnapshot(0, 0)
			: TaskProgressSnapshot.of(activeJob.collectedCount(), spec.quantity());
		return new TaskSnapshot(
			toTaskState(activeJob),
			compatibilityMission,
			compatibilityLedger,
			spec,
			progress,
			TaskStep.NONE,
			TaskOwnership.NONE,
			activeJob.source(),
			activeJob.lastError(),
			compatibilityLedger == null ? activeStepId() : compatibilityLedger.activeStepId(),
			compatibilityActiveStepKind(),
			lastStepResult(),
			activeJob.updatedTick(),
			activeJob.jobId()
		);
	}

	public MissionExecutionSnapshot missionExecutionSnapshot() {
		return new MissionExecutionSnapshot(
			compatibilityMission,
			compatibilityLedger,
			null,
			lastEvidence,
			lastStepResult(),
			lastPrimitiveExecution
		);
	}

	private ActiveJob tickCollectResource(
		ActiveJob job,
		TaskExecutionSnapshot primitiveExecution,
		WorldEvidence evidence,
		boolean actuationAllowed,
		boolean nearbyResourceTargetAvailable,
		long tick
	) {
		TaskSpec spec = job.taskSpec();
		int currentCount = evidence.inventoryCounts().getOrDefault(spec.resourceKind(), job.baselineResourceCount());
		int collected = Math.max(0, currentCount - job.baselineResourceCount());
		if (collected >= spec.quantity()) {
			ActiveJob completed = updated(job, ActiveJobStatus.COMPLETED, null, null, collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(completed, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), "inventory_delta_reached", tick);
			return completed;
		}
		if (!actuationAllowed || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_REFLEX) {
			ActiveJob blocked = updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(blocked, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), null, tick);
			return blocked;
		}
		if (primitiveExecution.state() == TaskExecutionState.FAILED) {
			ActiveJob failed = updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_failed"), collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(failed, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), failed.lastError(), tick);
			return failed;
		}
		if (primitiveExecution.state() == TaskExecutionState.CANCELLED) {
			ActiveJob cancelled = updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_cancelled"), collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(cancelled, currentCount, nearbyResourceTargetAvailable, primitiveExecution.state(), cancelled.lastError(), tick);
			return cancelled;
		}
		if (!nearbyResourceTargetAvailable && !"action_graph".equals(job.source())) {
			ActiveJob blocked = updated(job, ActiveJobStatus.BLOCKED, "target_missing", null, collected, tick);
			collectResourceDebugSnapshot = collectResourceProbe(blocked, currentCount, false, primitiveExecution.state(), null, tick);
			return blocked;
		}
		ActiveJob running = updated(job, ActiveJobStatus.RUNNING, null, null, collected, tick);
		collectResourceDebugSnapshot = collectResourceProbe(running, currentCount, true, primitiveExecution.state(), null, tick);
		return running;
	}

	private static ActiveJob tickAskUser(ActiveJob job, long tick) {
		return updated(job, ActiveJobStatus.BLOCKED, "waiting_for_user", null, job.collectedCount(), tick);
	}

	private static ActiveJob tickEnsureBlocksInInventory(
		ActiveJob job,
		TaskExecutionSnapshot primitiveExecution,
		WorldEvidence evidence,
		boolean actuationAllowed,
		long tick
	) {
		GoalMineSpec spec = job.directGoal() == null ? null : job.directGoal().mineSpec();
		if (spec == null) {
			return updated(job, ActiveJobStatus.FAILED, null, "missing_mine_blocks_args", job.collectedCount(), tick);
		}
		int currentItemCount = matchingItemCount(evidence.itemCounts(), spec.matchingItemIds());
		if (currentItemCount >= spec.quantity()) {
			return updated(job, ActiveJobStatus.COMPLETED, null, null, job.collectedCount(), tick);
		}
		if (!actuationAllowed || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_REFLEX) {
			return updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
		}
		return switch (primitiveExecution.state()) {
			case RUNNING -> updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
			case COMPLETED, IDLE -> updated(job, ActiveJobStatus.QUEUED, null, null, job.collectedCount(), tick);
			case FAILED -> updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_failed"), job.collectedCount(), tick);
			case CANCELLED -> updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_cancelled"), job.collectedCount(), tick);
			case PAUSED_BY_SESSION_GATE -> updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
			case PAUSED_BY_REFLEX -> updated(job, ActiveJobStatus.BLOCKED, "reflex", null, job.collectedCount(), tick);
		};
	}

	private static ActiveJob tickPrimitiveJob(
		ActiveJob job,
		WorldTaskRequest desiredPrimitiveTask,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		long tick
	) {
		if (!actuationAllowed) {
			return updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
		}
		if (primitiveExecution.state() == TaskExecutionState.IDLE) {
			return updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
		}
		if (!primitiveExecutionMatches(desiredPrimitiveTask, primitiveExecution)) {
			return job;
		}
		return switch (primitiveExecution.state()) {
			case RUNNING, IDLE -> updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
			case COMPLETED -> updated(job, ActiveJobStatus.COMPLETED, null, null, job.collectedCount(), tick);
			case FAILED -> updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), primitiveFailureFallback(job.type())), job.collectedCount(), tick);
			case CANCELLED -> updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), primitiveCancelledFallback(job.type())), job.collectedCount(), tick);
			case PAUSED_BY_SESSION_GATE -> updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
			case PAUSED_BY_REFLEX -> updated(job, ActiveJobStatus.BLOCKED, "reflex", null, job.collectedCount(), tick);
		};
	}

	private static boolean primitiveExecutionMatches(WorldTaskRequest desiredPrimitiveTask, TaskExecutionSnapshot primitiveExecution) {
		return desiredPrimitiveTask != null
			&& primitiveExecution != null
			&& Objects.equals(desiredPrimitiveTask.taskId(), primitiveExecution.taskId());
	}

	private static String primitiveFailureFallback(ActiveJobType type) {
		return switch (type) {
			case DROP_ITEMS -> "drop_items_failed";
			case SMELT_ITEMS -> "smelt_items_failed";
			case COLLECT_SMELTED_ITEMS -> "collect_smelted_items_failed";
			case ATTACK_ENTITY -> "attack_entity_failed";
			case USE_ENTITY -> "use_entity_failed";
			case PLACE_BLOCK -> "place_block_failed";
			case USE_BLOCK -> "use_block_failed";
			case RETURN_TO_SURFACE -> "return_to_surface_failed";
			default -> "crafting_failed";
		};
	}

	private static String primitiveCancelledFallback(ActiveJobType type) {
		return switch (type) {
			case DROP_ITEMS -> "drop_items_cancelled";
			case SMELT_ITEMS -> "smelt_items_cancelled";
			case COLLECT_SMELTED_ITEMS -> "collect_smelted_items_cancelled";
			case ATTACK_ENTITY -> "attack_entity_cancelled";
			case USE_ENTITY -> "use_entity_cancelled";
			case PLACE_BLOCK -> "place_block_cancelled";
			case USE_BLOCK -> "use_block_cancelled";
			case RETURN_TO_SURFACE -> "return_to_surface_cancelled";
			default -> "crafting_cancelled";
		};
	}

	private static ActiveJob tickMineBlocks(
		ActiveJob job,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		long tick
	) {
		GoalMineSpec spec = job.directGoal() == null ? null : job.directGoal().mineSpec();
		if (spec == null) {
			return updated(job, ActiveJobStatus.FAILED, null, "missing_mine_blocks_args", job.collectedCount(), tick);
		}
		if (job.collectedCount() >= spec.quantity()) {
			return updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
		}
		if (!actuationAllowed || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_REFLEX) {
			return updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
		}
		return switch (primitiveExecution.state()) {
			case RUNNING -> updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
			case COMPLETED -> updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
			case FAILED -> updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_failed"), job.collectedCount(), tick);
			case CANCELLED -> updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_cancelled"), job.collectedCount(), tick);
			case IDLE, PAUSED_BY_SESSION_GATE -> updated(job, ActiveJobStatus.QUEUED, null, null, job.collectedCount(), tick);
			case PAUSED_BY_REFLEX -> updated(job, ActiveJobStatus.BLOCKED, "reflex", null, job.collectedCount(), tick);
		};
	}

	private static ActiveJob tickGoalJob(
		ActiveJob job,
		WorldTaskRequest desiredPrimitiveTask,
		TaskExecutionSnapshot primitiveExecution,
		boolean actuationAllowed,
		long tick
	) {
		if (!primitiveExecutionMatches(desiredPrimitiveTask, primitiveExecution)) return job;
		if (!actuationAllowed || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_SESSION_GATE || primitiveExecution.state() == TaskExecutionState.PAUSED_BY_REFLEX) {
			return updated(job, ActiveJobStatus.BLOCKED, "session_gate", null, job.collectedCount(), tick);
		}
		return switch (primitiveExecution.state()) {
			case RUNNING -> updated(job, ActiveJobStatus.RUNNING, null, null, job.collectedCount(), tick);
			case COMPLETED -> updated(job, ActiveJobStatus.COMPLETED, null, null, job.collectedCount(), tick);
			case FAILED -> updated(job, ActiveJobStatus.FAILED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_failed"), job.collectedCount(), tick);
			case CANCELLED -> updated(job, ActiveJobStatus.CANCELLED, null, nonEmpty(primitiveExecution.lastPathEvent(), "task_cancelled"), job.collectedCount(), tick);
			case IDLE, PAUSED_BY_SESSION_GATE -> updated(job, ActiveJobStatus.QUEUED, null, null, job.collectedCount(), tick);
			case PAUSED_BY_REFLEX -> updated(job, ActiveJobStatus.BLOCKED, "reflex", null, job.collectedCount(), tick);
		};
	}

	private static ActiveJobStatus activeMiningStatus(ActiveJobStatus status) {
		return status == ActiveJobStatus.BLOCKED || status == ActiveJobStatus.QUEUED || status == ActiveJobStatus.IDLE
			? ActiveJobStatus.RUNNING
			: status;
	}

	private static boolean isActiveMiningInventoryTask(ActiveJobType type) {
		return type == ActiveJobType.MINE_BLOCKS || type == ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY;
	}

	private static TaskTerminalEvent withTerminalMessage(TaskTerminalEvent event, String message) {
		return new TaskTerminalEvent(
			event.taskId(),
			event.goal(),
			event.terminalState(),
			message,
			event.terminationCause(),
			event.failureCode()
		);
	}

	private static TaskTerminalEvent completedMineBlocksTerminalEvent(TaskTerminalEvent event) {
		if (event.terminalState() == TaskExecutionState.COMPLETED) {
			return event;
		}
		return new TaskTerminalEvent(
			event.taskId(),
			event.goal(),
			TaskExecutionState.COMPLETED,
			"Goal reached",
			TaskTerminationCause.GOAL_REACHED,
			ai.moeru.airicraft.agent.tasks.TaskFailureCode.NONE
		);
	}

	private static String appendBrokenBlocks(String message, int brokenBlocks) {
		return nonEmpty(message, "Task finished") + " brokenBlocks=" + Math.max(0, brokenBlocks);
	}

	private static String mineBlocksTerminalMessage(String message, int brokenBlocks, int requestedBlocks, boolean mismatch) {
		String result = appendBrokenBlocks(message, brokenBlocks) + " requestedBlocks=" + Math.max(0, requestedBlocks);
		return mismatch ? result + " warning=broken_block_count_mismatch" : result;
	}

	private static String mineBlocksMismatchWarning(String taskId, int brokenBlocks, int requestedBlocks) {
		return "TASK WARNING: mine_blocks broken_block_count_mismatch"
			+ " taskId=" + nonEmpty(taskId, "")
			+ " brokenBlocks=" + Math.max(0, brokenBlocks)
			+ " requestedBlocks=" + Math.max(0, requestedBlocks)
			+ ". Runtime will keep mining until the requested broken block count is reached.";
	}

	private static String ensureBlocksInventoryShortfallWarning(String taskId, int brokenBlocks, int itemCount, GoalMineSpec spec) {
		return "TASK WARNING: ensure_blocks_in_inventory inventory_target_not_satisfied"
			+ " taskId=" + nonEmpty(taskId, "")
			+ " brokenBlocks=" + Math.max(0, brokenBlocks)
			+ " itemCount=" + Math.max(0, itemCount)
			+ " requestedItemCount=" + (spec == null ? 0 : Math.max(0, spec.quantity()))
			+ " matchingItemIds=" + (spec == null ? List.of() : spec.matchingItemIds())
			+ ". Runtime will keep mining until the requested inventory count is reached.";
	}

	private static int matchingItemCount(Map<String, Integer> itemCounts, List<String> matchingItemIds) {
		if (itemCounts == null || itemCounts.isEmpty() || matchingItemIds == null || matchingItemIds.isEmpty()) {
			return 0;
		}
		int total = 0;
		for (String itemId : matchingItemIds) {
			total += itemCounts.getOrDefault(itemId, 0);
		}
		return total;
	}

	public record TerminalTaskReport(Optional<TaskTerminalEvent> event, Optional<String> warning) {
		public TerminalTaskReport {
			event = event == null ? Optional.empty() : event;
			warning = warning == null ? Optional.empty() : warning
				.map(String::trim)
				.filter(value -> !value.isEmpty());
		}

		public static TerminalTaskReport empty() {
			return new TerminalTaskReport(Optional.empty(), Optional.empty());
		}

		public static TerminalTaskReport emit(TaskTerminalEvent event) {
			return new TerminalTaskReport(Optional.ofNullable(event), Optional.empty());
		}

		public static TerminalTaskReport warnOnly(String warning) {
			return new TerminalTaskReport(Optional.empty(), Optional.ofNullable(warning));
		}

		public static TerminalTaskReport of(TaskTerminalEvent event, String warning) {
			return new TerminalTaskReport(Optional.ofNullable(event), Optional.ofNullable(warning));
		}
	}

	private ActiveJob fromLedger(TaskLedger ledger, int currentResourceCount, String source, long tick) {
		if (ledger.activeStepId() == null) {
			return new ActiveJob(ledger.missionId(), ActiveJobType.IDLE, ActiveJobStatus.COMPLETED, null, null, null, null, -1L, 0, 0, source, null, null, tick);
		}
		LedgerStep activeStep = ledger.steps().stream()
			.filter(step -> Objects.equals(step.id(), ledger.activeStepId()))
			.findFirst()
			.orElse(null);
		if (activeStep == null) {
			return ActiveJob.idle();
		}
		return switch (activeStep.kind()) {
			case COLLECT_RESOURCE -> fromCollectResourceStep(ledger.missionId(), activeStep.args().collectResource(), currentResourceCount, source, tick);
			case NAVIGATE_TO_POSITION -> fromDirectGoal(
				ledger.missionId(),
				new GoalSnapshot(GoalType.NAVIGATE_TO, null, activeStep.args().navigateToPosition(), null, tick, source),
				ActiveJobType.NAVIGATE_TO,
				source,
				tick
			);
			case MINE_BLOCKS -> fromDirectGoal(
				ledger.missionId(),
				new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, activeStep.args().mineBlocks(), tick, source),
				ActiveJobType.MINE_BLOCKS,
				source,
				tick
			);
			case CRAFT_RECIPE -> fromCraftRecipeStep(ledger.missionId(), activeStep.args().craftRecipe(), source, tick);
			case DROP_ITEMS -> fromDropItemsStep(ledger.missionId(), activeStep.args().dropItems(), source, tick);
			case ATTACK_ENTITY -> fromEntityInteractionStep(ledger.missionId(), ActiveJobType.ATTACK_ENTITY, activeStep.args().entityInteraction(), source, tick);
			case USE_ENTITY -> fromEntityInteractionStep(ledger.missionId(), ActiveJobType.USE_ENTITY, activeStep.args().entityInteraction(), source, tick);
			case PLACE_BLOCK -> fromBlockPlacementStep(ledger.missionId(), activeStep.args().placeBlock() == null ? null : new BlockPlacementStepArgs(
				activeStep.args().placeBlock().itemId(),
				activeStep.args().placeBlock().position(),
				null,
				null
			), source, tick);
			case USE_BLOCK -> fromBlockUseStep(ledger.missionId(), activeStep.args().blockUse(), source, tick);
			case BREAK_BLOCKS -> fromBlockBreakStep(ledger.missionId(), activeStep.args().blockBreak(), source, tick);
			case ASK_USER -> fromAskUserStep(ledger.missionId(), activeStep.args().askUser(), source, tick);
			case FINISH -> new ActiveJob(ledger.missionId(), ActiveJobType.IDLE, ActiveJobStatus.COMPLETED, null, null, null, null, -1L, 0, 0, source, null, null, tick);
			default -> new ActiveJob(ledger.missionId(), ActiveJobType.ASK_USER, ActiveJobStatus.BLOCKED, null, null, null, "Unsupported step: " + activeStep.kind().name(), -1L, 0, 0, source, "unsupported_step", null, tick);
		};
	}

	private static ActiveJob fromAskUserStep(String jobId, AskUserStepArgs askUser, String source, long tick) {
		return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.BLOCKED, null, null, null, askUser == null ? null : askUser.prompt(), -1L, 0, 0, source, "waiting_for_user", null, tick);
	}

	private static ActiveJob fromCraftRecipeStep(String jobId, CraftRecipeStepArgs craftRecipe, String source, long tick) {
		if (craftRecipe == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_craft_recipe_args", tick);
		}
		return new ActiveJob(jobId, ActiveJobType.CRAFT_RECIPE, ActiveJobStatus.QUEUED, null, null, craftRecipe, null, -1L, 0, 0, source, null, null, tick);
	}

	private static ActiveJob fromDropItemsStep(String jobId, DropItemsStepArgs dropItems, String source, long tick) {
		if (dropItems == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_drop_items_args", tick);
		}
		return new ActiveJob(jobId, ActiveJobType.DROP_ITEMS, ActiveJobStatus.QUEUED, null, null, null, dropItems, null, -1L, 0, 0, source, null, null, tick);
	}

	private static ActiveJob fromSmeltItemsStep(String jobId, SmeltItemsStepArgs smeltItems, String source, long tick) {
		if (smeltItems == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_smelt_items_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.SMELT_ITEMS,
			ActiveJobStatus.QUEUED,
			null,
			null,
			null,
			null,
			null,
			smeltItems,
			null,
			null,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private static ActiveJob fromCollectSmeltedItemsStep(String jobId, CollectSmeltedItemsStepArgs collectSmeltedItems, String source, long tick) {
		if (collectSmeltedItems == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_collect_smelted_items_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.COLLECT_SMELTED_ITEMS,
			ActiveJobStatus.QUEUED,
			null,
			null,
			null,
			null,
			null,
			null,
			collectSmeltedItems,
			null,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private static ActiveJob fromReturnToSurfaceStep(String jobId, ReturnToSurfaceStepArgs returnToSurface, String source, long tick) {
		if (returnToSurface == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_return_to_surface_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.RETURN_TO_SURFACE,
			ActiveJobStatus.QUEUED,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			returnToSurface,
			null,
			null,
			null,
			null,
			-1L,
			0,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private static ActiveJob fromEntityInteractionStep(
		String jobId,
		ActiveJobType type,
		EntityInteractionStepArgs entityInteraction,
		String source,
		long tick
	) {
		if (entityInteraction == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, null, -1L, 0, 0, source, null, "missing_entity_interaction_args", tick);
		}
		return new ActiveJob(jobId, type, ActiveJobStatus.QUEUED, null, null, null, null, entityInteraction, null, -1L, 0, 0, source, null, null, tick);
	}

	private static ActiveJob fromBlockPlacementStep(String jobId, BlockPlacementStepArgs blockPlacement, String source, long tick) {
		if (blockPlacement == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_place_block_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.PLACE_BLOCK,
			ActiveJobStatus.QUEUED,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			blockPlacement,
			null,
			null,
			null,
			-1L,
			0,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private static ActiveJob fromBlockUseStep(String jobId, BlockUseStepArgs blockUse, String source, long tick) {
		if (blockUse == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_use_block_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.USE_BLOCK,
			ActiveJobStatus.QUEUED,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			blockUse,
			null,
			null,
			-1L,
			0,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private static ActiveJob fromBlockBreakStep(String jobId, BlockBreakStepArgs blockBreak, String source, long tick) {
		if (blockBreak == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_break_blocks_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.BREAK_BLOCKS,
			ActiveJobStatus.QUEUED,
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
			blockBreak,
			null,
			-1L,
			0,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private ActiveJob fromCollectResourceStep(String jobId, CollectResourceStepArgs args, int currentResourceCount, String source, long tick) {
		if (args == null) {
			return new ActiveJob(jobId, ActiveJobType.ASK_USER, ActiveJobStatus.FAILED, null, null, null, null, -1L, 0, 0, source, null, "missing_collect_resource_args", tick);
		}
		return new ActiveJob(
			jobId,
			ActiveJobType.COLLECT_RESOURCE,
			ActiveJobStatus.QUEUED,
			null,
			new TaskSpec(ai.moeru.airicraft.agent.tasks.TaskType.COLLECT_RESOURCE, args.resourceKind(), args.quantity()),
			null,
			null,
			-1L,
			currentResourceCount,
			0,
			source,
			null,
			null,
			tick
		);
	}

	private static ActiveJob fromGoalResponse(DialogueResponse response, String source) {
		GoalSnapshot goal = new GoalSnapshot(
			response.intent().goalType(),
			response.intent().targetPlayer(),
			response.intent().position(),
			response.intent().mineSpec(),
			response.tick(),
			source
		);
		ActiveJobType type = switch (response.intent().goalType()) {
			case FOLLOW_PLAYER -> ActiveJobType.FOLLOW_PLAYER;
			case NAVIGATE_TO -> ActiveJobType.NAVIGATE_TO;
			case MINE_BLOCKS -> ActiveJobType.MINE_BLOCKS;
		};
		return fromDirectGoal(newJobId(), goal, type, source, response.tick());
	}

	private static ActiveJob fromProposal(ActiveJobProposal proposal, int currentResourceCount, String source, long tick) {
		return switch (proposal.type()) {
			case FOLLOW_PLAYER -> fromDirectGoal(
				newJobId(),
				new GoalSnapshot(GoalType.FOLLOW_PLAYER, proposal.targetPlayer(), null, null, tick, source),
				ActiveJobType.FOLLOW_PLAYER,
				source,
				tick
			);
			case NAVIGATE_TO -> fromDirectGoal(
				newJobId(),
				new GoalSnapshot(GoalType.NAVIGATE_TO, null, proposal.position(), null, tick, source),
				ActiveJobType.NAVIGATE_TO,
				source,
				tick
			);
			case MINE_BLOCKS -> fromDirectGoal(
				newJobId(),
				new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, proposal.mineSpec(), tick, source),
				ActiveJobType.MINE_BLOCKS,
				source,
				tick
			);
			case ENSURE_BLOCKS_IN_INVENTORY -> fromDirectGoal(
				newJobId(),
				new GoalSnapshot(GoalType.MINE_BLOCKS, null, null, proposal.mineSpec(), tick, source),
				ActiveJobType.ENSURE_BLOCKS_IN_INVENTORY,
				source,
				tick
			);
			case COLLECT_RESOURCE -> new ActiveJob(
				newJobId(),
				ActiveJobType.COLLECT_RESOURCE,
				ActiveJobStatus.QUEUED,
				null,
				proposal.taskSpec(),
				null,
				null,
				-1L,
				currentResourceCount,
				0,
				source,
				null,
				null,
				tick
			);
			case CRAFT_RECIPE -> fromCraftRecipeStep(newJobId(), proposal.craftRecipe(), source, tick);
			case DROP_ITEMS -> fromDropItemsStep(newJobId(), proposal.dropItems(), source, tick);
			case SMELT_ITEMS -> fromSmeltItemsStep(newJobId(), proposal.smeltItems(), source, tick);
			case COLLECT_SMELTED_ITEMS -> fromCollectSmeltedItemsStep(newJobId(), proposal.collectSmeltedItems(), source, tick);
			case ATTACK_ENTITY -> fromEntityInteractionStep(newJobId(), ActiveJobType.ATTACK_ENTITY, proposal.entityInteraction(), source, tick);
			case USE_ENTITY -> fromEntityInteractionStep(newJobId(), ActiveJobType.USE_ENTITY, proposal.entityInteraction(), source, tick);
			case PLACE_BLOCK -> fromBlockPlacementStep(newJobId(), proposal.blockPlacement(), source, tick);
			case USE_BLOCK -> fromBlockUseStep(newJobId(), proposal.blockUse(), source, tick);
			case BREAK_BLOCKS -> fromBlockBreakStep(newJobId(), proposal.blockBreak(), source, tick);
			case LURE_ENTITIES -> new ActiveJob(newJobId(), ActiveJobType.LURE_ENTITIES, ActiveJobStatus.QUEUED,
				null, null, null, null, null, null, null, null, null, null, null, null,
				-1L, 0, 0, source, null, null, tick, null, proposal.lureEntities());
			case TEND_CROPS -> new ActiveJob(newJobId(), ActiveJobType.TEND_CROPS, ActiveJobStatus.QUEUED,
				null, null, null, null, null, null, null, null, null, null, null, null,
				-1L, 0, 0, source, null, null, tick, proposal.cropTending());
			case RETURN_TO_SURFACE -> fromReturnToSurfaceStep(newJobId(), proposal.returnToSurface(), source, tick);
			case ASK_USER -> fromAskUserStep(newJobId(), new AskUserStepArgs(proposal.askPrompt()), source, tick);
			case IDLE -> ActiveJob.idle();
		};
	}

	private static ActiveJob fromDirectGoal(String jobId, GoalSnapshot goal, ActiveJobType type, String source, long tick) {
		return new ActiveJob(jobId, type, ActiveJobStatus.QUEUED, goal, null, null, null, -1L, 0, 0, source, null, null, tick);
	}

	private ActiveJob preserveProgressIfSame(ActiveJob next, long tick) {
		if (sameJobTarget(activeJob, next) && !activeJob.status().terminal()) {
			return new ActiveJob(
				activeJob.jobId(),
				next.type(),
				activeJob.status(),
				next.directGoal(),
				next.taskSpec(),
				next.craftRecipe(),
				next.dropItems(),
				next.entityInteraction(),
				next.smeltItems(),
				next.collectSmeltedItems(),
				next.returnToSurface(),
				next.blockPlacement(),
				next.blockUse(),
				next.blockBreak(),
				next.askPrompt(),
				next.waitUntilTick(),
				activeJob.baselineResourceCount(),
				activeJob.collectedCount(),
				next.source(),
				activeJob.blockedReason(),
				activeJob.lastError(),
				tick,
				next.cropTending(),
			next.lureEntities()
			);
		}
		return next;
	}

	private static boolean sameJobTarget(ActiveJob left, ActiveJob right) {
		if (left == null || right == null || left.type() != right.type()) {
			return false;
		}
		if (left.directGoal() != null || right.directGoal() != null) {
			return Objects.equals(left.directGoal(), right.directGoal());
		}
		if (left.taskSpec() != null || right.taskSpec() != null) {
			return Objects.equals(left.taskSpec(), right.taskSpec());
		}
		if (left.craftRecipe() != null || right.craftRecipe() != null) {
			return Objects.equals(left.craftRecipe(), right.craftRecipe());
		}
		if (left.dropItems() != null || right.dropItems() != null) {
			return Objects.equals(left.dropItems(), right.dropItems());
		}
		if (left.entityInteraction() != null || right.entityInteraction() != null) {
			return Objects.equals(left.entityInteraction(), right.entityInteraction());
		}
		if (left.smeltItems() != null || right.smeltItems() != null) {
			return Objects.equals(left.smeltItems(), right.smeltItems());
		}
		if (left.collectSmeltedItems() != null || right.collectSmeltedItems() != null) {
			return Objects.equals(left.collectSmeltedItems(), right.collectSmeltedItems());
		}
		if (left.lureEntities() != null || right.lureEntities() != null) return Objects.equals(left.lureEntities(), right.lureEntities());
		if (left.cropTending() != null || right.cropTending() != null) return Objects.equals(left.cropTending(), right.cropTending());
		if (left.returnToSurface() != null || right.returnToSurface() != null) {
			return Objects.equals(left.returnToSurface(), right.returnToSurface());
		}
		if (left.blockPlacement() != null || right.blockPlacement() != null) {
			return Objects.equals(left.blockPlacement(), right.blockPlacement());
		}
		if (left.blockUse() != null || right.blockUse() != null) {
			return Objects.equals(left.blockUse(), right.blockUse());
		}
		if (left.blockBreak() != null || right.blockBreak() != null) {
			return Objects.equals(left.blockBreak(), right.blockBreak());
		}
		return Objects.equals(left.askPrompt(), right.askPrompt()) && left.waitUntilTick() == right.waitUntilTick();
	}

	private MissionSpec missionSpec() {
		if (activeJob.isIdle()) {
			return null;
		}
		MissionType missionType = switch (activeJob.type()) {
			case COLLECT_RESOURCE -> MissionType.COLLECT_RESOURCE;
			case CRAFT_RECIPE -> MissionType.CRAFT_ITEM;
			case DROP_ITEMS -> MissionType.DELIVER_ITEM;
			case SMELT_ITEMS, COLLECT_SMELTED_ITEMS -> MissionType.CRAFT_ITEM;
			case RETURN_TO_SURFACE -> MissionType.COLLECT_RESOURCE;
			case ATTACK_ENTITY, USE_ENTITY -> MissionType.COLLECT_RESOURCE;
			default -> MissionType.COLLECT_RESOURCE;
		};
		return new MissionSpec(activeJob.jobId(), missionType, goalText());
	}

	private String goalText() {
		return switch (activeJob.type()) {
			case FOLLOW_PLAYER -> "Follow " + (activeJob.directGoal() == null ? "" : nonEmpty(activeJob.directGoal().targetPlayer(), "player"));
			case NAVIGATE_TO -> "Navigate to target";
			case MINE_BLOCKS -> "Mine blocks";
			case ENSURE_BLOCKS_IN_INVENTORY -> "Ensure blocks in inventory";
			case COLLECT_RESOURCE -> activeJob.taskSpec() == null ? "Collect resource" : "Collect " + activeJob.taskSpec().quantity() + " " + activeJob.taskSpec().resourceKind().name().toLowerCase();
			case CRAFT_RECIPE -> activeJob.craftRecipe() == null ? "Craft recipe" : "Run recipe " + activeJob.craftRecipe().recipeId() + " x" + activeJob.craftRecipe().times();
			case DROP_ITEMS -> activeJob.dropItems() == null ? "Drop items" : "Drop " + activeJob.dropItems().quantity() + " " + activeJob.dropItems().itemId();
			case SMELT_ITEMS -> activeJob.smeltItems() == null ? "Smelt items" : "Smelt " + activeJob.smeltItems().inputQuantity() + " via " + activeJob.smeltItems().optionId();
			case COLLECT_SMELTED_ITEMS -> activeJob.collectSmeltedItems() == null ? "Collect smelted items" : "Collect smelted output";
			case RETURN_TO_SURFACE -> "Return to surface";
			case PLACE_BLOCK -> activeJob.blockPlacement() == null ? "Place block" : "Place " + activeJob.blockPlacement().targets().size() + " " + activeJob.blockPlacement().itemId();
			case USE_BLOCK -> activeJob.blockUse() == null ? "Use block" : "Use block at " + activeJob.blockUse().targets().size() + " target blocks";
			case TEND_CROPS -> "Tend crops in a bounded plot";
			case LURE_ENTITIES -> "Lure selected animals into the destination area";
			case BREAK_BLOCKS -> activeJob.blockBreak() == null ? "Break blocks" : "Break " + activeJob.blockBreak().targets().size() + " target blocks";
			case ATTACK_ENTITY -> activeJob.entityInteraction() == null ? "Attack entity" : "Attack " + activeJob.entityInteraction().selector();
			case USE_ENTITY -> activeJob.entityInteraction() == null ? "Use entity" : "Use on " + activeJob.entityInteraction().selector();
			case ASK_USER -> "Ask user";
			case IDLE -> "";
		};
	}

	private ai.moeru.airicraft.agent.tasks.LedgerStepKind activeStepKind() {
		return switch (activeJob.type()) {
			case FOLLOW_PLAYER, NAVIGATE_TO, LURE_ENTITIES -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.NAVIGATE_TO_POSITION;
			case MINE_BLOCKS, ENSURE_BLOCKS_IN_INVENTORY -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.MINE_BLOCKS;
			case COLLECT_RESOURCE -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.COLLECT_RESOURCE;
			case CRAFT_RECIPE -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.CRAFT_RECIPE;
			case DROP_ITEMS -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.DROP_ITEMS;
			case SMELT_ITEMS -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.SMELT_ITEMS;
			case COLLECT_SMELTED_ITEMS -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.COLLECT_SMELTED_ITEMS;
			case RETURN_TO_SURFACE -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.NAVIGATE_TO_POSITION;
			case PLACE_BLOCK -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.PLACE_BLOCK;
			case USE_BLOCK, TEND_CROPS -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.USE_BLOCK;
			case BREAK_BLOCKS -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.MINE_BLOCKS;
			case ATTACK_ENTITY -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.ATTACK_ENTITY;
			case USE_ENTITY -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.USE_ENTITY;
			case ASK_USER -> ai.moeru.airicraft.agent.tasks.LedgerStepKind.ASK_USER;
			case IDLE -> null;
		};
	}

	private String activeStepId() {
		return activeJob.isIdle() ? null : activeJob.type().name().toLowerCase();
	}

	private ai.moeru.airicraft.agent.tasks.LedgerStepKind compatibilityActiveStepKind() {
		if (compatibilityLedger != null && compatibilityLedger.activeStepId() != null) {
			return compatibilityLedger.steps().stream()
				.filter(step -> Objects.equals(step.id(), compatibilityLedger.activeStepId()))
				.map(LedgerStep::kind)
				.findFirst()
				.orElse(activeStepKind());
		}
		return activeStepKind();
	}

	private StepExecutionResult lastStepResult() {
		if (activeJob.isIdle()) {
			return StepExecutionResult.idle();
		}
		StepExecutionStatus status = switch (activeJob.status()) {
			case IDLE, QUEUED -> StepExecutionStatus.IDLE;
			case RUNNING -> StepExecutionStatus.RUNNING;
			case BLOCKED -> StepExecutionStatus.WAITING;
			case COMPLETED -> StepExecutionStatus.COMPLETED;
			case FAILED -> StepExecutionStatus.FAILED;
			case CANCELLED -> StepExecutionStatus.CANCELLED;
		};
		return new StepExecutionResult(
			activeJob.type().name().toLowerCase(),
			status,
			activeJob.lastError(),
			Map.of(),
			Map.of("jobType", activeJob.type().name()),
			activeJob.updatedTick()
		);
	}

	private static TaskState toTaskState(ActiveJob job) {
		return switch (job.status()) {
			case IDLE -> TaskState.IDLE;
			case QUEUED -> TaskState.QUEUED;
			case RUNNING -> TaskState.RUNNING;
			case BLOCKED -> switch (job.blockedReason() == null ? "" : job.blockedReason()) {
				case "session_gate" -> TaskState.PAUSED_BY_SESSION_GATE;
				case "reflex" -> TaskState.PAUSED_BY_REFLEX;
				default -> TaskState.WAITING_FOR_PICKUP;
			};
			case COMPLETED -> TaskState.COMPLETED;
			case FAILED -> TaskState.FAILED;
			case CANCELLED -> TaskState.CANCELLED;
		};
	}

	private static ActiveJob updated(
		ActiveJob job,
		ActiveJobStatus status,
		String blockedReason,
		String lastError,
		int collectedCount,
		long tick
	) {
		return new ActiveJob(
			job.jobId(),
			job.type(),
			status,
			job.directGoal(),
			job.taskSpec(),
			job.craftRecipe(),
			job.dropItems(),
			job.entityInteraction(),
			job.smeltItems(),
			job.collectSmeltedItems(),
			job.returnToSurface(),
			job.blockPlacement(),
			job.blockUse(),
			job.blockBreak(),
			job.askPrompt(),
			job.waitUntilTick(),
			job.baselineResourceCount(),
			collectedCount,
			job.source(),
			blockedReason,
			lastError,
			tick,
			job.cropTending(),
			job.lureEntities()
		);
	}

	private static String normalizeSource(String source) {
		return source == null || source.isBlank() ? "runtime" : source;
	}

	private static MissionSpec missionFromLedger(TaskLedger ledger) {
		return new MissionSpec(ledger.missionId(), ledger.missionType(), ledger.goalText());
	}

	private static String newJobId() {
		return "job-" + UUID.randomUUID();
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static CollectResourceTaskDebugSnapshot collectResourceProbe(
		ActiveJob job,
		int currentResourceCount,
		boolean nearbyResourceTargetAvailable,
		TaskExecutionState primitiveExecutionState,
		String completionReason,
		long tick
	) {
		if (job == null || job.type() != ActiveJobType.COLLECT_RESOURCE || job.taskSpec() == null) {
			return CollectResourceTaskDebugSnapshot.empty();
		}
		int inventoryDelta = Math.max(0, currentResourceCount - job.baselineResourceCount());
		return new CollectResourceTaskDebugSnapshot(
			true,
			job.jobId(),
			job.taskSpec().resourceKind().name(),
			job.baselineResourceCount(),
			currentResourceCount,
			inventoryDelta,
			job.taskSpec().quantity(),
			job.collectedCount(),
			Math.max(0, job.taskSpec().quantity() - job.collectedCount()),
			nearbyResourceTargetAvailable,
			primitiveExecutionState == null ? null : primitiveExecutionState.name(),
			job.status().name(),
			job.blockedReason(),
			completionReason,
			tick
		);
	}
}
