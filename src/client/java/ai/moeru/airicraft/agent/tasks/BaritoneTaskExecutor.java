package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public final class BaritoneTaskExecutor implements WorldTaskExecutor {
	private static final int MAX_MINE_DROP_PICKUP_ATTEMPTS_PER_TARGET = 2;
	private static final int MAX_MINE_DROP_PICKUP_SETTLE_TICKS = 10;
	private static final double MINE_DROP_PICKUP_RADIUS_BLOCKS = 4.0D;
	private static final double MIN_RECOVERY_WATER_PENALTY = 12.0D;
	private static final double RECOVERY_WATER_PENALTY_MULTIPLIER = 4.0D;
	private static final double MAX_RECOVERY_WATER_PENALTY = 48.0D;

	private final BaritoneFacade facade;
	private final Supplier<MinecraftClient> clientSupplier;
	private final MineDropObserver mineDropObserver;
	private final WaterProgressObserver waterProgressObserver;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog();
	private final WaterStallRecovery waterStallRecovery = new WaterStallRecovery();

	private WorldTaskRequest appliedTask;
	private NavigationEnd pendingNavigationEnd;
	private record NavigationEnd(String taskId, String event, long tick) {}
	private String terminalEventTaskId;
	private TaskExecutionState terminalEventState;
	private TaskTerminationCause terminalEventCause;
	private String pendingInternalCancelTaskId;
	private long pendingInternalCancelAcknowledgement = -1L;
	private String mineDropPickupTaskId;
	private MineDropTarget mineDropPickupTarget;
	private int mineDropPickupAttempts;
	private int mineDropPickupSettleTicks;
	private TerminalOutcome pendingMineTerminalOutcome;
	private GoalSnapshot pendingWaterReplanGoal;
	private boolean mineSatisfiedAwaitingRelease;
	private Double temporaryWaterPenaltyBase;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BaritoneTaskExecutor(BaritoneFacade facade) {
		this(MinecraftClient::getInstance, facade);
	}

	BaritoneTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade facade) {
		this(
			clientSupplier,
			facade,
			request -> matchingMineDropsNearby(clientSupplier.get(), request),
			() -> waterProgressSample(clientSupplier.get())
		);
	}

	BaritoneTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade facade, MineDropObserver mineDropObserver) {
		this(clientSupplier, facade, mineDropObserver, () -> waterProgressSample(clientSupplier.get()));
	}

	BaritoneTaskExecutor(
		Supplier<MinecraftClient> clientSupplier,
		BaritoneFacade facade,
		MineDropObserver mineDropObserver,
		WaterProgressObserver waterProgressObserver
	) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.facade = Objects.requireNonNull(facade, "facade");
		this.mineDropObserver = Objects.requireNonNull(mineDropObserver, "mineDropObserver");
		this.waterProgressObserver = Objects.requireNonNull(waterProgressObserver, "waterProgressObserver");
		this.facade.applySettings();
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (!facade.isLoaded()) {
			if (activeTask.isPresent()) {
				clearWaterRecovery();
				return failUnavailable(activeTask.get());
			}
			reset();
			return Optional.empty();
		}

		if (activeTask.isEmpty()) {
			if (appliedTask != null) {
				requestInternalCancellation(appliedTask.taskId());
			}
			reset();
			return Optional.empty();
		}

		if (!sessionSnapshot.companionActuationAllowed()) {
			navigationStall.clear();
			if (pendingWaterReplanGoal == null || sessionSnapshot.requiresRespawn()) {
				clearWaterRecovery();
			}
			if (sessionSnapshot.requiresRespawn() && appliedTask != null) {
				requestInternalCancellation(appliedTask.taskId());
				appliedTask = null;
				clearMineDropPickupState();
			}
			clearTerminalEvent(activeTask.get());
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.PAUSED_BY_SESSION_GATE,
				activeTask.get().taskId(),
				activeTask.get().goal(),
				null,
				null,
				null,
				null
			);
			return Optional.empty();
		}

		boolean taskTargetChanged = !sameTaskTarget(activeTask.get(), appliedTask);
		boolean mineGoalJustSatisfied = mineGoalJustSatisfied(activeTask.get(), appliedTask);
		if (taskTargetChanged) {
			navigationStall.clear();
			clearWaterRecovery();
			clearMineDropPickupState();
			mineSatisfiedAwaitingRelease = false;
			if (appliedTask != null) {
				requestInternalCancellation(appliedTask.taskId());
				appliedTask = null;
			}
			if (!BaritoneReleaseBarrier.releaseAndDrain(facade)) {
				clearTerminalEvent(activeTask.get());
				snapshot = new TaskExecutionSnapshot(
					TaskExecutionState.RUNNING,
					activeTask.get().taskId(),
					activeTask.get().goal(),
					"Baritone",
					"waiting_for_baritone_release",
					null,
					null
				);
				return Optional.empty();
			}
			clearTerminalEvent(activeTask.get());
			facade.pollPathEvent();
			clearInternalCancellation();
			if (satisfiedMineRequest(activeTask.get())) {
				mineSatisfiedAwaitingRelease = true;
			}
			else {
				try {
					applyGoal(activeTask.get().goal());
				}
				catch (RuntimeException exception) {
					appliedTask = activeTask.get();
					return failTaskStart(appliedTask, exception);
				}
			}
		}
		else if (mineGoalJustSatisfied) {
			clearWaterRecovery();
			clearTerminalEvent(activeTask.get());
			requestInternalCancellation(activeTask.get().taskId());
			mineSatisfiedAwaitingRelease = true;
		}
		appliedTask = activeTask.get();

		if (Objects.equals(appliedTask.taskId(), terminalEventTaskId)
			&& "PATH_STUCK".equals(snapshot.lastPathEvent())) return Optional.empty();
		clearAcknowledgedInternalCancellation();
		Optional<String> pathEvent;
		if (mineSatisfiedAwaitingRelease) {
			// Drain any late event from the owned mine operation, but completion is
			// derived from the inventory fact rather than a cancellation spelling.
			facade.pollPathEvent();
			if (!BaritoneReleaseBarrier.releaseAndDrain(facade)) {
				snapshot = new TaskExecutionSnapshot(
					TaskExecutionState.RUNNING,
					appliedTask.taskId(),
					appliedTask.goal(),
					"Baritone",
					"waiting_for_satisfied_mine_release",
					null,
					null
				);
				return Optional.empty();
			}
			clearInternalCancellation();
			mineSatisfiedAwaitingRelease = false;
			pathEvent = Optional.of("CANCELED");
		}
		else {
			pathEvent = facade.pollPathEvent();
			if (isSuppressedInternalCancel(pathEvent)) {
				pathEvent = Optional.empty();
			}
		}
		pathEvent = observeNavigationEnd(pathEvent, appliedTask, sessionSnapshot.tickCount());
		boolean mineProcessOwnsPathEvent = mineProcessOwnsPathEvent(pathEvent, appliedTask);
		MineDropPickupResult mineDropPickupResult = mineProcessOwnsPathEvent
			? MineDropPickupResult.notHandled()
			: terminalMineDropPickupEvent(pathEvent, appliedTask);
		if (mineDropPickupResult.handled()) {
			if (mineDropPickupResult.event().isPresent()) {
				clearWaterRecovery();
			}
			return mineDropPickupResult.event();
		}
		if (continueFollow(pathEvent, appliedTask)) {
			return Optional.empty();
		}
		Optional<TerminalOutcome> terminalOutcome = terminalOutcomeFor(pathEvent, appliedTask);
		if (terminalOutcome.isEmpty() && appliedTask.goal().type() == GoalType.NAVIGATE_TO
			&& !navigateGoalReached(appliedTask)) {
			var sample = waterProgressObserver.observe().orElse(null);
			if (sample == null) navigationStall.clear();
			else if (facade.navigationProgress().map(progress -> navigationStall.observe(sessionSnapshot.tickCount(), progress))
				.orElseGet(() -> navigationStall.observe(sessionSnapshot.tickCount(), sample.x(), sample.y(), sample.z()))) {
				requestInternalCancellation(appliedTask.taskId());
				pendingNavigationEnd = null;
				pathEvent = Optional.of("PATH_STUCK");
				terminalOutcome = Optional.of(new TerminalOutcome(TaskExecutionState.FAILED, null, TaskFailureCode.TRANSIENT));
			}
		}
		else navigationStall.clear();
		Optional<String> effectivePathEvent = pendingNavigationEnd == null ? pathEvent : Optional.of("observing_navigation_end");
		if (terminalOutcome.isPresent()) {
			clearWaterRecovery();
		}
		else if (pathEvent.isEmpty() && pendingNavigationEnd == null) {
			try {
				effectivePathEvent = waterRecoveryEvent(sessionSnapshot.tickCount(), appliedTask);
			}
			catch (RuntimeException exception) {
				clearWaterRecovery();
				return failTaskStart(appliedTask, exception);
			}
		}
		TaskExecutionState state = terminalOutcome
			.map(TerminalOutcome::state)
			.orElseGet(() -> taskTargetChanged || !isTerminal(snapshot.state()) ? TaskExecutionState.RUNNING : snapshot.state());
		TaskTerminationCause terminationCause = terminalOutcome.map(TerminalOutcome::cause).orElse(null);
		snapshot = new TaskExecutionSnapshot(
			state,
			appliedTask.taskId(),
			appliedTask.goal(),
			facade.activeProcessName().orElse(null),
			effectivePathEvent.orElse(null),
			facade.estimatedTicksToGoal().orElse(null),
			terminationCause
		);

		if (terminalOutcome.isEmpty()) {
			return Optional.empty();
		}
		if (Objects.equals(appliedTask.taskId(), terminalEventTaskId)
			&& terminalOutcome.get().state() == terminalEventState
			&& terminalOutcome.get().cause() == terminalEventCause) {
			return Optional.empty();
		}
		terminalEventTaskId = appliedTask.taskId();
		terminalEventState = terminalOutcome.get().state();
		terminalEventCause = terminalOutcome.get().cause();
		return Optional.of(new TaskTerminalEvent(
			appliedTask.taskId(),
			appliedTask.goal(),
			terminalOutcome.get().state(),
			"PATH_STUCK".equals(snapshot.lastPathEvent())
				? "navigation_stuck: moved less than 0.75 blocks for 100 active ticks (5 seconds)"
				: terminalOutcome.get().failureCode() == TaskFailureCode.ENVIRONMENT_CHANGED
				? "navigation_arrival_unconfirmed" : messageFor(terminalOutcome.get().state()),
			terminalOutcome.get().cause(),
			terminalOutcome.get().failureCode()
		));
	}

	/** Observe a just-ended path briefly; this never issues movement or retries. */
	private Optional<String> observeNavigationEnd(Optional<String> event, WorldTaskRequest request, long tick) {
		if (request.goal().type() != GoalType.NAVIGATE_TO
			|| pendingNavigationEnd != null && !pendingNavigationEnd.taskId().equals(request.taskId())) pendingNavigationEnd = null;
		if (request.goal().type() != GoalType.NAVIGATE_TO) return event;
		if (pendingNavigationEnd == null && event.filter(e -> Set.of("AT_GOAL", "CANCELED", "CANCELLED").contains(e.trim().toUpperCase(Locale.ROOT))).isPresent()
			&& !navigateGoalReached(request)) pendingNavigationEnd = new NavigationEnd(request.taskId(), event.get(), tick);
		if (pendingNavigationEnd == null) return event;
		if (!navigateGoalReached(request) && tick - pendingNavigationEnd.tick() < 10) return Optional.empty();
		String ended = pendingNavigationEnd.event();
		pendingNavigationEnd = null;
		return Optional.of(ended);
	}

	private Optional<String> waterRecoveryEvent(long tick, WorldTaskRequest request) {
		if (pendingWaterReplanGoal != null) {
			if (!BaritoneReleaseBarrier.releaseAndDrain(facade)) {
				return Optional.of("WATER_STALL_RELEASING");
			}
			GoalSnapshot goal = pendingWaterReplanGoal;
			pendingWaterReplanGoal = null;
			facade.pollPathEvent();
			clearInternalCancellation();
			applyGoal(goal);
			return Optional.of("WATER_STALL_REPLAN");
		}
		WaterStallRecovery.Decision decision = waterStallRecovery.observe(
			tick,
			waterProgressObserver.observe().orElse(null),
			facade.estimatedTicksToGoal().isPresent()
		);
		if (decision == WaterStallRecovery.Decision.NONE) {
			return Optional.empty();
		}
		if (decision == WaterStallRecovery.Decision.RESTORE) {
			restoreTemporaryWaterPenalty();
			return Optional.of("WATER_STALL_RECOVERED");
		}

		double currentPenalty = facade.walkOnWaterPenalty();
		if (temporaryWaterPenaltyBase == null) {
			temporaryWaterPenaltyBase = currentPenalty;
		}
		double recoveryPenalty = Math.min(
			MAX_RECOVERY_WATER_PENALTY,
			Math.max(MIN_RECOVERY_WATER_PENALTY, currentPenalty * RECOVERY_WATER_PENALTY_MULTIPLIER)
		);
		facade.setWalkOnWaterPenalty(recoveryPenalty);
		pendingWaterReplanGoal = request.goal();
		requestInternalCancellation(request.taskId());
		if (!BaritoneReleaseBarrier.released(facade)) {
			return Optional.of("WATER_STALL_RELEASING");
		}
		GoalSnapshot goal = pendingWaterReplanGoal;
		pendingWaterReplanGoal = null;
		facade.pollPathEvent();
		clearInternalCancellation();
		applyGoal(goal);
		return Optional.of("WATER_STALL_REPLAN");
	}

	private void clearWaterRecovery() {
		waterStallRecovery.clear();
		pendingWaterReplanGoal = null;
		restoreTemporaryWaterPenalty();
	}

	private void restoreTemporaryWaterPenalty() {
		if (temporaryWaterPenaltyBase == null) {
			return;
		}
		double baseline = temporaryWaterPenaltyBase;
		facade.setWalkOnWaterPenalty(baseline);
		temporaryWaterPenaltyBase = null;
	}

	private static Optional<WaterStallRecovery.Sample> waterProgressSample(MinecraftClient client) {
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null) {
			return Optional.empty();
		}
		return Optional.of(new WaterStallRecovery.Sample(
			player.isTouchingWater(),
			player.getX(),
			player.getY(),
			player.getZ()
		));
	}

	private void applyGoal(GoalSnapshot goal) {
		switch (goal.type()) {
			case FOLLOW_PLAYER -> facade.startFollow(goal.targetPlayer());
			case NAVIGATE_TO -> facade.startNavigate(goal.position());
			case MINE_BLOCKS -> {
				MiningToolPreparation.Result result = ensureMiningToolSelected(goal);
				if (!result.ok()) {
					throw new IllegalStateException(result.message());
				}
				facade.startMine(goal.mineSpec());
			}
		}
	}

	private MiningToolPreparation.Result ensureMiningToolSelected(GoalSnapshot goal) {
		MinecraftClient client = clientSupplier.get();
		ClientPlayerEntity player = client == null ? null : client.player;
		if (client == null || client.world == null || client.interactionManager == null || player == null || goal.mineSpec() == null) {
			return MiningToolPreparation.Result.success();
		}
		if (player.currentScreenHandler != player.playerScreenHandler || !player.currentScreenHandler.getCursorStack().isEmpty()) {
			return MiningToolPreparation.Result.failed("inventory_unavailable_for_tool_selection");
		}
		ArrayList<BlockState> targetStates = new ArrayList<>();
		for (String blockId : goal.mineSpec().blockIds()) {
			Optional<Block> block = resolveBlock(blockId);
			if (block.isEmpty()) {
				return MiningToolPreparation.Result.failed("invalid_block_id " + blockId);
			}
			targetStates.add(block.get().getDefaultState());
		}
		return MiningToolPreparation.ensureSelected(client, player, targetStates, goal.mineSpec().requiredToolItemIds());
	}

	private static Optional<Block> resolveBlock(String blockId) {
		if (blockId == null || blockId.isBlank()) {
			return Optional.empty();
		}
		Identifier identifier;
		try {
			identifier = Identifier.of(blockId);
		}
		catch (RuntimeException ignored) {
			return Optional.empty();
		}
		return Registries.BLOCK.getOptionalValue(identifier);
	}


	private Optional<TaskTerminalEvent> failTaskStart(WorldTaskRequest request, RuntimeException exception) {
		String message = nonEmpty(exception.getMessage(), exception.getClass().getSimpleName());
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.FAILED,
			request.taskId(),
			request.goal(),
			null,
			message,
			null,
			null
		);
		terminalEventTaskId = request.taskId();
		terminalEventState = TaskExecutionState.FAILED;
		terminalEventCause = null;
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			message,
			null,
			TaskFailureCode.UNKNOWN
		));
	}

	private Optional<TaskTerminalEvent> failUnavailable(WorldTaskRequest request) {
		String message = "baritone_unavailable";
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.FAILED,
			request.taskId(),
			request.goal(),
			null,
			message,
			null,
			null
		);
		if (Objects.equals(request.taskId(), terminalEventTaskId)
			&& terminalEventState == TaskExecutionState.FAILED
			&& terminalEventCause == null) {
			return Optional.empty();
		}
		terminalEventTaskId = request.taskId();
		terminalEventState = TaskExecutionState.FAILED;
		terminalEventCause = null;
		return Optional.of(new TaskTerminalEvent(
			request.taskId(),
			request.goal(),
			TaskExecutionState.FAILED,
			message,
			null,
			TaskFailureCode.UNKNOWN
		));
	}

	private Optional<TerminalOutcome> terminalOutcomeFor(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (pathEvent.isEmpty()) {
			return Optional.empty();
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "AT_GOAL" -> mineProcessOwnsPathEvent(pathEvent, activeTask)
				? Optional.empty()
				: Optional.of(activeTask != null && activeTask.goal().type() == GoalType.NAVIGATE_TO && !navigateGoalReached(activeTask)
					? new TerminalOutcome(TaskExecutionState.FAILED, null, TaskFailureCode.ENVIRONMENT_CHANGED)
					: new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED, TaskFailureCode.NONE));
			case "CALC_FAILED" -> mineProcessOwnsPathEvent(pathEvent, activeTask)
				? Optional.empty()
				: Optional.of(navigateGoalReached(activeTask)
					? new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED, TaskFailureCode.NONE)
					: new TerminalOutcome(TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED, TaskFailureCode.TRANSIENT));
			case "CANCELLED", "CANCELED" -> mineProcessOwnsPathEvent(pathEvent, activeTask)
				? Optional.empty()
				: Optional.of(cancelledOutcomeFor(activeTask));
			default -> Optional.empty();
		};
	}

	private boolean navigateGoalReached(WorldTaskRequest activeTask) {
		return activeTask != null
			&& activeTask.goal() != null
			&& activeTask.goal().type() == GoalType.NAVIGATE_TO
			&& activeTask.goal().position() != null
			&& facade.navigationGoalReached(activeTask.goal().position());
	}

	private boolean continueFollow(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (pathEvent.isEmpty()
			|| activeTask == null
			|| activeTask.goal() == null
			|| activeTask.goal().type() != GoalType.FOLLOW_PLAYER) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		if (!"AT_GOAL".equals(normalized)
			&& !"CALC_FAILED".equals(normalized)
			&& !"CANCELLED".equals(normalized)
			&& !"CANCELED".equals(normalized)) {
			return false;
		}
		String pathState = normalized;
		if (!"AT_GOAL".equals(normalized)) {
			facade.startFollow(activeTask.goal().targetPlayer());
			pathState = "FOLLOW_REACQUIRING";
		}
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			activeTask.taskId(),
			activeTask.goal(),
			facade.activeProcessName().orElse(null),
			pathState,
			facade.estimatedTicksToGoal().orElse(null),
			null
		);
		return true;
	}

	private boolean mineProcessOwnsPathEvent(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		if (
			pathEvent.isEmpty()
				|| activeTask == null
				|| activeTask.goal() == null
				|| activeTask.goal().type() != GoalType.MINE_BLOCKS
		) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		return switch (normalized) {
			// MineProcess uses path goals per selected block. It owns target completion,
			// target blacklisting after CALC_FAILED, and reselection until it deactivates.
			case "AT_GOAL", "CALC_FAILED", "CANCELLED", "CANCELED" -> facade.mineProcessActive();
			default -> false;
		};
	}

	private MineDropPickupResult terminalMineDropPickupEvent(Optional<String> pathEvent, WorldTaskRequest activeTask) {
		boolean pickupInProgress = activeTask != null
			&& Objects.equals(activeTask.taskId(), mineDropPickupTaskId)
			&& pendingMineTerminalOutcome != null;
		Optional<TerminalOutcome> currentOutcome = terminalOutcomeFor(pathEvent, activeTask);
		if ((!pickupInProgress && currentOutcome.isEmpty()) || activeTask == null) {
			return MineDropPickupResult.notHandled();
		}
		WorldTaskRequest.Mine mine = mineTask(activeTask);
		if (mine == null || mine.pickupSweepPositions().isEmpty()) {
			return MineDropPickupResult.notHandled();
		}
		if (!pickupInProgress) {
			mineDropPickupTaskId = activeTask.taskId();
			mineDropPickupTarget = null;
			mineDropPickupAttempts = 0;
			mineDropPickupSettleTicks = 0;
			pendingMineTerminalOutcome = currentOutcome.orElseThrow();
		}

		List<MineDropTarget> matchingDrops = mineDropObserver.matchingNearbyDrops(activeTask);
		if (matchingDrops == null || matchingDrops.isEmpty()) {
			if (!pickupInProgress) {
				clearMineDropPickupState();
				return MineDropPickupResult.notHandled();
			}
			return finishMineDropPickup(activeTask);
		}

		MineDropTarget nextTarget = matchingDrops.stream()
			.filter(target -> mineDropPickupTarget != null && target.entityId() == mineDropPickupTarget.entityId())
			.findFirst()
			.orElse(matchingDrops.getFirst());
		boolean sameTarget = mineDropPickupTarget != null && nextTarget.entityId() == mineDropPickupTarget.entityId();
		if (sameTarget && currentOutcome.isEmpty() && mineDropPickupSettleTicks == 0) {
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.RUNNING,
				activeTask.taskId(),
				activeTask.goal(),
				facade.activeProcessName().orElse(null),
				"pickup_sweep",
				facade.estimatedTicksToGoal().orElse(null),
				null
			);
			return MineDropPickupResult.handledWithoutEvent();
		}
		if (sameTarget && mineDropPickupAttempts >= MAX_MINE_DROP_PICKUP_ATTEMPTS_PER_TARGET) {
			mineDropPickupSettleTicks++;
			if (mineDropPickupSettleTicks > MAX_MINE_DROP_PICKUP_SETTLE_TICKS) {
				return failMineDropPickup(activeTask);
			}
			snapshot = new TaskExecutionSnapshot(
				TaskExecutionState.RUNNING,
				activeTask.taskId(),
				activeTask.goal(),
				facade.activeProcessName().orElse(null),
				"pickup_settle",
				facade.estimatedTicksToGoal().orElse(null),
				null
			);
			return MineDropPickupResult.handledWithoutEvent();
		}
		if (!sameTarget) {
			mineDropPickupTarget = nextTarget;
			mineDropPickupAttempts = 0;
			mineDropPickupSettleTicks = 0;
		}
		mineDropPickupAttempts++;
		markInternalCancellation(activeTask.taskId());
		facade.startNavigate(nextTarget.position());
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.RUNNING,
			activeTask.taskId(),
			activeTask.goal(),
			facade.activeProcessName().orElse(null),
			"pickup_sweep",
			facade.estimatedTicksToGoal().orElse(null),
			null
		);
		return MineDropPickupResult.handledWithoutEvent();
	}

	private MineDropPickupResult finishMineDropPickup(WorldTaskRequest activeTask) {
		TerminalOutcome outcome = pendingMineTerminalOutcome;
		clearMineDropPickupState();
		snapshot = new TaskExecutionSnapshot(
			outcome.state(),
			activeTask.taskId(),
			activeTask.goal(),
			facade.activeProcessName().orElse(null),
			messageFor(outcome.state()),
			facade.estimatedTicksToGoal().orElse(null),
			outcome.cause()
		);
		terminalEventTaskId = activeTask.taskId();
		terminalEventState = outcome.state();
		terminalEventCause = outcome.cause();
		return MineDropPickupResult.withEvent(new TaskTerminalEvent(
			activeTask.taskId(),
			activeTask.goal(),
			outcome.state(),
			messageFor(outcome.state()),
			outcome.cause(),
			outcome.failureCode()
		));
	}

	private TerminalOutcome cancelledOutcomeFor(WorldTaskRequest activeTask) {
		if (
			activeTask != null
				&& mineGoalSatisfied(activeTask)
				&& activeTask.goal() != null
				&& activeTask.goal().type() == GoalType.MINE_BLOCKS
		) {
			return new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED, TaskFailureCode.NONE);
		}
		return new TerminalOutcome(
			cancelledStateFor(activeTask == null ? null : activeTask.goal()),
			TaskTerminationCause.BARITONE_CANCELLED,
			TaskFailureCode.NONE
		);
	}

	private MineDropPickupResult failMineDropPickup(WorldTaskRequest activeTask) {
		String message = "nearby_mined_drop_not_collected";
		clearMineDropPickupState();
		snapshot = new TaskExecutionSnapshot(
			TaskExecutionState.FAILED,
			activeTask.taskId(),
			activeTask.goal(),
			facade.activeProcessName().orElse(null),
			message,
			facade.estimatedTicksToGoal().orElse(null),
			null
		);
		terminalEventTaskId = activeTask.taskId();
		terminalEventState = TaskExecutionState.FAILED;
		terminalEventCause = null;
		return MineDropPickupResult.withEvent(new TaskTerminalEvent(activeTask.taskId(), activeTask.goal(), TaskExecutionState.FAILED, message, null, TaskFailureCode.UNKNOWN));
	}

	private static List<MineDropTarget> matchingMineDropsNearby(MinecraftClient client, WorldTaskRequest request) {
		WorldTaskRequest.Mine mine = mineTask(request);
		if (client == null || client.world == null || client.player == null || mine == null || mine.goal().mineSpec() == null || mine.pickupSweepPositions().isEmpty()) {
			return List.of();
		}
		Set<String> matchingItemIds = Set.copyOf(mine.goal().mineSpec().matchingItemIds());
		Set<Integer> seenEntityIds = new HashSet<>();
		ArrayList<ItemEntity> matchingDrops = new ArrayList<>();
		for (GoalPosition position : mine.pickupSweepPositions()) {
			Box area = Box.of(
				Vec3d.ofCenter(new BlockPos(position.x(), position.y(), position.z())),
				MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D,
				MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D,
				MINE_DROP_PICKUP_RADIUS_BLOCKS * 2.0D
			);
			for (ItemEntity itemEntity : client.world.getEntitiesByClass(ItemEntity.class, area, entity -> isMatchingMineDrop(entity, matchingItemIds))) {
				if (seenEntityIds.add(itemEntity.getId())) {
					matchingDrops.add(itemEntity);
				}
			}
		}
		matchingDrops.sort(Comparator.comparingDouble(itemEntity -> itemEntity.squaredDistanceTo(client.player)));
		return matchingDrops.stream()
			.map(itemEntity -> {
				BlockPos position = itemEntity.getBlockPos();
				return new MineDropTarget(itemEntity.getId(), new GoalPosition(position.getX(), position.getY(), position.getZ(), true));
			})
			.toList();
	}

	private static boolean isMatchingMineDrop(ItemEntity itemEntity, Set<String> matchingItemIds) {
		ItemStack stack = itemEntity == null ? ItemStack.EMPTY : itemEntity.getStack();
		return stack != null && !stack.isEmpty() && matchingItemIds.contains(Registries.ITEM.getId(stack.getItem()).toString());
	}

	private void clearMineDropPickupState() {
		mineDropPickupTaskId = null;
		mineDropPickupTarget = null;
		mineDropPickupAttempts = 0;
		mineDropPickupSettleTicks = 0;
		pendingMineTerminalOutcome = null;
	}

	private TaskExecutionState cancelledStateFor(GoalSnapshot activeGoal) {
		if (activeGoal == null || activeGoal.type() != GoalType.NAVIGATE_TO || activeGoal.position() == null) {
			return TaskExecutionState.CANCELLED;
		}
		return facade.navigationGoalReached(activeGoal.position())
			? TaskExecutionState.COMPLETED
			: TaskExecutionState.CANCELLED;
	}

	private static boolean isTerminal(TaskExecutionState state) {
		return state == TaskExecutionState.COMPLETED
			|| state == TaskExecutionState.FAILED
			|| state == TaskExecutionState.CANCELLED;
	}

	private boolean isSuppressedInternalCancel(Optional<String> pathEvent) {
		if (pathEvent.isEmpty() || pendingInternalCancelTaskId == null) {
			return false;
		}
		String normalized = pathEvent.get().trim().toUpperCase(Locale.ROOT);
		if (!isCancelledPathEvent(normalized)) {
			return false;
		}
		pendingInternalCancelTaskId = null;
		pendingInternalCancelAcknowledgement = -1L;
		return true;
	}

	private void requestInternalCancellation(String taskId) {
		long acknowledgementBeforeRequest = facade.cancellationAcknowledgement();
		facade.cancel();
		pendingInternalCancelTaskId = taskId;
		pendingInternalCancelAcknowledgement = acknowledgementBeforeRequest;
	}

	private void markInternalCancellation(String taskId) {
		pendingInternalCancelTaskId = taskId;
		pendingInternalCancelAcknowledgement = facade.cancellationAcknowledgement();
	}

	private void clearAcknowledgedInternalCancellation() {
		if (pendingInternalCancelTaskId != null
			&& pendingInternalCancelAcknowledgement >= 0L
			&& facade.cancellationAcknowledgement() > pendingInternalCancelAcknowledgement) {
			pendingInternalCancelTaskId = null;
			pendingInternalCancelAcknowledgement = -1L;
		}
	}

	private void clearInternalCancellation() {
		pendingInternalCancelTaskId = null;
		pendingInternalCancelAcknowledgement = -1L;
	}

	private static boolean isCancelledPathEvent(String normalizedPathEvent) {
		return "CANCELLED".equals(normalizedPathEvent) || "CANCELED".equals(normalizedPathEvent);
	}

	private static boolean sameTaskTarget(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& sameGoalTarget(left.goal(), right.goal());
	}

	private static boolean mineGoalJustSatisfied(WorldTaskRequest current, WorldTaskRequest previous) {
		return current != null
			&& mineGoalSatisfied(current)
			&& !mineGoalSatisfied(previous)
			&& current.goal() != null
			&& current.goal().type() == GoalType.MINE_BLOCKS;
	}

	private static boolean satisfiedMineRequest(WorldTaskRequest request) {
		return request != null
			&& mineGoalSatisfied(request)
			&& request.goal() != null
			&& request.goal().type() == GoalType.MINE_BLOCKS;
	}

	private static WorldTaskRequest.Mine mineTask(WorldTaskRequest request) {
		return request != null && request.task() instanceof WorldTaskRequest.Mine mine ? mine : null;
	}

	private static boolean mineGoalSatisfied(WorldTaskRequest request) {
		WorldTaskRequest.Mine mine = mineTask(request);
		return mine != null && mine.mineGoalSatisfied();
	}

	private static boolean sameGoalTarget(GoalSnapshot left, GoalSnapshot right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return left.type() == right.type()
			&& Objects.equals(left.targetPlayer(), right.targetPlayer())
			&& Objects.equals(left.position(), right.position())
			&& Objects.equals(left.mineSpec(), right.mineSpec());
	}

	private static String messageFor(TaskExecutionState state) {
		return switch (state) {
			case COMPLETED -> "Goal reached";
			case FAILED -> "Path calculation failed";
			case CANCELLED -> "Task cancelled";
			default -> "Task update";
		};
	}

	private static String nonEmpty(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	@FunctionalInterface
	interface MineDropObserver {
		List<MineDropTarget> matchingNearbyDrops(WorldTaskRequest request);
	}

	@FunctionalInterface
	interface WaterProgressObserver {
		Optional<WaterStallRecovery.Sample> observe();
	}

	record MineDropTarget(int entityId, GoalPosition position) {
		MineDropTarget {
			Objects.requireNonNull(position, "position");
		}
	}

	private record MineDropPickupResult(boolean handled, Optional<TaskTerminalEvent> event) {
		static MineDropPickupResult notHandled() { return new MineDropPickupResult(false, Optional.empty()); }
		static MineDropPickupResult handledWithoutEvent() { return new MineDropPickupResult(true, Optional.empty()); }
		static MineDropPickupResult withEvent(TaskTerminalEvent event) { return new MineDropPickupResult(true, Optional.of(event)); }
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		facade.cancel();
		reset();
	}

	@Override
	public void shutdown() {
		onWorldLeave();
	}

	private void reset() {
		navigationStall.clear();
		pendingNavigationEnd = null;
		clearWaterRecovery();
		appliedTask = null;
		terminalEventTaskId = null;
		terminalEventState = null;
		terminalEventCause = null;
		pendingInternalCancelTaskId = null;
		pendingInternalCancelAcknowledgement = -1L;
		mineSatisfiedAwaitingRelease = false;
		clearMineDropPickupState();
		snapshot = TaskExecutionSnapshot.idle();
	}

	private void clearTerminalEvent(WorldTaskRequest task) {
		if (!sameTaskTarget(task, appliedTask) || !Objects.equals(task.taskId(), terminalEventTaskId)) {
			terminalEventTaskId = null;
			terminalEventState = null;
			terminalEventCause = null;
		}
	}

	private record TerminalOutcome(TaskExecutionState state, TaskTerminationCause cause, TaskFailureCode failureCode) {
	}
}
