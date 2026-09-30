package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Harvests exact loaded source blocks inside a fixed local boundary. It owns
 * underwater approach and breathing; Baritone is used only to reach a safe
 * nearby position while the player is still breathing.
 */
public final class UnderwaterHarvestTaskExecutor implements WorldTaskExecutor {
	private static final double INTERACTION_RANGE_SQUARED = 20.25D;
	private static final int NAVIGATION_RADIUS_BLOCKS = 2;
	private static final int BREAK_TIMEOUT_TICKS = 200;
	private static final int PICKUP_TIMEOUT_TICKS = 80;

	private final Supplier<Minecraft> clientSupplier;
	private final NavigationFacade baritone;
	private final CameraController camera;
	private final MovementController movement;
	private final MinecraftUnderwaterEscapeController underwaterEscape;

	private WorldTaskRequest appliedTask;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();
	private boolean terminalEventEmitted;
	private HarvestRun run;
	private BlockPos breakingTarget;
	private long breakStartedTick = -1L;
	private final Set<UUID> unreachableDropIds = new HashSet<>();
	private int inventoryBeforeBreak;
	private boolean navigationStarted;
	private boolean surfacing;
	private boolean completionPending;
	private String completionMessage;
	private int harvestedBlocks;
	private BlockPos assistedApproachTarget;
	private int obstacleAscentTicksRemaining;
	private BlockPos groundingTarget;
	private int groundingTicks;

	public UnderwaterHarvestTaskExecutor(NavigationFacade baritone, CameraController camera) {
		this(Minecraft::getInstance, baritone, camera);
	}

