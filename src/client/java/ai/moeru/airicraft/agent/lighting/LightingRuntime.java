package ai.moeru.airicraft.agent.lighting;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.agent.tasks.WorldTaskType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ClipContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class LightingRuntime {
	private final Actuator actuator = new Actuator("lighting", Priority.BACKGROUND);
	private static final long ATTEMPT_INTERVAL_TICKS = 10L;
	private static final long CONFIRMATION_TIMEOUT_TICKS = 20L;
	private static final double MAX_REACH_SQUARED = 4.5D * 4.5D;
	private static final long STATIONARY_TICKS = 100L;

	private LightingPolicy policy = LightingPolicy.defaults();
	private PendingPlacement pendingPlacement;
	private long nextAttemptTick;
	private Vec3 stationaryPosition;
	private long stationarySinceTick;
	private long lastObservedTick;

	public LightingPolicy configure(
		boolean enabled,
		LightingPolicy.Mode mode,
		int maxLightLevel,
		boolean requireUnderground,
		int minSpacingBlocks
	) {
		policy = new LightingPolicy(
			enabled,
			mode,
			maxLightLevel,
			requireUnderground,
			minSpacingBlocks,
			policy.revision() + 1L
		);
		pendingPlacement = null;
		return policy;
	}

	public Optional<PlacementEvent> tick(Minecraft minecraft, WorldTaskType activity, boolean actuationAllowed, long tick) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null || minecraft.gameMode == null || !actuationAllowed) {
			pendingPlacement = null;
			stationaryPosition = null;
			return Optional.empty();
		}
		LocalPlayer player = minecraft.player;
		boolean stationaryLongEnough = observeStationary(player.position(), player.onGround(), tick);
		Optional<PlacementEvent> confirmation = confirmPending(minecraft, tick);
		if (confirmation.isPresent() || pendingPlacement != null || tick < nextAttemptTick) {
			return confirmation;
		}
		nextAttemptTick = tick + ATTEMPT_INTERVAL_TICKS;

		if (!LightingPolicyEvaluator.supportsActivity(activity, stationaryLongEnough) || !policy.enabled()
			|| player.isUsingItem() || minecraft.gameMode.isDestroying()
			|| player.containerMenu != player.inventoryMenu
			|| !player.containerMenu.getCarried().isEmpty()) return Optional.empty();
		BlockPos origin = player.blockPosition();
		// Do not interpret an unloaded edge of the sampling area as darkness.
		for (BlockPos sample : BlockPos.betweenClosed(origin.offset(-2, 0, -2), origin.offset(2, 0, 2))) {
			if (!minecraft.level.hasChunkAt(sample)) return Optional.empty();
		}
		double combinedLight = averageFootLevelLight(origin, minecraft.level::isEmptyBlock, pos -> minecraft.level.getMaxLocalRawBrightness(pos));
		double blockLight = averageFootLevelLight(origin, minecraft.level::isEmptyBlock, pos -> minecraft.level.getBrightness(LightLayer.BLOCK, pos));
		boolean nearbyTorch = hasNearbyTorch(minecraft, origin, policy.minSpacingBlocks());
		boolean placementRequired = LightingPolicyEvaluator.shouldPlace(
			policy,
			true,
			torchCount(player) > 0,
			hasFootLevelSkyAccess(origin, pos -> minecraft.level.canSeeSky(pos)),
			combinedLight,
			blockLight,
			nearbyTorch
		);
		if (!placementRequired) {
			return Optional.empty();
		}
		for (PlacementCandidate candidate : placementCandidates(player.blockPosition(), player.getDirection())) {
			if (tryPlace(minecraft, player, candidate, activity, tick,
				policy.mode() == LightingPolicy.Mode.SPAWN_PROOF ? blockLight : combinedLight)) {
				break;
			}
		}
		return Optional.empty();
	}

	boolean observeStationary(Vec3 position, boolean grounded, long tick) {
		if (!grounded) {
			stationaryPosition = null;
			lastObservedTick = tick;
			return false;
		}
		if (stationaryPosition == null || tick != lastObservedTick + 1
			|| stationaryPosition.distanceToSqr(position) > 0.0001D) {
			stationaryPosition = position;
			stationarySinceTick = tick;
		}
		lastObservedTick = tick;
		return tick - stationarySinceTick >= STATIONARY_TICKS;
	}

	static double averageFootLevelLight(BlockPos origin, java.util.function.Predicate<BlockPos> isAirAt,
		java.util.function.ToIntFunction<BlockPos> lightAt) {
		int total = 0;
		int samples = 0;
		for (BlockPos sample : BlockPos.betweenClosed(origin.offset(-2, 0, -2), origin.offset(2, 0, 2))) {
			if (!isAirAt.test(sample)) continue;
			total += lightAt.applyAsInt(sample);
			samples++;
		}
		// No air means no evidence of darkness that should trigger placement.
		return samples == 0 ? Double.POSITIVE_INFINITY : total / (double) samples;
	}

	static boolean hasFootLevelSkyAccess(BlockPos origin, java.util.function.Predicate<BlockPos> skyVisibleAt) {
		for (BlockPos sample : BlockPos.betweenClosed(origin.offset(-2, 0, -2), origin.offset(2, 0, 2))) {
			if (skyVisibleAt.test(sample)) return true;
		}
		return false;
	}

	public void reset() {
		policy = LightingPolicy.defaults();
		pendingPlacement = null;
		nextAttemptTick = 0L;
		stationaryPosition = null;
	}

	public LightingPolicy policy() {
		return policy;
	}

	private Optional<PlacementEvent> confirmPending(Minecraft minecraft, long tick) {
		if (pendingPlacement == null) {
			return Optional.empty();
		}
		BlockState state = minecraft.level.getBlockState(pendingPlacement.target());
		if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
			PendingPlacement confirmed = pendingPlacement;
			pendingPlacement = null;
			return Optional.of(new PlacementEvent(Map.of(
				"policyRevision", confirmed.policyRevision(),
				"mode", confirmed.mode().wireName(),
				"x", confirmed.target().getX(),
				"y", confirmed.target().getY(),
				"z", confirmed.target().getZ(),
				"lightLevelBefore", confirmed.lightLevelBefore(),
				"torchCount", torchCount(minecraft.player),
				"side", confirmed.side(),
				"facing", confirmed.face().getSerializedName(),
				"activity", confirmed.activity() == null ? "idle" : confirmed.activity().name().toLowerCase(java.util.Locale.ROOT)
			)));
		}
		if (tick - pendingPlacement.startedTick() > CONFIRMATION_TIMEOUT_TICKS) {
			pendingPlacement = null;
		}
		return Optional.empty();
	}

	private boolean tryPlace(Minecraft minecraft, LocalPlayer player, PlacementCandidate candidate, WorldTaskType activity, long tick, double lightBefore) {
		BlockPos target = candidate.target();
		if (!minecraft.level.hasChunkAt(target)) {
			return false;
		}
		BlockState targetState = minecraft.level.getBlockState(target);
		BlockState torchState = candidate.surface() == PlacementSurface.WALL
			? Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, candidate.face())
			: Blocks.TORCH.defaultBlockState();
		if (!(targetState.isAir() || targetState.canBeReplaced()) || !minecraft.level.getFluidState(target).isEmpty()
			|| !torchState.canSurvive(minecraft.level, target)) {
			return false;
		}
		Vec3 hit = Vec3.atCenterOf(candidate.support()).add(
			candidate.face().getStepX() * 0.5D,
			candidate.face().getStepY() * 0.5D,
			candidate.face().getStepZ() * 0.5D
		);
		if (player.getEyePosition().distanceToSqr(hit) > MAX_REACH_SQUARED) {
			return false;
		}
		// Ray ends just inside the support face, avoiding boundary misses and through-wall clicks.
		Vec3 inside = hit.subtract(Vec3.atLowerCornerOf(candidate.face().getUnitVec3i()).scale(0.001));
		var visible = minecraft.level.clip(new ClipContext(player.getEyePosition(), inside,
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(candidate.support())) return false;
		InteractionResult result = placeWithTorch(minecraft, player, new BlockHitResult(hit, candidate.face(), candidate.support(), false));
		if (!result.consumesAction()) {
			return false;
		}
		pendingPlacement = new PendingPlacement(
			target.immutable(),
			tick,
			policy.revision(),
			policy.mode(),
			lightBefore,
			candidate.side(),
			candidate.face(),
			activity
		);
		return true;
	}

	static List<PlacementCandidate> placementCandidates(BlockPos origin, Direction forward) {
		Direction left = forward.getCounterClockWise();
		Direction right = forward.getClockWise();
		List<BlockPos> anchors = List.of(
			origin.relative(forward.getOpposite()).above(),
			origin.above(),
			origin.relative(forward).above()
		);
		java.util.ArrayList<PlacementCandidate> candidates = new java.util.ArrayList<>(10);
		addWallCandidates(candidates, anchors, left, "left");
		addWallCandidates(candidates, anchors, right, "right");
		addFloorCandidates(candidates, origin, forward);
		return List.copyOf(candidates);
	}

	private static void addWallCandidates(
		List<PlacementCandidate> candidates,
		List<BlockPos> targets,
		Direction wallDirection,
		String side
	) {
		Direction clickedFace = wallDirection.getOpposite();
		for (BlockPos target : targets) {
			candidates.add(new PlacementCandidate(target, target.relative(wallDirection), clickedFace, side, PlacementSurface.WALL));
		}
	}

	private static void addFloorCandidates(List<PlacementCandidate> candidates, BlockPos origin, Direction forward) {
		for (BlockPos target : List.of(
			origin.relative(forward.getOpposite()),
			origin.relative(forward.getCounterClockWise()),
			origin.relative(forward.getClockWise()),
			origin.relative(forward)
		)) {
			candidates.add(new PlacementCandidate(target, target.below(), Direction.UP, "floor", PlacementSurface.FLOOR));
		}
	}

	private static boolean hasNearbyTorch(Minecraft minecraft, BlockPos origin, int radius) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = -radius; x <= radius; x++) {
			for (int y = -2; y <= 2; y++) {
				for (int z = -radius; z <= radius; z++) {
					cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					if (!minecraft.level.hasChunkAt(cursor)) {
						continue;
					}
					BlockState state = minecraft.level.getBlockState(cursor);
					if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static int torchCount(LocalPlayer player) {
		return player.getInventory().countItem(Items.TORCH);
	}

	private InteractionResult placeWithTorch(Minecraft minecraft, LocalPlayer player, BlockHitResult hit) {
		if (player.getOffhandItem().is(Items.TORCH)) {
			InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.OFF_HAND, hit);
			if (result.consumesAction()) player.swing(InteractionHand.OFF_HAND);
			return result;
		}
		AbstractContainerMenu menu = player.containerMenu;
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			if (!menu.getSlot(slot).getItem().is(Items.TORCH)) continue;
			int previousSlot = player.getInventory().getSelectedSlot();
			// A stronger actor holds the hotbar this tick: skip before any inventory swap can be stranded.
			if (!actuator.selectHotbar(minecraft, previousSlot)) return InteractionResult.PASS;
			boolean swap = slot < InventoryMenu.USE_ROW_SLOT_START;
			int torchSlot = swap ? previousSlot : slot - InventoryMenu.USE_ROW_SLOT_START;
			if (swap) minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, torchSlot, ClickType.SWAP, player);
			actuator.selectHotbar(minecraft, torchSlot);
			try {
				InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND, hit);
				if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
				return result;
			}
			finally {
				if (swap) minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, torchSlot, ClickType.SWAP, player);
				actuator.selectHotbarAndSync(minecraft, previousSlot);
			}
		}
		return InteractionResult.PASS;
	}

	public record PlacementEvent(Map<String, Object> payload) {
	}

	record PlacementCandidate(BlockPos target, BlockPos support, Direction face, String side, PlacementSurface surface) {
	}

	enum PlacementSurface {
		WALL,
		FLOOR
	}

	private record PendingPlacement(
		BlockPos target,
		long startedTick,
		long policyRevision,
		LightingPolicy.Mode mode,
		double lightLevelBefore,
		String side,
		Direction face,
		WorldTaskType activity
	) {
	}
}
