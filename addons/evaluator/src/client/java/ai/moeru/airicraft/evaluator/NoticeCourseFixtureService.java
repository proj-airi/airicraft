package ai.moeru.airicraft.evaluator;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.Relative;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluator-only perception benchmark setup (Phase 4, P12). {@code notice_walk} is a dark stone corridor at a fixed
 * height above the player's original position:
 * <ul>
 *   <li>a three-block diamond vein in the north wall, its faces exposed to the corridor (must be noticed);</li>
 *   <li>an emerald ore sealed inside that thick wall, every neighbour stone (must never be noticed: no X-ray);</li>
 *   <li>bread and cobblestone on the corridor floor, pickup disabled (bread noticed, cobblestone dropped as garbage).</li>
 * </ul>
 * The player starts at the west end; the goal is the east end. Each {@code run} is built {@link #RUN_SPACING} blocks
 * further south, so what the agent remembers noticing in one run does not hide the next run's course.
 */
final class NoticeCourseFixtureService {
	static final String COURSE = "notice_walk";
	private static final int COURSE_Y = 200;
	static final int RUN_SPACING = 64;
	private static final int PLACE_FLAGS = Block.UPDATE_CLIENTS;
	// Course-local bounds: a solid stone box with a corridor carved through it.
	private static final int MIN_X = 0, MAX_X = 30, MIN_Y = 0, MAX_Y = 4, MIN_Z = -4, MAX_Z = 5;
	private static final List<int[]> VEIN = List.of(new int[] {10, 1, -1}, new int[] {11, 1, -1}, new int[] {11, 2, -1});
	private static final int[] SEALED = {20, 2, -3};
	private static final int[] START = {1, 1, 1};
	private static final int[] GOAL = {28, 1, 1};

	private Origin origin;
	private BlockPos base;
	private ResourceKey<Level> builtIn;

	Map<String, Object> apply(Request request) {
		String action = request == null || request.action() == null || request.action().isBlank()
			? "build" : request.action().trim().toLowerCase(Locale.ROOT);
		if (action.equals("list")) {
			return Map.of("available", true, "action", "list", "courses", List.of(Map.of("course", COURSE,
				"description", "Walk a dark corridor past an exposed diamond vein, a sealed emerald ore, bread and cobblestone.")));
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || minecraft.getSingleplayerServer() == null) {
			throw new FixtureException("world_not_loaded", "A singleplayer world must be loaded");
		}
		ServerLevel level = minecraft.getSingleplayerServer().getLevel(minecraft.level.dimension());
		ServerPlayer player = level == null ? null : level.getServer().getPlayerList().getPlayer(minecraft.player.getUUID());
		if (level == null || player == null) throw new FixtureException("player_not_loaded", "The integrated server player is unavailable");
		return switch (action) {
			case "build" -> {
				if (request.course() != null && !request.course().isBlank() && !COURSE.equals(request.course().trim())) {
					throw new FixtureException("unknown_course", "Unknown notice course: " + request.course());
				}
				yield build(level, player, Math.max(0, request.run()));
			}
			case "cleanup" -> {
				cleanup(level, player);
				yield Map.of("available", true, "action", "cleanup");
			}
			default -> throw new FixtureException("invalid_request", "action must be build, cleanup, or list");
		};
	}

	private Map<String, Object> build(ServerLevel level, ServerPlayer player, int run) {
		clear(level);
		if (origin == null) origin = new Origin(level.dimension(), player.position(), player.getYRot(), player.getXRot());
		base = new BlockPos(Mth.floor(origin.position().x), COURSE_Y, Mth.floor(origin.position().z) + run * RUN_SPACING);
		builtIn = level.dimension();
		for (BlockPos pos : BlockPos.betweenClosed(at(MIN_X, MIN_Y, MIN_Z), at(MAX_X, MAX_Y, MAX_Z))) {
			level.setBlock(pos, Blocks.STONE.defaultBlockState(), PLACE_FLAGS);
		}
		for (BlockPos pos : BlockPos.betweenClosed(at(1, 1, 0), at(29, 2, 2))) level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
		for (int[] cell : VEIN) level.setBlock(at(cell[0], cell[1], cell[2]), Blocks.DIAMOND_ORE.defaultBlockState(), PLACE_FLAGS);
		level.setBlock(at(SEALED[0], SEALED[1], SEALED[2]), Blocks.EMERALD_ORE.defaultBlockState(), PLACE_FLAGS);
		var items = new ArrayList<Map<String, Object>>();
		items.add(drop(level, new ItemStack(Items.BREAD, 3), 16.5, 2.4, "noticed"));
		items.add(drop(level, new ItemStack(Items.COBBLESTONE, 8), 17.5, 2.4, "garbage"));

		level.getServer().setDifficulty(Difficulty.PEACEFUL, false);
		level.setDayTime(1000L);
		player.setGameMode(GameType.SURVIVAL);
		player.getInventory().clearContent();
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.setDeltaMovement(Vec3.ZERO);
		teleport(player, level, Vec3.atLowerCornerOf(at(START[0], START[1], START[2])).add(.5, 0, .5), -90F, 0F);

		var payload = new LinkedHashMap<String, Object>();
		payload.put("available", true);
		payload.put("action", "build");
		payload.put("course", COURSE);
		payload.put("run", run);
		payload.put("start", point(at(START[0], START[1], START[2])));
		BlockPos goal = at(GOAL[0], GOAL[1], GOAL[2]);
		payload.put("goal", Map.of("x", goal.getX(), "y", goal.getY(), "z", goal.getZ(), "exactY", true));
		payload.put("expectNoticed", List.of(Map.of("blockId", "minecraft:diamond_ore",
			"positions", VEIN.stream().map(cell -> point(at(cell[0], cell[1], cell[2]))).toList())));
		payload.put("expectNeverNoticed", List.of(Map.of("blockId", "minecraft:emerald_ore", "position", point(at(SEALED[0], SEALED[1], SEALED[2])))));
		payload.put("items", items);
		return payload;
	}

	private Map<String, Object> drop(ServerLevel level, ItemStack stack, double x, double z, String expectation) {
		BlockPos floor = at(0, 1, 0);
		var entity = new ItemEntity(level, base.getX() + x, floor.getY() + .05, base.getZ() + z, stack, 0, 0, 0);
		entity.setNeverPickUp();
		entity.setUnlimitedLifetime();
		level.addFreshEntity(entity);
		return Map.of("itemId", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), "count", stack.getCount(),
			"uuid", entity.getUUID().toString(), "expect", expectation);
	}

	private void cleanup(ServerLevel level, ServerPlayer player) {
		clear(level);
		if (origin != null) {
			ServerLevel originLevel = level.getServer().getLevel(origin.world());
			if (originLevel != null) teleport(player, originLevel, origin.position(), origin.yaw(), origin.pitch());
		}
		origin = null;
	}

	private void clear(ServerLevel current) {
		if (base == null || builtIn == null) return;
		ServerLevel level = current.getServer().getLevel(builtIn);
		if (level != null) {
			BlockPos min = at(MIN_X, MIN_Y, MIN_Z), max = at(MAX_X, MAX_Y, MAX_Z);
			level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max.offset(1, 1, 1))), entity -> true)
				.forEach(ItemEntity::discard);
			for (BlockPos pos : BlockPos.betweenClosed(min, max)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
		}
		base = null;
		builtIn = null;
	}

	private BlockPos at(int x, int y, int z) {
		return base.offset(x, y, z);
	}

	private static Map<String, Object> point(BlockPos pos) {
		return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
	}

	private static void teleport(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch) {
		if (!player.teleportTo(level, position.x, position.y, position.z, Set.<Relative>of(), yaw, pitch, true)) {
			throw new FixtureException("fixture_setup_failed", "Failed to teleport the notice course player");
		}
	}

	record Request(String action, String course, int run) {
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
