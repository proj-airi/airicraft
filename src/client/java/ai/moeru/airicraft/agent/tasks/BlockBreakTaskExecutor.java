package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class BlockBreakTaskExecutor implements WorldTaskExecutor {
	private final Actuator actuator = new Actuator("block_break", Priority.FOREGROUND);
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;
	private static final int TARGET_TIMEOUT_TICKS = 200;

	private final Supplier<Minecraft> clientSupplier;
	private final CameraController cameraController;

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
	private int targetIndex;
	private int brokenTargets;
	private int skippedTargets;
	private boolean breakingActive;
	private long targetStartTick = -1L;

	public BlockBreakTaskExecutor(CameraController cameraController) {
		this(Minecraft::getInstance, cameraController);
	}

	BlockBreakTaskExecutor(Supplier<Minecraft> clientSupplier) {
		this(clientSupplier, new CameraController());
	}

	private BlockBreakTaskExecutor(Supplier<Minecraft> clientSupplier, CameraController cameraController) {
		this.cameraController = Objects.requireNonNull(cameraController);
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.BREAK_BLOCKS) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (!BlockInteractionTaskExecutor.actuationAllowed(sessionSnapshot)) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}
		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.gameMode == null || minecraft.level == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "interaction_busy"));
		}

		BlockBreakStepArgs args = ((WorldTaskRequest.BreakBlocks) request.task()).args();
		while (targetIndex < args.targets().size()) {
			int previousTarget = targetIndex;
			Optional<TaskTerminalEvent> event = tickTarget(sessionSnapshot, minecraft, player, request, args.targets().get(targetIndex));
			if (event.isPresent() || targetIndex == previousTarget) {
				return event;
			}
		}
		return complete(request, "break_blocks_succeeded brokenTargets=" + brokenTargets + " skippedTargets=" + skippedTargets);
	}

	private Optional<TaskTerminalEvent> tickTarget(
		SessionSnapshot sessionSnapshot,
		Minecraft minecraft,
		LocalPlayer player,
		WorldTaskRequest request,
		BlockBreakStepArgs.Target target
	) {
		BlockPos pos = blockPos(target.position());
		if (!minecraft.level.hasChunkAt(pos)) {
			return fail(request, TaskFailure.of(TaskFailureCode.ENVIRONMENT_CHANGED, "target_unloaded targetPos=" + compactPos(pos)));
		}
		BlockState state = minecraft.level.getBlockState(pos);
		if (satisfied(state)) {
			skippedTargets++;
			targetIndex++;
			breakingActive = false;
			targetStartTick = -1L;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_skipped targetPos=" + compactPos(pos));
			return Optional.empty();
		}
		String currentBlockId = blockId(state);
		if (!target.expectedBlockIds().contains(currentBlockId)) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_block_mismatch targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId));
		}
		if (!withinInteractionRange(player, Vec3.atCenterOf(pos))) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_out_of_range targetPos=" + compactPos(pos)));
		}
		cameraController.lookAtBlock(minecraft, pos);
		var hit = cameraController.blockHit(minecraft, pos);
		if (hit.isEmpty()) {
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_aim targetPos=" + compactPos(pos));
			return Optional.empty();
		}
		long tick = sessionSnapshot == null ? 0L : sessionSnapshot.tickCount();
		if (!breakingActive) {
			MiningToolPreparation.Result toolSelection =
				MiningToolPreparation.ensureSelected(minecraft, actuator, player, List.of(state));
			if (!toolSelection.ok()) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_ITEM, toolSelection.message()));
			}
			boolean accepted = actuator.startDestroy(minecraft, pos, hit.get().getDirection());
			if (!accepted) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "break_start_failed targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId));
			}
			player.swing(InteractionHand.MAIN_HAND);
			breakingActive = true;
			targetStartTick = tick;
		}
		if (tick - targetStartTick > TARGET_TIMEOUT_TICKS) {
			actuator.stopDestroy(minecraft);
			return fail(request, TaskFailure.of(TaskFailureCode.TRANSIENT, "break_timeout targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId));
		}
		actuator.continueDestroy(minecraft, pos, hit.get().getDirection());
		player.swing(InteractionHand.MAIN_HAND);
		BlockState after = minecraft.level.hasChunkAt(pos) ? minecraft.level.getBlockState(pos) : state;
		if (satisfied(after)) {
			brokenTargets++;
			targetIndex++;
			breakingActive = false;
			targetStartTick = -1L;
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_broken targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId);
			return Optional.empty();
		}
		String afterBlockId = blockId(after);
		if (!target.expectedBlockIds().contains(afterBlockId)) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "target_block_mismatch targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId + " afterBlockId=" + afterBlockId));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "breaking targetPos=" + compactPos(pos) + " beforeBlockId=" + currentBlockId);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private static boolean satisfied(BlockState state) {
		return state.isAir() || state.getBlock() instanceof LiquidBlock;
	}

	private static boolean withinInteractionRange(LocalPlayer player, Vec3 pos) {
		return player.distanceToSqr(pos) <= INTERACTION_RANGE_SQUARED;
	}

	private static BlockPos blockPos(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.sourceJobId(), right.sourceJobId())
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
	}

	@Override
	public void shutdown() {
		reset();
	}

	private void reset() {
		if (breakingActive) {
			Minecraft minecraft = clientSupplier.get();
			if (minecraft != null && minecraft.gameMode != null) {
				actuator.stopDestroy(minecraft);
			}
		}
		appliedTask = null;
		terminalEventEmitted = false;
		snapshot = TaskExecutionSnapshot.idle();
		targetIndex = 0;
		brokenTargets = 0;
		skippedTargets = 0;
		breakingActive = false;
		targetStartTick = -1L;
	}
}
