package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import baritone.api.BaritoneAPI;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import ai.moeru.airicraft.agent.spatial.SurfaceTerrain;
import ai.moeru.airicraft.agent.spatial.VisibleSurfaceSampler;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static ai.moeru.airicraft.agent.tasks.TargetAcquisitionTaskExecutor.*;

/** Samples loaded client facts and performs exact interactions on the client tick. */
final class MinecraftAcquisitionEnvironment implements Environment {
	private final CameraController cameraController;
	MinecraftAcquisitionEnvironment(CameraController cameraController) { this.cameraController = cameraController; }
	private BlockPos breaking;
	private MinecraftClient client() { return MinecraftClient.getInstance(); }
	@Override public GoalPosition position() {
		return position(BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().playerFeet());
	}
	@Override public int inventoryCount(GoalMineSpec spec) {
		int count = 0;
		var inventory = client().player.getInventory();
		for (int i = 0; i < inventory.size(); i++) {
			var stack = inventory.getStack(i);
			if (spec.matchingItemIds().contains(Registries.ITEM.getId(stack.getItem()).toString())) count += stack.getCount();
		}
		return count;
	}
	@Override public boolean requiredToolAvailable(GoalMineSpec spec) {
		if (spec.requiredToolItemIds().isEmpty()) return true;
		var inventory = client().player.getInventory();
		for (int i = 0; i < inventory.size(); i++) {
			var stack = inventory.getStack(i);
			if (!stack.isEmpty() && spec.requiredToolItemIds().contains(Registries.ITEM.getId(stack.getItem()).toString())) return true;
		}
		return false;
	}
	@Override public boolean inScope(GoalPosition position, AcquisitionConstraints constraints, boolean standing) {
		BlockPos pos = block(position);
		if (!constraints.contains(position) || !client().world.isChunkLoaded(pos)) return false;
		if (!constraints.surfaceOnly()) return true;
		int groundY = surfaceGroundY(pos);
		boolean waterSurface = client().world.getFluidState(new BlockPos(pos.getX(), groundY, pos.getZ())).isIn(FluidTags.WATER);
		return SurfaceTerrain.isSurfacePosition(pos.getY(), groundY, standing, waterSurface);
	}

	private boolean travelEligible(GoalPosition pos) {
		return client().world.isChunkLoaded(block(pos)) && ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.permitsMovement(
			client().world, pos.x(),pos.y(),pos.z(),pos.x(),pos.y(),pos.z(),false);
	}

	private int surfaceGroundY(BlockPos column) {
		return SurfaceTerrain.groundY(client().world, column);
	}

	@Override public Set<GoalPosition> observeSources(GoalMineSpec spec, AcquisitionConstraints constraints) {
		var client = client();
		return VisibleSurfaceSampler.sample(client.player.getEyePos(), 24, (eye, end) -> client.world.raycast(
			new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.ANY, client.player)))
			.stream().map(hit -> hit.getBlockPos().toImmutable())
			.filter(pos -> eligibleSource(pos, spec, constraints)).map(MinecraftAcquisitionEnvironment::position)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Override public List<Candidate> opportunityCandidates(GoalMineSpec spec, AcquisitionConstraints constraints,
		boolean goalMet, Set<String> rejected) {
		var client = client();
		if (client.world == null || client.player == null || client.interactionManager == null
			|| !client.player.isOnGround() || client.player.isUsingItem()
			|| client.player.currentScreenHandler != client.player.playerScreenHandler
			|| !client.player.currentScreenHandler.getCursorStack().isEmpty()
			|| client.player.getInventory().getEmptySlot() < 0) return List.of();
		BlockPos origin = client.player.getBlockPos();
		Vec3d eye = client.player.getEyePos();
		List<Candidate> candidates = new ArrayList<>();
		for (BlockPos cursor : BlockPos.iterate(origin.add(-3, -2, -3), origin.add(3, 2, 3))) {
			if (!client.world.isChunkLoaded(cursor)) continue;
			BlockState state = client.world.getBlockState(cursor);
			String blockId = id(state);
			if (!isOreId(blockId) || goalMet != spec.blockIds().contains(blockId)
				|| !inScope(position(cursor), constraints, false)
				|| WorldPlacePreservation.contains(client.world, cursor)
				|| !HarvestableBlocks.ready(state) || state.getHardness(client.world, cursor) < 0
				|| !hasHarvestTool(state)) continue;
			BlockHitResult hit = interactionPath(eye, cursor);
			if (hit == null || !hit.getBlockPos().equals(cursor)) continue;
			Candidate candidate = new Candidate(Kind.BLOCK, blockId, position(cursor), position(origin));
			if (!rejected.contains(candidate.key())) candidates.add(candidate);
		}
		candidates.sort(Comparator.comparingDouble((Candidate value) ->
			block(value.position()).getSquaredDistance(client.player.getPos())).thenComparing(Candidate::key));
		return candidates;
	}

