package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.agent.navigation.NavigationPolicies;
import ai.moeru.airicraft.agent.reflex.EmergencyBunker;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.modded.ItemGroups;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.ZombifiedPiglin;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Digs, roofs, waits and climbs out of a night shelter through the foreground leases. The dig and roof follow the survival
 * reflex's {@link EmergencyBunker} step by step; the climb out pillars on carried blocks or digs steps into a wall.
 */
final class MinecraftShelterEnvironment implements ShelterForNightTaskExecutor.Environment {
	private static final float ALIGNED_DEGREES = 6.0F;
	private static final String OWNER = "shelter_for_night";
	private final Actuator actuator;
	private final MovementController movement;
	private final CameraController camera;
	private BlockPos breaking;
	private BlockPos climbTarget;
	private int placed, broken;
	private long ticks;

	MinecraftShelterEnvironment(CameraController camera) {
		this(camera, OWNER, Priority.FOREGROUND);
	}

	/** Leases under {@code owner} at {@code priority}: the survival reflex climbs out of its night bunker at its own priority. */
	MinecraftShelterEnvironment(CameraController camera, String owner, Priority priority) {
		this.camera = Objects.requireNonNull(camera, "camera");
		this.actuator = new Actuator(owner, priority);
		this.movement = new MovementController(owner, priority);
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public ShelterForNightTaskExecutor.Body observe() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		ticks++;
		return new ShelterForNightTaskExecutor.Body(player.position(), player.blockPosition(), player.onGround(), player.getHealth(),
			player.getFoodData().getFoodLevel(), level.getDayTime(), Level.OVERWORLD.equals(level.dimension()),
			level.dimension().location().toString(), buildingBlocks(player));
	}

	// ---- site ----

