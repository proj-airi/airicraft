package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Reads lava and obsidian around the player and acts through the foreground lease. */
final class MinecraftObsidianEnvironment implements CollectObsidianTaskExecutor.Environment {
	private static final int VERTICAL_RADIUS = 6;
	/** Stances sit within this many blocks of their target, horizontally. */
	private static final int STANCE_RADIUS = 4;
	/** Under the 4.5 block interaction range, so a slightly off stance still reaches. */
	private static final double REACH = 4.3D;
	private static final float AIM_TOLERANCE_DEGREES = 1.0F;
	private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	/** Faces water can be poured against: the floor under the cell, or a wall beside it. */
	private static final Direction[] SUPPORTS = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
	private final Actuator actuator = new Actuator("collect_obsidian", Priority.FOREGROUND);
	private final CameraController camera;
	private BlockPos breaking;

	MinecraftObsidianEnvironment(CameraController camera) {
		this.camera = java.util.Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public CollectObsidianTaskExecutor.Site observe() {
		LocalPlayer player = client().player;
		if (player == null || client().level == null) return null;
		boolean pickaxe = false;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
			if (player.getInventory().getItem(slot).isCorrectToolForDrops(Blocks.OBSIDIAN.defaultBlockState())) pickaxe = true;
		return new CollectObsidianTaskExecutor.Site(player.position(), player.getHealth(), count(player, Items.OBSIDIAN),
			slot(player, Items.WATER_BUCKET) >= 0, slot(player, Items.BUCKET) >= 0, pickaxe);
	}

	@Override
	public Optional<CollectObsidianTaskExecutor.Mine> mineTarget(CollectObsidianTaskExecutor.Site site, Set<GoalPosition> rejected,
		boolean opening) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		BlockPos feet = BlockPos.containing(site.feet());
		List<BlockPos> candidates = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-SEARCH_RADIUS, -VERTICAL_RADIUS, -SEARCH_RADIUS),
			feet.offset(SEARCH_RADIUS, VERTICAL_RADIUS, SEARCH_RADIUS))) {
			if (level.getBlockState(pos).is(Blocks.OBSIDIAN) && !rejected.contains(goal(pos)) && mineable(level, pos, opening))
				candidates.add(pos.immutable());
		}
		candidates.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(site.feet())));
		for (BlockPos block : candidates) {
			Vec3 aim = Vec3.atCenterOf(block);
			Optional<BlockPos> stance = stance(level, site.feet(), block, Set.of(block),
				eye -> eye.distanceTo(aim) <= REACH && reachesBlock(level, eye, block));
			if (stance.isPresent()) return Optional.of(new CollectObsidianTaskExecutor.Mine(goal(block), goal(stance.get()), opening));
		}
		return Optional.empty();
	}

	@Override
	public boolean stillMineable(CollectObsidianTaskExecutor.Mine mine) {
		ClientLevel level = client().level;
		if (level == null) return false;
		BlockPos pos = block(mine.block());
		BlockState state = level.getBlockState(pos);
		if (state.isAir()) return true;
		return state.is(Blocks.OBSIDIAN) && mineable(level, pos, mine.coversLava());
	}

	/**
	 * Safe to mine: no lava on any side, so nothing flows into the hole, solid ground under it to catch the drop, not part of a
	 * Nether portal and not in a preserved place. An opening instead has a lava source under it and no other lava.
	 */
	static boolean mineable(ClientLevel level, BlockPos pos, boolean opening) {
		if (WorldPlacePreservation.contains(level, pos)) return false;
		for (Direction direction : Direction.values()) {
			BlockPos side = pos.relative(direction);
			if (level.getBlockState(side).is(Blocks.NETHER_PORTAL)) return false;
			boolean lava = lava(level.getFluidState(side));
			if (direction == Direction.DOWN && opening) {
				if (!lava || !level.getFluidState(side).isSource()) return false;
			} else if (lava) return false;
		}
		if (opening) return true;
		BlockPos below = pos.below();
		return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
	}

	@Override
	public Optional<CollectObsidianTaskExecutor.Cast> castTarget(CollectObsidianTaskExecutor.Site site, Set<GoalPosition> rejected) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		BlockPos feet = BlockPos.containing(site.feet());
		record Candidate(BlockPos pour, int gain) {}
		List<Candidate> candidates = new ArrayList<>();
		for (BlockPos pos : BlockPos.betweenClosed(feet.offset(-SEARCH_RADIUS, -VERTICAL_RADIUS, -SEARCH_RADIUS),
			feet.offset(SEARCH_RADIUS, VERTICAL_RADIUS, SEARCH_RADIUS))) {
			if (rejected.contains(goal(pos)) || !pourable(level, pos)) continue;
			int gain = lavaTurned(level, pos);
			if (gain > 0) candidates.add(new Candidate(pos.immutable(), gain));
		}
		candidates.sort(Comparator.comparingInt((Candidate candidate) -> -candidate.gain())
			.thenComparingDouble(candidate -> candidate.pour().distToCenterSqr(site.feet())));
		for (Candidate candidate : candidates) {
			BlockPos pour = candidate.pour();
			for (Direction direction : SUPPORTS) {
				BlockPos support = pour.relative(direction);
				Direction face = direction.getOpposite();
				BlockState supportState = level.getBlockState(support);
				if (!supportState.isFaceSturdy(level, support, face) || !level.getFluidState(support).isEmpty()) continue;
				Optional<Vec3> found = Optional.empty();
				BlockPos stanceFound = null;
				for (Vec3 aim : faceAims(support, face)) {
					Optional<BlockPos> stance = stance(level, site.feet(), pour, Set.of(),
						eye -> eye.distanceTo(aim) <= REACH && pourLands(level, eye, aim, pour) && scoopReaches(level, eye, pour));
					if (stance.isPresent()) {
						found = Optional.of(aim);
						stanceFound = stance.get();
						break;
					}
				}
				if (found.isPresent())
					return Optional.of(new CollectObsidianTaskExecutor.Cast(goal(pour), goal(support), face.getSerializedName(),
						goal(stanceFound), candidate.gain()));
			}
		}
		return Optional.empty();
	}

	/** A lava source, or an open cell over one. */
	private static boolean pourable(ClientLevel level, BlockPos pos) {
		if (WorldPlacePreservation.contains(level, pos)) return false;
		FluidState fluid = level.getFluidState(pos);
		if (lava(fluid)) return fluid.isSource();
		BlockState state = level.getBlockState(pos);
		return state.isAir() && lava(level.getFluidState(pos.below()));
	}

	/** Water turns lava sources it touches from above or beside into obsidian. */
	static int lavaTurned(ClientLevel level, BlockPos pour) {
		int turned = 0;
		for (Direction direction : SUPPORTS) {
			FluidState fluid = level.getFluidState(pour.relative(direction));
			if (lava(fluid) && fluid.isSource()) turned++;
		}
		return turned;
	}

	private static List<Vec3> faceAims(BlockPos support, Direction face) {
		Vec3 center = Vec3.atCenterOf(support).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
		List<Vec3> aims = new ArrayList<>(List.of(center));
		for (double a : new double[] {-0.3D, 0.3D}) {
			for (double b : new double[] {-0.3D, 0.3D}) {
				aims.add(switch (face.getAxis()) {
					case X -> center.add(0.0D, a, b);
					case Y -> center.add(a, 0.0D, b);
					case Z -> center.add(a, b, 0.0D);
				});
			}
		}
		return aims;
	}

	/** The water bucket's own ray passes through lava and lands the water on the clicked face's side: the pour cell. */
	private static boolean pourLands(ClientLevel level, Vec3 eye, Vec3 aim, BlockPos pour) {
		Vec3 end = eye.add(aim.subtract(eye).scale(1.05D));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
			net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().relative(hit.getDirection()).equals(pour);
	}

	/** The empty bucket picks up the first source fluid on its ray; it must be the poured water, never lava. */
	private static boolean scoopReaches(ClientLevel level, Vec3 eye, BlockPos pour) {
		Vec3 aim = scoopAim(pour);
		if (eye.distanceTo(aim) > REACH) return false;
		BlockHitResult blocks = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
			net.minecraft.world.phys.shapes.CollisionContext.empty()));
		if (blocks.getType() != HitResult.Type.MISS && !blocks.getBlockPos().equals(pour)) return false;
		BlockHitResult fluids = level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY,
			net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return fluids.getType() == HitResult.Type.MISS || fluids.getBlockPos().equals(pour);
	}

	/**
	 * Just under the surface of a source block (8/9 high), so a sloped ray enters the poured cell through its top rather than
	 * through the lava beside it.
	 */
	private static Vec3 scoopAim(BlockPos pour) {
		return new Vec3(pour.getX() + 0.5D, pour.getY() + 0.8D, pour.getZ() + 0.5D);
	}

	private static boolean reachesBlock(ClientLevel level, Vec3 eye, BlockPos block) {
		BlockHitResult hit = level.clip(new ClipContext(eye, Vec3.atCenterOf(block), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
			net.minecraft.world.phys.shapes.CollisionContext.empty()));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(block);
	}

	/**
	 * The nearest cell to the player from which {@code visible} holds, standing on solid ground with no fluid in the body
	 * and no lava beside it. {@code notFloor} are blocks about to be removed.
	 */
	private static Optional<BlockPos> stance(ClientLevel level, Vec3 player, BlockPos target, Set<BlockPos> notFloor,
		Predicate<Vec3> visible) {
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(target.offset(-STANCE_RADIUS, -3, -STANCE_RADIUS),
			target.offset(STANCE_RADIUS, 3, STANCE_RADIUS))) {
			double distance = Vec3.atBottomCenterOf(pos).distanceToSqr(player);
			if (distance >= bestDistance || notFloor.contains(pos.below()) || !safeStance(level, pos)) continue;
			if (!visible.test(new Vec3(pos.getX() + 0.5D, pos.getY() + 1.62D, pos.getZ() + 0.5D))) continue;
			best = pos.immutable();
			bestDistance = distance;
		}
		return Optional.ofNullable(best);
	}

	static boolean safeStance(ClientLevel level, BlockPos feet) {
		if (!level.hasChunkAt(feet)) return false;
		BlockPos floor = feet.below();
		BlockState floorState = level.getBlockState(floor);
		if (!floorState.isFaceSturdy(level, floor, Direction.UP) || floorState.is(Blocks.MAGMA_BLOCK)
			|| !level.getFluidState(floor).isEmpty()) return false;
		for (BlockPos body : List.of(feet, feet.above())) {
			BlockState state = level.getBlockState(body);
			if (!state.getCollisionShape(level, body).isEmpty() || !level.getFluidState(body).isEmpty() || state.is(BlockTags.FIRE)
				|| state.is(Blocks.NETHER_PORTAL)) return false;
			for (Direction direction : HORIZONTAL)
				if (lava(level.getFluidState(body.relative(direction)))) return false;
		}
		return true;
	}

	@Override
	public CollectObsidianTaskExecutor.BreakResult breakBlock(GoalPosition block) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.gameMode == null) return CollectObsidianTaskExecutor.BreakResult.UNREACHABLE;
		BlockPos pos = block(block);
		BlockState state = level.getBlockState(pos);
		if (state.isAir() || state.canBeReplaced()) {
			stopBreaking();
			return CollectObsidianTaskExecutor.BreakResult.GONE;
		}
		if (!player.canInteractWithBlock(pos, 0.0D)) return CollectObsidianTaskExecutor.BreakResult.UNREACHABLE;
		var tool = MiningToolPreparation.ensureSelectedForClearance(minecraft, actuator, player, List.of(state));
		if (!tool.ok()) return tool.message().startsWith("missing_suitable_tool")
			? CollectObsidianTaskExecutor.BreakResult.NO_TOOL : CollectObsidianTaskExecutor.BreakResult.WORKING;
		if (camera.lookAtBlock(minecraft, pos).isEmpty()) return CollectObsidianTaskExecutor.BreakResult.UNREACHABLE;
		var hit = camera.blockHit(minecraft, pos);
		if (hit.isEmpty()) return CollectObsidianTaskExecutor.BreakResult.WORKING;
		if (!pos.equals(breaking)) {
			stopBreaking();
			if (!actuator.startDestroy(minecraft, pos, hit.get().getDirection())) return CollectObsidianTaskExecutor.BreakResult.WORKING;
			breaking = pos;
		} else actuator.continueDestroy(minecraft, pos, hit.get().getDirection());
		player.swing(InteractionHand.MAIN_HAND);
		return CollectObsidianTaskExecutor.BreakResult.WORKING;
	}

	@Override
	public CollectObsidianTaskExecutor.PourResult pour(CollectObsidianTaskExecutor.Cast cast) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return CollectObsidianTaskExecutor.PourResult.NO_SHOT;
		BlockPos pour = block(cast.pour());
		if (level.getFluidState(pour).is(FluidTags.WATER)) return CollectObsidianTaskExecutor.PourResult.POURED;
		if (!pourable(level, pour)) return CollectObsidianTaskExecutor.PourResult.NOT_LAVA;
		if (!hold(minecraft, player, Items.WATER_BUCKET)) return slot(player, Items.WATER_BUCKET) < 0
			? CollectObsidianTaskExecutor.PourResult.NO_WATER_BUCKET : CollectObsidianTaskExecutor.PourResult.WORKING;
		Direction face = Direction.byName(cast.supportFace());
		if (face == null) return CollectObsidianTaskExecutor.PourResult.NO_SHOT;
		BlockPos support = block(cast.support());
		Vec3 eye = player.getEyePosition();
		Vec3 aim = faceAims(support, face).stream().filter(point -> pourLands(level, eye, point, pour)).findFirst().orElse(null);
		if (aim == null) return CollectObsidianTaskExecutor.PourResult.NO_SHOT;
		camera.lookAt(minecraft, aim);
		if (!camera.isLookingAt(minecraft, aim, AIM_TOLERANCE_DEGREES)) return CollectObsidianTaskExecutor.PourResult.WORKING;
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange())),
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
		if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().relative(hit.getDirection()).equals(pour))
			return CollectObsidianTaskExecutor.PourResult.WORKING;
		actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
		return CollectObsidianTaskExecutor.PourResult.WORKING;
	}

	@Override
	public CollectObsidianTaskExecutor.ScoopResult scoop(GoalPosition water) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return CollectObsidianTaskExecutor.ScoopResult.NO_SHOT;
		BlockPos pos = block(water);
		FluidState fluid = level.getFluidState(pos);
		if (!fluid.is(FluidTags.WATER) || !fluid.isSource())
			return slot(player, Items.WATER_BUCKET) >= 0 ? CollectObsidianTaskExecutor.ScoopResult.SCOOPED
				: CollectObsidianTaskExecutor.ScoopResult.NO_WATER;
		if (!hold(minecraft, player, Items.BUCKET)) return slot(player, Items.BUCKET) < 0
			? CollectObsidianTaskExecutor.ScoopResult.NO_BUCKET : CollectObsidianTaskExecutor.ScoopResult.WORKING;
		Vec3 aim = scoopAim(pos);
		camera.lookAt(minecraft, aim);
		if (!camera.isLookingAt(minecraft, aim, AIM_TOLERANCE_DEGREES)) return CollectObsidianTaskExecutor.ScoopResult.WORKING;
		Vec3 eye = player.getEyePosition();
		// The bucket's own ray: the first source fluid it meets is what it fills with, so make sure that is the water.
		BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(player.getViewVector(1.0F).scale(player.blockInteractionRange())),
			ClipContext.Block.OUTLINE, ClipContext.Fluid.SOURCE_ONLY, player));
		if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return CollectObsidianTaskExecutor.ScoopResult.NO_SHOT;
		actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
		return CollectObsidianTaskExecutor.ScoopResult.WORKING;
	}

	@Override
	public Optional<GoalPosition> obsidianDrop(GoalPosition near) {
		ClientLevel level = client().level;
		LocalPlayer player = client().player;
		if (level == null || player == null) return Optional.empty();
		Vec3 center = Vec3.atCenterOf(block(near));
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(3.0D),
				item -> item.isAlive() && item.getItem().is(Items.OBSIDIAN)).stream()
			.min(Comparator.comparingDouble(item -> item.distanceToSqr(center)))
			.map(item -> goal(BlockPos.containing(item.position())));
	}

	@Override
	public void stopBreaking() {
		if (breaking == null) return;
		breaking = null;
		actuator.stopDestroy(client());
	}

	@Override
	public void release() {
		stopBreaking();
	}

	private static boolean lava(FluidState fluid) {
		return fluid.is(FluidTags.LAVA);
	}

	private static int count(LocalPlayer player, Item item) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	private static int slot(LocalPlayer player, Item item) {
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++)
			if (player.getInventory().getItem(slot).is(item)) return slot;
		return -1;
	}

	/** True once the item is in the main hand; otherwise starts putting it there. */
	private boolean hold(Minecraft minecraft, LocalPlayer player, Item item) {
		if (player.getMainHandItem().is(item)) return true;
		int inventorySlot = slot(player, item);
		if (inventorySlot < 0) return false;
		if (inventorySlot < 9) {
			actuator.selectHotbar(minecraft, inventorySlot);
			return false;
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() || minecraft.gameMode == null)
			return false;
		if (inventorySlot < InventoryMenu.INV_SLOT_START || inventorySlot >= InventoryMenu.INV_SLOT_END) return false;
		minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, inventorySlot,
			player.getInventory().getSelectedSlot(), ClickType.SWAP, player);
		return false;
	}

	private static BlockPos block(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}

	private static GoalPosition goal(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}
}