	private boolean hasHarvestTool(BlockState state) {
		if (!state.isToolRequired()) return true;
		var inventory = client().player.getInventory();
		for (int slot = 0; slot < inventory.size(); slot++) {
			var stack = inventory.getStack(slot);
			if (!stack.isEmpty() && stack.isSuitableFor(state)
				&& (!stack.isDamageable() || stack.getMaxDamage() - stack.getDamage() > 1)) return true;
		}
		return false;
	}

	private boolean eligibleSource(BlockPos pos, GoalMineSpec spec, AcquisitionConstraints constraints) {
		var world = client().world;
		if (!world.isChunkLoaded(pos) || !inScope(position(pos), constraints, false)) return false;
		BlockState state = world.getBlockState(pos);
		return spec.blockIds().contains(id(state)) && HarvestableBlocks.ready(state) && !WorldPlacePreservation.contains(world, pos);
	}

	@Override public boolean dropsAvailable(GoalMineSpec spec, AcquisitionConstraints constraints) {
		// Settling polls only item entities, not the full block/work-position search.
		return !client().world.getEntitiesByClass(ItemEntity.class,
			new Box(block(constraints.center())).expand(constraints.radius(), constraints.verticalRadius(), constraints.radius()),
			item -> item.isAlive()
				&& spec.matchingItemIds().contains(Registries.ITEM.getId(item.getStack().getItem()).toString())
				&& inScope(position(item.getBlockPos()), constraints, true)).isEmpty();
	}

