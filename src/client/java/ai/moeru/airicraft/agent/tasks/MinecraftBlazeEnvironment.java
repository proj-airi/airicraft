package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.llm.WorldFeatureSearchService;
import ai.moeru.airicraft.agent.reflex.ReflexArchery;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Anchor;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Arena;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Blaze;
import ai.moeru.airicraft.agent.tasks.CollectBlazeRodsTaskExecutor.Spot;
import ai.moeru.airicraft.control.Priority;
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
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.SmallFireball;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Reads a blaze fight from the client world, as a player would see it, and acts through foreground leases. */
final class MinecraftBlazeEnvironment implements CollectBlazeRodsTaskExecutor.Environment {
	private static final double ENTITY_RADIUS = 32.0D;
	private static final int SPAWNER_SEARCH_BLOCKS = 64;
	private static final int FORTRESS_SEARCH_BLOCKS = 128;
	private static final float ALIGNED_DEGREES = 1.5F;
	private static final int CONSUME_TIMEOUT_TICKS = 80;
	/** The swap button that puts a clicked slot in the offhand. */
	private static final int OFFHAND_BUTTON = 40;
	/** Foods that poison, starve or teleport the eater. */
	private static final Set<Item> BAD_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.POISONOUS_POTATO, Items.PUFFERFISH,
		Items.CHORUS_FRUIT, Items.SUSPICIOUS_STEW);
	private final Actuator actuator = new Actuator("collect_blaze_rods", Priority.FOREGROUND);
	private final MovementController movement = new MovementController("collect_blaze_rods", Priority.FOREGROUND);
	private final CameraController camera;
	private long ticks;
	private boolean drawing;
	private boolean shieldUp;
	private Item consumingItem;
	private CollectBlazeRodsTaskExecutor.Consumable consumingKind;
	private int consumingCount, consumingTicks;
	private boolean consumeStarted;

	MinecraftBlazeEnvironment(CameraController camera) {
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
		AABB area = player.getBoundingBox().inflate(ENTITY_RADIUS, 24.0D, ENTITY_RADIUS);
		List<Blaze> blazes = level.getEntitiesOfClass(net.minecraft.world.entity.monster.Blaze.class, area, Entity::isAlive).stream()
			.map(blaze -> {
				Vec3 center = blaze.getBoundingBox().getCenter();
				return new Blaze(blaze.getId(), center, eye.distanceTo(center), player.hasLineOfSight(blaze),
					player.canInteractWithEntity(blaze.getBoundingBox(), 0.0D));
			}).toList();
		List<CollectBlazeRodsTaskExecutor.Fireball> fireballs = level.getEntitiesOfClass(SmallFireball.class,
				player.getBoundingBox().inflate(CollectBlazeRodsTaskExecutor.FIREBALL_WATCH_DISTANCE), Entity::isAlive).stream()
			.filter(fireball -> fireball.getOwner() != player)
			.map(fireball -> new CollectBlazeRodsTaskExecutor.Fireball(fireball.getBoundingBox().getCenter(), fireball.getDeltaMovement(),
				fireball.getOwner() == null ? null : fireball.getOwner().getBoundingBox().getCenter()))
			.toList();
		List<CollectBlazeRodsTaskExecutor.DroppedRod> rods = level.getEntitiesOfClass(ItemEntity.class, area,
				item -> item.isAlive() && item.getItem().is(Items.BLAZE_ROD)).stream()
			.map(item -> new CollectBlazeRodsTaskExecutor.DroppedRod(item.position(), safeRod(level, item)))
			.toList();
		return new Arena(level.dimension() == Level.NETHER, player.position(), player.getHealth(),
			player.getFoodData().getFoodLevel(), player.isOnFire(), player.hasEffect(MobEffects.FIRE_RESISTANCE),
			count(player, stack -> stack.is(Items.BLAZE_ROD)), blazes, fireballs, rods,
			slot(player, stack -> stack.is(Items.BOW)) >= 0 && hasArrows(player), hasShield(player),
			foodSlot(player) >= 0, slot(player, MinecraftBlazeEnvironment::fireResistancePotion) >= 0);
	}

	@Override
	public Optional<GoalPosition> blazeSpawner(Vec3 feet) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		return WorldFeatureSearchService.nearest(level, BlockPos.containing(feet), "blaze_spawner", SPAWNER_SEARCH_BLOCKS)
			.map(found -> goal(found.targetPos()));
	}

	@Override
	public Optional<GoalPosition> fortress(Vec3 feet) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		return WorldFeatureSearchService.nearest(level, BlockPos.containing(feet), "nether_fortress", FORTRESS_SEARCH_BLOCKS)
			.map(found -> goal(found.targetPos()));
	}

	@Override
	public List<Spot> fightingSpots(Arena arena, Anchor anchor) {
		ClientLevel level = client().level;
		LocalPlayer player = client().player;
		if (level == null || player == null) return List.of();
		GoalPosition at = anchor.position();
		BlockPos anchorPos = new BlockPos(at.x(), at.y(), at.z());
		Vec3 target = Vec3.atCenterOf(anchorPos);
		int max = (int) Math.ceil(CollectBlazeRodsTaskExecutor.SPOT_MAX_DISTANCE);
		List<Spot> spots = new ArrayList<>();
		for (int dx = -max; dx <= max; dx++) {
			for (int dz = -max; dz <= max; dz++) {
				double horizontal = Math.hypot(dx, dz);
				if (horizontal < CollectBlazeRodsTaskExecutor.SPOT_MIN_DISTANCE - 0.5D || horizontal > max + 0.5D) continue;
				for (int dy = -4; dy <= 6; dy++) {
					BlockPos cell = anchorPos.offset(dx, dy, dz);
					if (!level.hasChunkAt(cell) || !standable(level, cell)) continue;
					Vec3 eye = new Vec3(cell.getX() + 0.5D, cell.getY() + player.getEyeHeight(), cell.getZ() + 0.5D);
					boolean sight = clear(level, player, eye, target, anchor.spawner() ? anchorPos : null);
					spots.add(spot(level, player, cell, arena, sight, Math.hypot(cell.getX() + 0.5D - target.x, cell.getZ() + 0.5D - target.z)));
				}
			}
		}
		return spots;
	}

	@Override
	public List<Spot> coverSpots(Arena arena) {
		ClientLevel level = client().level;
		LocalPlayer player = client().player;
		if (level == null || player == null) return List.of();
		BlockPos feet = BlockPos.containing(arena.feet().add(0.0D, 0.1D, 0.0D));
		int radius = (int) Math.ceil(CollectBlazeRodsTaskExecutor.COVER_RADIUS);
		List<Spot> spots = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (Math.hypot(dx, dz) > radius + 0.5D) continue;
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos cell = feet.offset(dx, dy, dz);
					if (!level.hasChunkAt(cell) || !standable(level, cell)) continue;
					spots.add(spot(level, player, cell, arena, false, 0.0D));
				}
			}
		}
		return spots;
	}

	private Spot spot(ClientLevel level, LocalPlayer player, BlockPos cell, Arena arena, boolean sight, double anchorDistance) {
		Vec3 eye = new Vec3(cell.getX() + 0.5D, cell.getY() + player.getEyeHeight(), cell.getZ() + 0.5D);
		int seen = 0;
		for (Blaze blaze : arena.blazes()) if (clear(level, player, eye, blaze.position(), null)) seen++;
		int cover = 0;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos head = cell.above().relative(side);
			if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) cover++;
		}
		BlockPos roof = cell.above(2);
		if (!level.getBlockState(roof).getCollisionShape(level, roof).isEmpty()) cover += 2;
		return new Spot(goal(cell), true, hazardNear(level, cell, 2), edgeDrop(level, cell), sight, seen, cover, anchorDistance);
	}

	@Override
	public CollectBlazeRodsTaskExecutor.StrafeOptions strafeOptions(Vec3 feet, Vec3 facing) {
		ClientLevel level = client().level;
		if (level == null) return new CollectBlazeRodsTaskExecutor.StrafeOptions(false, false);
		Vec3 left = new Vec3(facing.z, 0.0D, -facing.x);
		return new CollectBlazeRodsTaskExecutor.StrafeOptions(safeStep(level, feet, left), safeStep(level, feet, left.reverse()));
	}

	/** Whether a step and a half this way stays on level, safe ground with nothing in the way. */
	private static boolean safeStep(ClientLevel level, Vec3 feet, Vec3 direction) {
		for (double distance : new double[] {0.8D, 1.5D}) {
			BlockPos cell = BlockPos.containing(feet.add(direction.scale(distance)).add(0.0D, 0.1D, 0.0D));
			if (!level.hasChunkAt(cell) || !standable(level, cell) || hazardNear(level, cell, 1) || edgeDrop(level, cell)) return false;
		}
		return true;
	}

	@Override
	public boolean raiseShield(Vec3 face) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) return false;
		if (!player.getOffhandItem().is(Items.SHIELD)) {
			if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty()) return false;
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
		if (!shield.is(Items.SHIELD) || player.getCooldowns().isOnCooldown(shield)) return false;
		cancelBow();
		ItemStack main = player.getMainHandItem();
		// The use key tries the main hand first: a bow or food there would be used instead of the shield.
		if (main.get(DataComponents.CONSUMABLE) != null || main.is(Items.BOW) || main.is(Items.CROSSBOW) || main.is(Items.TRIDENT))
			selectMelee(minecraft, player);
		camera.lookAt(minecraft, new Vec3(face.x, player.getEyeY(), face.z));
		if (!actuator.holdUse()) return false;
		if (!player.isUsingItem() || player.getUsedItemHand() != InteractionHand.OFF_HAND)
			actuator.useItem(minecraft, player, InteractionHand.OFF_HAND);
		shieldUp = true;
		return true;
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

	@Override
	public void strafe(CollectBlazeRodsTaskExecutor.Side side, Vec3 face) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || side == CollectBlazeRodsTaskExecutor.Side.NONE) return;
		camera.lookAt(minecraft, new Vec3(face.x, player.getEyeY(), face.z));
		movement.moveDirectional(minecraft, false, false, side == CollectBlazeRodsTaskExecutor.Side.LEFT,
			side == CollectBlazeRodsTaskExecutor.Side.RIGHT, false, false, ticks);
	}

	@Override
	public void stopMoving() {
		movement.stop(client());
	}

	@Override
	public CollectBlazeRodsTaskExecutor.MeleeResult melee(int blazeId) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return CollectBlazeRodsTaskExecutor.MeleeResult.OUT_OF_REACH;
		Entity blaze = level.getEntity(blazeId);
		if (blaze == null || !blaze.isAlive() || !player.canInteractWithEntity(blaze.getBoundingBox(), 0.0D))
			return CollectBlazeRodsTaskExecutor.MeleeResult.OUT_OF_REACH;
		cancelBow();
		selectMelee(minecraft, player);
		camera.lookAt(minecraft, blaze.getBoundingBox().getCenter());
		if (!camera.isAimingAt(minecraft, blaze.getBoundingBox())) return CollectBlazeRodsTaskExecutor.MeleeResult.AIMING;
		if (player.getAttackStrengthScale(0.0F) < 0.92F) return CollectBlazeRodsTaskExecutor.MeleeResult.COOLDOWN;
		if (!actuator.attack(minecraft, player, blaze)) return CollectBlazeRodsTaskExecutor.MeleeResult.COOLDOWN;
		player.swing(InteractionHand.MAIN_HAND);
		return CollectBlazeRodsTaskExecutor.MeleeResult.HIT;
	}

	@Override
	public CollectBlazeRodsTaskExecutor.AimResult aimBow(int blazeId) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || !(level.getEntity(blazeId) instanceof LivingEntity blaze) || !blaze.isAlive())
			return CollectBlazeRodsTaskExecutor.AimResult.NO_SOLUTION;
		// The same leading ballistic arc the reflex shoots with: a blaze bobs and drifts while the arrow flies.
		Vec3 aim = ReflexArchery.aimPoint(minecraft, player, player.getEyePosition().subtract(0.0D, 0.1D, 0.0D), blaze);
		if (aim == null) return CollectBlazeRodsTaskExecutor.AimResult.NO_SOLUTION;
		camera.lookAt(minecraft, aim);
		return camera.isLookingAt(minecraft, aim, ALIGNED_DEGREES)
			? CollectBlazeRodsTaskExecutor.AimResult.ALIGNED : CollectBlazeRodsTaskExecutor.AimResult.AIMING;
	}

	@Override
	public boolean drawBow() {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) return false;
		lowerShield();
		if (!player.getMainHandItem().is(Items.BOW)) {
			if (!select(minecraft, player, slot(player, stack -> stack.is(Items.BOW)))) return false;
			drawing = false;
			return true;
		}
		if (!drawing) {
			if (!actuator.holdUse()) return true;
			actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
			drawing = true;
			return true;
		}
		actuator.holdUse();
		return true;
	}

	@Override
	public void releaseBow() {
		cancelBow();
	}

	@Override
	public void cancelBow() {
		Minecraft minecraft = client();
		if (drawing) actuator.releaseUse(minecraft);
		if (minecraft.player != null && minecraft.gameMode != null && minecraft.player.isUsingItem()
			&& minecraft.player.getUseItem().is(Items.BOW))
			minecraft.gameMode.releaseUsingItem(minecraft.player);
		drawing = false;
	}

	@Override
	public CollectBlazeRodsTaskExecutor.ConsumeResult consume(CollectBlazeRodsTaskExecutor.Consumable kind) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) return CollectBlazeRodsTaskExecutor.ConsumeResult.NOTHING;
		Predicate<ItemStack> wanted = kind == CollectBlazeRodsTaskExecutor.Consumable.FOOD
			? stack -> consumingItem != null && stack.is(consumingItem)
			: MinecraftBlazeEnvironment::fireResistancePotion;
		if (consumingKind != kind || consumingItem == null || count(player, wanted) == 0) {
			stopConsuming();
			int slot = kind == CollectBlazeRodsTaskExecutor.Consumable.FOOD ? foodSlot(player) : slot(player, wanted);
			if (slot < 0) return CollectBlazeRodsTaskExecutor.ConsumeResult.NOTHING;
			consumingKind = kind;
			consumingItem = player.getInventory().getItem(slot).getItem();
			consumingCount = count(player, wanted);
		}
		lowerShield();
		cancelBow();
		if (++consumingTicks > CONSUME_TIMEOUT_TICKS) {
			stopConsuming();
			return CollectBlazeRodsTaskExecutor.ConsumeResult.NOTHING;
		}
		if (count(player, wanted) < consumingCount) {
			stopConsuming();
			return CollectBlazeRodsTaskExecutor.ConsumeResult.DONE;
		}
		if (!player.getMainHandItem().is(consumingItem)) {
			if (!select(minecraft, player, slot(player, wanted))) {
				stopConsuming();
				return CollectBlazeRodsTaskExecutor.ConsumeResult.NOTHING;
			}
			consumeStarted = false;
			return CollectBlazeRodsTaskExecutor.ConsumeResult.CONSUMING;
		}
		if (!actuator.holdUse()) return CollectBlazeRodsTaskExecutor.ConsumeResult.CONSUMING;
		if (!consumeStarted || !player.isUsingItem()) {
			actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
			consumeStarted = true;
		}
		return CollectBlazeRodsTaskExecutor.ConsumeResult.CONSUMING;
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
		consumingKind = null;
		consumingCount = consumingTicks = 0;
		consumeStarted = false;
	}

	@Override
	public void release() {
		cancelBow();
		lowerShield();
		stopConsuming();
		movement.stop(client());
	}

	// ---- terrain ----

	/** Solid floor (not magma), room for the body, no fluid or fire in it. */
	private static boolean standable(ClientLevel level, BlockPos cell) {
		BlockPos below = cell.below();
		BlockState floor = level.getBlockState(below);
		if (!floor.isFaceSturdy(level, below, Direction.UP) || floor.is(Blocks.MAGMA_BLOCK) || !floor.getFluidState().isEmpty()) return false;
		for (BlockPos body : List.of(cell, cell.above())) {
			BlockState state = level.getBlockState(body);
			if (!state.getCollisionShape(level, body).isEmpty() || !state.getFluidState().isEmpty() || state.is(BlockTags.FIRE)
				|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return false;
		}
		return true;
	}

	/** Lava, fire or magma within {@code radius} blocks to the side, two below or one above. */
	private static boolean hazardNear(ClientLevel level, BlockPos cell, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-radius, -2, -radius), cell.offset(radius, 1, radius))) {
			BlockState state = level.getBlockState(pos);
			if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK)) return true;
		}
		return false;
	}

	/** A neighbouring open cell whose floor is more than the safe fall below: a knockback off the edge. */
	private static boolean edgeDrop(ClientLevel level, BlockPos cell) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos side = cell.offset(dx, 0, dz);
				if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()
					|| !level.getBlockState(side.above()).getCollisionShape(level, side.above()).isEmpty()) continue;
				boolean floor = false;
				for (int down = 1; down <= CollectBlazeRodsTaskExecutor.MAX_SAFE_FALL + 1 && !floor; down++) {
					BlockPos below = side.below(down);
					BlockState state = level.getBlockState(below);
					if (state.getFluidState().is(FluidTags.LAVA)) break;
					floor = state.isFaceSturdy(level, below, Direction.UP);
				}
				if (!floor) return true;
			}
		}
		return false;
	}

	/** A rod on standable ground with no lava or fire beside it. */
	private static boolean safeRod(ClientLevel level, ItemEntity item) {
		BlockPos cell = BlockPos.containing(item.position().add(0.0D, 0.1D, 0.0D));
		return level.hasChunkAt(cell) && standable(level, cell) && !hazardNear(level, cell, 1) && !edgeDrop(level, cell);
	}

	/** Whether nothing solid stands between the points; a hit on {@code allowed} (the spawner itself) still counts as seen. */
	private static boolean clear(ClientLevel level, LocalPlayer player, Vec3 from, Vec3 to, BlockPos allowed) {
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit.getType() == HitResult.Type.MISS || allowed != null && hit.getBlockPos().equals(allowed);
	}

	// ---- inventory ----

	private static boolean fireResistancePotion(ItemStack stack) {
		if (!stack.is(Items.POTION)) return false;
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		if (contents == null) return false;
		for (var effect : contents.getAllEffects()) if (effect.getEffect().value() == MobEffects.FIRE_RESISTANCE.value()) return true;
		return false;
	}

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
		return slot(player, stack -> stack.is(Items.SHIELD)) >= 0;
	}

	private static boolean hasArrows(LocalPlayer player) {
		return player.hasInfiniteMaterials()
			|| slot(player, stack -> stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)) >= 0
			|| player.getOffhandItem().is(Items.ARROW);
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
		if (matches.test(player.getOffhandItem())) total += player.getOffhandItem().getCount();
		return total;
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

	/** Puts an inventory slot in the hand: selects it on the hotbar, or swaps it into the selected slot. */
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

	private static GoalPosition goal(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true);
	}
}
