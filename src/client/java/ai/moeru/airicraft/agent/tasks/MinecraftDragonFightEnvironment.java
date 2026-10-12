package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reads the End fight from the client world and acts through the foreground lease. */
final class MinecraftDragonFightEnvironment implements DragonFightTaskExecutor.Environment {
	/** The dragon perches on the exit portal at the fight origin, which is the world origin in vanilla. */
	private static final int PORTAL_X = 0, PORTAL_Z = 0;
	private static final double SCAN_RADIUS = 192.0D;
	private static final float ALIGNED_DEGREES = 0.6F;
	private final Actuator actuator = new Actuator("dragon_fight", Priority.FOREGROUND);
	private final CameraController camera;
	private Vec3 aimPoint;
	private int flightTicks;
	private boolean drawing;

	MinecraftDragonFightEnvironment(CameraController camera) {
		this.camera = java.util.Objects.requireNonNull(camera, "camera");
	}

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public DragonFightTaskExecutor.Fight observe() {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return null;
		boolean inEnd = level.dimension() == Level.END;
		AABB area = player.getBoundingBox().inflate(SCAN_RADIUS, 128.0D, SCAN_RADIUS);
		DragonFightTaskExecutor.Dragon dragon = level.getEntitiesOfClass(EnderDragon.class, area, e -> true).stream()
			.min(Comparator.comparingDouble(player::distanceToSqr))
			.map(MinecraftDragonFightEnvironment::dragon).orElse(null);
		List<DragonFightTaskExecutor.Crystal> crystals = level.getEntitiesOfClass(EndCrystal.class, area, Entity::isAlive).stream()
			.map(crystal -> new DragonFightTaskExecutor.Crystal(crystal.getStringUUID(), crystal.position(), caged(level, crystal)))
			.toList();
		List<DragonHazards.Cloud> clouds = level.getEntitiesOfClass(AreaEffectCloud.class, player.getBoundingBox().inflate(48.0D),
				cloud -> cloud.isAlive() && cloud.getParticle().getType() == ParticleTypes.DRAGON_BREATH).stream()
			.map(cloud -> new DragonHazards.Cloud(cloud.position(), cloud.getRadius())).toList();
		List<DragonHazards.Fireball> fireballs = level.getEntitiesOfClass(DragonFireball.class, player.getBoundingBox().inflate(64.0D),
				Entity::isAlive).stream()
			.map(fireball -> new DragonHazards.Fireball(fireball.position(), fireball.getDeltaMovement())).toList();
		return new DragonFightTaskExecutor.Fight(inEnd, player.position(), player.getHealth(), dragon, crystals, clouds, fireballs,
			bowSlot(player) >= 0 && hasArrows(player), footing(level, player));
	}

	/** Whether the player stands on the island's end stone; null in the air, where the last footing still holds. */
	private static Boolean footing(ClientLevel level, LocalPlayer player) {
		if (!player.onGround()) return null;
		BlockPos feet = player.blockPosition();
		for (BlockPos below = feet.below(); below.getY() >= feet.getY() - 2; below = below.below()) {
			var state = level.getBlockState(below);
			if (!state.getCollisionShape(level, below).isEmpty()) return state.is(Blocks.END_STONE);
		}
		return null;
	}

	private static DragonFightTaskExecutor.Dragon dragon(EnderDragon dragon) {
		EnderDragonPhase<?> phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
		boolean perched = phase == EnderDragonPhase.LANDING || dragon.getPhaseManager().getCurrentPhase().isSitting();
		return new DragonFightTaskExecutor.Dragon(dragon.isAlive() && dragon.getHealth() > 0.0F, phase == EnderDragonPhase.DYING,
			perched, dragon.getHealth(), phase.toString().replaceAll(" \\(#\\d+\\)$", ""), dragon.position(), dragon.head.getBoundingBox().getCenter());
	}

