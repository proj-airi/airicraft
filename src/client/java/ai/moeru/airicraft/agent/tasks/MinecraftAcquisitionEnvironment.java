package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.agent.goals.AcquisitionConstraints;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import ai.moeru.airicraft.agent.spatial.SurfaceTerrain;
import ai.moeru.airicraft.agent.spatial.VisibleSurfaceSampler;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static ai.moeru.airicraft.agent.tasks.TargetAcquisitionTaskExecutor.*;

/** Samples loaded client facts and performs exact interactions on the client tick. */
final class MinecraftAcquisitionEnvironment implements Environment {
	private final Actuator actuator = new Actuator("target_acquisition", Priority.FOREGROUND);
	private final CameraController cameraController;
	MinecraftAcquisitionEnvironment(CameraController cameraController) { this.cameraController = cameraController; }
	private BlockPos breaking;
	private Minecraft client() { return Minecraft.getInstance(); }
	@Override public GoalPosition position() {
		return position(ai.moeru.airicraft.agent.navigation.PlayerFeet.of(client().player));
	}
	@Override public int inventoryCount(GoalMineSpec spec) {
		int count = 0;
		var inventory = client().player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			var stack = inventory.getItem(i);
			if (spec.matchingItemIds().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) count += stack.getCount();
		}
		return count;
	}
	@Override public boolean requiredToolAvailable(GoalMineSpec spec) {
		if (spec.requiredToolItemIds().isEmpty()) return true;
		var inventory = client().player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			var stack = inventory.getItem(i);
			if (!stack.isEmpty() && spec.requiredToolItemIds().contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) return true;
		}
		return false;
	}
	@Override public boolean inScope(GoalPosition position, AcquisitionConstraints constraints, boolean standing) {
		BlockPos pos = block(position);
		if (!constraints.contains(position) || !client().level.hasChunkAt(pos)) return false;
		if (!constraints.surfaceOnly()) return true;
		int groundY = surfaceGroundY(pos);
		boolean waterSurface = client().level.getFluidState(new BlockPos(pos.getX(), groundY, pos.getZ())).is(FluidTags.WATER);
		return SurfaceTerrain.isSurfacePosition(pos.getY(), groundY, standing, waterSurface);
	}

	private boolean travelEligible(GoalPosition pos) {
		return client().level.hasChunkAt(block(pos)) && ai.moeru.airicraft.agent.spatial.WorldTravelPolicy.permitsMovement(
			client().level, pos.x(),pos.y(),pos.z(),pos.x(),pos.y(),pos.z(),false);
	}

	private int surfaceGroundY(BlockPos column) {
		return SurfaceTerrain.groundY(client().level, column);
	}

	@Override public Set<GoalPosition> observeSources(GoalMineSpec spec, AcquisitionConstraints constraints) {
		var minecraft = client();
		return VisibleSurfaceSampler.sample(minecraft.player.getEyePosition(), 24, (eye, end) -> minecraft.level.clip(
			new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, minecraft.player)))
			.stream().map(hit -> hit.getBlockPos().immutable())
			.filter(pos -> eligibleSource(pos, spec, constraints)).map(MinecraftAcquisitionEnvironment::position)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Override public List<Candidate> opportunityCandidates(GoalMineSpec spec, AcquisitionConstraints constraints,
		boolean goalMet, Set<String> rejected) {
		var minecraft = client();
		if (minecraft.level == null || minecraft.player == null || minecraft.gameMode == null
			|| !minecraft.player.onGround() || minecraft.player.isUsingItem()
			|| minecraft.player.containerMenu != minecraft.player.inventoryMenu
			|| !minecraft.player.containerMenu.getCarried().isEmpty()
			|| minecraft.player.getInventory().getFreeSlot() < 0) return List.of();
		BlockPos origin = minecraft.player.blockPosition();
		Vec3 eye = minecraft.player.getEyePosition();
		List<Candidate> candidates = new ArrayList<>();
		for (BlockPos cursor : BlockPos.betweenClosed(origin.offset(-3, -2, -3), origin.offset(3, 2, 3))) {
			if (!minecraft.level.hasChunkAt(cursor)) continue;
			BlockState state = minecraft.level.getBlockState(cursor);
			String blockId = id(state);
			if (!isOreId(blockId) || goalMet != spec.blockIds().contains(blockId)
				|| !inScope(position(cursor), constraints, false)
				|| WorldPlacePreservation.contains(minecraft.level, cursor)
				|| !HarvestableBlocks.ready(state) || state.getDestroySpeed(minecraft.level, cursor) < 0
				|| !hasHarvestTool(state)) continue;
			BlockHitResult hit = interactionPath(eye, cursor);
			if (hit == null || !hit.getBlockPos().equals(cursor)) continue;
			Candidate candidate = new Candidate(Kind.BLOCK, blockId, position(cursor), position(origin));
			if (!rejected.contains(candidate.key())) candidates.add(candidate);
		}
		candidates.sort(Comparator.comparingDouble((Candidate value) ->
			block(value.position()).distToCenterSqr(minecraft.player.position())).thenComparing(Candidate::key));
		return candidates;
	}

	private boolean hasHarvestTool(BlockState state) {
		if (!state.requiresCorrectToolForDrops()) return true;
		var inventory = client().player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			var stack = inventory.getItem(slot);
			if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)
				&& (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 1)) return true;
		}
		return false;
	}

	private boolean eligibleSource(BlockPos pos, GoalMineSpec spec, AcquisitionConstraints constraints) {
		var level = client().level;
		if (!level.hasChunkAt(pos) || !inScope(position(pos), constraints, false)) return false;
		BlockState state = level.getBlockState(pos);
		return spec.blockIds().contains(id(state)) && HarvestableBlocks.ready(state) && !WorldPlacePreservation.contains(level, pos);
	}

	@Override public boolean dropsAvailable(GoalMineSpec spec, AcquisitionConstraints constraints) {
		// Settling polls only item entities, not the full block/work-position search.
		return !client().level.getEntitiesOfClass(ItemEntity.class,
			new AABB(block(constraints.center())).inflate(constraints.radius(), constraints.verticalRadius(), constraints.radius()),
			item -> item.isAlive()
				&& spec.matchingItemIds().contains(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString())
				&& inScope(position(item.blockPosition()), constraints, true)).isEmpty();
	}

	@Override public List<Candidate> candidates(GoalMineSpec spec, AcquisitionConstraints constraints, Set<String> rejected,
		Set<GoalPosition> observedSources) {
		var level = client().level;
		BlockPos center = block(constraints.center());
		List<Candidate> result = new ArrayList<>(dropCandidates(spec, constraints, rejected));
		List<BlockPos> blocks = new ArrayList<>();
		Iterable<BlockPos> sources = constraints.visibleOnly()
			? observedSources.stream().map(MinecraftAcquisitionEnvironment::block).toList()
			: BlockPos.betweenClosed(center.offset(-constraints.radius(), -constraints.verticalRadius(), -constraints.radius()),
				center.offset(constraints.radius(), constraints.verticalRadius(), constraints.radius()));
		for (BlockPos cursor : sources) {
			if (eligibleSource(cursor, spec, constraints)) blocks.add(cursor.immutable());
		}
		blocks.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distToCenterSqr(client().player.position()))
			.thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
		for (BlockPos pos : blocks) {
			String blockId = id(level.getBlockState(pos));
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
		var level = client().level;
		for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
			new AABB(center).inflate(constraints.radius(), constraints.verticalRadius(), constraints.radius()), ItemEntity::isAlive)) {
			if (!spec.matchingItemIds().contains(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString())) continue;
			GoalPosition pos = position(item.blockPosition());
			if (!inScope(pos, constraints, true)) continue;
			// A drop's cell may be water even when a dry adjacent landing collects it.
			List<BlockPos> pickupSites = new ArrayList<>(AcquisitionPickupSites.find(block(pos), this::standable));
			if (!pickupSites.contains(block(pos))) pickupSites.add(block(pos));
			for (BlockPos site : pickupSites) {
				GoalPosition work = position(site);
				if (!travelEligible(work)) continue;
				Candidate drop = new Candidate(Kind.DROP, item.getStringUUID(), pos, work);
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
		var level = client().level;
		BlockState state = level.getBlockState(pos);
		return (state.getCollisionShape(level, pos).isEmpty() || !WorldPlacePreservation.contains(level, pos))
			&& state.getFluidState().isEmpty() && !state.hasBlockEntity()
			&& state.getDestroySpeed(level, pos) >= 0 && !hazardous(state);
	}

	private boolean safeSupport(BlockPos pos) {
		var level = client().level;
		if (!level.hasChunkAt(pos)) return false;
		BlockState state = level.getBlockState(pos);
		return state.getFluidState().isEmpty() && !hazardous(state)
			&& state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP);
	}

	private static boolean hazardous(BlockState state) {
		return Set.of("minecraft:cactus", "minecraft:magma_block", "minecraft:campfire", "minecraft:soul_campfire",
			"minecraft:fire", "minecraft:soul_fire", "minecraft:lava", "minecraft:powder_snow").contains(id(state));
	}

	private GoalPosition workPosition(BlockPos target, AcquisitionConstraints constraints) {
	if (travelEligible(position()) && interactionPath(client().player.getEyePosition(), target) != null) return position();
	// Dry footing mines fastest, then the sea floor (mining is five times slower submerged, five times
	// slower again when floating), so a swimming site is a fallback in that order.
	List<BlockPos> dry = new ArrayList<>(), grounded = new ArrayList<>(), floating = new ArrayList<>();
	for (BlockPos cursor : workPositions(target)) {
	if (!travelEligible(position(cursor))) continue;
	boolean isDry = standable(cursor);
	if (!isDry && !swimmable(cursor)) continue;
	if (interactionPath(Vec3.atBottomCenterOf(cursor).add(0, 1.62, 0), target) == null) continue;
	(isDry ? dry : safeSupport(cursor.below()) ? grounded : floating).add(cursor.immutable());
	}
	List<BlockPos> sites = !dry.isEmpty() ? dry : !grounded.isEmpty() ? grounded : floating;
	return sites.stream().min(Comparator.comparingDouble(pos -> pos.distToCenterSqr(client().player.position())))
	.map(MinecraftAcquisitionEnvironment::position).orElse(null);
	}

	/** Open water a swimmer can float in with room for the body: the feet cell is water, nothing solid at feet or head. */
	private boolean swimmable(BlockPos pos) {
	var level = client().level;
	if (!level.hasChunkAt(pos) || !level.hasChunkAt(pos.above())) return false;
	BlockState feet = level.getBlockState(pos), head = level.getBlockState(pos.above());
	return feet.getFluidState().is(FluidTags.WATER)
	&& feet.getCollisionShape(level, pos).isEmpty() && head.getCollisionShape(level, pos.above()).isEmpty()
	&& !hazardous(feet) && !hazardous(head) && !head.getFluidState().is(FluidTags.LAVA);
	}

	static Iterable<BlockPos> workPositions(BlockPos target) {
		// Feet can be five blocks below a source while its center is within eye reach.
		return BlockPos.betweenClosed(target.offset(-3, -5, -3), target.offset(3, 3, 3));
	}

	private boolean standable(BlockPos pos) {
		var level = client().level;
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
			&& level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
			&& level.getBlockState(pos).getFluidState().isEmpty()
			&& safeSupport(pos.below());
	}

	/** Leaves may be cleared explicitly; other occluders are not acquisition targets. */
	private BlockHitResult interactionPath(Vec3 eye, BlockPos target) {
		if (eye.distanceToSqr(Vec3.atCenterOf(target)) > 20.25) return null;
		BlockHitResult hit = client().level.clip(new ClipContext(eye, Vec3.atCenterOf(target),
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client().player));
		if (hit.getType() != HitResult.Type.BLOCK) return null;
		return hit.getBlockPos().equals(target) || client().level.getBlockState(hit.getBlockPos()).is(BlockTags.LEAVES) ? hit : null;
	}

	@Override public boolean targetPresent(Candidate target) {
		if (target.kind() == Kind.BLOCK) return client().level.hasChunkAt(block(target.position()))
			&& !WorldPlacePreservation.contains(client().level, block(target.position()))
			&& id(client().level.getBlockState(block(target.position()))).equals(target.id())
			&& HarvestableBlocks.ready(client().level.getBlockState(block(target.position())));
		return client().level.getEntitiesOfClass(ItemEntity.class, new AABB(block(target.position())).inflate(3),
			item -> item.isAlive() && item.getStringUUID().equals(target.id())).size() > 0;
	}
	@Override public boolean canCollectDrop(Candidate target) {
		var inventory = client().player.getInventory();
		if (inventory.getFreeSlot() >= 0) return true;
		var drops = client().level.getEntitiesOfClass(ItemEntity.class, new AABB(block(target.position())).inflate(3),
			item -> item.isAlive() && item.getStringUUID().equals(target.id()));
		// Disappearance is handled by targetPresent, not evidence of a full inventory.
		return drops.isEmpty() || inventory.getSlotWithRemainingSpace(drops.getFirst().getItem()) >= 0;
	}
	@Override public boolean canInteract(Candidate target) {
	// A swimmer is never on the ground; being in the water is as steady as the sea floor gets.
	if (!client().player.onGround() && !client().player.isInWater()) return false;
		if (target.kind() == Kind.DROP) return client().level.getEntitiesOfClass(ItemEntity.class,
			new AABB(block(target.position())).inflate(3), item -> item.isAlive() && item.getStringUUID().equals(target.id())
				&& client().player.distanceToSqr(item) <= 1).size() > 0;
		return interactionPath(client().player.getEyePosition(), block(target.position())) != null;
	}
	@Override public BreakResult breakTarget(Candidate target, GoalMineSpec spec) {
		var minecraft = client();
		if (!targetPresent(target)) return BreakStatus.BROKEN;
		if (minecraft.player.containerMenu != minecraft.player.inventoryMenu
			|| !minecraft.player.containerMenu.getCarried().isEmpty()) return BreakStatus.FAILED;
		BlockHitResult hit = interactionPath(minecraft.player.getEyePosition(), block(target.position()));
		if (hit == null || WorldPlacePreservation.contains(minecraft.level, hit.getBlockPos())) return BreakStatus.FAILED;
		BlockPos pos = hit.getBlockPos();
		// Aim at the actual hit, which may be leaves being cleared in front of the resource.
		cameraController.lookAt(minecraft, hit.getLocation());
		var cursorHit = cameraController.blockHit(minecraft, pos);
		if (cursorHit.isEmpty()) return BreakStatus.BREAKING;
		hit = cursorHit.get();
		if (!pos.equals(breaking)) {
			cancelBreaking();
			var result = MiningToolPreparation.ensureSelected(minecraft, actuator, minecraft.player,
				List.of(minecraft.level.getBlockState(pos)), pos.equals(block(target.position())) ? spec.requiredToolItemIds() : List.of());
			if (!result.ok()) return new ToolFailure(result.message());
			if (!actuator.startDestroy(minecraft, pos, hit.getDirection())) return BreakStatus.FAILED;
			breaking = pos;
		}
		actuator.continueDestroy(minecraft, pos, hit.getDirection());
		minecraft.player.swing(InteractionHand.MAIN_HAND);
		return targetPresent(target) ? BreakStatus.BREAKING : BreakStatus.BROKEN;
	}
	@Override public boolean submerged() {
	return client().player != null && client().player.isUnderWater();
	}
	@Override public boolean breakFitsBreath(Candidate target) {
	return breakFits(target, client().player.getAirSupply());
	}
	@Override public boolean breakFitsFullBreath(Candidate target) {
	return breakFits(target, client().player.getMaxAirSupply());
	}
	@Override public String breathDetail(Candidate target) {
	var player = client().player;
	return "expectedBreakTicks=" + expectedBreakTicks(target) + " air=" + player.getAirSupply() + "/" + player.getMaxAirSupply()
	+ " onGround=" + player.onGround() + " inWater=" + player.isInWater();
	}
	private boolean breakFits(Candidate target, int air) {
	int reflexFloor = ai.moeru.airicraft.agent.AgentConfig.ReflexConfig.defaults().lowAirTicks();
	return expectedBreakTicks(target) + BREAK_BREATH_MARGIN_TICKS <= air - reflexFloor;
	}
	/**
 * Ticks the break takes at the speed it will have, or 0 when unknown: with the best tool carried (the
 * executor picks it when it starts breaking, not before), the water penalty when swimming, and the
 * fivefold loss for not being on the ground only when there is no floor to settle on.
 */