	@Override public List<Candidate> candidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected,
		Set<GoalPosition> observedSources) {
		var world = client().world;
		BlockPos center = block(constraints.center());
		List<Candidate> result = new ArrayList<>(dropCandidates(spec, constraints, rejected));
		List<BlockPos> blocks = new ArrayList<>();
		Iterable<BlockPos> sources = constraints.visibleOnly()
			? observedSources.stream().map(MinecraftAcquisitionEnvironment::block).toList()
			: BlockPos.iterate(center.add(-constraints.radius(), -constraints.verticalRadius(), -constraints.radius()),
				center.add(constraints.radius(), constraints.verticalRadius(), constraints.radius()));
		for (BlockPos cursor : sources) {
			if (eligibleSource(cursor, spec, constraints)) blocks.add(cursor.toImmutable());
		}
		blocks.sort(Comparator.comparingDouble((BlockPos pos) -> pos.getSquaredDistance(client().player.getPos()))
			.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
		for (BlockPos pos : blocks) {
			String blockId = id(world.getBlockState(pos));
			for (GoalPosition work : workPositions(pos, constraints)) {
				Candidate candidate = new Candidate(Kind.BLOCK, blockId, position(pos), work);
				if (!rejected.contains(candidate.key())) result.add(candidate);
			}
			if (result.size() >= 32) break;
		}
		result.sort(Comparator.comparing(Candidate::kind)
			.thenComparingDouble(value -> distanceSquared(position(), value.workPosition()))
			.thenComparing(Candidate::key));
		return result;
	}

	@Override public List<Candidate> dropCandidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected) {
		BlockPos center = block(constraints.center());
		List<Candidate> result = new ArrayList<>();
		var world = client().world;
		for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class,
			new Box(center).expand(constraints.radius(), constraints.verticalRadius(), constraints.radius()), ItemEntity::isAlive)) {
			if (!spec.matchingItemIds().contains(Registries.ITEM.getId(item.getStack().getItem()).toString())) continue;
			GoalPosition pos = position(item.getBlockPos());
			if (!inScope(pos, constraints, true)) continue;
			// A drop's cell may be water even when a dry adjacent landing collects it.
			List<BlockPos> pickupSites = new ArrayList<>(AcquisitionPickupSites.find(block(pos), this::standable));
			if (!pickupSites.contains(block(pos))) pickupSites.add(block(pos));
			for (BlockPos site : pickupSites) {
				GoalPosition work = position(site);
				if (!travelEligible(work)) continue;
				Candidate drop = new Candidate(Kind.DROP, item.getUuidAsString(), pos, work);
				if (!rejected.contains(drop.key())) result.add(drop);
			}
		}
		result.sort(Comparator.comparing(Candidate::kind)
			.thenComparingDouble(value -> distanceSquared(position(), value.workPosition()))
			.thenComparing(Candidate::key));
		return result;
	}

	private List<GoalPosition> workPositions(BlockPos source, AcquisitionConstraints constraints) {
		List<GoalPosition> sites = new ArrayList<>();
		GoalPosition open = workPosition(source, constraints);
		if (open != null) sites.add(open);
		// Navigation can carve its destination's feet/head space. Requiring air here
		// would discard fully enclosed ore before A* ever has a chance to approach it.
		for (BlockPos pos : AcquisitionExcavationSites.find(source,
			candidate -> travelEligible(position(candidate)) && clearable(candidate),
			this::safeSupport)) {
			GoalPosition site = position(pos);
			if (!sites.contains(site)) sites.add(site);
		}
		return sites;
	}

	private boolean clearable(BlockPos pos) {
		var world = client().world;
		BlockState state = world.getBlockState(pos);
		return (state.getCollisionShape(world, pos).isEmpty() || !WorldPlacePreservation.contains(world, pos))
			&& state.getFluidState().isEmpty() && !state.hasBlockEntity()
			&& state.getHardness(world, pos) >= 0 && !hazardous(state);
	}

	private boolean safeSupport(BlockPos pos) {
		var world = client().world;
		if (!world.isChunkLoaded(pos)) return false;
		BlockState state = world.getBlockState(pos);
		return state.getFluidState().isEmpty() && !hazardous(state)
			&& state.isSideSolidFullSquare(world, pos, net.minecraft.util.math.Direction.UP);
	}

	private static boolean hazardous(BlockState state) {
		return Set.of("minecraft:cactus", "minecraft:magma_block", "minecraft:campfire", "minecraft:soul_campfire",
			"minecraft:fire", "minecraft:soul_fire", "minecraft:lava", "minecraft:powder_snow").contains(id(state));
	}

	private GoalPosition workPosition(BlockPos target, AcquisitionConstraints constraints) {
		if (travelEligible(position()) && interactionPath(client().player.getEyePos(), target) != null) return position();
		List<BlockPos> sites = new ArrayList<>();
		for (BlockPos cursor : workPositions(target)) {
			if (travelEligible(position(cursor)) && standable(cursor)
				&& interactionPath(Vec3d.ofBottomCenter(cursor).add(0, 1.62, 0), target) != null) sites.add(cursor.toImmutable());
		}
		return sites.stream().min(Comparator.comparingDouble(pos -> pos.getSquaredDistance(client().player.getPos())))
			.map(MinecraftAcquisitionEnvironment::position).orElse(null);
	}

	static Iterable<BlockPos> workPositions(BlockPos target) {
		// Feet can be five blocks below a source while its center is within eye reach.
		return BlockPos.iterate(target.add(-3, -5, -3), target.add(3, 3, 3));
	}

	private boolean standable(BlockPos pos) {
		var world = client().world;
		return world.getBlockState(pos).getCollisionShape(world, pos).isEmpty()
			&& world.getBlockState(pos.up()).getCollisionShape(world, pos.up()).isEmpty()
			&& world.getBlockState(pos).getFluidState().isEmpty()
			&& safeSupport(pos.down());
	}

	/** Leaves may be cleared explicitly; other occluders are not acquisition targets. */
	private BlockHitResult interactionPath(Vec3d eye, BlockPos target) {
		if (eye.squaredDistanceTo(Vec3d.ofCenter(target)) > 20.25) return null;
		BlockHitResult hit = client().world.raycast(new RaycastContext(eye, Vec3d.ofCenter(target),
			RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, client().player));
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		return hit.getBlockPos().equals(target) || client().world.getBlockState(hit.getBlockPos()).isIn(BlockTags.LEAVES) ? hit : null;
	}

	@Override public boolean targetPresent(Candidate target) {
		if (target.kind() == Kind.BLOCK) return client().world.isChunkLoaded(block(target.position()))
			&& !WorldPlacePreservation.contains(client().world, block(target.position()))
			&& id(client().world.getBlockState(block(target.position()))).equals(target.id())
			&& HarvestableBlocks.ready(client().world.getBlockState(block(target.position())));
		return client().world.getEntitiesByClass(ItemEntity.class, new Box(block(target.position())).expand(3),
			item -> item.isAlive() && item.getUuidAsString().equals(target.id())).size() > 0;
	}
	@Override public boolean canCollectDrop(Candidate target) {
		var inventory = client().player.getInventory();
		if (inventory.getEmptySlot() >= 0) return true;
		var drops = client().world.getEntitiesByClass(ItemEntity.class, new Box(block(target.position())).expand(3),
			item -> item.isAlive() && item.getUuidAsString().equals(target.id()));
		// Disappearance is handled by targetPresent, not evidence of a full inventory.
		return drops.isEmpty() || inventory.getOccupiedSlotWithRoomForStack(drops.getFirst().getStack()) >= 0;
	}
	@Override public boolean canInteract(Candidate target) {
		if (!client().player.isOnGround()) return false;
		if (target.kind() == Kind.DROP) return client().world.getEntitiesByClass(ItemEntity.class,
			new Box(block(target.position())).expand(3), item -> item.isAlive() && item.getUuidAsString().equals(target.id())
				&& client().player.squaredDistanceTo(item) <= 1).size() > 0;
		return interactionPath(client().player.getEyePos(), block(target.position())) != null;
	}
	@Override public BreakResult breakTarget(Candidate target, GoalMineSpec spec) {
		var client = client();
		if (!targetPresent(target)) return BreakStatus.BROKEN;
		if (client.player.currentScreenHandler != client.player.playerScreenHandler
			|| !client.player.currentScreenHandler.getCursorStack().isEmpty()) return BreakStatus.FAILED;
		BlockHitResult hit = interactionPath(client.player.getEyePos(), block(target.position()));
		if (hit == null || WorldPlacePreservation.contains(client.world, hit.getBlockPos())) return BreakStatus.FAILED;
		BlockPos pos = hit.getBlockPos();
		// Aim at the actual hit, which may be leaves being cleared in front of the resource.
		cameraController.lookAt(client, hit.getPos());
		var cursorHit = cameraController.blockHit(client, pos);
		if (cursorHit.isEmpty()) return BreakStatus.BREAKING;
		hit = cursorHit.get();
		if (!pos.equals(breaking)) {
			cancelBreaking();
			var result = MiningToolPreparation.ensureSelected(client, client.player,
				List.of(client.world.getBlockState(pos)), pos.equals(block(target.position())) ? spec.requiredToolItemIds() : List.of());
			if (!result.ok()) return new ToolFailure(result.message());
			if (!client.interactionManager.attackBlock(pos, hit.getSide())) return BreakStatus.FAILED;
			breaking = pos;
		}
		client.interactionManager.updateBlockBreakingProgress(pos, hit.getSide());
		client.player.swingHand(Hand.MAIN_HAND);
		return targetPresent(target) ? BreakStatus.BREAKING : BreakStatus.BROKEN;
	}
	@Override public void cancelBreaking() {
		if (breaking != null && client().interactionManager != null) client().interactionManager.cancelBlockBreaking();
		breaking = null;
	}
	private static String id(BlockState state) { return Registries.BLOCK.getId(state.getBlock()).toString(); }
	private static BlockPos block(GoalPosition p) { return new BlockPos(p.x(), p.y(), p.z()); }
	private static GoalPosition position(BlockPos p) { return new GoalPosition(p.getX(), p.getY(), p.getZ(), true); }
}
