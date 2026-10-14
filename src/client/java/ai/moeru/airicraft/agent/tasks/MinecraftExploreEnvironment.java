package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.llm.WorldFeatureSearchService;
import ai.moeru.airicraft.agent.navigation.NavigationPolicies;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime;
import ai.moeru.airicraft.control.Priority;
import ai.moeru.airicraft.modded.ItemGroups;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Reads the client world for explore_for and acts through foreground leases: the player, the feature search over loaded
 * chunks, the top of a column, the top surface of loaded chunks, food and food animals, and a dug night shelter.
 * Under an open sky the top of a column is the motion-blocking heightmap (water counts, leaves do not); under a ceiling,
 * as in the Nether, it is the first standing cell found scanning down from just above the body.
 */
final class MinecraftExploreEnvironment implements ExploreForTaskExecutor.Environment {
	private static final int CEILING_SCAN_UP = 8;
	private static final int CEILING_SCAN_DOWN = 16;
	/** Columns read per chunk side when sampling the surface: every other block, so one small tree is not missed. */
	private static final int SURFACE_GRID = 8;
	private static final float ALIGNED_DEGREES = 1.5F;
	/** Off the column centre by more than this, the body steps to it before digging, or it would not drop in. */
	private static final double CENTERED_BLOCKS = 0.18D;
	private static final Set<EntityType<?>> FOOD_ANIMALS = Set.of(EntityType.COW, EntityType.PIG, EntityType.SHEEP,
		EntityType.CHICKEN, EntityType.RABBIT, EntityType.MOOSHROOM);
	/** Eaten only when starving: hunger effects, but still food. */
	private static final Set<String> STARVING_FOOD = Set.of("minecraft:chicken", "minecraft:rotten_flesh");
	/** Blocks that hold still as a roof and are cheap enough to spend, best first; logs last, they are often what is wanted. */
	private static final List<String> ROOF_BLOCKS = List.of("minecraft:sandstone", "minecraft:red_sandstone", "minecraft:dirt",
		"minecraft:coarse_dirt", "minecraft:packed_mud", "minecraft:mud_bricks", "minecraft:smooth_sandstone", "minecraft:cut_sandstone");

	private final Actuator actuator = new Actuator("explore_for", Priority.FOREGROUND);
	private final MovementController movement = new MovementController("explore_for", Priority.FOREGROUND);
	private final CameraController camera;
	private long ticks;
	private net.minecraft.world.item.Item eatingItem;
	private int eatingCount;
	private boolean eatStarted;
	private boolean digging;

	MinecraftExploreEnvironment(CameraController camera) {
		this.camera = Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public ExploreForTaskExecutor.Body observe() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		ticks++;
		boolean night = !level.dimensionType().hasFixedTime() && ExploreForTaskExecutor.night(level.getDayTime());
		return new ExploreForTaskExecutor.Body(player.position(), player.getHealth(), player.getMaxHealth(),
			player.getFoodData().getFoodLevel(), night, player.onGround(), player.isInWater());
	}

	@Override
	public String worldKey() {
		Minecraft minecraft = client();
		ClientLevel level = minecraft.level;
		String world = minecraft.getSingleplayerServer() != null
			? "local:" + minecraft.getSingleplayerServer().getWorldData().getLevelName()
			: minecraft.getCurrentServer() != null ? "server:" + minecraft.getCurrentServer().ip : "unknown";
		return world + "|" + (level == null ? "none" : level.dimension().location().toString());
	}

