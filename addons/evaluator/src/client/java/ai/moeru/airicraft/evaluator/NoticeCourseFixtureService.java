package ai.moeru.airicraft.evaluator;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

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
 * The player starts at the west end; the goal is the east end.
 */
final class NoticeCourseFixtureService {
	static final String COURSE = "notice_walk";
	private static final int COURSE_Y = 200;
	private static final int PLACE_FLAGS = Block.NOTIFY_LISTENERS;
	// Course-local bounds: a solid stone box with a corridor carved through it.
	private static final int MIN_X = 0, MAX_X = 30, MIN_Y = 0, MAX_Y = 4, MIN_Z = -4, MAX_Z = 5;
	private static final List<int[]> VEIN = List.of(new int[] {10, 1, -1}, new int[] {11, 1, -1}, new int[] {11, 2, -1});
	private static final int[] SEALED = {20, 2, -3};
	private static final int[] START = {1, 1, 1};
	private static final int[] GOAL = {28, 1, 1};

	private Origin origin;
	private BlockPos base;
	private RegistryKey<World> builtIn;

	Map<String, Object> apply(Request request) {
		String action = request == null || request.action() == null || request.action().isBlank()
			? "build" : request.action().trim().toLowerCase(Locale.ROOT);
		if (action.equals("list")) {
			return Map.of("available", true, "action", "list", "courses", List.of(Map.of("course", COURSE,
				"description", "Walk a dark corridor past an exposed diamond vein, a sealed emerald ore, bread and cobblestone.")));
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.getServer() == null) {
			throw new FixtureException("world_not_loaded", "A singleplayer world must be loaded");
		}
		ServerWorld world = client.getServer().getWorld(client.world.getRegistryKey());
		ServerPlayerEntity player = world == null ? null : world.getServer().getPlayerManager().getPlayer(client.player.getUuid());
		if (world == null || player == null) throw new FixtureException("player_not_loaded", "The integrated server player is unavailable");
		return switch (action) {
			case "build" -> {
				if (request.course() != null && !request.course().isBlank() && !COURSE.equals(request.course().trim())) {
					throw new FixtureException("unknown_course", "Unknown notice course: " + request.course());
				}
				yield build(world, player);
			}
			case "cleanup" -> {
				cleanup(world, player);
				yield Map.of("available", true, "action", "cleanup");
			}
			default -> throw new FixtureException("invalid_request", "action must be build, cleanup, or list");
		};
	}

	private Map<String, Object> build(ServerWorld world, ServerPlayerEntity player) {
		clear(world);
		if (origin == null) origin = new Origin(world.getRegistryKey(), player.getPos(), player.getYaw(), player.getPitch());
		base = new BlockPos(MathHelper.floor(origin.position().x), COURSE_Y, MathHelper.floor(origin.position().z));
		builtIn = world.getRegistryKey();
		for (BlockPos pos : BlockPos.iterate(at(MIN_X, MIN_Y, MIN_Z), at(MAX_X, MAX_Y, MAX_Z))) {
			world.setBlockState(pos, Blocks.STONE.getDefaultState(), PLACE_FLAGS);
		}
		for (BlockPos pos : BlockPos.iterate(at(1, 1, 0), at(29, 2, 2))) world.setBlockState(pos, Blocks.AIR.getDefaultState(), PLACE_FLAGS);
		for (int[] cell : VEIN) world.setBlockState(at(cell[0], cell[1], cell[2]), Blocks.DIAMOND_ORE.getDefaultState(), PLACE_FLAGS);
		world.setBlockState(at(SEALED[0], SEALED[1], SEALED[2]), Blocks.EMERALD_ORE.getDefaultState(), PLACE_FLAGS);
		var items = new ArrayList<Map<String, Object>>();
		items.add(drop(world, new ItemStack(Items.BREAD, 3), 16.5, 2.4, "noticed"));
		items.add(drop(world, new ItemStack(Items.COBBLESTONE, 8), 17.5, 2.4, "garbage"));

		world.getServer().setDifficulty(Difficulty.PEACEFUL, false);
		world.setTimeOfDay(1000L);
		player.changeGameMode(GameMode.SURVIVAL);
		player.getInventory().clear();
		player.setHealth(player.getMaxHealth());
		player.getHungerManager().setFoodLevel(20);
		player.setVelocity(Vec3d.ZERO);
		teleport(player, world, Vec3d.of(at(START[0], START[1], START[2])).add(.5, 0, .5), -90F, 0F);

		var payload = new LinkedHashMap<String, Object>();
		payload.put("available", true);
		payload.put("action", "build");
		payload.put("course", COURSE);
		payload.put("start", point(at(START[0], START[1], START[2])));
		BlockPos goal = at(GOAL[0], GOAL[1], GOAL[2]);
		payload.put("goal", Map.of("x", goal.getX(), "y", goal.getY(), "z", goal.getZ(), "exactY", true));
		payload.put("expectNoticed", List.of(Map.of("blockId", "minecraft:diamond_ore",
			"positions", VEIN.stream().map(cell -> point(at(cell[0], cell[1], cell[2]))).toList())));
		payload.put("expectNeverNoticed", List.of(Map.of("blockId", "minecraft:emerald_ore", "position", point(at(SEALED[0], SEALED[1], SEALED[2])))));
		payload.put("items", items);
		return payload;
	}

	private Map<String, Object> drop(ServerWorld world, ItemStack stack, double x, double z, String expectation) {
		BlockPos floor = at(0, 1, 0);
		var entity = new ItemEntity(world, base.getX() + x, floor.getY() + .05, base.getZ() + z, stack, 0, 0, 0);
		entity.setPickupDelayInfinite();
		entity.setNeverDespawn();
		world.spawnEntity(entity);
		return Map.of("itemId", net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString(), "count", stack.getCount(),
			"uuid", entity.getUuid().toString(), "expect", expectation);
	}

	private void cleanup(ServerWorld world, ServerPlayerEntity player) {
		clear(world);
		if (origin != null) {
			ServerWorld originWorld = world.getServer().getWorld(origin.world());
			if (originWorld != null) teleport(player, originWorld, origin.position(), origin.yaw(), origin.pitch());
		}
		origin = null;
	}

	private void clear(ServerWorld current) {
		if (base == null || builtIn == null) return;
		ServerWorld world = current.getServer().getWorld(builtIn);
		if (world != null) {
			BlockPos min = at(MIN_X, MIN_Y, MIN_Z), max = at(MAX_X, MAX_Y, MAX_Z);
			world.getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(Vec3d.of(min), Vec3d.of(max.add(1, 1, 1))), entity -> true)
				.forEach(ItemEntity::discard);
			for (BlockPos pos : BlockPos.iterate(min, max)) world.setBlockState(pos, Blocks.AIR.getDefaultState(), PLACE_FLAGS);
		}
		base = null;
		builtIn = null;
	}

	private BlockPos at(int x, int y, int z) {
		return base.add(x, y, z);
	}

	private static Map<String, Object> point(BlockPos pos) {
		return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
	}

	private static void teleport(ServerPlayerEntity player, ServerWorld world, Vec3d position, float yaw, float pitch) {
		if (!player.teleport(world, position.x, position.y, position.z, Set.<PositionFlag>of(), yaw, pitch, true)) {
			throw new FixtureException("fixture_setup_failed", "Failed to teleport the notice course player");
		}
	}

	record Request(String action, String course) {
	}

	private record Origin(RegistryKey<World> world, Vec3d position, float yaw, float pitch) {
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
