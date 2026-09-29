package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class ReturnToSurfaceTaskExecutor implements WorldTaskExecutor {
	private static final int NAVIGATION_RADIUS_BLOCKS = 3;
	private static final int MAX_TOWER_BLOCKS = 96;
	private static final int BREATHABLE_STABLE_TICKS = 12;
	private static final int HEADROOM_BREAK_TIMEOUT_TICKS = 600;
	private static final int TOWER_SUPPORT_SEARCH_DEPTH = 3;
	private static final int TOWER_SUPPORT_UNAVAILABLE_TIMEOUT_TICKS = 100;
	private static final int UNDERWATER_ESCAPE_PHASE_TICKS = 20;
	private static final double TARGET_FORWARD_HORIZONTAL_DISTANCE_SQUARED = 4.0D;

	private final Supplier<Minecraft> clientSupplier;
	private final BaritoneFacade baritoneFacade;
	private final MovementController movementController = new MovementController();
	private final CameraController cameraController;
	private final OwnedKeyPress jumpKeyControl = new OwnedKeyPress();

	private WorldTaskRequest appliedTask;
	private boolean terminalEventEmitted;
	private boolean navigationStarted;
	private boolean exactSurfaceNavigationStarted;
	private boolean toweringStarted;
	private int towerStartY;
	private BlockPos headroomBreakTarget;
	private long headroomBreakStartTick = -1L;
	private boolean underwaterRecoveryStarted;
	private int breathableTicks;
	private int underwaterStuckTicks;
	private int towerSupportUnavailableTicks;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public ReturnToSurfaceTaskExecutor(BaritoneFacade baritoneFacade) {
		this(Minecraft::getInstance, baritoneFacade);
	}

	ReturnToSurfaceTaskExecutor(Supplier<Minecraft> clientSupplier, BaritoneFacade baritoneFacade) {
		this(clientSupplier, baritoneFacade, new CameraController());
	}

	public ReturnToSurfaceTaskExecutor(BaritoneFacade baritoneFacade, CameraController cameraController) {
		this(Minecraft::getInstance, baritoneFacade, cameraController);
	}

	private ReturnToSurfaceTaskExecutor(Supplier<Minecraft> clientSupplier, BaritoneFacade baritoneFacade, CameraController cameraController) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritoneFacade = baritoneFacade;
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.RETURN_TO_SURFACE) {
			if (appliedTask != null) {
				reset();
			}
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}

		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.level == null || minecraft.gameMode == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		ReturnToSurfaceStepArgs args = ((WorldTaskRequest.ReturnToSurface) request.task()).args();
		if (isSurfaceReached(minecraft, player)) {
			return complete(request, "surface_reached");
		}
		if (underwaterRecoveryStarted || shouldEnterUnderwaterRecovery(player)) {
			Optional<TaskTerminalEvent> recoveryEvent = tickUnderwaterRecovery(
				sessionSnapshot,
				request,
				minecraft,
				player,
				args
			);
			if (recoveryEvent.isPresent()) {
				return recoveryEvent;
			}
			if (underwaterRecoveryStarted) {
				return Optional.empty();
			}
		}
		if (args.targetPosition() != null
			&& !exactSurfaceNavigationStarted
			&& reachedTarget(player, args.targetPosition())) {
			return handleSurfaceTargetReached(request, minecraft, player, args, canRefineSurfaceNavigation());
		}
		if (toweringStarted || shouldTowerFirst(args)) {
			return tickTowering(request, minecraft, player, args);
		}
		if (args.targetPosition() == null) {
			return args.useTowering()
				? tickTowering(request, minecraft, player, args)
				: fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "surface_target_unavailable"));
		}
		if (baritoneFacade == null || !baritoneFacade.isLoaded()) {
			return args.useTowering()
				? tickTowering(request, minecraft, player, args)
					: fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "baritone_unavailable"));
		}
		if (!navigationStarted) {
			baritoneFacade.startNavigateNear(args.targetPosition(), NAVIGATION_RADIUS_BLOCKS);
			navigationStarted = true;
		}
		Optional<String> pathEvent = baritoneFacade.pollPathEvent();
		if (pathEvent.isPresent()) {
			String event = pathEvent.get();
			if ("AT_GOAL".equalsIgnoreCase(event)) {
				return handleSurfaceTargetReached(
					request,
					minecraft,
					player,
					args,
					canRefineSurfaceNavigation()
				);
			}
			if ("CALC_FAILED".equalsIgnoreCase(event) || "CANCELLED".equalsIgnoreCase(event) || "CANCELED".equalsIgnoreCase(event)) {
				return args.useTowering()
					? tickTowering(request, minecraft, player, args)
					: fail(request, TaskFailure.of(TaskFailureCode.TRANSIENT, "surface_path_" + event.toLowerCase(java.util.Locale.ROOT)));
			}
		}
		snapshot = snapshot(
			TaskExecutionState.RUNNING,
			request,
			exactSurfaceNavigationStarted ? "navigating_exact_surface_target" : "navigating_to_surface"
		);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickUnderwaterRecovery(
		SessionSnapshot sessionSnapshot,
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		ReturnToSurfaceStepArgs args
	) {
		if (!underwaterRecoveryStarted) {
			cancelNavigationIfStarted();
			toweringStarted = false;
			underwaterRecoveryStarted = true;
			breathableTicks = 0;
			underwaterStuckTicks = 0;
		}
		if (isBreathable(player)) {
			breathableTicks++;
			movementController.stop(minecraft);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "underwater_recovery:breathable");
			if (shouldExitUnderwaterRecovery(true, breathableTicks)) {
				underwaterRecoveryStarted = false;
				breathableTicks = 0;
				underwaterStuckTicks = 0;
				return Optional.empty();
			}
			return Optional.empty();
		}
		breathableTicks = 0;
		long tick = sessionSnapshot == null ? 0L : sessionSnapshot.tickCount();
		double horizontalDistanceSquared = horizontalDistanceSquared(player, args.targetPosition());
		boolean stuck = movementController.snapshot().stuck();
		RecoveryMovement recoveryMovement = recoveryMovement(
			true,
			args.targetPosition() != null,
			horizontalDistanceSquared,
			stuck
		);
		if (stuck) {
			underwaterStuckTicks++;
		}
		else {
			underwaterStuckTicks = 0;
		}
		UnderwaterRecoveryKeys keys = underwaterRecoveryKeys(recoveryMovement, underwaterStuckTicks);
		if (recoveryMovement == RecoveryMovement.TOWARD_TARGET || (recoveryMovement == RecoveryMovement.STUCK && args.targetPosition() != null)) {
			cameraController.lookAt(minecraft, targetSwimPoint(args.targetPosition()));
		}
		movementController.swimUp(
			minecraft,
			keys.forward(),
			keys.sprint(),
			keys.left(),
			keys.right(),
			keys.back(),
			tick
		);
		String event = recoveryMovementEvent(recoveryMovement);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, event);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> handleSurfaceTargetReached(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		ReturnToSurfaceStepArgs args,
		boolean canRefineNavigation
	) {
		return switch (surfaceTargetOutcome(
			isSurfaceReached(minecraft, player),
			args.useTowering(),
			args.targetKind(),
			canRefineNavigation
		)) {
			case COMPLETE -> complete(request, "surface_target_reached");
			case NAVIGATE_EXACT -> startExactSurfaceNavigation(request, args.targetPosition());
			case TOWER -> tickTowering(request, minecraft, player, args);
			case FAIL -> fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "surface_target_not_surface"));
		};
	}

	private boolean canRefineSurfaceNavigation() {
		return !exactSurfaceNavigationStarted
			&& baritoneFacade != null
			&& baritoneFacade.isLoaded();
	}

	private Optional<TaskTerminalEvent> startExactSurfaceNavigation(
		WorldTaskRequest request,
		GoalPosition targetPosition
	) {
		baritoneFacade.startNavigate(new GoalPosition(targetPosition.x(), targetPosition.y(), targetPosition.z(), true));
		navigationStarted = true;
		exactSurfaceNavigationStarted = true;
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "navigating_exact_surface_target");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickTowering(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		ReturnToSurfaceStepArgs args
	) {
		if (!toweringStarted) {
			movementController.stop(minecraft);
			cancelNavigationIfStarted();
			toweringStarted = true;
			towerStartY = player.getBlockY();
		}
		if (isSurfaceReached(minecraft, player)) {
			return complete(request, "surface_reached_by_towering");
		}
		if (shouldStopToweringAtSurfaceTargetElevation(args, player.getBlockY())) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "surface_target_elevation_reached_not_surface"));
		}
		if (player.getBlockY() - towerStartY > MAX_TOWER_BLOCKS) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "tower_limit_reached"));
		}
		Optional<HeadroomClearance> headroomClearance = clearTowerHeadroom(minecraft, player);
		if (headroomClearance.isPresent()) {
			HeadroomClearance clearance = headroomClearance.get();
			if (clearance.failed()) {
				return fail(request, clearance.failure());
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, clearance.event());
			return Optional.empty();
		}
		InteractionHand hand = selectFillerHand(minecraft, player, args.fillerBlockIds());
		if (hand == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "missing_filler_block fillerBlockIds=" + args.fillerBlockIds()));
		}
		jumpKeyControl.press(minecraft.options.keyJump);
		PlacementAttempt placement = placeUnderFoot(minecraft, player, hand);
		if (placement.accepted()) {
			towerSupportUnavailableTicks = 0;
			player.swing(hand);
		}
		else if ("support_unavailable".equals(placement.reason())) {
			towerSupportUnavailableTicks++;
			if (towerSupportUnavailableTimedOut(towerSupportUnavailableTicks)) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "towering:support_unavailable_timeout"));
			}
		}
		else {
			towerSupportUnavailableTicks = 0;
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "towering:" + placement.reason());
		return Optional.empty();
	}

	private Optional<HeadroomClearance> clearTowerHeadroom(Minecraft minecraft, LocalPlayer player) {
		if (minecraft == null || minecraft.level == null || minecraft.gameMode == null || player == null) {
			return Optional.empty();
		}
		BlockPos target = headroomBreakTarget == null ? firstTowerHeadroomObstruction(minecraft, player.blockPosition()) : headroomBreakTarget;
		if (target == null) {
			clearHeadroomBreakState(minecraft);
			return Optional.empty();
		}
		if (!minecraft.level.hasChunkAt(target)) {
			clearHeadroomBreakState(minecraft);
			return Optional.of(new HeadroomClearance(
				"towering:headroom_unloaded", true,
				TaskFailure.of(TaskFailureCode.ENVIRONMENT_CHANGED, "towering:headroom_unloaded")
			));
		}
		BlockState state = minecraft.level.getBlockState(target);
		if (!shouldClearTowerHeadroom(!state.isAir(), state.canBeReplaced(), !state.getFluidState().isEmpty())) {
			clearHeadroomBreakState(minecraft);
			return Optional.of(new HeadroomClearance("towering:headroom_cleared", false, null));
		}
		MiningToolPreparation.Result tool = MiningToolPreparation.ensureSelectedForClearance(minecraft, player, List.of(state));
		if (!tool.ok()) {
			clearHeadroomBreakState(minecraft);
			return Optional.of(new HeadroomClearance("towering:" + tool.message(), true,
				TaskFailure.of(TaskFailureCode.MISSING_ITEM, tool.message())));
		}
		long tick = minecraft.level.getGameTime();
		if (headroomBreakTarget == null || !headroomBreakTarget.equals(target)) {
			clearHeadroomBreakState(minecraft);
			boolean accepted = minecraft.gameMode.startDestroyBlock(target, Direction.DOWN);
			if (!accepted) {
				return Optional.of(new HeadroomClearance(
					"towering:headroom_break_start_failed", true,
					TaskFailure.of(TaskFailureCode.UNKNOWN, "towering:headroom_break_start_failed")
				));
			}
			headroomBreakTarget = target;
			headroomBreakStartTick = tick;
		}
		if (tick - headroomBreakStartTick > HEADROOM_BREAK_TIMEOUT_TICKS) {
			clearHeadroomBreakState(minecraft);
			return Optional.of(new HeadroomClearance(
				"towering:headroom_break_timeout", true,
				TaskFailure.of(TaskFailureCode.TRANSIENT, "towering:headroom_break_timeout")
			));
		}
		jumpKeyControl.release(minecraft.options.keyJump);
		minecraft.gameMode.continueDestroyBlock(target, Direction.DOWN);
		player.swing(InteractionHand.MAIN_HAND);
		BlockState after = minecraft.level.hasChunkAt(target) ? minecraft.level.getBlockState(target) : state;
		if (!shouldClearTowerHeadroom(!after.isAir(), after.canBeReplaced(), !after.getFluidState().isEmpty())) {
			clearHeadroomBreakState(minecraft);
			return Optional.of(new HeadroomClearance("towering:headroom_cleared", false, null));
		}
		return Optional.of(new HeadroomClearance("towering:clearing_headroom", false, null));
	}

	static boolean towerSupportUnavailableTimedOut(int unavailableTicks) {
		return unavailableTicks >= TOWER_SUPPORT_UNAVAILABLE_TIMEOUT_TICKS;
	}

	private void clearHeadroomBreakState(Minecraft minecraft) {
		if (headroomBreakTarget != null && minecraft != null && minecraft.gameMode != null) {
			minecraft.gameMode.stopDestroyBlock();
		}
		headroomBreakTarget = null;
		headroomBreakStartTick = -1L;
	}

	private static BlockPos firstTowerHeadroomObstruction(Minecraft minecraft, BlockPos feetPos) {
		if (minecraft == null || minecraft.level == null || feetPos == null) {
			return null;
		}
		for (int offset = 1; offset <= 2; offset++) {
			BlockPos candidate = feetPos.above(offset);
			if (!minecraft.level.hasChunkAt(candidate)) {
				return candidate;
			}
			BlockState state = minecraft.level.getBlockState(candidate);
			if (shouldClearTowerHeadroom(!state.isAir(), state.canBeReplaced(), !state.getFluidState().isEmpty())) {
				return candidate;
			}
		}
		return null;
	}

	private boolean shouldTowerFirst(ReturnToSurfaceStepArgs args) {
		return args != null && args.useTowering() && args.targetPosition() == null;
	}

	private static boolean shouldEnterUnderwaterRecovery(LocalPlayer player) {
		return player != null
			&& shouldEnterUnderwaterRecovery(player.isInWater(), player.isUnderWater(), isBreathable(player));
	}

	static boolean shouldEnterUnderwaterRecovery(boolean touchingWater, boolean submergedInWater, boolean breathable) {
		return !breathable && (touchingWater || submergedInWater);
	}

	private static boolean isBreathable(LocalPlayer player) {
		return player != null && !player.isUnderWater() && player.getAirSupply() >= player.getMaxAirSupply();
	}

	public static RecoveryMovement recoveryMovement(boolean underwater, boolean targetAvailable, double horizontalDistanceSquared, boolean stuck) {
		if (!underwater) {
			return RecoveryMovement.BREATHABLE;
		}
		if (stuck) {
			return RecoveryMovement.STUCK;
		}
		return targetAvailable && horizontalDistanceSquared > TARGET_FORWARD_HORIZONTAL_DISTANCE_SQUARED
			? RecoveryMovement.TOWARD_TARGET
			: RecoveryMovement.ASCENDING;
	}

	static boolean shouldExitUnderwaterRecovery(boolean breathable, int breathableTicks) {
		return breathable && breathableTicks >= BREATHABLE_STABLE_TICKS;
	}

	static boolean shouldClearTowerHeadroom(boolean occupied, boolean replaceable, boolean hasFluid) {
		return occupied && !replaceable && !hasFluid;
	}

	public static UnderwaterRecoveryKeys underwaterRecoveryKeys(RecoveryMovement recoveryMovement, int stuckTicks) {
		if (recoveryMovement == RecoveryMovement.TOWARD_TARGET) {
			return new UnderwaterRecoveryKeys(true, true, false, false, false);
		}
		if (recoveryMovement != RecoveryMovement.STUCK) {
			return new UnderwaterRecoveryKeys(false, false, false, false, false);
		}
		int phase = Math.floorDiv(Math.max(0, stuckTicks), UNDERWATER_ESCAPE_PHASE_TICKS) % 4;
		return switch (phase) {
			case 0 -> new UnderwaterRecoveryKeys(true, true, true, false, false);
			case 1 -> new UnderwaterRecoveryKeys(true, true, false, true, false);
			case 2 -> new UnderwaterRecoveryKeys(false, false, true, false, true);
			default -> new UnderwaterRecoveryKeys(false, false, false, true, true);
		};
	}

	private static double horizontalDistanceSquared(LocalPlayer player, GoalPosition target) {
		if (player == null || target == null) {
			return 0.0D;
		}
		double dx = target.x() + 0.5D - player.getX();
		double dz = target.z() + 0.5D - player.getZ();
		return dx * dx + dz * dz;
	}

	private static Vec3 targetSwimPoint(GoalPosition target) {
		return new Vec3(target.x() + 0.5D, target.y() + 1.0D, target.z() + 0.5D);
	}

	private static String recoveryMovementEvent(RecoveryMovement recoveryMovement) {
		return switch (recoveryMovement) {
			case ASCENDING -> "underwater_recovery:ascending";
			case TOWARD_TARGET -> "underwater_recovery:toward_target";
			case BREATHABLE -> "underwater_recovery:breathable";
			case STUCK -> "underwater_recovery:stuck";
		};
	}

	private static PlacementAttempt placeUnderFoot(Minecraft minecraft, LocalPlayer player, InteractionHand hand) {
		Optional<BlockPos> support = findTowerSupport(minecraft, player, hand);
		if (support.isEmpty()) {
			return new PlacementAttempt(false, "support_unavailable");
		}
		BlockPos supportPos = support.get();
		BlockHitResult hitResult = new BlockHitResult(
			new Vec3(supportPos.getX() + 0.5D, supportPos.getY() + 1.0D, supportPos.getZ() + 0.5D),
			Direction.UP,
			supportPos,
			false
		);
		InteractionResult result = minecraft.gameMode.useItemOn(player, hand, hitResult);
		return new PlacementAttempt(result.consumesAction(), result.consumesAction() ? "placed" : "interact_" + result);
	}

	private static Optional<BlockPos> findTowerSupport(Minecraft minecraft, LocalPlayer player, InteractionHand hand) {
		if (player == null) {
			return Optional.empty();
		}
		BlockPos feet = player.blockPosition();
		for (int offset = 1; offset <= TOWER_SUPPORT_SEARCH_DEPTH; offset++) {
			BlockPos support = feet.below(offset);
			if (canPlaceAgainst(minecraft, player, hand, support)) {
				return Optional.of(support);
			}
		}
		return Optional.empty();
	}

	private static boolean canPlaceAgainst(Minecraft minecraft, LocalPlayer player, InteractionHand hand, BlockPos support) {
		if (minecraft == null || minecraft.level == null || player == null || support == null) {
			return false;
		}
		BlockPos target = support.above();
		if (!minecraft.level.hasChunkAt(support) || !minecraft.level.hasChunkAt(target)) {
			return false;
		}
		BlockState supportState = minecraft.level.getBlockState(support);
		BlockState targetState = minecraft.level.getBlockState(target);
		ItemStack selected = hand == InteractionHand.OFF_HAND ? player.getOffhandItem() : player.getMainHandItem();
		if (!(selected.getItem() instanceof BlockItem blockItem)) {
			return false;
		}
		return supportState.isFaceSturdy(minecraft.level, support, Direction.UP)
			&& (targetState.isAir() || targetState.canBeReplaced())
			&& minecraft.level.isUnobstructed(blockItem.getBlock().defaultBlockState(), target, CollisionContext.of(player));
	}

	private static InteractionHand selectFillerHand(Minecraft minecraft, LocalPlayer player, List<String> fillerBlockIds) {
		if (matchesFiller(player.getOffhandItem(), fillerBlockIds)) {
			return InteractionHand.OFF_HAND;
		}
		return selectHotbarFiller(minecraft, player, fillerBlockIds) ? InteractionHand.MAIN_HAND : null;
	}

	private static boolean selectHotbarFiller(Minecraft minecraft, LocalPlayer player, List<String> fillerBlockIds) {
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
			return false;
		}
		AbstractContainerMenu menu = player.containerMenu;
		int sourceSlot = findFillerSlot(menu, fillerBlockIds);
		if (sourceSlot < 0) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= InventoryMenu.USE_ROW_SLOT_START && sourceSlot < InventoryMenu.USE_ROW_SLOT_END) {
			selectAndSyncHotbarSlot(minecraft, player, sourceSlot - InventoryMenu.USE_ROW_SLOT_START);
			return matchesFiller(player.getInventory().getSelectedItem(), fillerBlockIds);
		}
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, selectedHotbarSlot, ClickType.SWAP, player);
		selectAndSyncHotbarSlot(minecraft, player, selectedHotbarSlot);
		return matchesFiller(player.getInventory().getSelectedItem(), fillerBlockIds);
	}

	private static int findFillerSlot(AbstractContainerMenu menu, List<String> fillerBlockIds) {
		if (!(menu instanceof InventoryMenu)) {
			return -1;
		}
		for (int slot = InventoryMenu.USE_ROW_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			if (matchesFiller(menu.getSlot(slot).getItem(), fillerBlockIds)) {
				return slot;
			}
		}
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_START; slot++) {
			if (matchesFiller(menu.getSlot(slot).getItem(), fillerBlockIds)) {
				return slot;
			}
		}
		return -1;
	}

	private static boolean matchesFiller(ItemStack stack, List<String> fillerBlockIds) {
		if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof BlockItem) || fillerBlockIds == null) {
			return false;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		return fillerBlockIds.stream().anyMatch(itemId::equals);
	}

	private static void selectAndSyncHotbarSlot(Minecraft minecraft, LocalPlayer player, int hotbarSlot) {
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (minecraft.getConnection() != null) {
			minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
		}
	}

	private static boolean reachedTarget(LocalPlayer player, GoalPosition position) {
		return player != null && reachedTarget(player.blockPosition(), position);
	}

	static boolean reachedTarget(BlockPos playerPos, GoalPosition position) {
		if (playerPos == null || position == null) {
			return false;
		}
		return Math.abs(playerPos.getX() - position.x()) <= NAVIGATION_RADIUS_BLOCKS
			&& Math.abs(playerPos.getZ() - position.z()) <= NAVIGATION_RADIUS_BLOCKS
			&& Math.abs(playerPos.getY() - position.y()) <= 2;
	}

	private static boolean isSurfaceReached(Minecraft minecraft, LocalPlayer player) {
		return player != null
			&& player.onGround()
			&& SurfaceMemory.isSurfaceStandingPosition(minecraft, player.blockPosition());
	}

	static SurfaceTargetOutcome surfaceTargetOutcome(
		boolean surfaceReached,
		boolean useTowering,
		String targetKind,
		boolean canRefineNavigation
	) {
		if (surfaceReached) {
			return SurfaceTargetOutcome.COMPLETE;
		}
		if (isRememberedSurfaceTarget(targetKind)) {
			return canRefineNavigation ? SurfaceTargetOutcome.NAVIGATE_EXACT : SurfaceTargetOutcome.FAIL;
		}
		return useTowering ? SurfaceTargetOutcome.TOWER : SurfaceTargetOutcome.FAIL;
	}

	static boolean shouldStopToweringAtSurfaceTargetElevation(ReturnToSurfaceStepArgs args, int playerBlockY) {
		return args != null
			&& args.targetPosition() != null
			&& isRememberedSurfaceTarget(args.targetKind())
			&& playerBlockY >= args.targetPosition().y();
	}

	static boolean isRememberedSurfaceTarget(String targetKind) {
		return "nearest_surface".equals(targetKind) || "last_surface".equals(targetKind);
	}

	private Optional<TaskTerminalEvent> complete(WorldTaskRequest request, String message) {
		cancelNavigationIfStarted();
		releaseMovementControls();
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		cancelNavigationIfStarted();
		releaseMovementControls();
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private void cancelNavigationIfStarted() {
		if (navigationStarted && baritoneFacade != null && baritoneFacade.isLoaded()) {
			baritoneFacade.cancel();
		}
		navigationStarted = false;
		exactSurfaceNavigationStarted = false;
	}

	private void releaseMovementControls() {
		Minecraft minecraft = clientSupplier.get();
		if (minecraft != null) {
			movementController.stop(minecraft);
		}
		jumpKeyControl.release(minecraft == null ? null : minecraft.options.keyJump);
		clearHeadroomBreakState(minecraft);
	}

	private void reset() {
		cancelNavigationIfStarted();
		releaseMovementControls();
		appliedTask = null;
		terminalEventEmitted = false;
		toweringStarted = false;
		towerStartY = 0;
		underwaterRecoveryStarted = false;
		breathableTicks = 0;
		underwaterStuckTicks = 0;
		towerSupportUnavailableTicks = 0;
		snapshot = TaskExecutionSnapshot.idle();
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		if (left == right) {
			return true;
		}
		if (left == null || right == null || left.type() != WorldTaskType.RETURN_TO_SURFACE || right.type() != WorldTaskType.RETURN_TO_SURFACE) {
			return false;
		}
		return Objects.equals(left.taskId(), right.taskId())
			&& Objects.equals(left.task(), right.task());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), null, "ReturnToSurface", event, null, null);
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

	private record PlacementAttempt(boolean accepted, String reason) {
	}

	private record HeadroomClearance(String event, boolean failed, TaskFailure failure) {
	}

	public record UnderwaterRecoveryKeys(boolean forward, boolean sprint, boolean left, boolean right, boolean back) {
	}

	enum SurfaceTargetOutcome {
		COMPLETE,
		NAVIGATE_EXACT,
		TOWER,
		FAIL
	}

	public enum RecoveryMovement {
		ASCENDING,
		TOWARD_TARGET,
		BREATHABLE,
		STUCK
	}
}
