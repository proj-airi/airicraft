package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.agent.navigation.NavigationPolicies;
import ai.moeru.airicraft.agent.reflex.EmergencyBunker;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.Arena;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.BreakResult;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.ConsumeResult;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.Dimension;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.DroppedPearl;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.Enderman;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.MeleeResult;
import ai.moeru.airicraft.agent.tasks.CollectEnderPearlsTaskExecutor.PlaceResult;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
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
import java.util.Set;
import java.util.function.Predicate;

/** Reads an enderman hunt from the client world, as a player would see it, and acts through foreground leases. */
final class MinecraftEnderPearlEnvironment implements CollectEnderPearlsTaskExecutor.Environment {
	private static final String OWNER = "collect_ender_pearls";
	/** The slowest block the pit is dug through, in ticks with the best carried tool. */
	private static final int MAX_BREAK_TICKS = 100;
	private static final float PLACE_ALIGNED_DEGREES = 24.0F;
	private static final int CONSUME_TIMEOUT_TICKS = 80;
	/** The swap button that puts a clicked slot in the offhand. */
	private static final int OFFHAND_BUTTON = 40;
	/** Foods that poison, starve or teleport the eater. */
	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
		Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW);
	private final Actuator actuator = new Actuator(OWNER, Priority.FOREGROUND);
	private final MovementController movement = new MovementController(OWNER, Priority.FOREGROUND);
	private final CameraController camera;
	private long ticks;
	private BlockPos breaking;
	private boolean shieldUp;
	private Item consumingItem;
	private int consumingCount, consumingTicks;
	private boolean consumeStarted;

	MinecraftEnderPearlEnvironment(CameraController camera) {
		this.camera = Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public Arena observe() {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return null;
		ticks++;
		Vec3 eye = player.getEyePosition();
		double range = CollectEnderPearlsTaskExecutor.SEARCH_RANGE;
		AABB area = player.getBoundingBox().inflate(range, 24.0D, range);
		List<Enderman> endermen = level.getEntitiesOfClass(EnderMan.class, area, Entity::isAlive).stream()
			.map(enderman -> new Enderman(enderman.getId(), enderman.position(), eye.distanceTo(enderman.getBoundingBox().getCenter()),
				player.hasLineOfSight(enderman), legsInReach(level, player, enderman), enderman.isCreepy()))
			.toList();
		List<DroppedPearl> drops = level.getEntitiesOfClass(ItemEntity.class, area,
				item -> item.isAlive() && item.getItem().is(Items.ENDER_PEARL)).stream()
			.map(item -> new DroppedPearl(item.position(), safeDrop(level, item)))
			.toList();
		BlockPos feet = player.blockPosition();
		long time = level.getDayTime() % 24_000L;
		return new Arena(dimension(level), raining(level, feet), time < 12_500L || time >= 23_500L, player.position(), player.onGround(),
			player.getHealth(), player.getFoodData().getFoodLevel(), count(player, stack -> stack.is(Items.ENDER_PEARL)), endermen, drops,
			hasShield(player), foodSlot(player) >= 0, buildingBlocks(player));
	}

	private static Dimension dimension(Level level) {
		if (level.dimension() == Level.OVERWORLD) return Dimension.OVERWORLD;
		if (level.dimension() == Level.NETHER) return Dimension.NETHER;
		if (level.dimension() == Level.END) return Dimension.END;
		return Dimension.OTHER;
	}

	/** Rain falling in the body's biome: endermen out in it take damage and teleport away. */
	private static boolean raining(ClientLevel level, BlockPos feet) {
		return level.isRaining() && level.dimension() == Level.OVERWORLD
			&& level.getBiome(feet).value().getPrecipitationAt(feet, level.getSeaLevel()) == Biome.Precipitation.RAIN;
	}

	/** The point on an enderman's legs the body aims at: level with the eyes when it can be, never at its head. */
	private static Vec3 legs(LocalPlayer player, Entity enderman) {
		double y = Math.max(enderman.getY() + 0.3D, Math.min(player.getEyeY(), enderman.getY() + 1.4D));
		return new Vec3(enderman.getX(), y, enderman.getZ());
	}

	private static boolean legsInReach(ClientLevel level, LocalPlayer player, Entity enderman) {
		return player.canInteractWithEntity(enderman.getBoundingBox(), 0.0D)
			&& CoverCells.clear(level, player, player.getEyePosition(), legs(player, enderman), null);
	}

	private static boolean safeDrop(ClientLevel level, ItemEntity item) {
		BlockPos cell = BlockPos.containing(item.position().add(0.0D, 0.1D, 0.0D));
		return level.hasChunkAt(cell) && CoverCells.standable(level, cell) && !CoverCells.hazardNear(level, cell, 1)
			&& !CoverCells.edgeDrop(level, cell, CollectEnderPearlsTaskExecutor.MAX_SAFE_FALL);
	}

	// ---- terrain ----

	@Override
	public CollectEnderPearlsTaskExecutor.Cells cells() {
		ClientLevel level = client().level;
		LocalPlayer player = client().player;
		if (level == null || player == null) return null;
		EmergencyBunker.Cells base = EmergencyBunker.cells(level, player);
		return new CollectEnderPearlsTaskExecutor.Cells() {
			@Override public boolean solid(GoalPosition cell) {
				BlockPos pos = block(cell);
				return level.hasChunkAt(pos) && level.getBlockState(pos).isCollisionShapeFullBlock(level, pos);
			}
			@Override public boolean passable(GoalPosition cell) {
				BlockPos pos = block(cell);
				return level.hasChunkAt(pos) && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
			}
			@Override public boolean fluid(GoalPosition cell) {
				BlockPos pos = block(cell);
				return level.hasChunkAt(pos) && !level.getFluidState(pos).isEmpty();
			}
			@Override public boolean hazard(GoalPosition cell) {
				BlockState state = level.getBlockState(block(cell));
				return state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK)
					|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE);
			}
			@Override public boolean diggable(GoalPosition cell) {
				BlockPos pos = block(cell);
				if (!level.hasChunkAt(pos) || WorldPlacePreservation.contains(level, pos) || base.falling(pos)) return false;
				int breakTicks = base.breakTicks(pos);
				return breakTicks >= 0 && breakTicks <= MAX_BREAK_TICKS;
			}
			@Override public boolean yieldsBlock(GoalPosition cell) {
				BlockState state = level.getBlockState(block(cell));
				if (!state.is(BlockTags.BASE_STONE_OVERWORLD) && !state.is(BlockTags.BASE_STONE_NETHER) && !state.is(Blocks.COBBLESTONE)
					&& !state.is(Blocks.END_STONE)) return false;
				for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
					ItemStack stack = player.getInventory().getItem(slot);
					if (!stack.isEmpty() && stack.isCorrectToolForDrops(state)) return true;
				}
				return false;
			}
		};
	}

	@Override
	public PlaceResult place(GoalPosition cell, GoalPosition support) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.gameMode == null) return PlaceResult.WORKING;
		BlockPos target = block(cell);
		BlockPos against = block(support);
		BlockState state = level.getBlockState(target);
		if (state.isCollisionShapeFullBlock(level, target)) return PlaceResult.PLACED;
		if (!(state.isAir() || state.canBeReplaced()) || !level.getFluidState(target).isEmpty()
			|| !level.getBlockState(against).isCollisionShapeFullBlock(level, against) || !player.canInteractWithBlock(against, 0.0D))
			return PlaceResult.BLOCKED;
		stopDigging();
		int slot = buildingSlot(player);
		if (slot < 0) return PlaceResult.NO_BLOCK;
		if (buildingRank(player.getMainHandItem()) == Integer.MAX_VALUE) {
			select(minecraft, player, slot);
			return PlaceResult.WORKING;
		}
		if (player.getMainHandItem().getItem() instanceof BlockItem item
			&& !level.isUnobstructed(item.getBlock().defaultBlockState(), target, CollisionContext.of(player)))
			return PlaceResult.WORKING;
		Direction face = Direction.getApproximateNearest(target.getX() - against.getX(), target.getY() - against.getY(),
			target.getZ() - against.getZ());
		Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
		camera.lookAt(minecraft, hit);
		if (!camera.isLookingAt(minecraft, hit, PLACE_ALIGNED_DEGREES)) return PlaceResult.WORKING;
		InteractionResult result = actuator.useItemOn(minecraft, player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, against, false));
		if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
		return PlaceResult.WORKING;
	}

	@Override
	public BreakResult dig(GoalPosition cell) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || minecraft.gameMode == null) return BreakResult.WORKING;
		BlockPos pos = block(cell);
		BlockState state = level.getBlockState(pos);
		if (state.getCollisionShape(level, pos).isEmpty()) {
			stopDigging();
			return BreakResult.GONE;
		}
		if (state.getDestroySpeed(level, pos) < 0 || WorldPlacePreservation.contains(level, pos)) return BreakResult.UNBREAKABLE;
		if (!player.canInteractWithBlock(pos, 0.0D)) return BreakResult.OUT_OF_REACH;
		movement.stop(minecraft);
		selectBestTool(minecraft, player, state);
		Vec3 eye = player.getEyePosition();
		Direction face = Direction.getApproximateNearest(eye.x - (pos.getX() + 0.5D), eye.y - (pos.getY() + 0.5D), eye.z - (pos.getZ() + 0.5D));
		camera.lookAt(minecraft, Vec3.atCenterOf(pos).add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D));
		// Start once, then continue: the game drops isDestroying every tick the attack key is up.
		if (!pos.equals(breaking)) {
			stopDigging();
			if (!actuator.startDestroy(minecraft, pos, face)) return BreakResult.WORKING;
			breaking = pos;
		} else actuator.continueDestroy(minecraft, pos, face);
		player.swing(InteractionHand.MAIN_HAND);
		return BreakResult.WORKING;
	}

	@Override
	public void stopDigging() {
		if (breaking == null) return;
		breaking = null;
		actuator.stopDestroy(client());
	}

	@Override
	public void nudge(GoalPosition cell) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null) return;
		stopDigging();
		camera.lookAt(minecraft, new Vec3(cell.x() + 0.5D, player.getEyeY(), cell.z() + 0.5D));
		movement.moveForward(minecraft, false, false, ticks);
	}

	// ---- fighting ----

	@Override
	public MeleeResult melee(int endermanId, boolean guard) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return MeleeResult.OUT_OF_REACH;
		Entity enderman = level.getEntity(endermanId);
		if (!(enderman instanceof EnderMan) || !enderman.isAlive() || !legsInReach(level, player, enderman)) return MeleeResult.OUT_OF_REACH;
		stopDigging();
		movement.stop(minecraft);
		selectMelee(minecraft, player);
		camera.lookAt(minecraft, legs(player, enderman));
		if (!camera.isAimingAt(minecraft, enderman.getBoundingBox())) return MeleeResult.AIMING;
		if (player.getAttackStrengthScale(0.0F) < 0.92F) {
			if (guard) raiseShield(minecraft, player);
			return MeleeResult.COOLDOWN;
		}
		lowerShield();
		if (!actuator.attack(minecraft, player, enderman)) return MeleeResult.COOLDOWN;
		player.swing(InteractionHand.MAIN_HAND);
		return MeleeResult.HIT;
	}

	@Override
	public void stare(int endermanId) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || !(level.getEntity(endermanId) instanceof EnderMan enderman)) return;
		lowerShield();
		movement.stop(minecraft);
		camera.lookAt(minecraft, enderman.getEyePosition());
	}

	@Override
	public void watch(int endermanId, boolean guard) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || !(level.getEntity(endermanId) instanceof EnderMan enderman)) return;
		movement.stop(minecraft);
		camera.lookAt(minecraft, legs(player, enderman));
		if (guard) raiseShield(minecraft, player);
		else lowerShield();
	}

	private void raiseShield(Minecraft minecraft, LocalPlayer player) {
		if (minecraft.gameMode == null) return;
		if (!player.getOffhandItem().is(Items.SHIELD)) {
			if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return;
			for (var slot : player.containerMenu.slots) {
				if (slot.container == player.getInventory() && slot.getContainerSlot() < Inventory.INVENTORY_SIZE
					&& slot.getItem().is(Items.SHIELD)) {
					minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, slot.index, OFFHAND_BUTTON,
						ClickType.SWAP, player);
					break;
				}
			}
		}
		ItemStack shield = player.getOffhandItem();
		if (!shield.is(Items.SHIELD) || player.getCooldowns().isOnCooldown(shield)) return;
		ItemStack main = player.getMainHandItem();
		// The use key tries the main hand first: food or a bow there would be used instead of the shield.
		if (main.get(DataComponents.CONSUMABLE) != null || main.is(Items.BOW) || main.is(Items.CROSSBOW) || main.is(Items.TRIDENT)
			|| main.getItem() instanceof BlockItem)
			selectMelee(minecraft, player);
		if (!actuator.holdUse()) return;
		if (!player.isUsingItem() || player.getUsedItemHand() != InteractionHand.OFF_HAND)
			actuator.useItem(minecraft, player, InteractionHand.OFF_HAND);
		shieldUp = true;
	}

	@Override
	public void lowerShield() {
		if (!shieldUp) return;
		shieldUp = false;
		Minecraft minecraft = client();
		actuator.releaseUse(minecraft);
		if (minecraft.player != null && minecraft.gameMode != null && minecraft.player.isUsingItem()
			&& minecraft.player.getUseItem().is(Items.SHIELD))
			minecraft.gameMode.releaseUsingItem(minecraft.player);
	}

	// ---- eating ----

	@Override
	public ConsumeResult consume() {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) return ConsumeResult.NOTHING;
		Predicate<ItemStack> wanted = stack -> consumingItem != null && stack.is(consumingItem);
		if (consumingItem == null || count(player, wanted) == 0) {
			stopConsuming();
			int slot = foodSlot(player);
			if (slot < 0) return ConsumeResult.NOTHING;
			consumingItem = player.getInventory().getItem(slot).getItem();
			consumingCount = count(player, wanted);
		}
		lowerShield();
		stopDigging();
		if (++consumingTicks > CONSUME_TIMEOUT_TICKS) {
			stopConsuming();
			return ConsumeResult.NOTHING;
		}
		if (count(player, wanted) < consumingCount) {
			stopConsuming();
			return ConsumeResult.DONE;
		}
		if (!player.getMainHandItem().is(consumingItem)) {
			int slot = -1;
			for (int i = 0; i < Inventory.INVENTORY_SIZE && slot < 0; i++) if (wanted.test(player.getInventory().getItem(i))) slot = i;
			if (slot < 0) {
				stopConsuming();
				return ConsumeResult.NOTHING;
			}
			select(minecraft, player, slot);
			consumeStarted = false;
			return ConsumeResult.CONSUMING;
		}
		if (!actuator.holdUse()) return ConsumeResult.CONSUMING;
		if (!consumeStarted || !player.isUsingItem()) {
			actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
			consumeStarted = true;
		}
		return ConsumeResult.CONSUMING;
	}

	@Override
	public void stopConsuming() {
		Minecraft minecraft = client();
		if (consumingItem != null) {
			actuator.releaseUse(minecraft);
			if (minecraft.player != null && minecraft.gameMode != null && minecraft.player.isUsingItem()
				&& minecraft.player.getUseItem().is(consumingItem))
				minecraft.gameMode.releaseUsingItem(minecraft.player);
		}
		consumingItem = null;
		consumingCount = consumingTicks = 0;
		consumeStarted = false;
	}

	@Override
	public void release() {
		stopDigging();
		lowerShield();
		stopConsuming();
		movement.stop(client());
	}

	// ---- inventory ----

	/** The carried food with the most nutrition, leaving out foods that harm or teleport. */
	private static int foodSlot(LocalPlayer player) {
		int best = -1, bestNutrition = -1;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			var food = stack.get(DataComponents.FOOD);
			if (stack.isEmpty() || food == null || stack.get(DataComponents.CONSUMABLE) == null || BAD_FOOD.contains(stack.getItem())) continue;
			if (food.nutrition() > bestNutrition) {
				bestNutrition = food.nutrition();
				best = slot;
			}
		}
		return best;
	}

	private static boolean hasShield(LocalPlayer player) {
		ItemStack offhand = player.getOffhandItem();
		if (offhand.is(Items.SHIELD)) return !player.getCooldowns().isOnCooldown(offhand);
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) if (player.getInventory().getItem(slot).is(Items.SHIELD)) return true;
		return false;
	}

	private static int count(LocalPlayer player, Predicate<ItemStack> matches) {
		int total = 0;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (matches.test(stack)) total += stack.getCount();
		}
		if (matches.test(player.getOffhandItem())) total += player.getOffhandItem().getCount();
		return total;
	}

	private static int buildingBlocks(LocalPlayer player) {
		return count(player, stack -> buildingRank(stack) < Integer.MAX_VALUE);
	}

	/** The carried roof block to place, or -1. */
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

	/**
	 * Throwaway stone (cobblestone, netherrack...), then planks, then logs. Never a block an enderman can pick up (dirt,
	 * grass, sand, nylium): one beside the pit would carry the roof away. Never a falling or partial block either.
	 */
	static int buildingRank(ItemStack stack) {
		if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem item)) return Integer.MAX_VALUE;
		Block block = item.getBlock();
		BlockState state = block.defaultBlockState();
		if (block instanceof FallingBlock || state.hasBlockEntity() || state.is(BlockTags.ENDERMAN_HOLDABLE)
			|| !state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) return Integer.MAX_VALUE;
		String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		if (NavigationPolicies.isThrowaway(stack)) return 0;
		if (ItemGroups.contains(ItemGroups.Group.PLANKS, id)) return 1;
		if (ItemGroups.contains(ItemGroups.Group.LOGS, id)) return 2;
		return Integer.MAX_VALUE;
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

	private static BlockPos block(GoalPosition position) {
		return new BlockPos(position.x(), position.y(), position.z());
	}
}
