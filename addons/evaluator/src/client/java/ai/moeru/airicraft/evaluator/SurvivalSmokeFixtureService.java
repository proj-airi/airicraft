package ai.moeru.airicraft.evaluator;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Relative;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Evaluator-only deterministic setup for the live survival reflex smokes. */
final class SurvivalSmokeFixtureService {
	private static final int FIXTURE_Y = 200;
	private static final int PLATFORM_RADIUS = 16;
	private static final int CLEAR_RADIUS = PLATFORM_RADIUS + 1;
	private static final int CLEAR_BOTTOM = FIXTURE_Y - 1;
	private static final int CLEAR_TOP = FIXTURE_Y + 6;
	private static final int FLEE_THREAT_SYNC_DELAY_TICKS = 20;
	/** Just over the low-air reflex threshold (100), so the reflex starts almost at once. */
	private static final int FLOODED_CAVE_AIR_TICKS = 110;

	private Origin origin;
	private BlockPos fixtureCenter;
	private Entity threat;
	private long underwaterExitAtWorldTime = -1L;
	private long fleeThreatSpawnAtWorldTime = -1L;

	void onClientTick(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null || minecraft.getSingleplayerServer() == null) {
			return;
		}
		ServerLevel level = minecraft.getSingleplayerServer().getLevel(minecraft.level.dimension());
		if (level == null) {
			return;
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(minecraft.player.getUUID());
		if (player == null) {
			return;
		}
		if (underwaterExitAtWorldTime >= 0L && level.getGameTime() >= underwaterExitAtWorldTime) {
			cleanup(level, player);
			return;
		}
		if (fleeThreatSpawnAtWorldTime >= 0L && level.getGameTime() >= fleeThreatSpawnAtWorldTime) {
			fleeThreatSpawnAtWorldTime = -1L;
			threat = spawnZombie(level, player);
		}
	}

	Map<String, Object> apply(Request request) {
		Mode mode = request == null ? null : Mode.parse(request.mode());
		if (mode == null) {
			throw new FixtureException("invalid_request",
				"mode must be loadout, underwater, flooded_cave, sunken_ore, mob_defend, mob_flee, mob_flee_natural, or cleanup");
		}

		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || minecraft.getSingleplayerServer() == null) {
			throw new FixtureException("world_not_loaded", "A singleplayer world must be loaded");
		}
		ServerLevel level = minecraft.getSingleplayerServer().getLevel(minecraft.level.dimension());
		if (level == null) {
			throw new FixtureException("world_not_loaded", "The integrated server world is unavailable");
		}
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(minecraft.player.getUUID());
		if (player == null) {
			throw new FixtureException("player_not_loaded", "The integrated server player is unavailable");
		}

		if (mode == Mode.CLEANUP) {
			cleanup(level, player);
			return payload(mode, player, null);
		}

		cleanup(level, player);
		origin = new Origin(level.dimension(), player.position(), player.getYRot(), player.getXRot());
		level.getServer().setDifficulty(Difficulty.NORMAL, false);
		if (mode == Mode.MOB_FLEE_NATURAL) {
			return setupNaturalMobFlee(level, player);
		}
		fixtureCenter = new BlockPos(player.getBlockX(), FIXTURE_Y, player.getBlockZ());
		clearFixture(level);