	/** The two lowest pillars cage their crystal in iron bars; arrows cannot pass them. */
	private static boolean caged(ClientLevel level, EndCrystal crystal) {
		BlockPos base = crystal.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(base.offset(-2, -1, -2), base.offset(2, 3, 2)))
			if (level.getBlockState(pos).is(Blocks.IRON_BARS)) return true;
		return false;
	}

	@Override
	public Optional<GoalPosition> escapePosition(DragonFightTaskExecutor.Fight fight, Vec3 avoid) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		BlockPos feet = BlockPos.containing(fight.feet());
		List<Vec3> candidates = new ArrayList<>();
		for (int radius = 2; radius <= 14; radius += 2) {
			int samples = Math.max(8, radius * 4);
			for (int i = 0; i < samples; i++) {
				double angle = Math.PI * 2.0D * i / samples;
				int x = feet.getX() + (int) Math.round(Math.cos(angle) * radius);
				int z = feet.getZ() + (int) Math.round(Math.sin(angle) * radius);
				ground(level, x, z, feet.getY() - 4, feet.getY() + 4).ifPresent(y -> candidates.add(new Vec3(x + 0.5D, y, z + 0.5D)));
			}
		}
		return DragonHazards.escape(fight.feet(), candidates, fight.clouds(), fight.fireballs(), avoid, ignored -> true)
			.map(MinecraftDragonFightEnvironment::goal);
	}

	@Override
	public Optional<GoalPosition> perchApproach(DragonFightTaskExecutor.Fight fight, DragonFightTaskExecutor.Dragon dragon) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		Vec3 head = dragon.head();
		BlockPos below = BlockPos.containing(head);
		Vec3 best = null;
		double bestCost = Double.MAX_VALUE;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dz = -3; dz <= 3; dz++) {
				int x = below.getX() + dx, z = below.getZ() + dz;
				Optional<Integer> y = islandGround(level, x, z, below.getY() - 6, below.getY() + 1);
				if (y.isEmpty()) continue;
				Vec3 spot = new Vec3(x + 0.5D, y.get(), z + 0.5D);
				// Eyes must come within entity reach of the head box.
				if (spot.add(0.0D, 1.62D, 0.0D).distanceTo(head) > 3.6D) continue;
				if (DragonHazards.insideCloud(spot, fight.clouds(), DragonHazards.CLOUD_MARGIN)) continue;
				double cost = spot.distanceTo(fight.feet());
				if (cost < bestCost) {
					bestCost = cost;
					best = spot;
				}
			}
		}
		return Optional.ofNullable(best).map(MinecraftDragonFightEnvironment::goal);
	}

	@Override
	public Optional<GoalPosition> shootingSpot(DragonFightTaskExecutor.Fight fight, DragonFightTaskExecutor.Crystal crystal,
		Set<GoalPosition> rejected) {
		ClientLevel level = client().level;
		LocalPlayer player = client().player;
		if (level == null || player == null) return Optional.empty();
		Vec3 target = target(crystal);
		Vec3 best = null;
		double bestCost = Double.MAX_VALUE;
		for (int distance = 10; distance <= 30; distance += 4) {
			for (int i = 0; i < 24; i++) {
				double angle = Math.PI * 2.0D * i / 24;
				int x = (int) Math.floor(target.x + Math.cos(angle) * distance);
				int z = (int) Math.floor(target.z + Math.sin(angle) * distance);
				Optional<Integer> y = islandGround(level, x, z, level.getMinY(), (int) target.y - 8);
				if (y.isEmpty()) continue;
				Vec3 spot = new Vec3(x + 0.5D, y.get(), z + 0.5D);
				if (rejected.contains(goal(spot)) || spot.distanceTo(fight.feet()) > 96.0D) continue;
				if (DragonHazards.insideCloud(spot, fight.clouds(), DragonHazards.CLOUD_MARGIN)) continue;
				// Walking matters more than the shot angle; closer to the player also keeps the path short.
				double cost = spot.distanceTo(fight.feet()) + Math.abs(distance - 18) * 0.5D;
				if (cost >= bestCost || !clearShot(level, player, spot.add(0.0D, player.getEyeHeight() - 0.1D, 0.0D), target)) continue;
				bestCost = cost;
				best = spot;
			}
		}
		return Optional.ofNullable(best).map(MinecraftDragonFightEnvironment::goal);
	}

	/** Whether the arrow's arc from {@code launch} reaches the crystal without meeting a block. */
	private static boolean clearShot(ClientLevel level, LocalPlayer player, Vec3 launch, Vec3 target) {
		double horizontal = Math.hypot(target.x - launch.x, target.z - launch.z);
		var solution = ArrowBallistics.solve(horizontal, target.y - launch.y, ArrowBallistics.FULL_DRAW_SPEED);
		if (solution.isEmpty()) return false;
		double dirX = (target.x - launch.x) / horizontal, dirZ = (target.z - launch.z) / horizontal;
		List<double[]> path = ArrowBallistics.path(solution.get().elevationDegrees(), horizontal, ArrowBallistics.FULL_DRAW_SPEED);
		Vec3 previous = launch;
		for (int i = 1; i < path.size(); i++) {
			double[] point = path.get(i);
			// Stop short of the crystal: it stands on a bedrock block.
			double along = Math.min(point[0], horizontal - 1.2D);
			Vec3 next = new Vec3(launch.x + dirX * along, launch.y + point[1], launch.z + dirZ * along);
			if (level.clip(new ClipContext(previous, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType()
				!= HitResult.Type.MISS) return false;
			if (along < point[0]) break;
			previous = next;
		}
		return true;
	}

	@Override
	public Optional<GoalPosition> waitingSpot(DragonFightTaskExecutor.Fight fight) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		// Close enough to reach a perch quickly, outside the portal rim where the breath lands.
		Vec3 best = null;
		double bestCost = Double.MAX_VALUE;
		for (int i = 0; i < 16; i++) {
			double angle = Math.PI * 2.0D * i / 16;
			int x = PORTAL_X + (int) Math.round(Math.cos(angle) * 9);
			int z = PORTAL_Z + (int) Math.round(Math.sin(angle) * 9);
			Optional<Integer> y = islandGround(level, x, z, level.getMinY(), 120);
			if (y.isEmpty()) continue;
			Vec3 spot = new Vec3(x + 0.5D, y.get(), z + 0.5D);
			if (DragonHazards.insideCloud(spot, fight.clouds(), DragonHazards.CLOUD_MARGIN)) continue;
			double cost = spot.distanceTo(fight.feet());
			if (cost < bestCost) {
				bestCost = cost;
				best = spot;
			}
		}
		if (best != null) return Optional.of(goal(best));
		return stepTowardPortal(level, fight.feet());
	}

	/**
	 * Arriving on the obsidian platform 100 blocks out, the portal's chunks are not loaded yet: walk up to
	 * 40 blocks toward it, to the nearest loaded ground around that point.
	 */
	private static Optional<GoalPosition> stepTowardPortal(ClientLevel level, Vec3 feet) {
		double distance = Math.hypot(feet.x - PORTAL_X, feet.z - PORTAL_Z);
		if (distance < 12.0D) return Optional.empty();
		double step = Math.min(40.0D, distance - 9.0D);
		double dirX = (PORTAL_X - feet.x) / distance, dirZ = (PORTAL_Z - feet.z) / distance;
		for (double back = 0.0D; back < step; back += 4.0D) {
			int cx = (int) Math.floor(feet.x + dirX * (step - back)), cz = (int) Math.floor(feet.z + dirZ * (step - back));
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					Optional<Integer> y = ground(level, cx + dx, cz + dz, (int) feet.y - 24, (int) feet.y + 24);
					if (y.isPresent()) return Optional.of(new GoalPosition(cx + dx, y.get(), cz + dz, true));
				}
			}
		}
		return Optional.empty();
	}

	@Override
	public DragonFightTaskExecutor.AimResult aimBow(DragonFightTaskExecutor.Crystal crystal) {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null) return DragonFightTaskExecutor.AimResult.NO_SOLUTION;
		Vec3 launch = player.getEyePosition().subtract(0.0D, 0.1D, 0.0D);
		Vec3 target = target(crystal);
		double horizontal = Math.hypot(target.x - launch.x, target.z - launch.z);
		var solution = ArrowBallistics.solve(horizontal, target.y - launch.y, ArrowBallistics.FULL_DRAW_SPEED);
		if (solution.isEmpty()) return DragonFightTaskExecutor.AimResult.NO_SOLUTION;
		double elevation = Math.toRadians(solution.get().elevationDegrees());
		Vec3 direction = new Vec3((target.x - launch.x) / horizontal * Math.cos(elevation), Math.sin(elevation),
			(target.z - launch.z) / horizontal * Math.cos(elevation));
		aimPoint = player.getEyePosition().add(direction.scale(16.0D));
		flightTicks = solution.get().flightTicks();
		camera.lookAt(minecraft, aimPoint);
		return camera.isLookingAt(minecraft, aimPoint, ALIGNED_DEGREES)
			? DragonFightTaskExecutor.AimResult.ALIGNED : DragonFightTaskExecutor.AimResult.AIMING;
	}

	@Override
	public boolean drawBow() {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null) return false;
		if (!player.getMainHandItem().is(Items.BOW)) {
			if (!select(minecraft, player, bowSlot(player))) return false;
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
	public int releaseBow() {
		cancelBow();
		return flightTicks;
	}

	@Override
	public void cancelBow() {
		Minecraft minecraft = client();
		actuator.releaseUse(minecraft);
		if (minecraft.player != null && minecraft.gameMode != null && minecraft.player.isUsingItem()
			&& minecraft.player.getUseItem().is(Items.BOW))
			minecraft.gameMode.releaseUsingItem(minecraft.player);
		drawing = false;
	}

	@Override
	public DragonFightTaskExecutor.MeleeResult meleeHead() {
		Minecraft minecraft = client();
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		if (player == null || level == null) return DragonFightTaskExecutor.MeleeResult.OUT_OF_REACH;
		EnderDragon dragon = level.getEntitiesOfClass(EnderDragon.class, player.getBoundingBox().inflate(16.0D), Entity::isAlive)
			.stream().findFirst().orElse(null);
		if (dragon == null) return DragonFightTaskExecutor.MeleeResult.OUT_OF_REACH;
		AABB head = dragon.head.getBoundingBox();
		if (!player.canInteractWithEntity(head, 0.0D)) return DragonFightTaskExecutor.MeleeResult.OUT_OF_REACH;
		selectMelee(minecraft, player);
		Vec3 point = head.getCenter();
		camera.lookAt(minecraft, point);
		if (!camera.isAimingAt(minecraft, head)) return DragonFightTaskExecutor.MeleeResult.AIMING;
		if (player.getAttackStrengthScale(0.0F) < 0.92F) return DragonFightTaskExecutor.MeleeResult.COOLDOWN;
		if (!actuator.attack(minecraft, player, dragon.head)) return DragonFightTaskExecutor.MeleeResult.COOLDOWN;
		player.swing(InteractionHand.MAIN_HAND);
		return DragonFightTaskExecutor.MeleeResult.HIT;
	}

	@Override
	public void release() {
		cancelBow();
		aimPoint = null;
	}

	private static Vec3 target(DragonFightTaskExecutor.Crystal crystal) {
		return crystal.position().add(0.0D, 1.0D, 0.0D);
	}

	/**
	 * {@link #ground} on the island's own end stone. Pillar tops, the portal's bedrock and the player's own bridges
	 * stand in the air, where one wing hit knocks the player into the void.
	 */
	private static Optional<Integer> islandGround(ClientLevel level, int x, int z, int minY, int maxY) {
		return ground(level, x, z, minY, maxY).filter(y -> level.getBlockState(new BlockPos(x, y - 1, z)).is(Blocks.END_STONE));
	}

	/** The highest standing height at (x, z) between {@code minY} and {@code maxY}: solid below, room for the body. */
	private static Optional<Integer> ground(ClientLevel level, int x, int z, int minY, int maxY) {
		BlockPos column = new BlockPos(x, minY, z);
		if (!level.hasChunkAt(column)) return Optional.empty();
		int top = Math.min(maxY, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
		for (int y = top; y > minY; y--) {
			BlockPos feet = new BlockPos(x, y, z);
			BlockPos below = feet.below();
			if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) continue;
			if (level.getBlockState(below).is(Blocks.END_PORTAL)) continue;
			if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				|| !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
			if (!level.getFluidState(feet).isEmpty()) continue;
			return Optional.of(y);
		}
		return Optional.empty();
	}

	private static GoalPosition goal(Vec3 position) {
		return new GoalPosition((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z), true);
	}

	private static int bowSlot(LocalPlayer player) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
			if (player.getInventory().getItem(slot).is(Items.BOW)) return slot;
		return -1;
	}

	private static boolean hasArrows(LocalPlayer player) {
		if (player.hasInfiniteMaterials()) return true;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW)) return true;
		}
		return false;
	}

	private void selectMelee(Minecraft minecraft, LocalPlayer player) {
		int best = -1, bestRank = Integer.MAX_VALUE;
		for (int slot = 0; slot < net.minecraft.world.entity.player.Inventory.INVENTORY_SIZE; slot++) {
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
		int menuSlot = inventorySlot; // inventory slots 9..35 share their index in the player menu
		if (menuSlot < InventoryMenu.INV_SLOT_START || menuSlot >= InventoryMenu.INV_SLOT_END) return false;
		minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, menuSlot,
			player.getInventory().getSelectedSlot(), ClickType.SWAP, player);
		return true;
	}
}
