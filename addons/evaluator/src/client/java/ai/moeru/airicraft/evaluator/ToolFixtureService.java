package ai.moeru.airicraft.evaluator;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Evaluator-only scratch arena for driving planner tools without a model: a stone floor with walls high above
 * the player's origin, inventory and entity setup, and a readback of inventory, blocks and entities so a
 * driver can judge each tool by its effect on the world. Coordinates are absolute.
 */
final class ToolFixtureService {
	private static final int ARENA_Y = 200;
	private static final int RADIUS = 10;
	private static final int HEIGHT = 10;
	private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS;

	private Origin origin;
	private BlockPos center;
	private final List<UUID> spawned = new ArrayList<>();

	Map<String, Object> apply(Request request) {
		String action = request == null || request.action() == null ? "" : request.action().trim().toLowerCase(Locale.ROOT);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || minecraft.getSingleplayerServer() == null) {
			throw new FixtureException("world_not_loaded", "A singleplayer world must be loaded");
		}
		ServerLevel level = minecraft.getSingleplayerServer().getLevel(minecraft.level.dimension());
		if (level == null) throw new FixtureException("world_not_loaded", "The integrated server world is unavailable");
		ServerPlayer player = level.getServer().getPlayerList().getPlayer(minecraft.player.getUUID());
		if (player == null) throw new FixtureException("player_not_loaded", "The integrated server player is unavailable");