		return switch (mode) {
			case LOADOUT -> setupLoadout(level, player);
			case UNDERWATER -> setupUnderwater(level, player);
			case FLOODED_CAVE -> setupFloodedCave(level, player);
			case SUNKEN_ORE -> setupSunkenOre(level, player, request);
			case MOB_DEFEND -> setupMob(level, player, true);
			case MOB_FLEE -> setupMob(level, player, false);
			case MOB_FLEE_NATURAL -> throw new IllegalStateException("natural flee handled above");
			case CLEANUP -> throw new IllegalStateException("cleanup handled above");
		};
	}

	private Map<String, Object> setupNaturalMobFlee(ServerLevel level, ServerPlayer player) {
		player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_SWORD));
		player.setDeltaMovement(Vec3.ZERO);
		player.setAirSupply(player.getMaxAirSupply());
		player.setHealth(player.getMaxHealth() * 0.5F);
		BlockPos spawn = findNaturalThreatSpawn(level, player.blockPosition());
		Zombie zombie = spawnZombie(level, player, spawn);
		threat = zombie;
		return payload(Mode.MOB_FLEE_NATURAL, player, zombie.getUUID());
	}

	private Map<String, Object> setupLoadout(ServerLevel level, ServerPlayer player) {
		prepareMobPlatform(level, player);
		player.getInventory().clearContent();
		player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
		player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
		player.getInventory().add(new ItemStack(Items.IRON_CHESTPLATE));
		player.getInventory().add(new ItemStack(Items.IRON_LEGGINGS));
		player.getInventory().add(new ItemStack(Items.IRON_SWORD));
		player.getInventory().add(new ItemStack(Items.BREAD, 2));
		player.getFoodData().setFoodLevel(12);
		player.getFoodData().setSaturation(0.0F);
		player.setHealth(player.getMaxHealth());
		return payload(Mode.LOADOUT, player, null);
	}

	private Map<String, Object> setupUnderwater(ServerLevel level, ServerPlayer player) {
		BlockPos center = fixtureCenter;
		fill(level, center.offset(-4, -1, -4), center.offset(4, -1, 4), Blocks.STONE.defaultBlockState());
		fill(level, center.offset(-2, 0, -2), center.offset(-2, 4, 2), Blocks.GLASS.defaultBlockState());
		fill(level, center.offset(2, 0, -2), center.offset(2, 4, 2), Blocks.GLASS.defaultBlockState());
		fill(level, center.offset(-1, 0, -2), center.offset(1, 4, -2), Blocks.GLASS.defaultBlockState());
		fill(level, center.offset(-1, 0, 2), center.offset(1, 4, 2), Blocks.GLASS.defaultBlockState());
		fill(level, center.offset(-1, 0, -1), center.offset(1, 3, 1), Blocks.WATER.defaultBlockState());
		teleport(player, level, center.getX() + 0.5, center.getY() + 0.1, center.getZ() + 0.5);
		player.setDeltaMovement(Vec3.ZERO);
		player.setHealth(player.getMaxHealth());
		player.setAirSupply(80);
		underwaterExitAtWorldTime = level.getGameTime() + 80L;
		return payload(Mode.UNDERWATER, player, null);
	}

	/**
	 * A sealed stone block with a flooded shaft capped over the player, a one-block-high flooded tunnel
	 * off its foot and an air pocket at the end of the tunnel. Swimming up reaches stone, so only a route
	 * that follows the tunnel in the swimming pose gets the player to air.
	 */
	private Map<String, Object> setupFloodedCave(ServerLevel level, ServerPlayer player) {
		BlockPos center = fixtureCenter;
		var water = Blocks.WATER.defaultBlockState();
		fill(level, center.offset(-2, -1, -3), center.offset(15, 8, 3), Blocks.STONE.defaultBlockState());
		fill(level, center.offset(0, 0, 0), center.offset(0, 4, 0), water);
		fill(level, center.offset(1, 2, 0), center.offset(10, 2, 0), water);
		fill(level, center.offset(11, 2, 0), center.offset(12, 2, 0), water);
		fill(level, center.offset(11, 3, 0), center.offset(12, 3, 0), Blocks.AIR.defaultBlockState());
		teleport(player, level, center.getX() + 0.5, center.getY() + 0.1, center.getZ() + 0.5);
		player.setDeltaMovement(Vec3.ZERO);
		player.setHealth(player.getMaxHealth());
		player.setAirSupply(FLOODED_CAVE_AIR_TICKS);
		return payload(Mode.FLOODED_CAVE, player, null);
	}

	/**
	 * A four-deep pond cut into stone with a coal ore on its floor, the player on the shore with a wooden
	 * pickaxe. The ore can only be mined from the water.
	 */
	private Map<String, Object> setupSunkenOre(ServerLevel level, ServerPlayer player, Request request) {
		BlockPos center = fixtureCenter;
		fill(level, center.offset(-8, -6, -8), center.offset(16, -1, 8), Blocks.STONE.defaultBlockState());
		fill(level, center.offset(2, -4, -3), center.offset(8, -1, 3), Blocks.WATER.defaultBlockState());
		level.setBlockAndUpdate(center.offset(5, -5, 0), Blocks.COAL_ORE.defaultBlockState());
		teleport(player, level, center.getX() + 0.5, center.getY() + 0.1, center.getZ() + 0.5);
		player.setDeltaMovement(Vec3.ZERO);
		player.getInventory().clearContent();
		String pickaxe = request.pickaxe() == null ? "wooden" : request.pickaxe();
		player.getInventory().add(new ItemStack(switch (pickaxe) {
			case "stone" -> Items.STONE_PICKAXE;
			case "iron" -> Items.IRON_PICKAXE;
			default -> Items.WOODEN_PICKAXE;
		}));
		player.setHealth(player.getMaxHealth());
		player.setAirSupply(request.air() == null ? player.getMaxAirSupply() : request.air());
		return payload(Mode.SUNKEN_ORE, player, null);
	}

	private Map<String, Object> setupMob(ServerLevel level, ServerPlayer player, boolean defend) {
		prepareMobPlatform(level, player);
		if (!defend) {
			BlockPos center = fixtureCenter;
			fill(level, center.offset(-15, -2, -3), center.offset(-1, -2, 3), Blocks.STONE.defaultBlockState());
			fill(level, center.offset(-15, -1, -3), center.offset(-1, -1, 3), Blocks.WATER.defaultBlockState());
			player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
			player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
		}
		player.setHealth(defend ? player.getMaxHealth() : player.getMaxHealth() * 0.5F);
		if (!defend) {
			fleeThreatSpawnAtWorldTime = level.getGameTime() + FLEE_THREAT_SYNC_DELAY_TICKS;
			return payload(Mode.MOB_FLEE, player, null);
		}
		Zombie zombie = spawnZombie(level, player);
		threat = zombie;
		return payload(Mode.MOB_DEFEND, player, zombie.getUUID());
	}

	private Zombie spawnZombie(ServerLevel level, ServerPlayer player) {
		return spawnZombie(level, player, fixtureCenter);
	}

	private Zombie spawnZombie(ServerLevel level, ServerPlayer player, BlockPos center) {
		Zombie zombie = new Zombie(EntityType.ZOMBIE, level);
		double xOffset = fixtureCenter == null ? 0.5D : 2.5D;
		zombie.snapTo(center.getX() + xOffset, center.getY(), center.getZ() + 0.5, 90.0F, 0.0F);
		zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
		var attackDamage = zombie.getAttribute(Attributes.ATTACK_DAMAGE);
		if (attackDamage != null) {
			attackDamage.setBaseValue(0.5D);
		}
		zombie.setPersistenceRequired();
		zombie.setTarget(player);
		if (!level.addFreshEntity(zombie)) {
			throw new FixtureException("fixture_setup_failed", "Failed to spawn the survival fixture zombie");
		}
		return zombie;
	}

	private static BlockPos findNaturalThreatSpawn(ServerLevel level, BlockPos playerPos) {
		int[][] offsets = {
			{2, 0}, {-2, 0}, {0, 2}, {0, -2},
			{3, 0}, {-3, 0}, {0, 3}, {0, -3},
			{2, 2}, {-2, 2}, {2, -2}, {-2, -2}
		};
		for (int[] offset : offsets) {
			for (int dy = 2; dy >= -2; dy--) {
				BlockPos feet = playerPos.offset(offset[0], dy, offset[1]);
				if (isNaturalStandingPosition(level, feet)) {
					return feet;
				}
			}
		}
		throw new FixtureException("fixture_setup_failed", "No nearby natural standing position for the survival threat");
	}

	private static boolean isNaturalStandingPosition(ServerLevel level, BlockPos feet) {
		return level.getFluidState(feet).isEmpty()
			&& level.getFluidState(feet.above()).isEmpty()
			&& (level.getBlockState(feet).isAir() || level.getBlockState(feet).canBeReplaced())
			&& (level.getBlockState(feet.above()).isAir() || level.getBlockState(feet.above()).canBeReplaced())
			&& level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP);
	}

	private void prepareMobPlatform(ServerLevel level, ServerPlayer player) {
		BlockPos center = fixtureCenter;
		fill(
			level,
			center.offset(-PLATFORM_RADIUS, -1, -PLATFORM_RADIUS),
			center.offset(PLATFORM_RADIUS, -1, PLATFORM_RADIUS),
			Blocks.STONE.defaultBlockState()
		);
		fill(level, center.offset(-PLATFORM_RADIUS, 0, -PLATFORM_RADIUS), center.offset(-PLATFORM_RADIUS, 2, PLATFORM_RADIUS), Blocks.BARRIER.defaultBlockState());
		fill(level, center.offset(PLATFORM_RADIUS, 0, -PLATFORM_RADIUS), center.offset(PLATFORM_RADIUS, 2, PLATFORM_RADIUS), Blocks.BARRIER.defaultBlockState());
		fill(level, center.offset(-PLATFORM_RADIUS + 1, 0, -PLATFORM_RADIUS), center.offset(PLATFORM_RADIUS - 1, 2, -PLATFORM_RADIUS), Blocks.BARRIER.defaultBlockState());
		fill(level, center.offset(-PLATFORM_RADIUS + 1, 0, PLATFORM_RADIUS), center.offset(PLATFORM_RADIUS - 1, 2, PLATFORM_RADIUS), Blocks.BARRIER.defaultBlockState());
		teleport(player, level, center.getX() + 0.5, center.getY(), center.getZ() + 0.5);
		player.setDeltaMovement(Vec3.ZERO);
		player.setAirSupply(player.getMaxAirSupply());
	}

	private void cleanup(ServerLevel currentLevel, ServerPlayer player) {
		if (threat != null && !threat.isRemoved()) {
			threat.discard();
		}
		threat = null;
		underwaterExitAtWorldTime = -1L;
		fleeThreatSpawnAtWorldTime = -1L;
		Origin savedOrigin = origin;
		if (savedOrigin != null) {
			ServerLevel originLevel = currentLevel.getServer().getLevel(savedOrigin.world());
			if (originLevel != null) {
				teleport(player, originLevel, savedOrigin.position().x, savedOrigin.position().y, savedOrigin.position().z, savedOrigin.yaw(), savedOrigin.pitch());
			}
		}
		if (fixtureCenter != null) {
			clearFixture(currentLevel);
		}
		origin = null;
		fixtureCenter = null;
	}

	private void clearFixture(ServerLevel level) {
		if (fixtureCenter == null) {
			return;
		}
		fill(
			level,
			fixtureCenter.offset(-CLEAR_RADIUS, CLEAR_BOTTOM - FIXTURE_Y, -CLEAR_RADIUS),
			fixtureCenter.offset(CLEAR_RADIUS, CLEAR_TOP - FIXTURE_Y, CLEAR_RADIUS),
			Blocks.AIR.defaultBlockState()
		);
	}

	private static void fill(ServerLevel level, BlockPos from, BlockPos to, net.minecraft.world.level.block.state.BlockState state) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			level.setBlockAndUpdate(pos, state);
		}
	}

	private static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z) {
		teleport(player, level, x, y, z, player.getYRot(), player.getXRot());
	}

	private static void teleport(ServerPlayer player, ServerLevel level, double x, double y, double z, float yaw, float pitch) {
		if (!player.teleportTo(level, x, y, z, Set.<Relative>of(), yaw, pitch, true)) {
			throw new FixtureException("fixture_setup_failed", "Failed to teleport the survival fixture player");
		}
	}

	private Map<String, Object> payload(Mode mode, ServerPlayer player, UUID threatId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("mode", mode.wireValue);
		payload.put("difficulty", player.level().getDifficulty().getKey());
		payload.put("health", player.getHealth());
		payload.put("air", player.getAirSupply());
		payload.put("hunger", player.getFoodData().getFoodLevel());
		payload.put("armor", player.getArmorValue());
		payload.put("mainHandItemId", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
		payload.put("player", position(player.position()));
		payload.put("fixtureCenter", fixtureCenter == null ? Map.of() : position(Vec3.atCenterOf(fixtureCenter)));
		payload.put("threatId", threatId == null ? "" : threatId.toString());
		return payload;
	}

	private static Map<String, Double> position(Vec3 position) {
		return Map.of("x", position.x, "y", position.y, "z", position.z);
	}

	/** {@code pickaxe} (wooden, stone, iron) and {@code air} only shape the sunken_ore mode. */
	record Request(String mode, String pickaxe, Integer air) {
	}

	private record Origin(ResourceKey<Level> world, Vec3 position, float yaw, float pitch) {
	}

	private enum Mode {
		LOADOUT("loadout"),
		UNDERWATER("underwater"),
		FLOODED_CAVE("flooded_cave"),
		SUNKEN_ORE("sunken_ore"),
		MOB_DEFEND("mob_defend"),
		MOB_FLEE("mob_flee"),
		MOB_FLEE_NATURAL("mob_flee_natural"),
		CLEANUP("cleanup");

		private final String wireValue;

		Mode(String wireValue) {
			this.wireValue = wireValue;
		}

		private static Mode parse(String value) {
			if (value == null) {
				return null;
			}
			for (Mode mode : values()) {
				if (mode.wireValue.equals(value.trim().toLowerCase())) {
					return mode;
				}
			}
			return null;
		}
	}

	static final class FixtureException extends RuntimeException {
		private final String code;

		private FixtureException(String code, String message) {
			super(message);
			this.code = code;
		}

		String code() {
			return code;
		}
	}
}
