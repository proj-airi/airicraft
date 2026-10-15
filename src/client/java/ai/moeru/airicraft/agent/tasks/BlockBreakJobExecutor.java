package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The planner's {@code break_blocks} job: breaks the targets with a {@link BlockBreakTaskExecutor} and then picks up what
 * they dropped ({@link BlockBreakDrops}) before reporting, appending {@code picked=...} (and {@code dropsLeft=... reason=...})
 * to the break's own result. A pickup that fails never changes whether the break succeeded. Only the dispatcher's
 * BREAK_BLOCKS slot uses it; tasks that break blocks through their own child executor handle the drops themselves.
 */
public final class BlockBreakJobExecutor implements WorldTaskExecutor {
	private final Supplier<Minecraft> clientSupplier;
	private final BlockBreakTaskExecutor breaker;
	private final NavigationFacade navigation;

	private WorldTaskRequest appliedTask;
	private Optional<BlockBreakDrops> drops;
	private TaskTerminalEvent breakEnd;
	private boolean terminalEventEmitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public BlockBreakJobExecutor(CameraController cameraController, NavigationFacade navigation) {
		this(Minecraft::getInstance, new BlockBreakTaskExecutor(cameraController), navigation);
	}

	BlockBreakJobExecutor(Supplier<Minecraft> clientSupplier, BlockBreakTaskExecutor breaker, NavigationFacade navigation) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.breaker = Objects.requireNonNull(breaker, "breaker");
		this.navigation = navigation;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.BREAK_BLOCKS) {
			reset();
			breaker.tick(sessionSnapshot, activeTask);
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (terminalEventEmitted) return Optional.empty();
		if (drops == null) drops = BlockBreakDrops.capture(clientSupplier, navigation, targets(request));
		if (breakEnd == null) {
			Optional<TaskTerminalEvent> terminal = breaker.tick(sessionSnapshot, activeTask);
			snapshot = breaker.snapshot();
			if (terminal.isEmpty()) return Optional.empty();
			List<BlockPos> broken = breaker.brokenPositions();
			if (broken.isEmpty() || drops.isEmpty()) return emit(terminal.get());
			breakEnd = terminal.get();
			drops.get().begin(broken);
		}
		if (!BlockInteractionTaskExecutor.actuationAllowed(sessionSnapshot)) {
			drops.get().pause();
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate " + drops.get().status());
			return Optional.empty();
		}
		Optional<String> outcome = drops.get().tick();
		if (outcome.isEmpty()) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, drops.get().status() + " then=" + PlacedStationReclaim.firstToken(breakEnd.message()));
			return Optional.empty();
		}
		return emit(withPickup(breakEnd, outcome.get()));
	}

	private Optional<TaskTerminalEvent> emit(TaskTerminalEvent event) {
		terminalEventEmitted = true;
		snapshot = new TaskExecutionSnapshot(event.terminalState(), event.taskId(), null, "BlockBreak", event.message(), null,
			event.terminationCause());
		return Optional.of(event);
	}

	/** The break's own terminal event with the pickup outcome appended to its message; state and failure code stay. */
	static TaskTerminalEvent withPickup(TaskTerminalEvent breakEnd, String pickup) {
		String message = breakEnd.message() == null || breakEnd.message().isBlank() ? pickup : breakEnd.message() + " " + pickup;
		return new TaskTerminalEvent(breakEnd.taskId(), breakEnd.goal(), breakEnd.terminalState(), message,
			breakEnd.terminationCause(), breakEnd.failureCode(), breakEnd.diagnostics());
	}

	private static List<BlockPos> targets(WorldTaskRequest request) {
		return ((WorldTaskRequest.BreakBlocks) request.task()).args().targets().stream()
			.map(target -> new BlockPos(target.position().x(), target.position().y(), target.position().z()))
			.toList();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) return true;
		if (left == null || right == null) return false;
		return Objects.equals(left.taskId(), right.taskId()) && Objects.equals(left.sourceJobId(), right.sourceJobId())
			&& left.type() == right.type();
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "BlockBreak", event, null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() {
		return snapshot;
	}

	@Override
	public void onWorldLeave() {
		reset();
		breaker.onWorldLeave();
	}

	@Override
	public void shutdown() {
		reset();
		breaker.shutdown();
	}

	private void reset() {
		if (drops != null) drops.ifPresent(BlockBreakDrops::cancel);
		appliedTask = null;
		drops = null;
		breakEnd = null;
		terminalEventEmitted = false;
		snapshot = TaskExecutionSnapshot.idle();
	}
}
