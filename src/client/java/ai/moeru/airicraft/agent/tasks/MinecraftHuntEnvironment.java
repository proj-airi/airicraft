package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.memory.WorldPlacePreservation;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Animal;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.AttackResult;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Body;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Drop;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Fuel;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.SmeltChoice;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.SmeltStart;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.StationSource;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Stock;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Reads a hunt from the client world, as a player would see it, and acts through foreground leases. Client thread only. */
final class MinecraftHuntEnvironment implements HuntFoodTaskExecutor.Environment {
	private static final String OWNER = "hunt_food";
	/** How high above and below the body an animal is still looked for. */
	private static final double VERTICAL_RANGE = 16.0D;
	private static final float ATTACK_READY = 0.92F;
	private final Actuator actuator = new Actuator(OWNER, Priority.FOREGROUND);
	private final MovementController movement = new MovementController(OWNER, Priority.FOREGROUND);
	private final CameraController camera;
	private final SmeltingProcessManager smelting;
	private final SmeltingPlannerService smeltingService = new SmeltingPlannerService();
	private long ticks;

	MinecraftHuntEnvironment(CameraController camera, SmeltingProcessManager smelting) {
		this.camera = Objects.requireNonNull(camera, "camera");
		this.smelting = Objects.requireNonNull(smelting, "smelting");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public Body observe() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		ticks++;
		BlockPos head = BlockPos.containing(player.getEyePosition());
		return new Body(player.position(), player.blockPosition(), player.getHealth(), player.getFoodData().getFoodLevel(),
			level.getDayTime(), level.isRaining(), level.isThundering(), Level.OVERWORLD.equals(level.dimension()), level.canSeeSky(head));
	}