private int expectedBreakTicks(Candidate target) {
var minecraft = client();
if (target.kind() != Kind.BLOCK || minecraft.level == null || minecraft.player == null) return 0;
BlockPos pos = block(target.position());
BlockState state = minecraft.level.getBlockState(pos);
float hardness = state.getDestroySpeed(minecraft.level, pos);
if (!(hardness > 0)) return 0;
var player = minecraft.player;
float speed = 1.0F;
boolean harvests = !state.requiresCorrectToolForDrops();
var inventory = player.getInventory();
for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
var stack = inventory.getItem(slot);
if (stack.isEmpty()) continue;
boolean toolHarvests = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
float toolSpeed = stack.getDestroySpeed(state);
if (toolHarvests && !harvests) {
// The first tool that can harvest at all beats any that cannot.
harvests = true;
speed = Math.max(1.0F, toolSpeed);
}
else if (toolHarvests == harvests && toolSpeed > speed) speed = toolSpeed;
}
if (player.isInWater()) speed *= (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SUBMERGED_MINING_SPEED);
if (!player.onGround() && !(player.isInWater() && floorBelow(player))) speed /= 5.0F;
float progress = speed / hardness / (harvests ? 30.0F : 100.0F);
return progress > 0 ? (int) Math.min(100_000, Math.ceil(1.0 / progress)) : 0;
}