		return switch (action) {
			case "arena" -> arena(level, player, request);
			case "clear" -> {
				player.getInventory().clearContent();
				yield state(level, player, null);
			}
			case "give" -> {
				give(player, request.items());
				yield state(level, player, null);
			}
			case "set_blocks" -> {
				setBlocks(level, request.blocks());
				yield state(level, player, null);
			}
			case "spawn" -> spawn(level, request);
			case "hunger" -> {
				player.getFoodData().setFoodLevel(request.hunger() == null ? 20 : request.hunger());
				player.getFoodData().setSaturation(0.0F);
				yield state(level, player, null);
			}
			case "state" -> state(level, player, request);
			case "cleanup" -> {
				cleanup(level, player);
				yield Map.of("available", true, "action", "cleanup");
			}
			default -> throw new FixtureException("invalid_request",
				"action must be arena, clear, give, set_blocks, spawn, hunger, state, or cleanup");
		};
	}

	private Map<String, Object> arena(ServerLevel currentLevel, ServerPlayer player, Request request) {
		cleanup(currentLevel, player);
		origin = new Origin(currentLevel.dimension(), player.position(), player.getYRot(), player.getXRot());
		ServerLevel level = currentLevel;
		center = new BlockPos(Mth.floor(origin.position().x), ARENA_Y, Mth.floor(origin.position().z));
		level.getServer().setDifficulty("normal".equalsIgnoreCase(request.difficulty()) ? Difficulty.NORMAL : Difficulty.PEACEFUL, false);
		level.setDayTime(1000L);
		fill(level, center.offset(-RADIUS, -1, -RADIUS), center.offset(RADIUS, -1, RADIUS), Blocks.STONE);
		fill(level, center.offset(-RADIUS, 0, -RADIUS), center.offset(RADIUS, HEIGHT, RADIUS), Blocks.AIR);
		fill(level, center.offset(-RADIUS - 1, 0, -RADIUS - 1), center.offset(-RADIUS - 1, 3, RADIUS + 1), Blocks.BARRIER);
		fill(level, center.offset(RADIUS + 1, 0, -RADIUS - 1), center.offset(RADIUS + 1, 3, RADIUS + 1), Blocks.BARRIER);
		fill(level, center.offset(-RADIUS, 0, -RADIUS - 1), center.offset(RADIUS, 3, -RADIUS - 1), Blocks.BARRIER);
		fill(level, center.offset(-RADIUS, 0, RADIUS + 1), center.offset(RADIUS, 3, RADIUS + 1), Blocks.BARRIER);
		player.setGameMode(GameType.SURVIVAL);
		player.getInventory().clearContent();
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5.0F);
		player.setAirSupply(player.getMaxAirSupply());
		player.clearFire();
		player.setDeltaMovement(Vec3.ZERO);
		teleport(player, level, new Vec3(center.getX() + 0.5D, center.getY(), center.getZ() + 0.5D));
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("action", "arena");
		payload.put("center", Map.of("x", center.getX(), "y", center.getY(), "z", center.getZ()));
		payload.put("radius", RADIUS);
		return payload;
	}

	private void cleanup(ServerLevel level, ServerPlayer player) {
		for (UUID id : spawned) {
			Entity entity = level.getEntity(id);
			if (entity != null) entity.discard();
		}
		spawned.clear();
		if (center != null) {
			AABB area = new AABB(center.getX() - RADIUS - 2, center.getY() - 2, center.getZ() - RADIUS - 2,
				center.getX() + RADIUS + 3, center.getY() + HEIGHT + 2, center.getZ() + RADIUS + 3);
			level.getEntitiesOfClass(ItemEntity.class, area, entity -> true).forEach(ItemEntity::discard);
			fill(level, center.offset(-RADIUS - 1, -1, -RADIUS - 1), center.offset(RADIUS + 1, HEIGHT, RADIUS + 1), Blocks.AIR);
		}
		if (origin != null) {
			ServerLevel originLevel = level.getServer().getLevel(origin.world());
			if (originLevel != null) teleport(player, originLevel, origin.position());
		}
		origin = null;
		center = null;
	}

	private static void give(ServerPlayer player, Map<String, Integer> items) {
		if (items == null) return;
		for (Map.Entry<String, Integer> entry : items.entrySet()) {
			Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(entry.getKey()))
				.orElseThrow(() -> new FixtureException("invalid_request", "Unknown item " + entry.getKey()));
			if (!player.getInventory().add(new ItemStack(item, entry.getValue()))) {
				throw new FixtureException("fixture_setup_failed", "Inventory full giving " + entry.getKey());
			}
		}
	}

	private static void setBlocks(ServerLevel level, List<BlockSpec> blocks) {
		if (blocks == null) return;
		for (BlockSpec spec : blocks) {
			Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(spec.block()))
				.orElseThrow(() -> new FixtureException("invalid_request", "Unknown block " + spec.block()));
			level.setBlock(new BlockPos(spec.x(), spec.y(), spec.z()), block.defaultBlockState(), PLACE_FLAGS);
		}
	}

	private Map<String, Object> spawn(ServerLevel level, Request request) {
		EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.parse(request.type()))
			.orElseThrow(() -> new FixtureException("invalid_request", "Unknown entity type " + request.type()));
		Entity entity = type.create(level, EntitySpawnReason.COMMAND);
		if (entity == null) throw new FixtureException("fixture_setup_failed", "Could not create " + request.type());
		entity.snapTo(request.x() + 0.5D, request.y(), request.z() + 0.5D, 0.0F, 0.0F);
		if (!level.addFreshEntity(entity)) throw new FixtureException("fixture_setup_failed", "Could not spawn " + request.type());
		spawned.add(entity.getUUID());
		return Map.of("available", true, "action", "spawn", "uuid", entity.getUUID().toString(),
			"x", entity.getX(), "y", entity.getY(), "z", entity.getZ());
	}

	private Map<String, Object> state(ServerLevel level, ServerPlayer player, Request request) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("action", "state");
		Map<String, Integer> inventory = new LinkedHashMap<>();
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty()) inventory.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
		}
		payload.put("inventory", inventory);
		payload.put("selectedSlot", player.getInventory().getSelectedSlot());
		payload.put("heldItem", BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString());
		payload.put("offhandItem", BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem()).toString());
		payload.put("hunger", player.getFoodData().getFoodLevel());
		payload.put("health", player.getHealth());
		payload.put("position", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
		List<Map<String, Object>> blocks = new ArrayList<>();
		if (request != null && request.query() != null) {
			for (Pos pos : request.query()) {
				blocks.add(Map.of("x", pos.x(), "y", pos.y(), "z", pos.z(), "block",
					BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(pos.x(), pos.y(), pos.z())).getBlock()).toString()));
			}
		}
		payload.put("blocks", blocks);
		List<Map<String, Object>> entities = new ArrayList<>();
		if (request != null && request.entities() != null) {
			for (String id : request.entities()) {
				Entity entity = level.getEntity(UUID.fromString(id));
				Map<String, Object> entry = new LinkedHashMap<>();
				entry.put("uuid", id);
				entry.put("alive", entity != null && entity.isAlive());
				if (entity instanceof LivingEntity living) entry.put("health", living.getHealth());
				if (entity != null) entry.put("position", Map.of("x", entity.getX(), "y", entity.getY(), "z", entity.getZ()));
				entities.add(entry);
			}
		}
		payload.put("entities", entities);
		List<Map<String, Object>> drops = new ArrayList<>();
		if (center != null) {
			AABB area = new AABB(center.getX() - RADIUS, center.getY() - 1, center.getZ() - RADIUS,
				center.getX() + RADIUS + 1, center.getY() + HEIGHT, center.getZ() + RADIUS + 1);
			for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class, area, entity -> true)) {
				drops.add(Map.of("item", BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString(),
					"count", drop.getItem().getCount()));
			}
		}
		payload.put("drops", drops);
		return payload;
	}

	private static void fill(ServerLevel level, BlockPos from, BlockPos to, Block block) {
		for (BlockPos pos : BlockPos.betweenClosed(from, to)) {
			level.setBlock(pos, block.defaultBlockState(), PLACE_FLAGS);
		}
	}

	private static void teleport(ServerPlayer player, ServerLevel level, Vec3 position) {
		if (!player.teleportTo(level, position.x, position.y, position.z, Set.<Relative>of(), player.getYRot(), player.getXRot(), true)) {
			throw new FixtureException("fixture_setup_failed", "Failed to teleport the tool fixture player");
		}
	}

	record Request(String action, String difficulty, Map<String, Integer> items, List<BlockSpec> blocks, String type,
		Double x, Double y, Double z, Integer hunger, List<Pos> query, List<String> entities) {
	}

	record BlockSpec(int x, int y, int z, String block) {
	}

	record Pos(int x, int y, int z) {
	}

	private record Origin(ResourceKey<Level> world, Vec3 position, float yaw, float pitch) {
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