	@Override
	public Stock stock() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return new Stock(Map.of(), Map.of(), 0.0D, 0, 0, List.of());
		Map<String, Integer> raw = new HashMap<>(), cooked = new HashMap<>();
		Map<String, int[]> fuels = new LinkedHashMap<>();
		double weapon = 0.0D;
		int furnaces = 0, material = 0;
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) continue;
			String id = id(stack);
			if (HuntFoodTaskExecutor.COOKED.containsKey(id)) raw.merge(id, stack.getCount(), Integer::sum);
			if (HuntFoodTaskExecutor.COOKED.containsValue(id)) cooked.merge(id, stack.getCount(), Integer::sum);
			if (slot < Inventory.INVENTORY_SIZE && SurvivalReflexRuntime.combatItemRank(id) != Integer.MAX_VALUE)
				weapon = Math.max(weapon, attackDamage(stack));
			if (stack.is(Items.FURNACE)) furnaces += stack.getCount();
			if (stack.is(ItemTags.STONE_CRAFTING_MATERIALS)) material += stack.getCount();
			if (slot < Inventory.INVENTORY_SIZE && level.fuelValues().isFuel(stack)) {
				int[] fuel = fuels.computeIfAbsent(id, ignored -> new int[] {0, level.fuelValues().burnDuration(stack), stack.getMaxStackSize()});
				fuel[0] += stack.getCount();
			}
		}
		List<Fuel> fuelList = new ArrayList<>();
		fuels.forEach((id, fuel) -> fuelList.add(new Fuel(id, fuel[0], fuel[1], fuel[2])));
		return new Stock(raw, cooked, weapon, furnaces, material, fuelList);
	}

	/** Attack damage in the main hand, as {@code UnarmedFlight} counts it: the fist's 1 plus the item's flat bonus. */
	private static double attackDamage(ItemStack stack) {
		double[] damage = {1.0D};
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (attribute.value() == Attributes.ATTACK_DAMAGE.value() && modifier.operation() == AttributeModifier.Operation.ADD_VALUE)
				damage[0] += modifier.amount();
		});
		return damage[0];
	}

	@Override
	public List<Animal> animals(double maxDistance) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return List.of();
		AABB area = player.getBoundingBox().inflate(maxDistance, VERTICAL_RANGE, maxDistance);
		List<Animal> animals = new ArrayList<>();
		for (var animal : level.getEntitiesOfClass(net.minecraft.world.entity.animal.Animal.class, area,
			entity -> entity.isAlive() && HuntFoodTaskExecutor.FOOD_ANIMALS.contains(type(entity)))) {
			Animal view = view(level, player, animal);
			if (view.distance() <= maxDistance) animals.add(view);
		}
		return animals;
	}

	@Override
	public Animal animal(int id) {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		Entity entity = level.getEntity(id);
		if (!(entity instanceof net.minecraft.world.entity.animal.Animal animal) || entity.isRemoved()) return null;
		return view(level, player, animal);
	}

	private static Animal view(ClientLevel level, LocalPlayer player, net.minecraft.world.entity.animal.Animal animal) {
		Vec3 eye = player.getEyePosition();
		boolean dead = !animal.isAlive() || animal.isDeadOrDying();
		boolean named = animal.hasCustomName() || animal instanceof Leashable leashable && leashable.isLeashed();
		return new Animal(animal.getId(), type(animal), animal.isBaby(), named, WorldPlacePreservation.contains(level, animal.blockPosition()),
			animal.position(), eye.distanceTo(animal.getBoundingBox().getCenter()), animal.getY() - player.getY(),
			player.hasLineOfSight(animal), animal.isInWater(), inReach(level, player, animal), dead);
	}

	private static String type(Entity entity) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
	}

	/** Within the player's entity reach, with no block between the eyes and the middle of the animal. */
	private static boolean inReach(ClientLevel level, LocalPlayer player, Entity target) {
		if (!player.canInteractWithEntity(target.getBoundingBox(), 0.0D)) return false;
		Vec3 start = player.getEyePosition(), end = target.getBoundingBox().getCenter();
		HitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		return hit == null || hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(start) + 0.25D >= end.distanceToSqr(start);
	}

	@Override
	public AttackResult attack(int id) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || !(level.getEntity(id) instanceof net.minecraft.world.entity.animal.Animal animal)
			|| !animal.isAlive() || !inReach(level, player, animal)) return AttackResult.OUT_OF_REACH;
		movement.stop(minecraft);
		selectWeapon(minecraft, player);
		camera.lookAt(minecraft, animal.getBoundingBox().getCenter());
		if (!camera.isAimingAt(minecraft, animal.getBoundingBox())) return AttackResult.AIMING;
		if (player.getAttackStrengthScale(0.0F) < ATTACK_READY) return AttackResult.COOLDOWN;
		if (!actuator.attack(minecraft, player, animal)) return AttackResult.COOLDOWN;
		player.swing(InteractionHand.MAIN_HAND);
		return AttackResult.HIT;
	}

	@Override
	public boolean stepToward(int id) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null || !(level.getEntity(id) instanceof net.minecraft.world.entity.animal.Animal animal)) return false;
		if (movement.snapshot().stuck() || EntityInteractionTaskExecutor.directChaseStepUnsafe(level, player.position(), animal.position())
			|| nextStepWet(level, player.position(), animal.position())) {
			movement.stop(minecraft);
			return false;
		}
		camera.lookAt(minecraft, animal.getBoundingBox().getCenter());
		movement.moveForward(minecraft, false, false, ticks);
		return true;
	}

	/** Whether the next block straight toward the target holds water: the route avoids it, the straight walk must too. */
	private static boolean nextStepWet(ClientLevel level, Vec3 position, Vec3 target) {
		Vec3 heading = new Vec3(target.x - position.x, 0.0D, target.z - position.z);
		if (heading.lengthSqr() < 1.0E-6D) return false;
		heading = heading.normalize();
		BlockPos feet = BlockPos.containing(position.x + heading.x, position.y, position.z + heading.z);
		return !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.below()).isEmpty() && level.getBlockState(feet.below())
			.getCollisionShape(level, feet.below()).isEmpty();
	}

	@Override
	public List<Drop> drops(Vec3 center, double radius) {
		ClientLevel level = client().level;
		if (level == null || center == null) return List.of();
		AABB area = new AABB(center, center).inflate(radius, 3.0D, radius);
		return level.getEntitiesOfClass(ItemEntity.class, area, item -> item.isAlive() && !item.getItem().isEmpty()).stream()
			.map(item -> new Drop(item.position(), safeDrop(level, item)))
			.toList();
	}

	private static boolean safeDrop(ClientLevel level, ItemEntity item) {
		BlockPos cell = BlockPos.containing(item.position().add(0.0D, 0.1D, 0.0D));
		return level.hasChunkAt(cell) && !CoverCells.hazardNear(level, cell, 1)
			&& !CoverCells.edgeDrop(level, cell, HuntFoodTaskExecutor.MAX_SAFE_FALL);
	}

	@Override
	public List<SmeltChoice> smeltChoices(String rawItemId, long tick) {
		Minecraft minecraft = client();
		List<SmeltChoice> choices = new ArrayList<>();
		for (SmeltingOption option : smeltingService.inspectOpportunities(minecraft, smelting, tick).availableSmelts()) {
			if (!option.inputItemId().equals(rawItemId)) continue;
			SmeltingStationCandidate candidate = option.stationCandidate();
			if (candidate.state() != SmeltingStationState.EMPTY && candidate.state() != SmeltingStationState.AIRICRAFT_OWNED) continue;
			StationSource source = switch (candidate.source()) {
				case OPEN_SCREEN -> StationSource.OPEN_SCREEN;
				case NEARBY_EXISTING -> StationSource.NEARBY;
				case PLACE_FROM_INVENTORY -> StationSource.PLACE;
			};
			choices.add(new SmeltChoice(option.optionId(), source, candidate.kind().name().toLowerCase(java.util.Locale.ROOT),
				option.cookTimeTicks(), option.maxInputQuantity()));
		}
		return choices;
	}

	@Override
	public SmeltStart startSmelt(String optionId, int quantity, String fuelItemId, int fuelQuantity, long tick) {
		SmeltingActionResult result = smeltingService.startSmelting(client(), smelting,
			new SmeltItemsStepArgs(optionId, quantity, SmeltingFuelMode.MANUAL, fuelItemId, fuelQuantity, null), tick);
		if (result.accepted() && !result.confirmationRequired() && result.processId() != null) return new SmeltStart(result.processId(), null);
		return new SmeltStart(null, result.confirmationRequired() ? "station_occupied" : result.errorCode());
	}

	@Override
	public boolean smeltReady(String processId, long tick) {
		smeltingService.pollTrackedOutputReady(client(), smelting, tick);
		for (SmeltingProcessSnapshot process : smelting.processSnapshots()) {
			if (process.processId().equals(processId)) return process.outputReady();
		}
		return true;
	}

	/** Puts the best melee item in the main hand, as the survival reflex ranks them; nothing changes without one. */
	private void selectWeapon(Minecraft minecraft, LocalPlayer player) {
		Inventory inventory = player.getInventory();
		int best = -1, bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			int rank = SurvivalReflexRuntime.combatItemRank(id(inventory.getItem(slot)));
			if (rank < bestRank) {
				bestRank = rank;
				best = slot;
			}
		}
		if (best < 0 || best == inventory.getSelectedSlot()) return;
		if (best < Inventory.SELECTION_SIZE) {
			actuator.selectHotbarAndSync(minecraft, best);
			return;
		}
		if (player.containerMenu != player.inventoryMenu || !player.containerMenu.getCarried().isEmpty() || minecraft.gameMode == null)
			return;
		if (best < InventoryMenu.INV_SLOT_START || best >= InventoryMenu.INV_SLOT_END) return;
		int selected = inventory.getSelectedSlot();
		minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, best, selected, ClickType.SWAP, player);
		actuator.syncHotbar(minecraft, selected);
	}

	private static String id(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	@Override
	public void stop() {
		movement.stop(client());
	}

	@Override
	public void release() {
		stop();
	}
}