	@Override
	public Optional<ExploreForTaskExecutor.Feature> findFeature(String featureKind, Vec3 feet, int radius, int minTrees) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		return WorldFeatureSearchService.nearest(level, BlockPos.containing(feet), featureKind, radius, minTrees)
			.map(found -> new ExploreForTaskExecutor.Feature(found.targetPos().getX(), found.targetPos().getY(),
				found.targetPos().getZ(), found.standPos() == null ? null
					: new int[]{found.standPos().getX(), found.standPos().getY(), found.standPos().getZ()}, found.summary()));
	}

	@Override
	public ExploreForTaskExecutor.Ground ground(int x, int z, int nearY) {
		ClientLevel level = client().level;
		if (level == null || !level.hasChunkAt(new BlockPos(x, nearY, z))) return ExploreForTaskExecutor.Ground.UNLOADED;
		if (!level.dimensionType().hasCeiling()) {
			return classify(level, x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		}
		for (int y = nearY + CEILING_SCAN_UP; y >= nearY - CEILING_SCAN_DOWN && y > level.getMinY(); y--) {
			BlockPos feet = new BlockPos(x, y, z);
			if (open(level, feet) && open(level, feet.above()) && !open(level, feet.below())) return classify(level, x, y, z);
		}
		return new ExploreForTaskExecutor.Ground(ExploreForTaskExecutor.Ground.Kind.DANGER, nearY);
	}

	/** What standing with the feet at {@code y} would be like: on dry ground, in water, or on or in something that hurts. */
	private static ExploreForTaskExecutor.Ground classify(ClientLevel level, int x, int y, int z) {
		BlockPos feet = new BlockPos(x, y, z);
		BlockState below = level.getBlockState(feet.below());
		var kind = ExploreForTaskExecutor.Ground.Kind.DRY;
		if (below.getFluidState().is(FluidTags.LAVA) || level.getFluidState(feet).is(FluidTags.LAVA)
			|| below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS) || below.is(BlockTags.CAMPFIRES) || below.is(BlockTags.FIRE)
			|| level.getBlockState(feet).is(BlockTags.FIRE)) {
			kind = ExploreForTaskExecutor.Ground.Kind.DANGER;
		}
		else if (below.getFluidState().is(FluidTags.WATER) || level.getFluidState(feet).is(FluidTags.WATER)) {
			kind = ExploreForTaskExecutor.Ground.Kind.WATER;
		}
		else if (!clear(level, feet) || !clear(level, feet.above())) {
			// Not room to stand, such as under low leaves.
			kind = ExploreForTaskExecutor.Ground.Kind.DANGER;
		}
		return new ExploreForTaskExecutor.Ground(kind, y);
	}

	/**
	 * Reads the top block of every other column of each loaded chunk not seen before: what a player sees looking across
	 * the land. Only under an open sky; in the Nether and the End the top of a chunk says nothing about what is below.
	 */
	@Override
	public void sampleSurface(Vec3 feet, int radius, ExplorationMemory memory) {
		ClientLevel level = client().level;
		if (level == null || level.dimensionType().hasCeiling() || !level.dimensionType().hasSkyLight()) return;
		int centerX = (int) Math.floor(feet.x) >> 4, centerZ = (int) Math.floor(feet.z) >> 4;
		int chunks = (radius >> 4) + 1;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = centerX - chunks; cx <= centerX + chunks; cx++) {
			for (int cz = centerZ - chunks; cz <= centerZ + chunks; cz++) {
				if (memory.sampled(cx, cz) || !level.hasChunk(cx, cz)) continue;
				if (Math.hypot((cx - centerX) * 16.0D, (cz - centerZ) * 16.0D) > radius) continue;
				int samples = 0, trees = 0, grass = 0, sand = 0, water = 0;
				int step = 16 / SURFACE_GRID;
				for (int i = 0; i < SURFACE_GRID; i++) {
					for (int j = 0; j < SURFACE_GRID; j++) {
						int x = (cx << 4) + i * step + 1, z = (cz << 4) + j * step + 1;
						int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
						if (top <= level.getMinY()) continue;
						pos.set(x, top, z);
						BlockState state = level.getBlockState(pos);
						if (state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW)) state = level.getBlockState(pos.move(Direction.DOWN));
						samples++;
						if (state.getFluidState().is(FluidTags.WATER)) water++;
						else if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS)) trees++;
						else if (state.is(BlockTags.DIRT)) grass++;
						else if (state.is(BlockTags.SAND) || state.is(BlockTags.TERRACOTTA) || state.is(Blocks.SANDSTONE)
							|| state.is(Blocks.RED_SANDSTONE) || state.is(Blocks.CACTUS)) sand++;
					}
				}
				if (samples > 0) memory.record(cx, cz, new ExplorationMemory.Surface(samples, trees, grass, sand, water));
			}
		}
	}

	// ---- food ----

	@Override
	public boolean hasFood(boolean starving) {
		LocalPlayer player = client().player;
		return player != null && foodSlot(player, starving) >= 0;
	}

	@Override
	public ExploreForTaskExecutor.ConsumeResult eat(boolean starving) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) return ExploreForTaskExecutor.ConsumeResult.NOTHING;
		if (eatingItem == null || count(player, stack -> stack.is(eatingItem)) == 0) {
			stopEating();
			int slot = foodSlot(player, starving);
			if (slot < 0) return ExploreForTaskExecutor.ConsumeResult.NOTHING;
			eatingItem = player.getInventory().getItem(slot).getItem();
			eatingCount = count(player, stack -> stack.is(eatingItem));
		}
		movement.stop(minecraft);
		if (count(player, stack -> stack.is(eatingItem)) < eatingCount) {
			stopEating();
			return ExploreForTaskExecutor.ConsumeResult.DONE;
		}
		if (!player.getMainHandItem().is(eatingItem)) {
			if (!select(minecraft, player, slot(player, stack -> stack.is(eatingItem)))) {
				stopEating();
				return ExploreForTaskExecutor.ConsumeResult.NOTHING;
			}
			eatStarted = false;
			return ExploreForTaskExecutor.ConsumeResult.CONSUMING;
		}
		if (!actuator.holdUse()) return ExploreForTaskExecutor.ConsumeResult.CONSUMING;
		if (!eatStarted || !player.isUsingItem()) {
			actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
			eatStarted = true;
		}
		return ExploreForTaskExecutor.ConsumeResult.CONSUMING;
	}

	@Override
	public void stopEating() {
		Minecraft minecraft = client();
		if (eatingItem != null) {
			actuator.releaseUse(minecraft);
			if (minecraft.player != null && minecraft.gameMode != null && minecraft.player.isUsingItem()
				&& minecraft.player.getUseItem().is(eatingItem))
				minecraft.gameMode.releaseUsingItem(minecraft.player);
		}
		eatingItem = null;
		eatingCount = 0;
		eatStarted = false;
	}

	/** The carried food with the most nutrition that is safe to eat now. */
	private static int foodSlot(LocalPlayer player, boolean starving) {
		int best = -1, bestNutrition = -1;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			var food = stack.get(DataComponents.FOOD);
			if (stack.isEmpty() || food == null || stack.get(DataComponents.CONSUMABLE) == null) continue;
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			boolean safe = ItemGroups.contains(ItemGroups.Group.COOKED_FOOD, id) || ItemGroups.contains(ItemGroups.Group.OTHER_SAFE_FOOD, id);
			if (!safe && !(starving && STARVING_FOOD.contains(id))) continue;
			if (food.nutrition() > bestNutrition) {
				bestNutrition = food.nutrition();
				best = slot;
			}
		}
		return best;
	}

	// ---- hunting ----

	@Override
	public List<ExploreForTaskExecutor.Animal> animals(Vec3 feet, double radius) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return List.of();
		return level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(radius, 8.0D, radius),
				entity -> entity.isAlive() && !entity.isBaby() && FOOD_ANIMALS.contains(entity.getType())
					&& !entity.isInWater() && player.hasLineOfSight(entity)).stream()
			.sorted(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)))
			.map(entity -> new ExploreForTaskExecutor.Animal(entity.getId(),
				BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), entity.position()))
			.toList();
	}

	@Override
	public void melee(int entityId) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return;
		Entity target = level.getEntity(entityId);
		if (target == null || !target.isAlive()) return;
		movement.stop(minecraft);
		selectMelee(minecraft, player);
		camera.lookAt(minecraft, target.getBoundingBox().getCenter());
		if (!player.canInteractWithEntity(target.getBoundingBox(), 0.0D)) {
			// Just out of reach: step in.
			movement.moveForward(minecraft, false, false, ticks);
			return;
		}
		if (!camera.isAimingAt(minecraft, target.getBoundingBox()) || player.getAttackStrengthScale(0.0F) < 0.92F) return;
		if (actuator.attack(minecraft, player, target)) player.swing(InteractionHand.MAIN_HAND);
	}

	private void selectMelee(Minecraft minecraft, LocalPlayer player) {
		int best = -1, bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			int rank = SurvivalReflexRuntime.combatItemRank(BuiltInRegistries.ITEM.getKey(player.getInventory().getItem(slot).getItem()).toString());
			if (rank < bestRank) {
				bestRank = rank;
				best = slot;
			}
		}
		if (best >= 0 && best != player.getInventory().getSelectedSlot()) select(minecraft, player, best);
	}

	// ---- shelter ----

	@Override
	public boolean hasRoofBlock() {
		LocalPlayer player = client().player;
		return player != null && roofSlot(player) >= 0;
	}

	@Override
	public ExploreShelter.Plan shelterNear(Vec3 feet) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		return ExploreShelter.near(cells(level, player), BlockPos.containing(feet));
	}

	@Override
	public ExploreForTaskExecutor.ShelterTick digShelter(ExploreShelter.Plan plan) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return ExploreForTaskExecutor.ShelterTick.FAILED;
		BlockPos at = player.blockPosition();
		if (at.getX() != plan.top().getX() || at.getZ() != plan.top().getZ() || at.getY() > plan.top().getY())
			return ExploreForTaskExecutor.ShelterTick.FAILED;
		var step = ExploreShelter.step(plan, at.getY(), player.onGround(), level.getBlockState(plan.roof()).isSolidRender());
		if (step == ExploreShelter.Step.CLOSED) {
			stopDigging();
			movement.stop(minecraft);
			return ExploreForTaskExecutor.ShelterTick.CLOSED;
		}
		if (step == ExploreShelter.Step.WAIT) {
			movement.stop(minecraft);
			return ExploreForTaskExecutor.ShelterTick.WORKING;
		}
		if (step == ExploreShelter.Step.DIG) {
			Vec3 center = Vec3.atBottomCenterOf(at);
			if (Math.hypot(player.getX() - center.x, player.getZ() - center.z) > CENTERED_BLOCKS) {
				// Step onto the middle of the column, or the body stays on the rim when the block under it goes.
				stopDigging();
				camera.lookAt(minecraft, center.add(0.0D, player.getEyeHeight(), 0.0D));
				movement.moveForward(minecraft, false, false, ticks);
				return ExploreForTaskExecutor.ShelterTick.WORKING;
			}
			movement.stop(minecraft);
			BlockPos below = at.below();
			BlockState state = level.getBlockState(below);
			if (state.isAir()) return ExploreForTaskExecutor.ShelterTick.WORKING;
			if (!plan.dig().contains(below)) return ExploreForTaskExecutor.ShelterTick.FAILED;
			selectBestTool(minecraft, player, state);
			camera.lookAt(minecraft, Vec3.atCenterOf(below).add(0.0D, 0.5D, 0.0D));
			if (digging && minecraft.gameMode != null && minecraft.gameMode.isDestroying()) actuator.continueDestroy(minecraft, below, Direction.UP);
			else actuator.startDestroy(minecraft, below, Direction.UP);
			digging = true;
			return ExploreForTaskExecutor.ShelterTick.WORKING;
		}
		stopDigging();
		movement.stop(minecraft);
		int slot = roofSlot(player);
		if (slot < 0) return ExploreForTaskExecutor.ShelterTick.FAILED;
		if (!isRoofBlock(player.getMainHandItem())) {
			select(minecraft, player, slot);
			return ExploreForTaskExecutor.ShelterTick.WORKING;
		}
		Direction face = Direction.getApproximateNearest(
			plan.roof().getX() - plan.roofSupport().getX(), 0, plan.roof().getZ() - plan.roofSupport().getZ());
		Vec3 hit = Vec3.atCenterOf(plan.roofSupport()).add(face.getStepX() * 0.5D, 0.0D, face.getStepZ() * 0.5D);
		camera.lookAt(minecraft, hit);
		if (!camera.isLookingAt(minecraft, hit, ALIGNED_DEGREES * 4)) return ExploreForTaskExecutor.ShelterTick.WORKING;
		actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, plan.roofSupport(), false));
		return ExploreForTaskExecutor.ShelterTick.WORKING;
	}

	@Override
	public void stopDigging() {
		if (digging) actuator.stopDestroy(client());
		digging = false;
	}

	private static ExploreShelter.Cells cells(ClientLevel level, LocalPlayer player) {
		return new ExploreShelter.Cells() {
			@Override public boolean solid(BlockPos pos) {
				return level.hasChunkAt(pos) && level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
			}

			@Override public int breakTicks(BlockPos pos) {
				BlockState state = level.getBlockState(pos);
				float hardness = state.getDestroySpeed(level, pos);
				if (hardness < 0 || state.hasBlockEntity()) return -1;
				if (hardness == 0) return 0;
				float best = 0;
				Inventory inventory = player.getInventory();
				for (int slot = -1; slot < Inventory.INVENTORY_SIZE; slot++) {
					ItemStack tool = slot < 0 ? ItemStack.EMPTY : inventory.getItem(slot);
					float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
					boolean harvests = !state.requiresCorrectToolForDrops() || !tool.isEmpty() && tool.isCorrectToolForDrops(state);
					best = Math.max(best, speed / hardness / (harvests ? 30.0F : 100.0F));
				}
				return best <= 0 ? -1 : (int) Math.ceil(1.0F / best);
			}

			@Override public boolean fluid(BlockPos pos) { return !level.getFluidState(pos).isEmpty(); }

			@Override public boolean falling(BlockPos pos) { return level.getBlockState(pos).getBlock() instanceof FallingBlock; }

			@Override public boolean open(BlockPos pos) {
				return level.hasChunkAt(pos) && MinecraftExploreEnvironment.open(level, pos);
			}
		};
	}

	/** The cheapest carried block that stays put as a roof: never sand or gravel, which would fall onto the head. */
	private static int roofSlot(LocalPlayer player) {
		int best = -1, bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			int rank = roofRank(player.getInventory().getItem(slot));
			if (rank < bestRank) {
				bestRank = rank;
				best = slot;
			}
		}
		return best;
	}

	private static boolean isRoofBlock(ItemStack stack) { return roofRank(stack) < Integer.MAX_VALUE; }

	private static int roofRank(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return Integer.MAX_VALUE;
		Block block = item.getBlock();
		if (block instanceof FallingBlock || block.defaultBlockState().hasBlockEntity()) return Integer.MAX_VALUE;
		if (NavigationPolicies.isThrowaway(stack)) return 0;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		int index = ROOF_BLOCKS.indexOf(id);
		if (index >= 0) return 1 + index;
		if (ItemGroups.contains(ItemGroups.Group.PLANKS, id)) return 100;
		if (ItemGroups.contains(ItemGroups.Group.LOGS, id)) return 200;
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

	// ---- shared ----

	@Override
	public void stopMoving() { movement.stop(client()); }

	@Override
	public void release() {
		stopEating();
		stopDigging();
		movement.stop(client());
	}

	private static int slot(LocalPlayer player, Predicate<ItemStack> matches) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) if (matches.test(player.getInventory().getItem(slot))) return slot;
		return -1;
	}

	private static int count(LocalPlayer player, Predicate<ItemStack> matches) {
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (matches.test(stack)) total += stack.getCount();
		}
		return total;
	}

	private boolean select(Minecraft minecraft, LocalPlayer player, int inventorySlot) {
		if (inventorySlot < 0) return false;
		if (inventorySlot < 9) return actuator.selectHotbar(minecraft, inventorySlot);
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() || minecraft.gameMode == null)
			return false;
		if (inventorySlot < InventoryMenu.INV_SLOT_START || inventorySlot >= InventoryMenu.INV_SLOT_END) return false;
		minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, inventorySlot,
			player.getInventory().getSelectedSlot(), ClickType.SWAP, player);
		return true;
	}

	/** No collision and no fluid. */
	private static boolean open(ClientLevel level, BlockPos pos) {
		return clear(level, pos) && level.getFluidState(pos).isEmpty();
	}

	private static boolean clear(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}
}
