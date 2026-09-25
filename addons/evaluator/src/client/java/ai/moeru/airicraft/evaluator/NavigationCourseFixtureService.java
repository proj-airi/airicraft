package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.evaluator.NavigationCourse.Block;
import ai.moeru.airicraft.evaluator.NavigationCourse.Box;
import ai.moeru.airicraft.evaluator.NavigationCourse.Cell;
import ai.moeru.airicraft.evaluator.NavigationCourse.Kind;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Evaluator-only navigation benchmark setup. Courses are built at a fixed height above the position the
 * player held before the first course, and the player is returned there on cleanup.
 */
final class NavigationCourseFixtureService {
	private static final int COURSE_Y = 200;
	/** Space above a course that pillaring may have filled and cleanup clears. */
	private static final int CLEAR_HEADROOM = 8;
	private static final int PLACE_FLAGS = net.minecraft.block.Block.NOTIFY_LISTENERS;

	private Origin origin;
	private Placed placed;

	Map<String, Object> apply(Request request) {
		String action = request == null || request.action() == null || request.action().isBlank()
			? "build" : request.action().trim().toLowerCase(Locale.ROOT);
		if (action.equals("list")) {
			return listPayload();
		}

		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || client.world == null || client.getServer() == null) {
			throw new FixtureException("world_not_loaded", "A singleplayer world must be loaded");
		}
		ServerWorld world = client.getServer().getWorld(client.world.getRegistryKey());
		if (world == null) {
			throw new FixtureException("world_not_loaded", "The integrated server world is unavailable");
		}
		ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(client.player.getUuid());
		if (player == null) {
			throw new FixtureException("player_not_loaded", "The integrated server player is unavailable");
		}

