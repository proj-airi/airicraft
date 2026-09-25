package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Follow and navigate through Baritone. Mining is owned by {@link TargetAcquisitionTaskExecutor}. */
public final class BaritoneTaskExecutor implements WorldTaskExecutor {
	private static final double MIN_RECOVERY_WATER_PENALTY = 12.0D;
	private static final double RECOVERY_WATER_PENALTY_MULTIPLIER = 4.0D;
	private static final double MAX_RECOVERY_WATER_PENALTY = 48.0D;

	private final BaritoneFacade facade;
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
	private GoalSnapshot pendingWaterReplanGoal;
	private Double temporaryWaterPenaltyBase;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BaritoneTaskExecutor(BaritoneFacade facade) {
		this(MinecraftClient::getInstance, facade);
	}

	BaritoneTaskExecutor(Supplier<MinecraftClient> clientSupplier, BaritoneFacade facade) {
		this(facade, () -> waterProgressSample(clientSupplier.get()));
	}

	BaritoneTaskExecutor(BaritoneFacade facade, WaterProgressObserver waterProgressObserver) {
		this.facade = Objects.requireNonNull(facade, "facade");
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
		if (taskTargetChanged) {
			navigationStall.clear();
			clearWaterRecovery();
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
			try {
				applyGoal(activeTask.get().goal());
			}
			catch (RuntimeException exception) {
				appliedTask = activeTask.get();
				return failTaskStart(appliedTask, exception);
			}
		}
		appliedTask = activeTask.get();

		if (Objects.equals(appliedTask.taskId(), terminalEventTaskId)
			&& "PATH_STUCK".equals(snapshot.lastPathEvent())) return Optional.empty();
		clearAcknowledgedInternalCancellation();
		Optional<String> pathEvent = facade.pollPathEvent();
		if (isSuppressedInternalCancel(pathEvent)) {
			pathEvent = Optional.empty();
		}
		pathEvent = observeNavigationEnd(pathEvent, appliedTask, sessionSnapshot.tickCount());
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
			case MINE_BLOCKS -> throw new IllegalStateException("mine_goal_owned_by_target_acquisition");
		}
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
			case "AT_GOAL" -> Optional.of(activeTask != null && activeTask.goal().type() == GoalType.NAVIGATE_TO && !navigateGoalReached(activeTask)
				? new TerminalOutcome(TaskExecutionState.FAILED, null, TaskFailureCode.ENVIRONMENT_CHANGED)
				: new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED, TaskFailureCode.NONE));
			case "CALC_FAILED" -> Optional.of(navigateGoalReached(activeTask)
				? new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED, TaskFailureCode.NONE)
				: new TerminalOutcome(TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED, TaskFailureCode.TRANSIENT));
			case "CANCELLED", "CANCELED" -> Optional.of(new TerminalOutcome(
				cancelledStateFor(activeTask == null ? null : activeTask.goal()),
				TaskTerminationCause.BARITONE_CANCELLED,
				TaskFailureCode.NONE
			));
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
	interface WaterProgressObserver {
		Optional<WaterStallRecovery.Sample> observe();
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