/** Whether something solid lies within a few blocks under the player, so sinking ends on it. */
	private boolean floorBelow(net.minecraft.world.entity.player.Player player) {
	var level = client().level;
	BlockPos cursor = player.blockPosition();
	for (int depth = 0; depth < 4; depth++, cursor = cursor.below()) {
	BlockState state = level.getBlockState(cursor);
	if (state.isFaceSturdy(level, cursor, net.minecraft.core.Direction.UP) && state.getFluidState().isEmpty()) return true;
	}
	return false;
	}
	@Override public int breakTimeoutTicks(Candidate target) {
int expected = expectedBreakTicks(target);
if (expected <= 0) return DEFAULT_BREAK_TIMEOUT_TICKS;
return Math.max(DEFAULT_BREAK_TIMEOUT_TICKS, (int) Math.min(6_000, expected * 3L / 2 + 40));
}
	@Override public void cancelBreaking() {
		if (breaking != null && client().gameMode != null) actuator.stopDestroy(client());
		breaking = null;
	}
	private static String id(BlockState state) { return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(); }
	private static BlockPos block(GoalPosition p) { return new BlockPos(p.x(), p.y(), p.z()); }
	private static GoalPosition position(BlockPos p) { return new GoalPosition(p.getX(), p.getY(), p.getZ(), true); }
}