	@Override
	public ShelterForNightTaskExecutor.SiteFacts siteFacts(BlockPos top) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		var cells = cells(level, player);
		EmergencyBunker.Plan plan = EmergencyBunker.plan(cells, top, ShelterForNightTaskExecutor.MAX_BREAK_TICKS);
		boolean yields = plan != null && plan.dig().stream().anyMatch(cell -> yieldsBuildingBlock(level.getBlockState(cell), player));
		return new ShelterForNightTaskExecutor.SiteFacts(plan, ShelterForNightTaskExecutor.standable(cells, top), yields);
	}

	@Override
	public EmergencyBunker.Plan enclosedShelter(BlockPos feet) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		return ShelterForNightTaskExecutor.enclosedShelter(cells(level, player), feet);
	}

	/**
	 * Whether breaking this block puts a roof or pillar block in the inventory: soil that drops dirt, or overworld stone
	 * (cobblestone, cobbled deepslate, granite...) with a carried tool that harvests it.
	 */
	private static boolean yieldsBuildingBlock(BlockState state, LocalPlayer player) {
		if (EmergencyBunker.dropsDirt(state)) return true;
		if (!state.is(BlockTags.BASE_STONE_OVERWORLD) && !state.is(Blocks.COBBLESTONE)) return false;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) return true;
		}
		return false;
	}

	@Override
	public void approach(BlockPos top) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null) return;
		stopBreaking();
		Vec3 center = Vec3.atBottomCenterOf(top);
		camera.lookAt(minecraft, new Vec3(center.x, player.getEyeY(), center.z));
		movement.moveForward(minecraft, false, false, ticks);
	}

	// ---- dig and roof ----

	@Override
	public ShelterForNightTaskExecutor.DigTick dig(EmergencyBunker.Plan plan) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return ShelterForNightTaskExecutor.DigTick.FALLING;
		BlockPos at = player.blockPosition();
		BlockPos top = plan.top();
		boolean offColumn = at.getX() != top.getX() || at.getZ() != top.getZ();
		if (offColumn && at.getY() == top.getY() && Math.abs(at.getX() - top.getX()) <= 1 && Math.abs(at.getZ() - top.getZ()) <= 1
			&& breaking == null) {
			// Arrived with momentum and slid one cell over before the first dig: step back.
			approach(top);
			return ShelterForNightTaskExecutor.DigTick.ALIGNING;
		}
		if (offColumn || at.getY() > top.getY()) {
			stop();
			return ShelterForNightTaskExecutor.DigTick.LEFT_COLUMN;
		}
		EmergencyBunker.Step step = EmergencyBunker.step(plan, at.getY(), player.onGround(), roofClosed(plan));
		switch (step) {
			case CLOSED -> {
				stop();
				return ShelterForNightTaskExecutor.DigTick.CLOSED;
			}
			case WAIT -> {
				movement.stop(minecraft);
				return ShelterForNightTaskExecutor.DigTick.FALLING;
			}
			case DIG -> {
				BlockPos below = at.below();
				BlockState state = level.getBlockState(below);
				if (state.getCollisionShape(level, below).isEmpty()) {
					// Dug, but the body still stands on the rim: step toward the middle and it drops in.
					approach(at);
					return ShelterForNightTaskExecutor.DigTick.ALIGNING;
				}
				movement.stop(minecraft);
				if (!plan.dig().contains(below)) return ShelterForNightTaskExecutor.DigTick.OFF_PLAN;
				selectBestTool(minecraft, player, state);
				camera.lookAt(minecraft, Vec3.atCenterOf(below).add(0.0D, 0.5D, 0.0D));
				destroy(minecraft, player, below, Direction.UP);
				return ShelterForNightTaskExecutor.DigTick.DIGGING;
			}
			case ROOF -> {
				stopBreaking();
				movement.stop(minecraft);
				return placeAgainst(minecraft, player, plan.roofSupport(), plan.roof())
					? ShelterForNightTaskExecutor.DigTick.ROOFING : ShelterForNightTaskExecutor.DigTick.NO_ROOF_BLOCK;
			}
		}
		return ShelterForNightTaskExecutor.DigTick.DIGGING;
	}

	/** Places a building block into {@code target} against a face of {@code support}; false with none carried. */
	private boolean placeAgainst(Minecraft minecraft, LocalPlayer player, BlockPos support, BlockPos target) {
		int slot = buildingSlot(player);
		if (slot < 0) return false;
		if (buildingRank(player.getMainHandItem()) == Integer.MAX_VALUE) {
			select(minecraft, player, slot);
			return true;
		}
		Direction face = Direction.getApproximateNearest(target.getX() - support.getX(), target.getY() - support.getY(),
			target.getZ() - support.getZ());
		Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
		camera.lookAt(minecraft, hit);
		if (!camera.isLookingAt(minecraft, hit, ALIGNED_DEGREES * 4)) return true;
		InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return true;
	}

	@Override
	public boolean roofClosed(EmergencyBunker.Plan plan) {
		ClientLevel level = client().level;
		return level != null && level.getBlockState(plan.roof()).isCollisionShapeFullBlock(level, plan.roof());
	}

	// ---- wait ----

	@Override
	public boolean intruded(EmergencyBunker.Plan plan) {
		ClientLevel level = client().level;
		if (level == null) return false;
		AABB shaft = EmergencyBunker.shaft(plan);
		List<AABB> mobs = level.getEntitiesOfClass(LivingEntity.class, shaft.inflate(2.0D), MinecraftShelterEnvironment::hostile).stream()
			.map(LivingEntity::getBoundingBox).toList();
		return EmergencyBunker.intruded(shaft, mobs);
	}

	@Override
	public boolean hostileNear(BlockPos top, double radius) {
		ClientLevel level = client().level;
		if (level == null) return false;
		Vec3 center = Vec3.atBottomCenterOf(top);
		return !level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(radius),
			entity -> hostile(entity) && entity.position().distanceToSqr(center) <= radius * radius).isEmpty();
	}

	/** Mobs that hunt a player in the open; endermen and zombified piglins leave one alone that leaves them alone. */
	private static boolean hostile(LivingEntity entity) {
		return entity.isAlive() && entity instanceof Enemy && !(entity instanceof EnderMan) && !(entity instanceof ZombifiedPiglin);
	}

	// ---- exit ----

	@Override
	public ShelterForNightTaskExecutor.BreakTick breakRoof(EmergencyBunker.Plan plan) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return ShelterForNightTaskExecutor.BreakTick.WORKING;
		BlockPos roof = plan.roof();
		BlockState state = level.getBlockState(roof);
		if (state.getCollisionShape(level, roof).isEmpty()) {
			stopBreaking();
			return ShelterForNightTaskExecutor.BreakTick.GONE;
		}
		if (state.getDestroySpeed(level, roof) < 0) return ShelterForNightTaskExecutor.BreakTick.FAILED;
		movement.stop(minecraft);
		selectBestTool(minecraft, player, state);
		camera.lookAt(minecraft, new Vec3(roof.getX() + 0.5D, roof.getY(), roof.getZ() + 0.5D));
		destroy(minecraft, player, roof, Direction.DOWN);
		return ShelterForNightTaskExecutor.BreakTick.WORKING;
	}

	@Override
	public ShelterForNightTaskExecutor.ClimbTick pillar(EmergencyBunker.Plan plan) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "no_player");
		BlockPos at = player.blockPosition();
		BlockPos top = plan.top();
		int surfaceY = top.getY();
		switch (ShelterForNightTaskExecutor.pillarStep(at.getY(), surfaceY, player.onGround(), buildingBlocks(player))) {
			case OUT -> {
				stop();
				return climb(ShelterForNightTaskExecutor.ClimbState.OUT, "out");
			}
			case NO_BLOCKS -> {
				stop();
				return climb(ShelterForNightTaskExecutor.ClimbState.BLOCKED, "no_blocks");
			}
			case FALLING -> {
				movement.hold(minecraft, false, false);
				return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "falling");
			}
			case TOWER -> {}
		}
		if (at.getX() != top.getX() || at.getZ() != top.getZ()) {
			stop();
			return climb(ShelterForNightTaskExecutor.ClimbState.BLOCKED, "off_column at=" + at.toShortString());
		}
		BlockPos overHead = at.above(2);
		if (player.onGround() && !level.getBlockState(overHead).getCollisionShape(level, overHead).isEmpty()) {
			stop();
			return climb(ShelterForNightTaskExecutor.ClimbState.BLOCKED, "no_headroom at=" + overHead.toShortString());
		}
		stopBreaking();
		int slot = buildingSlot(player);
		if (buildingRank(player.getMainHandItem()) == Integer.MAX_VALUE) {
			select(minecraft, player, slot);
			return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "selecting_block");
		}
		camera.lookAt(minecraft, new Vec3(top.getX() + 0.5D, player.getY() - 1.0D, top.getZ() + 0.5D));
		movement.hold(minecraft, true, false);
		// Place on the first block under the feet whose top cell the body has risen clear of, never at or above the top.
		for (int offset = 1; offset <= 3; offset++) {
			BlockPos support = at.below(offset);
			BlockPos target = support.above();
			if (target.getY() >= surfaceY) continue;
			BlockState supportState = level.getBlockState(support);
			BlockState targetState = level.getBlockState(target);
			if (!supportState.isFaceSturdy(level, support, Direction.UP)) continue;
			if (!(targetState.isAir() || targetState.canBeReplaced())) continue;
			if (!(player.getMainHandItem().getItem() instanceof BlockItem item)) break;
			if (!level.isUnobstructed(item.getBlock().defaultBlockState(), target, CollisionContext.of(player))) break;
			InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND,
				new BlockHitResult(new Vec3(support.getX() + 0.5D, support.getY() + 1.0D, support.getZ() + 0.5D), Direction.UP, support, false));
			if (result.consumesAction()) {
				placed++;
				player.swing(InteractionHand.MAIN_HAND);
			}
			break;
		}
		return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "tower placed=" + placed);
	}

	@Override
	public Optional<Direction> stairDirection(BlockPos origin, int surfaceY) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return Optional.empty();
		return ShelterForNightTaskExecutor.stairDirection(origin, surfaceY, cells(level, player));
	}

	@Override
	public ShelterForNightTaskExecutor.ClimbTick stairs(BlockPos origin, Direction direction, int surfaceY) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "no_player");
		var cells = cells(level, player);
		BlockPos at = player.blockPosition();
		var action = ShelterForNightTaskExecutor.stairAction(at, direction, surfaceY, player.onGround(), cells::open);
		switch (action.kind()) {
			case DONE -> {
				stop();
				return climb(ShelterForNightTaskExecutor.ClimbState.OUT, "out");
			}
			case WAIT -> {
				// In the air: keep pressing toward the step being climbed so the body lands on it.
				if (climbTarget != null) moveToward(minecraft, player, climbTarget, true);
				else movement.stop(minecraft);
				return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "in_air");
			}
			case BREAK -> {
				BlockPos cell = action.cell();
				climbTarget = null;
				movement.stop(minecraft);
				BlockState state = level.getBlockState(cell);
				if (cells.fluid(cell) || cells.breakTicks(cell) < 0)
					return climb(ShelterForNightTaskExecutor.ClimbState.BLOCKED, "cannot_break " + cell.toShortString() + " "
						+ BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
				selectBestTool(minecraft, player, state);
				camera.lookAt(minecraft, Vec3.atCenterOf(cell));
				Vec3 eye = player.getEyePosition();
				Direction face = Direction.getApproximateNearest(eye.x - (cell.getX() + 0.5D), eye.y - (cell.getY() + 0.5D),
					eye.z - (cell.getZ() + 0.5D));
				destroy(minecraft, player, cell, face);
				return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "breaking " + cell.toShortString());
			}
			case CLIMB -> {
				stopBreaking();
				climbTarget = action.cell();
				moveToward(minecraft, player, climbTarget, true);
				return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "climbing " + climbTarget.toShortString());
			}
		}
		return climb(ShelterForNightTaskExecutor.ClimbState.WORKING, "");
	}

	private void moveToward(Minecraft minecraft, LocalPlayer player, BlockPos cell, boolean jump) {
		Vec3 center = Vec3.atBottomCenterOf(cell);
		camera.lookAt(minecraft, new Vec3(center.x, player.getEyeY(), center.z));
		movement.moveForward(minecraft, false, jump, ticks);
	}

	private ShelterForNightTaskExecutor.ClimbTick climb(ShelterForNightTaskExecutor.ClimbState state, String note) {
		return new ShelterForNightTaskExecutor.ClimbTick(state, placed + broken, note);
	}

	// ---- shared ----

	/** Start once, then continue: the game drops isDestroying every tick the attack key is up, so restarting would reset it. */
	private void destroy(Minecraft minecraft, LocalPlayer player, BlockPos pos, Direction face) {
		if (!pos.equals(breaking)) {
			stopBreaking();
			if (!actuator.startDestroy(minecraft, pos, face)) return;
			breaking = pos;
		}
		else actuator.continueDestroy(minecraft, pos, face);
		player.swing(InteractionHand.MAIN_HAND);
	}

	private void stopBreaking() {
		if (breaking == null) return;
		ClientLevel level = client().level;
		if (level != null && level.getBlockState(breaking).getCollisionShape(level, breaking).isEmpty()) broken++;
		breaking = null;
		actuator.stopDestroy(client());
	}

	@Override
	public void stop() {
		stopBreaking();
		climbTarget = null;
		movement.stop(client());
	}

	@Override
	public void release() {
		stop();
	}

	/** The bunker's cells, refusing to dig into a preserved place, plus room for the body. */
	private static ShelterForNightTaskExecutor.ShelterCells cells(ClientLevel level, LocalPlayer player) {
		EmergencyBunker.Cells base = EmergencyBunker.cells(level, player);
		return new ShelterForNightTaskExecutor.ShelterCells() {
			@Override public boolean solid(BlockPos pos) { return base.solid(pos); }
			@Override public int breakTicks(BlockPos pos) {
				if (!level.hasChunkAt(pos) || WorldPlacePreservation.contains(level, pos)) return -1;
				return base.breakTicks(pos);
			}
			@Override public boolean fluid(BlockPos pos) { return base.fluid(pos); }
			@Override public boolean falling(BlockPos pos) { return base.falling(pos); }
			@Override public boolean open(BlockPos pos) {
				return level.hasChunkAt(pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
					&& level.getFluidState(pos).isEmpty();
			}
		};
	}

	/** Carried blocks that stay put as a roof or pillar: never sand or gravel, which would fall onto the head. */
	private static int buildingBlocks(LocalPlayer player) {
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (buildingRank(stack) < Integer.MAX_VALUE) total += stack.getCount();
		}
		return total;
	}

	/** The cheapest carried building block, dirt first (it breaks fast when the roof comes off), or -1. */
	private static int buildingSlot(LocalPlayer player) {
		int best = -1, bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			int rank = buildingRank(player.getInventory().getItem(slot));
			if (rank < bestRank) {
				bestRank = rank;
				best = slot;
			}
		}
		return best;
	}

	/** Dirt, then throwaway stone, then planks, then logs; anything else (or a falling or partial block) is not used. */
	static int buildingRank(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return Integer.MAX_VALUE;
		Block block = item.getBlock();
		BlockState state = block.defaultBlockState();
		if (block instanceof FallingBlock || state.hasBlockEntity()
			|| !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return Integer.MAX_VALUE;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		if (id.equals("minecraft:dirt")) return 0;
		if (NavigationPolicies.isThrowaway(stack)) return 1;
		if (ItemGroups.contains(ItemGroups.Group.PLANKS, id)) return 2;
		if (ItemGroups.contains(ItemGroups.Group.LOGS, id)) return 3;
		return Integer.MAX_VALUE;
	}

	private void selectBestTool(Minecraft minecraft, LocalPlayer player, BlockState state) {
		int best = -1;
		float bestSpeed = 1.0F;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.isEmpty()) continue;
			float speed = stack.getDestroySpeed(state);
			if (speed > bestSpeed) {
				best = slot;
				bestSpeed = speed;
			}
		}
		if (best >= 0 && best != player.getInventory().getSelectedSlot()) select(minecraft, player, best);
	}

	/** Puts an inventory slot in the main hand: a hotbar slot is selected, a main-inventory slot swapped into the held one. */
	private void select(Minecraft minecraft, LocalPlayer player, int slot) {
		if (slot < 0) return;
		if (slot < Inventory.SELECTION_SIZE) {
			actuator.selectHotbarAndSync(minecraft, slot);
			return;
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() || minecraft.gameMode == null)
			return;
		if (slot < InventoryMenu.INV_SLOT_START || slot >= InventoryMenu.INV_SLOT_END) return;
		int selected = player.getInventory().getSelectedSlot();
		minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, slot, selected, ClickType.SWAP, player);
		actuator.syncHotbar(minecraft, selected);
	}
}