		return switch (action) {
			case "build" -> build(world, player, request.course());
			case "status" -> statusPayload(player);
			case "cleanup" -> {
				cleanup(world, player);
				yield Map.of("available", true, "action", "cleanup");
			}
			default -> throw new FixtureException("invalid_request", "action must be build, status, cleanup, or list");
		};
	}

	private Map<String, Object> build(ServerWorld currentWorld, ServerPlayerEntity player, String courseId) {
		NavigationCourse course = NavigationCourses.byId(courseId == null ? "" : courseId.trim())
			.orElseThrow(() -> new FixtureException("unknown_course", "Unknown navigation course: " + courseId));
		clearPlaced(currentWorld);
		if (origin == null) {
			origin = new Origin(currentWorld.getRegistryKey(), player.getPos(), player.getYaw(), player.getPitch());
		}
		ServerWorld world = currentWorld.getServer().getWorld(origin.world());
		if (world == null) {
			throw new FixtureException("world_not_loaded", "The recorded origin world is unavailable");
		}

		BlockPos base = course.kind() == Kind.TERRAIN
			? BlockPos.ofFloored(origin.position())
			: new BlockPos(MathHelper.floor(origin.position().x), COURSE_Y, MathHelper.floor(origin.position().z));
		if (course.kind() == Kind.FIXTURE) {
			for (Map.Entry<Cell, Block> entry : course.blocks().entrySet()) {
				world.setBlockState(at(base, entry.getKey()), state(entry.getValue()), PLACE_FLAGS);
			}
		}
		placed = new Placed(course, base, world.getRegistryKey());

		prepare(world, player, course);
		Vec3d start = course.kind() == Kind.TERRAIN
			? origin.position()
			: Vec3d.of(at(base, course.start())).add(0.5D, 0.0D, 0.5D);
		teleport(player, world, start, origin.yaw(), origin.pitch());
		return buildPayload(course, base);
	}

	private static void prepare(ServerWorld world, ServerPlayerEntity player, NavigationCourse course) {
		world.getServer().setDifficulty(Difficulty.PEACEFUL, false);
		world.setTimeOfDay(1000L);
		player.changeGameMode(GameMode.SURVIVAL);
		player.getInventory().clear();
		for (Map.Entry<String, Integer> entry : course.loadout().entrySet()) {
			Item item = Registries.ITEM.getOptionalValue(Identifier.of(entry.getKey()))
				.orElseThrow(() -> new FixtureException("fixture_setup_failed", "Unknown loadout item " + entry.getKey()));
			player.getInventory().insertStack(new ItemStack(item, entry.getValue()));
		}
		player.setHealth(player.getMaxHealth());
		player.getHungerManager().setFoodLevel(20);
		player.getHungerManager().setSaturationLevel(5.0F);
		player.setAir(player.getMaxAir());
		player.extinguish();
		player.setVelocity(Vec3d.ZERO);
	}

	private void cleanup(ServerWorld currentWorld, ServerPlayerEntity player) {
		clearPlaced(currentWorld);
		if (origin != null) {
			ServerWorld originWorld = currentWorld.getServer().getWorld(origin.world());
			if (originWorld != null) {
				teleport(player, originWorld, origin.position(), origin.yaw(), origin.pitch());
			}
		}
		origin = null;
	}

	private void clearPlaced(ServerWorld currentWorld) {
		Placed current = placed;
		placed = null;
		if (current == null || current.course().kind() != Kind.FIXTURE) {
			return;
		}
		ServerWorld world = currentWorld.getServer().getWorld(current.world());
		if (world == null) {
			return;
		}
		Box box = current.course().footprint();
		BlockPos min = at(current.base(), box.min());
		BlockPos max = at(current.base(), box.max()).up(CLEAR_HEADROOM);
		world.getEntitiesByClass(ItemEntity.class, new net.minecraft.util.math.Box(Vec3d.of(min), Vec3d.of(max.add(1, 1, 1))), entity -> true)
			.forEach(ItemEntity::discard);
		for (BlockPos pos : BlockPos.iterate(min, max)) {
			world.setBlockState(pos, Blocks.AIR.getDefaultState(), PLACE_FLAGS);
		}
	}

	private Map<String, Object> statusPayload(ServerPlayerEntity player) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("action", "status");
		payload.put("course", placed == null ? "" : placed.course().id());
		payload.put("player", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
		payload.put("feet", blockPayload(player.getBlockPos()));
		payload.put("health", player.getHealth());
		payload.put("maxHealth", player.getMaxHealth());
		if (placed != null) {
			BlockPos feet = player.getBlockPos();
			Cell cell = new Cell(feet.getX() - placed.base().getX(), feet.getY() - placed.base().getY(), feet.getZ() - placed.base().getZ());
			if (placed.course().kind() == Kind.FIXTURE) {
				payload.put("insideFootprint", placed.course().footprint().contains(cell));
			}
			if (placed.course().travelBounds() != null) {
				payload.put("insideTravelBounds", placed.course().travelBounds().contains(cell));
			}
		}
		return payload;
	}

	private static Map<String, Object> buildPayload(NavigationCourse course, BlockPos base) {
		BlockPos goal = at(base, course.goal());
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("action", "build");
		payload.put("course", course.id());
		payload.put("description", course.description());
		payload.put("kind", course.kind().name().toLowerCase(Locale.ROOT));
		payload.put("expectation", course.expectation().name().toLowerCase(Locale.ROOT));
		payload.put("start", blockPayload(at(base, course.start())));
		payload.put("goal", Map.of("x", goal.getX(), "y", goal.getY(), "z", goal.getZ(), "exactY", course.exactY()));
		payload.put("loadout", course.loadout());
		if (course.kind() == Kind.FIXTURE) {
			payload.put("footprint", boundsPayload(base, course.footprint()));
		}
		if (course.travelBounds() != null) {
			payload.put("travelBounds", boundsPayload(base, course.travelBounds()));
		}
		return payload;
	}

	private static Map<String, Object> listPayload() {
		return Map.of("available", true, "action", "list", "courses", NavigationCourses.all().stream()
			.map(course -> Map.of(
				"course", course.id(),
				"description", course.description(),
				"kind", course.kind().name().toLowerCase(Locale.ROOT),
				"expectation", course.expectation().name().toLowerCase(Locale.ROOT)))
			.toList());
	}

	/** Keys match configure_travel bounds. */
	private static Map<String, Object> boundsPayload(BlockPos base, Box box) {
		BlockPos min = at(base, box.min());
		BlockPos max = at(base, box.max());
		return Map.of("minX", min.getX(), "minY", min.getY(), "minZ", min.getZ(),
			"maxX", max.getX(), "maxY", max.getY(), "maxZ", max.getZ());
	}

	private static Map<String, Object> blockPayload(BlockPos pos) {
		return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ());
	}

	private static BlockPos at(BlockPos base, Cell cell) {
		return base.add(cell.x(), cell.y(), cell.z());
	}

	private static BlockState state(Block block) {
		return switch (block) {
			case STONE -> Blocks.STONE.getDefaultState();
			case DIRT -> Blocks.DIRT.getDefaultState();
			case BARRIER -> Blocks.BARRIER.getDefaultState();
			case WATER -> Blocks.WATER.getDefaultState();
			case OAK_DOOR_LOWER -> door(DoubleBlockHalf.LOWER);
			case OAK_DOOR_UPPER -> door(DoubleBlockHalf.UPPER);
		};
	}

	private static BlockState door(DoubleBlockHalf half) {
		return Blocks.OAK_DOOR.getDefaultState()
			.with(DoorBlock.HALF, half)
			.with(DoorBlock.FACING, Direction.EAST)
			.with(DoorBlock.OPEN, false);
	}

	private static void teleport(ServerPlayerEntity player, ServerWorld world, Vec3d position, float yaw, float pitch) {
		if (!player.teleport(world, position.x, position.y, position.z, Set.<PositionFlag>of(), yaw, pitch, true)) {
			throw new FixtureException("fixture_setup_failed", "Failed to teleport the navigation course player");
		}
	}

	record Request(String action, String course) {
	}

	private record Origin(RegistryKey<World> world, Vec3d position, float yaw, float pitch) {
	}

	private record Placed(NavigationCourse course, BlockPos base, RegistryKey<World> world) {
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