	UnderwaterHarvestTaskExecutor(
		Supplier<Minecraft> clientSupplier,
		NavigationFacade baritone,
		CameraController camera
	) {
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.baritone = baritone;
		this.camera = camera == null ? new CameraController() : camera;
		this.movement = new MovementController("underwater_harvest", Priority.FOREGROUND);
		this.underwaterEscape = new MinecraftUnderwaterEscapeController(baritone, movement, this.camera);
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot sessionSnapshot, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.UNDERWATER_HARVEST) {
			reset();
			return Optional.empty();
		}
		WorldTaskRequest request = activeTask.get();
		if (!sameTask(request, appliedTask)) {
			reset();
			appliedTask = request;
			BlockPos searchOrigin = searchOrigin(request);
			run = searchOrigin == null ? null : new HarvestRun(searchOrigin);
		}
		if (sessionSnapshot == null || !sessionSnapshot.companionActuationAllowed()) {
			releaseControls();
			snapshot = snapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, request, "session_gate");
			return Optional.empty();
		}
		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.level == null || minecraft.gameMode == null || player == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.UNKNOWN, "world_unavailable"));
		}
		if (request.goal() == null || request.goal().mineSpec() == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.INVALID_ACTION, "unsupported_acquisition_method missing_mine_spec"));
		}
		if (run == null) {
			return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "missing_underwater_harvest_origin"));
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) {
			return fail(request, TaskFailure.of(TaskFailureCode.BUSY, "interaction_busy"));
		}

		GoalMineSpec spec = request.goal().mineSpec();
		int inventoryCount = matchingInventoryCount(player, spec.matchingItemIds());
		if (UnderwaterHarvestPolicy.terminalDecision(inventoryCount, spec.quantity(), 1)
			== UnderwaterHarvestPolicy.TerminalDecision.COMPLETE) {
			completionPending = true;
			completionMessage = "underwater_harvest_succeeded itemCount=" + inventoryCount
				+ " targetCount=" + spec.quantity() + " harvestedBlocks=" + harvestedBlocks;
		}
		long tick = sessionSnapshot.tickCount();
		boolean airRecoveryRequired = UnderwaterHarvestPolicy.shouldSurface(
			player.isUnderWater(),
			player.getAirSupply(),
			player.getMaxAirSupply()
		);
		boolean airRecoveryComplete = UnderwaterHarvestPolicy.mayResumeHarvest(
			player.isUnderWater(),
			player.getAirSupply(),
			player.getMaxAirSupply()
		);
		UnderwaterHarvestPolicy.PreSourceAction preSourceAction = UnderwaterHarvestPolicy.preSourceAction(
			surfacing,
			airRecoveryRequired,
			completionPending,
			airRecoveryComplete,
			run.pickupTicksRemaining() >= 0
		);
		if (preSourceAction == UnderwaterHarvestPolicy.PreSourceAction.RECOVER_AIR) {
			run.tickPickup(true);
			return tickSurfacing(request, minecraft, player, tick);
		}
		if (preSourceAction == UnderwaterHarvestPolicy.PreSourceAction.COMPLETE) {
			return tickCompletion(request, minecraft);
		}
		if (preSourceAction == UnderwaterHarvestPolicy.PreSourceAction.PICK_UP) {
			Optional<TaskTerminalEvent> pickup = tickPickup(request, minecraft, player, spec, tick);
			if (pickup.isPresent() || run.pickupTicksRemaining() >= 0) {
				return pickup;
			}
		}
		if (run.needsSourceScan()) {
			SourceScan scan = selectBatch(minecraft, spec, run.searchOrigin(), run.unreachableTargets());
			run.installBatch(scan);
			if (run.currentTarget().isEmpty()) {
				boolean everyDiscoveredExcluded = !scan.discoveredTargets().isEmpty()
					&& run.unreachableTargets().containsAll(scan.discoveredTargets());
				UnderwaterHarvestPolicy.SourceExhaustion exhaustion = UnderwaterHarvestPolicy.sourceExhaustion(
					scan.discoveredTargets().size(),
					run.unreachableTargets().size(),
					everyDiscoveredExcluded
				);
				String reason = exhaustion == UnderwaterHarvestPolicy.SourceExhaustion.RESOURCE_UNREACHABLE_NEARBY
					? "resource_unreachable_nearby"
					: "resource_not_found_nearby";
				return fail(request, TaskFailure.of(
					exhaustion == UnderwaterHarvestPolicy.SourceExhaustion.RESOURCE_NOT_FOUND_NEARBY
						? TaskFailureCode.MISSING_FACT
						: TaskFailureCode.UNKNOWN,
					reason + " radius=" + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS
						+ " verticalRadius=" + UnderwaterHarvestPolicy.VERTICAL_RADIUS + " blockIds=" + spec.blockIds()
						+ " itemCount=" + inventoryCount + " targetCount=" + spec.quantity()
				));
			}
		}

		HarvestTarget target = run.currentTarget().orElseThrow();
		BlockState currentState = minecraft.level.getBlockState(target.pos());
		if (!spec.blockIds().contains(blockId(currentState))) {
			cancelNavigation();
			movement.stop(minecraft);
			clearBreak(minecraft);
			run.invalidateBatch();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_reassess targetPos=" + compactPos(target.pos()));
			return Optional.empty();
		}
		Optional<UnderwaterHarvestPolicy.SourceEnvironment> observedEnvironment =
			MinecraftUnderwaterSourceClassifier.classify(minecraft, target.pos(), currentState);
		if (observedEnvironment.isEmpty()) {
			cancelNavigation();
			movement.stop(minecraft);
			clearBreak(minecraft);
			run.invalidateBatch();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_no_longer_accessible targetPos="
				+ compactPos(target.pos()));
			return Optional.empty();
		}
		UnderwaterHarvestPolicy.SourceEnvironment currentEnvironment = observedEnvironment.orElseThrow();
		if (!run.reconcileEnvironment(currentEnvironment)) {
			cancelNavigation();
			movement.stop(minecraft);
			clearBreak(minecraft);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_environment_changed targetPos="
				+ compactPos(target.pos()) + " environment=" + currentEnvironment.name().toLowerCase(java.util.Locale.ROOT));
			return Optional.empty();
		}
		if (target.environment().underwater()
			&& UnderwaterHarvestPolicy.shouldSurface(player.isUnderWater(), player.getAirSupply(), player.getMaxAirSupply())) {
			return tickSurfacing(request, minecraft, player, tick);
		}
		// A successful break can consume the last required tool. Air recovery and
		// pickup must still finish before another source action needs that tool.
		if (!selectRequiredTool(minecraft, player, spec.requiredToolItemIds())) {
			return fail(request, TaskFailure.of(TaskFailureCode.INVALID_ACTION, "unsupported_acquisition_method missing_required_tool requiredToolItemIds=" + spec.requiredToolItemIds()));
		}

		Vec3 targetCenter = Vec3.atCenterOf(target.pos());
		double distanceSquared = player.getEyePosition().distanceToSqr(targetCenter);
		if (distanceSquared > INTERACTION_RANGE_SQUARED) {
			return tickApproach(request, minecraft, player, target, targetCenter, distanceSquared, tick);
		}
		run.clearApproach();
		cancelNavigation();
		NavigationRelease.release(baritone);
		clearApproachAssist();
		if (tickGroundingForBreak(request, minecraft, player, target, tick)) {
			return Optional.empty();
		}
		movement.stop(minecraft);
		camera.lookAtBlock(minecraft, target.pos());
		var cursorHit = camera.blockHit(minecraft, target.pos());
		if (cursorHit.isEmpty()) return Optional.empty();
		if (breakingTarget == null) {
			if (!minecraft.gameMode.startDestroyBlock(target.pos(), cursorHit.get().getDirection())) {
				return fail(request, TaskFailure.of(TaskFailureCode.MISSING_FACT, "break_start_failed targetPos=" + compactPos(target.pos())));
			}
			breakingTarget = target.pos();
			breakStartedTick = tick;
			inventoryBeforeBreak = inventoryCount;
		}
		if (tick - breakStartedTick > BREAK_TIMEOUT_TICKS) {
			return fail(request, TaskFailure.of(TaskFailureCode.TRANSIENT, "break_timeout targetPos=" + compactPos(target.pos())));
		}
		minecraft.gameMode.continueDestroyBlock(target.pos(), cursorHit.get().getDirection());
		player.swing(InteractionHand.MAIN_HAND);
		if (!spec.blockIds().contains(blockId(minecraft.level.getBlockState(target.pos())))) {
			harvestedBlocks++;
			clearBreak(minecraft);
			// Breaking a source can expose water around other candidates. Re-scan
			// from the immutable origin so dry-first ordering uses current fluid.
			run.invalidateBatch();
			run.startPickupWindow(PICKUP_TIMEOUT_TICKS);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_broken targetPos=" + compactPos(target.pos())
				+ " environment=" + target.environment().name().toLowerCase());
			return Optional.empty();
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "breaking targetPos=" + compactPos(target.pos())
			+ " environment=" + target.environment().name().toLowerCase() + " air=" + player.getAirSupply());
		return Optional.empty();
	}

	private boolean tickGroundingForBreak(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		HarvestTarget target,
		long tick
	) {
		BlockPos immutableTarget = target.pos().immutable();
		if (!immutableTarget.equals(groundingTarget)) {
			groundingTarget = immutableTarget;
			groundingTicks = 0;
		}
		UnderwaterHarvestPolicy.GroundingDecision decision = UnderwaterHarvestPolicy.groundingDecision(
			target.environment().underwater(),
			player.isUnderWater(),
			player.onGround(),
			hasSolidSupportDirectlyBelow(minecraft, player),
			groundingTicks
		);
		if (decision != UnderwaterHarvestPolicy.GroundingDecision.DESCEND) {
			clearGrounding();
			return false;
		}
		groundingTicks++;
		movement.moveDirectional(minecraft, false, false, false, false, false, false, true, tick);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "descending_to_ground targetPos="
			+ compactPos(target.pos()) + " groundingTicks=" + groundingTicks);
		return true;
	}

	private static boolean hasSolidSupportDirectlyBelow(Minecraft minecraft, LocalPlayer player) {
		if (minecraft == null || minecraft.level == null || player == null) {
			return false;
		}
		BlockPos supportPos = player.blockPosition().below();
		return minecraft.level.getBlockState(supportPos).isFaceSturdy(minecraft.level, supportPos, Direction.UP);
	}

	private Optional<TaskTerminalEvent> tickApproach(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		HarvestTarget target,
		Vec3 targetCenter,
		double distanceSquared,
		long tick
	) {
		if (run.prepareApproach(target.pos(), Math.sqrt(distanceSquared))) {
			movement.stop(minecraft);
			clearApproachAssist();
		}
		UnderwaterHarvestPolicy.PositioningMode positioningMode = UnderwaterHarvestPolicy.positioningMode(target.environment());
		if (positioningMode == UnderwaterHarvestPolicy.PositioningMode.BARITONE) {
			if (!navigationStarted) NavigationRelease.release(baritone);
		}
		else {
			cancelNavigation();
			NavigationRelease.release(baritone);
		}
		UnderwaterHarvestPolicy.ApproachUpdate progress = run.observeApproach(Math.sqrt(distanceSquared));
		if (progress.decision() == UnderwaterHarvestPolicy.ApproachDecision.EXCLUDE_TARGET) {
			String reason = run.approachProgress().activeTicks() >= UnderwaterHarvestPolicy.APPROACH_TIMEOUT_TICKS
				? "approach_timeout"
				: "approach_stalled";
			return excludeTarget(request, minecraft, target, reason);
		}
		return routeApproachEffect(
			target.environment(),
			() -> tickDryApproach(request, minecraft, target),
			() -> tickUnderwaterApproach(request, minecraft, player, target, targetCenter, tick)
		);
	}

	private Optional<TaskTerminalEvent> tickDryApproach(
		WorldTaskRequest request,
		Minecraft minecraft,
		HarvestTarget target
	) {
		movement.stop(minecraft);
		if (baritone == null || !baritone.isLoaded()) {
			return excludeTarget(request, minecraft, target, "baritone_unavailable");
		}
		if (!navigationStarted) {
			baritone.startNavigateNear(goalPosition(target.pos()), NAVIGATION_RADIUS_BLOCKS);
			navigationStarted = true;
		}
		Optional<String> event = baritone.pollPathEvent();
		if (event.isPresent() && ("CALC_FAILED".equalsIgnoreCase(event.get())
			|| "CANCELLED".equalsIgnoreCase(event.get()) || "CANCELED".equalsIgnoreCase(event.get()))) {
			return excludeTarget(request, minecraft, target, "baritone_" + event.orElseThrow().toLowerCase(java.util.Locale.ROOT));
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "approaching_dry_target_with_baritone targetPos=" + compactPos(target.pos())
			+ " approachTicks=" + run.approachProgress().activeTicks());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickUnderwaterApproach(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		HarvestTarget target,
		Vec3 targetCenter,
		long tick
	) {
		camera.lookAt(minecraft, targetCenter);
		UnderwaterHarvestPolicy.VerticalMotion verticalMotion = moveUnderwaterToward(
			minecraft,
			player,
			target.pos(),
			targetCenter,
			tick
		);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "approaching_underwater_target targetPos=" + compactPos(target.pos())
			+ " environment=" + target.environment().name().toLowerCase() + " air=" + player.getAirSupply()
			+ " verticalMotion=" + verticalMotion.name().toLowerCase()
			+ " approachTicks=" + run.approachProgress().activeTicks());
		return Optional.empty();
	}

	private UnderwaterHarvestPolicy.VerticalMotion moveUnderwaterToward(
		Minecraft minecraft,
		LocalPlayer player,
		BlockPos targetPos,
		Vec3 target,
		long tick
	) {
		if (!player.isInWater() && !player.isUnderWater()) {
			clearApproachAssist();
			movement.moveForward(minecraft, false, false, tick);
			return UnderwaterHarvestPolicy.VerticalMotion.LEVEL;
		}
		BlockPos immutableTarget = targetPos.immutable();
		if (!immutableTarget.equals(assistedApproachTarget)) {
			assistedApproachTarget = immutableTarget;
			obstacleAscentTicksRemaining = 0;
		}
		boolean ascentClear = verticalClearance(minecraft, player, 0.6D);
		if (player.horizontalCollision && ascentClear) {
			obstacleAscentTicksRemaining = UnderwaterHarvestPolicy.OBSTACLE_ASCENT_TICKS;
		}
		boolean descentClear = verticalClearance(minecraft, player, -0.6D);
		UnderwaterHarvestPolicy.VerticalMotion motion = UnderwaterHarvestPolicy.underwaterVerticalMotion(
			target.y - player.getEyeY(),
			player.horizontalCollision,
			obstacleAscentTicksRemaining > 0,
			ascentClear,
			descentClear
		);
		if (motion == UnderwaterHarvestPolicy.VerticalMotion.ASCEND && obstacleAscentTicksRemaining > 0) {
			obstacleAscentTicksRemaining--;
		}
		else if (!ascentClear) {
			obstacleAscentTicksRemaining = 0;
		}
		movement.moveDirectional(
			minecraft,
			true,
			false,
			false,
			false,
			false,
			motion == UnderwaterHarvestPolicy.VerticalMotion.ASCEND,
			motion == UnderwaterHarvestPolicy.VerticalMotion.DESCEND,
			tick
		);
		return motion;
	}

	private static boolean verticalClearance(Minecraft minecraft, LocalPlayer player, double offsetY) {
		return minecraft != null
			&& minecraft.level != null
			&& player != null
			&& minecraft.level.noCollision(player, player.getBoundingBox().move(0.0D, offsetY, 0.0D));
	}

	static <T> T routeApproachEffect(
		UnderwaterHarvestPolicy.SourceEnvironment environment,
		Supplier<T> baritoneEffect,
		Supplier<T> directEffect
	) {
		Objects.requireNonNull(environment, "environment");
		Objects.requireNonNull(baritoneEffect, "baritoneEffect");
		Objects.requireNonNull(directEffect, "directEffect");
		return UnderwaterHarvestPolicy.positioningMode(environment)
			== UnderwaterHarvestPolicy.PositioningMode.BARITONE
			? baritoneEffect.get()
			: directEffect.get();
	}

	private Optional<TaskTerminalEvent> excludeTarget(
		WorldTaskRequest request,
		Minecraft minecraft,
		HarvestTarget target,
		String reason
	) {
		run.excludeCurrentTarget(target.pos());
		cancelNavigation();
		clearBreak(minecraft);
		movement.stop(minecraft);
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "target_excluded targetPos=" + compactPos(target.pos())
			+ " reason=" + reason);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickSurfacing(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		long tick
	) {
		if (!surfacing) {
			cancelNavigation();
			clearBreak(minecraft);
			clearApproachAssist();
			underwaterEscape.reset(minecraft);
			surfacing = true;
		}
		MinecraftUnderwaterEscapeController.Snapshot escape = underwaterEscape.tick(
			minecraft,
			UnderwaterEscapeSearch.SearchMode.BREATHABLE,
			player.getAirSupply(),
			tick,
			!player.isUnderWater()
		);
		boolean mayResume = UnderwaterHarvestPolicy.mayResumeHarvest(
			player.isUnderWater(), player.getAirSupply(), player.getMaxAirSupply());
		if (UnderwaterHarvestPolicy.recoveryComplete(
			mayResume,
			escape.navigation().phase() == UnderwaterEscapeNavigator.Phase.REACHED,
			escape.navigation().ownsBaritone(),
			NavigationRelease.idle(baritone)
		)) {
			underwaterEscape.reset(minecraft);
			movement.stop(minecraft);
			surfacing = false;
			if (completionPending) {
				return tickCompletion(request, minecraft);
			}
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "air_replenished air=" + player.getAirSupply());
			return Optional.empty();
		}
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "surfacing_for_air air=" + player.getAirSupply()
			+ " reserve=" + UnderwaterHarvestPolicy.AIR_RESERVE_TICKS
			+ " escapePhase=" + escape.navigation().phase().name().toLowerCase(java.util.Locale.ROOT)
			+ " escapeCandidates=" + escape.candidateCount());
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> tickPickup(
		WorldTaskRequest request,
		Minecraft minecraft,
		LocalPlayer player,
		GoalMineSpec spec,
		long tick
	) {
		int inventoryCount = matchingInventoryCount(player, spec.matchingItemIds());
		if (inventoryCount >= spec.quantity()) {
			run.clearPickupWindow();
			completionPending = true;
			completionMessage = "underwater_harvest_succeeded itemCount=" + inventoryCount
				+ " targetCount=" + spec.quantity() + " harvestedBlocks=" + harvestedBlocks;
			if (!UnderwaterHarvestPolicy.mayResumeHarvest(
				player.isUnderWater(), player.getAirSupply(), player.getMaxAirSupply())) {
				return tickSurfacing(request, minecraft, player, tick);
			}
			return tickCompletion(request, minecraft);
		}
		Optional<ItemEntity> drop = nearestMatchingDrop(minecraft, player, spec.matchingItemIds(), unreachableDropIds);
		UnderwaterHarvestPolicy.PickupDecision decision = UnderwaterHarvestPolicy.pickupDecision(
			inventoryBeforeBreak,
			inventoryCount,
			run.pickupTicksRemaining(),
			drop.isPresent()
		);
		if (decision == UnderwaterHarvestPolicy.PickupDecision.COLLECTED) {
			movement.stop(minecraft);
			clearApproachAssist();
			run.clearPickupWindow();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "pickup_collected itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			return Optional.empty();
		}
		if (decision == UnderwaterHarvestPolicy.PickupDecision.UNREACHABLE) {
			movement.stop(minecraft);
			clearApproachAssist();
			drop.ifPresent(entity -> unreachableDropIds.add(entity.getUUID()));
			run.clearPickupWindow();
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "pickup_unreachable itemCount=" + inventoryCount + " targetCount=" + spec.quantity());
			return Optional.empty();
		}
		run.tickPickup(false);
		if (decision == UnderwaterHarvestPolicy.PickupDecision.APPROACH) {
			Vec3 itemPos = drop.orElseThrow().position();
			UnderwaterHarvestPolicy.PickupTarget blockCenter = UnderwaterHarvestPolicy.pickupTarget(
				itemPos.x,
				itemPos.y,
				itemPos.z
			);
			Vec3 target = new Vec3(blockCenter.x(), blockCenter.y(), blockCenter.z());
			camera.lookAt(minecraft, target);
			UnderwaterHarvestPolicy.VerticalMotion verticalMotion = moveUnderwaterToward(
				minecraft,
				player,
				new BlockPos(
					(int) Math.floor(target.x),
					(int) Math.floor(target.y),
					(int) Math.floor(target.z)
				),
				target,
				tick
			);
			snapshot = snapshot(TaskExecutionState.RUNNING, request, "collecting_drop itemCount=" + inventoryCount
				+ " targetCount=" + spec.quantity() + " verticalMotion=" + verticalMotion.name().toLowerCase());
			return Optional.empty();
		}
		movement.stop(minecraft);
		clearApproachAssist();
		snapshot = snapshot(TaskExecutionState.RUNNING, request, "waiting_for_drop itemCount=" + inventoryCount);
		return Optional.empty();
	}

	private static SourceScan selectBatch(
		Minecraft minecraft,
		GoalMineSpec spec,
		BlockPos origin,
		Set<BlockPos> excludedTargets
	) {
		Set<String> blockIds = new HashSet<>(spec.blockIds());
		ArrayList<UnderwaterHarvestPolicy.Target> candidates = new ArrayList<>();
		Set<BlockPos> discovered = new HashSet<>();
		for (int x = origin.getX() - UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; x <= origin.getX() + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; x++) {
			for (int z = origin.getZ() - UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; z <= origin.getZ() + UnderwaterHarvestPolicy.HORIZONTAL_RADIUS; z++) {
				for (int y = origin.getY() - UnderwaterHarvestPolicy.VERTICAL_RADIUS; y <= origin.getY() + UnderwaterHarvestPolicy.VERTICAL_RADIUS; y++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (!minecraft.level.isInWorldBounds(pos) || !minecraft.level.hasChunkAt(pos)) {
						continue;
					}
					BlockState state = minecraft.level.getBlockState(pos);
					String blockId = blockId(state);
					if (!blockIds.contains(blockId)) {
						continue;
					}
					discovered.add(pos.immutable());
					if (excludedTargets.contains(pos)) {
						continue;
					}
					Optional<UnderwaterHarvestPolicy.SourceEnvironment> environment =
						MinecraftUnderwaterSourceClassifier.classify(minecraft, pos, state);
					if (environment.isPresent()) {
						candidates.add(new UnderwaterHarvestPolicy.Target(
							new UnderwaterHarvestPolicy.Position(x, y, z),
							blockId,
							environment.orElseThrow()
						));
					}
				}
			}
		}
		UnderwaterHarvestPolicy.Position policyOrigin = new UnderwaterHarvestPolicy.Position(origin.getX(), origin.getY(), origin.getZ());
		List<HarvestTarget> selected = UnderwaterHarvestPolicy.selectBatch(candidates, policyOrigin).stream()
			.map(target -> new HarvestTarget(
				new BlockPos(target.position().x(), target.position().y(), target.position().z()),
				target.blockId(),
				target.environment()
			))
			.toList();
		return new SourceScan(selected, Set.copyOf(discovered));
	}

	private static Optional<ItemEntity> nearestMatchingDrop(
		Minecraft minecraft,
		LocalPlayer player,
		List<String> matchingItemIds,
		Set<UUID> excludedDropIds
	) {
		Set<String> ids = new HashSet<>(matchingItemIds);
		return minecraft.level.getEntitiesOfClass(
			ItemEntity.class,
			new AABB(player.blockPosition()).inflate(8.0D),
			entity -> ids.contains(itemId(entity.getItem())) && !excludedDropIds.contains(entity.getUUID())
		).stream().min(java.util.Comparator.comparingDouble(player::distanceToSqr));
	}

	private static int matchingInventoryCount(LocalPlayer player, List<String> matchingItemIds) {
		Set<String> ids = new HashSet<>(matchingItemIds);
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && ids.contains(itemId(stack))) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static boolean selectRequiredTool(Minecraft minecraft, LocalPlayer player, List<String> requiredToolItemIds) {
		if (requiredToolItemIds == null || requiredToolItemIds.isEmpty()) {
			return true;
		}
		Set<String> required = new HashSet<>(requiredToolItemIds);
		if (matchesRequiredTool(player.getInventory().getSelectedItem(), required)) {
			return true;
		}
		AbstractContainerMenu menu = player.containerMenu;
		int sourceSlot = findRequiredToolSlot(menu, required);
		if (sourceSlot < 0 || !isRequiredToolSourceSlot(sourceSlot)) {
			return false;
		}
		int selectedHotbarSlot = player.getInventory().getSelectedSlot();
		if (sourceSlot >= InventoryMenu.USE_ROW_SLOT_START && sourceSlot < InventoryMenu.USE_ROW_SLOT_END) {
			selectAndSyncHotbarSlot(minecraft, player, sourceSlot - InventoryMenu.USE_ROW_SLOT_START);
		}
		else {
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, selectedHotbarSlot, ClickType.SWAP, player);
			selectAndSyncHotbarSlot(minecraft, player, selectedHotbarSlot);
		}
		return matchesRequiredTool(player.getInventory().getSelectedItem(), required);
	}

	private static int findRequiredToolSlot(AbstractContainerMenu menu, Set<String> requiredItemIds) {
		if (!(menu instanceof InventoryMenu)) {
			return -1;
		}
		for (int slot = InventoryMenu.USE_ROW_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			if (matchesRequiredTool(menu.getSlot(slot).getItem(), requiredItemIds)) {
				return slot;
			}
		}
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_START; slot++) {
			if (matchesRequiredTool(menu.getSlot(slot).getItem(), requiredItemIds)) {
				return slot;
			}
		}
		return matchesRequiredTool(menu.getSlot(InventoryMenu.SHIELD_SLOT).getItem(), requiredItemIds)
			? InventoryMenu.SHIELD_SLOT
			: -1;
	}

	static boolean isRequiredToolSourceSlot(int slot) {
		return (slot >= InventoryMenu.INV_SLOT_START && slot < InventoryMenu.USE_ROW_SLOT_END)
			|| slot == InventoryMenu.SHIELD_SLOT;
	}

	private static boolean matchesRequiredTool(ItemStack stack, Set<String> requiredItemIds) {
		return stack != null
			&& !stack.isEmpty()
			&& requiredItemIds.contains(itemId(stack));
	}

	private static void selectAndSyncHotbarSlot(Minecraft minecraft, LocalPlayer player, int hotbarSlot) {
		if (player.getInventory().getSelectedSlot() == hotbarSlot) {
			return;
		}
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (minecraft.getConnection() != null) {
			minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
		}
	}

	private Optional<TaskTerminalEvent> tickCompletion(WorldTaskRequest request, Minecraft minecraft) {
		cancelNavigation();
		clearBreak(minecraft);
		movement.stop(minecraft);
		NavigationRelease.release(baritone);
		underwaterEscape.reset(minecraft);
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		GoalMineSpec spec = request.goal() == null ? null : request.goal().mineSpec();
		int inventoryCount = player == null || spec == null
			? 0
			: matchingInventoryCount(player, spec.matchingItemIds());
		if (spec == null || UnderwaterHarvestPolicy.terminalDecision(inventoryCount, spec.quantity(), 1)
			!= UnderwaterHarvestPolicy.TerminalDecision.COMPLETE) {
			completionPending = false;
			completionMessage = null;
			snapshot = snapshot(
				TaskExecutionState.RUNNING,
				request,
				"completion_revalidated itemCount=" + inventoryCount
					+ " targetCount=" + (spec == null ? 0 : spec.quantity())
			);
			return Optional.empty();
		}
		String message = completionMessage == null || completionMessage.isBlank()
			? "underwater_harvest_succeeded"
			: completionMessage;
		snapshot = snapshot(TaskExecutionState.COMPLETED, request, message);
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.COMPLETED, message, TaskTerminationCause.GOAL_REACHED));
	}

	private Optional<TaskTerminalEvent> fail(WorldTaskRequest request, TaskFailure failure) {
		releaseControls();
		snapshot = snapshot(TaskExecutionState.FAILED, request, failure.detail());
		if (terminalEventEmitted) {
			return Optional.empty();
		}
		terminalEventEmitted = true;
		return Optional.of(new TaskTerminalEvent(request.taskId(), null, TaskExecutionState.FAILED, failure.detail(), null, failure.code()));
	}

	private void clearBreak(Minecraft minecraft) {
		if (breakingTarget != null && minecraft != null && minecraft.gameMode != null) {
			minecraft.gameMode.stopDestroyBlock();
		}
		breakingTarget = null;
		breakStartedTick = -1L;
		clearGrounding();
	}

	private void clearApproachAssist() {
		assistedApproachTarget = null;
		obstacleAscentTicksRemaining = 0;
	}

	private void clearGrounding() {
		groundingTarget = null;
		groundingTicks = 0;
	}

	private void cancelNavigation() {
		if (navigationStarted && baritone != null && baritone.isLoaded()) {
			baritone.cancel();
		}
		navigationStarted = false;
	}

	private void releaseControls() {
		Minecraft minecraft = clientSupplier.get();
		cancelNavigation();
		clearBreak(minecraft);
		underwaterEscape.reset(minecraft);
		movement.stop(minecraft);
		clearApproachAssist();
		clearGrounding();
	}

	private void reset() {
		releaseControls();
		appliedTask = null;
		snapshot = TaskExecutionSnapshot.idle();
		terminalEventEmitted = false;
		run = null;
		unreachableDropIds.clear();
		inventoryBeforeBreak = 0;
		surfacing = false;
		completionPending = false;
		completionMessage = null;
		harvestedBlocks = 0;
	}

	private static boolean sameTask(WorldTaskRequest left, WorldTaskRequest right) {
		return left != null && right != null
			&& left.type() == WorldTaskType.UNDERWATER_HARVEST
			&& right.type() == WorldTaskType.UNDERWATER_HARVEST
			&& Objects.equals(left.taskId(), right.taskId());
	}

	private static TaskExecutionSnapshot snapshot(TaskExecutionState state, WorldTaskRequest request, String event) {
		return new TaskExecutionSnapshot(state, request.taskId(), request.goal(), "UnderwaterHarvest", event, null, null);
	}

	private static GoalPosition goalPosition(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}

	BlockPos searchOriginSnapshot() {
		return run == null ? null : run.searchOrigin();
	}

	private static BlockPos searchOrigin(WorldTaskRequest request) {
		UnderwaterHarvestStepArgs args = request == null
			? null
			: ((WorldTaskRequest.UnderwaterHarvest) request.task()).args();
		GoalPosition origin = args == null ? null : args.searchOrigin();
		return origin == null ? null : new BlockPos(origin.x(), origin.y(), origin.z());
	}

	private static String blockId(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
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

	/**
	 * Mutable per-task progress isolated from the Minecraft effect shell. The
	 * handoff origin is captured once in the constructor and cannot drift while
	 * surfacing, collecting drops, or re-scanning changed fluid state.
	 */
	static final class HarvestRun {
		private final BlockPos searchOrigin;
		private List<HarvestTarget> batch = List.of();
		private int batchCursor;
		private int pickupTicksRemaining = -1;
		private final Set<BlockPos> unreachableTargets = new HashSet<>();
		private BlockPos approachTarget;
		private UnderwaterHarvestPolicy.ApproachProgress approachProgress;

		HarvestRun(BlockPos searchOrigin) {
			this.searchOrigin = Objects.requireNonNull(searchOrigin, "searchOrigin").immutable();
		}

		BlockPos searchOrigin() {
			return searchOrigin;
		}

		void installBatch(SourceScan scan) {
			batch = scan == null ? List.of() : List.copyOf(scan.batch());
			batchCursor = 0;
			clearApproach();
		}

		Optional<HarvestTarget> currentTarget() {
			return batchCursor >= 0 && batchCursor < batch.size()
				? Optional.of(batch.get(batchCursor))
				: Optional.empty();
		}

		boolean needsSourceScan() {
			return currentTarget().isEmpty();
		}

		void invalidateBatch() {
			batch = List.of();
			batchCursor = 0;
			clearApproach();
		}

		boolean reconcileEnvironment(UnderwaterHarvestPolicy.SourceEnvironment currentEnvironment) {
			HarvestTarget target = currentTarget().orElse(null);
			if (target != null && target.environment() == currentEnvironment) {
				return true;
			}
			invalidateBatch();
			return false;
		}

		boolean prepareApproach(BlockPos target, double distanceBlocks) {
			BlockPos immutableTarget = Objects.requireNonNull(target, "target").immutable();
			if (immutableTarget.equals(approachTarget)) {
				return false;
			}
			clearApproach();
			approachTarget = immutableTarget;
			approachProgress = UnderwaterHarvestPolicy.beginApproach(distanceBlocks);
			return true;
		}

		UnderwaterHarvestPolicy.ApproachProgress approachProgress() {
			return approachProgress;
		}

		UnderwaterHarvestPolicy.ApproachUpdate observeApproach(double distanceBlocks) {
			UnderwaterHarvestPolicy.ApproachUpdate update = UnderwaterHarvestPolicy.observeApproach(
				approachProgress,
				distanceBlocks
			);
			approachProgress = update.progress();
			return update;
		}

		void clearApproach() {
			approachTarget = null;
			approachProgress = null;
		}

		void excludeCurrentTarget(BlockPos target) {
			BlockPos immutableTarget = Objects.requireNonNull(target, "target").immutable();
			unreachableTargets.add(immutableTarget);
			if (currentTarget().map(HarvestTarget::pos).filter(immutableTarget::equals).isPresent()) {
				batchCursor++;
				clearApproach();
			}
			else {
				invalidateBatch();
			}
		}

		Set<BlockPos> unreachableTargets() {
			return Set.copyOf(unreachableTargets);
		}

		void startPickupWindow(int ticks) {
			pickupTicksRemaining = Math.max(-1, ticks);
		}

		void tickPickup(boolean recoveringAir) {
			pickupTicksRemaining = UnderwaterHarvestPolicy.pickupTicksAfterTick(
				pickupTicksRemaining,
				recoveringAir
			);
		}

		void clearPickupWindow() {
			pickupTicksRemaining = -1;
		}

		int pickupTicksRemaining() {
			return pickupTicksRemaining;
		}

		RunSnapshot snapshot() {
			HarvestTarget current = currentTarget().orElse(null);
			return new RunSnapshot(
				searchOrigin,
				pickupTicksRemaining,
				unreachableTargets,
				needsSourceScan(),
				current == null ? null : current.pos(),
				current == null ? null : current.environment(),
				approachProgress
			);
		}
	}

	record RunSnapshot(
		BlockPos searchOrigin,
		int pickupTicksRemaining,
		Set<BlockPos> unreachableTargets,
		boolean needsSourceScan,
		BlockPos currentTarget,
		UnderwaterHarvestPolicy.SourceEnvironment currentEnvironment,
		UnderwaterHarvestPolicy.ApproachProgress approachProgress
	) {
		RunSnapshot {
			searchOrigin = searchOrigin == null ? null : searchOrigin.immutable();
			unreachableTargets = Set.copyOf(unreachableTargets);
			currentTarget = currentTarget == null ? null : currentTarget.immutable();
		}
	}

	static record HarvestTarget(
		BlockPos pos,
		String blockId,
		UnderwaterHarvestPolicy.SourceEnvironment environment
	) {
		HarvestTarget {
			pos = Objects.requireNonNull(pos, "pos").immutable();
			Objects.requireNonNull(blockId, "blockId");
			Objects.requireNonNull(environment, "environment");
		}
	}

	static record SourceScan(List<HarvestTarget> batch, Set<BlockPos> discoveredTargets) {
		SourceScan {
			batch = List.copyOf(batch);
			discoveredTargets = Set.copyOf(discoveredTargets);
		}
	}
}
