package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.navigation.PathfindSettings;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/** Follow and navigate through the in-house pathfinder. Mining is owned by {@link TargetAcquisitionTaskExecutor}. */
public final class NavigationTaskExecutor implements WorldTaskExecutor {
	private static final double MIN_RECOVERY_WATER_PENALTY = 12.0D;
	private static final double RECOVERY_WATER_PENALTY_MULTIPLIER = 4.0D;
	private static final double MAX_RECOVERY_WATER_PENALTY = 48.0D;

	private final NavigationFacade facade;
	private final WaterProgressObserver waterProgressObserver;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog();
	private final WaterStallRecovery waterStallRecovery = new WaterStallRecovery();

	private WorldTaskRequest appliedTask;
	private String terminalEventTaskId;
	private TaskExecutionState terminalEventState;
	private TaskTerminationCause terminalEventCause;
	/** Water penalty for the replan after a water stall; null plans with the configured cost. */
	private Double waterRecoveryPenalty;
	private NavigationRunMetrics metrics;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public NavigationTaskExecutor(NavigationFacade facade) {
		this(Minecraft::getInstance, facade);
	}

	NavigationTaskExecutor(Supplier<Minecraft> clientSupplier, NavigationFacade facade) {
		this(facade, () -> waterProgressSample(clientSupplier.get()));
	}

	NavigationTaskExecutor(NavigationFacade facade, WaterProgressObserver waterProgressObserver) {
		this.facade = Objects.requireNonNull(facade, "facade");
		this.waterProgressObserver = Objects.requireNonNull(waterProgressObserver, "waterProgressObserver");
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
				facade.cancel();
			}
			reset();
			return Optional.empty();
		}

		if (!sessionSnapshot.companionActuationAllowed()) {
			navigationStall.clear();
			if (waterRecoveryPenalty == null || sessionSnapshot.requiresRespawn()) {
				clearWaterRecovery();
			}
			if (sessionSnapshot.requiresRespawn() && appliedTask != null) {
				facade.cancel();
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
				facade.cancel();
				appliedTask = null;
			}
			clearTerminalEvent(activeTask.get());
			facade.pollPathEvent();
			metrics = new NavigationRunMetrics(sessionSnapshot.tickCount());
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
		if (metrics != null) metrics.observe(sessionSnapshot.tickCount(), waterProgressObserver.observe().orElse(null));
		Optional<String> pathEvent = facade.pollPathEvent();
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
				facade.cancel();
				pathEvent = Optional.of("PATH_STUCK");
				terminalOutcome = Optional.of(new TerminalOutcome(TaskExecutionState.FAILED, null, TaskFailureCode.TRANSIENT));
			}
		}
		else navigationStall.clear();
		Optional<String> effectivePathEvent = pathEvent;
		if (terminalOutcome.isPresent()) {
			clearWaterRecovery();
		}
		else if (pathEvent.isEmpty()) {
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
				: messageFor(terminalOutcome.get().state()),
			terminalOutcome.get().cause(),
			terminalOutcome.get().failureCode(),
			terminalDiagnostics("PATH_STUCK".equals(snapshot.lastPathEvent()))
		));
	}

	private Map<String, Object> terminalDiagnostics(boolean stalled) {
		if (metrics == null) return Map.of();
		Map<String, Object> diagnostics = new java.util.LinkedHashMap<>();
		diagnostics.put("navigation", metrics.summary(appliedTask.goal().position(), stalled));
		Map<String, Object> planner = facade.navigationDiagnostics();
		if (planner != null && !planner.isEmpty()) diagnostics.put("planner", planner);
		return diagnostics;
	}

	private Optional<String> waterRecoveryEvent(long tick, WorldTaskRequest request) {
		WaterStallRecovery.Decision decision = waterStallRecovery.observe(
			tick,
			waterProgressObserver.observe().orElse(null),
			facade.estimatedTicksToGoal().isPresent()
		);
		if (decision == WaterStallRecovery.Decision.NONE) {
			return Optional.empty();
		}
		if (decision == WaterStallRecovery.Decision.RESTORE) {
			waterRecoveryPenalty = null;
			return Optional.of("WATER_STALL_RECOVERED");
		}

		double base = waterRecoveryPenalty != null ? waterRecoveryPenalty : PathfindSettings.current().waterCost();
		waterRecoveryPenalty = Math.min(
			MAX_RECOVERY_WATER_PENALTY,
			Math.max(MIN_RECOVERY_WATER_PENALTY, base * RECOVERY_WATER_PENALTY_MULTIPLIER)
		);
		facade.cancel();
		facade.pollPathEvent();
		applyGoal(request.goal());
		countReplan();
		return Optional.of("WATER_STALL_REPLAN");
	}

	private void countReplan() {
		if (metrics != null) metrics.replanned();
	}

	private void clearWaterRecovery() {
		waterStallRecovery.clear();
		waterRecoveryPenalty = null;
	}

	private static Optional<WaterStallRecovery.Sample> waterProgressSample(Minecraft minecraft) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null) {
			return Optional.empty();
		}
		return Optional.of(new WaterStallRecovery.Sample(
			player.isInWater(),
			player.getX(),
			player.getY(),
			player.getZ()
		));
	}

	private void applyGoal(GoalSnapshot goal) {
		switch (goal.type()) {
			case FOLLOW_PLAYER -> facade.startFollow(goal.targetPlayer());
			case NAVIGATE_TO -> facade.startNavigate(goal.position(), waterRecoveryPenalty == null
				? NavigationOptions.DEFAULT : NavigationOptions.DEFAULT.withWaterPenalty(waterRecoveryPenalty));
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
		String message = "navigation_unavailable";
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
			// The backend decided arrival with the predicate it planned against. Re-checking the body a tick
			// later would fail a correct arrival whenever momentum carries the player out of the goal cell.
			case "AT_GOAL" -> Optional.of(new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED, TaskFailureCode.NONE));
			case "CALC_FAILED" -> Optional.of(navigateGoalReached(activeTask)
				? new TerminalOutcome(TaskExecutionState.COMPLETED, TaskTerminationCause.GOAL_REACHED, TaskFailureCode.NONE)
				: new TerminalOutcome(TaskExecutionState.FAILED, TaskTerminationCause.CALCULATION_FAILED, TaskFailureCode.TRANSIENT));
			case "CANCELLED", "CANCELED" -> Optional.of(new TerminalOutcome(
				cancelledStateFor(activeTask == null ? null : activeTask.goal()),
				TaskTerminationCause.NAVIGATION_CANCELLED,
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
			countReplan();
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
		clearWaterRecovery();
		appliedTask = null;
		terminalEventTaskId = null;
		terminalEventState = null;
		terminalEventCause = null;
		metrics = null;
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
