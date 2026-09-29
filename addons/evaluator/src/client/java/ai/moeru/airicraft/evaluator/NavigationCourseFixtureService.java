package ai.moeru.airicraft.evaluator;

import ai.moeru.airicraft.evaluator.NavigationCourse.Block;
import ai.moeru.airicraft.evaluator.NavigationCourse.Box;
import ai.moeru.airicraft.evaluator.NavigationCourse.Cell;
import ai.moeru.airicraft.evaluator.NavigationCourse.Kind;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.Relative;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

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
	private static final int PLACE_FLAGS = net.minecraft.world.level.block.Block.UPDATE_CLIENTS;

	private Origin origin;
	private Placed placed;

	Map<String, Object> apply(Request request) {
		String action = request == null || request.action() == null || request.action().isBlank()
			? "build" : request.action().trim().toLowerCase(Locale.ROOT);
		if (action.equals("list")) {
			return listPayload();
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

		return switch (action) {
			case "build" -> build(level, player, request.course());
			case "status" -> statusPayload(player);
			case "cleanup" -> {
				cleanup(level, player);
				yield Map.of("available", true, "action", "cleanup");
			}
			default -> throw new FixtureException("invalid_request", "action must be build, status, cleanup, or list");
		};
	}

	private Map<String, Object> build(ServerLevel currentLevel, ServerPlayer player, String courseId) {
		NavigationCourse course = NavigationCourses.byId(courseId == null ? "" : courseId.trim())
			.orElseThrow(() -> new FixtureException("unknown_course", "Unknown navigation course: " + courseId));
		clearPlaced(currentLevel);
		if (origin == null) {
			origin = new Origin(currentLevel.dimension(), player.position(), player.getYRot(), player.getXRot());
		}
		ServerLevel level = currentLevel.getServer().getLevel(origin.world());
		if (level == null) {
			throw new FixtureException("world_not_loaded", "The recorded origin world is unavailable");
		}

		BlockPos base = course.kind() == Kind.TERRAIN
			? BlockPos.containing(origin.position())
			: new BlockPos(Mth.floor(origin.position().x), COURSE_Y, Mth.floor(origin.position().z));
		if (course.kind() == Kind.FIXTURE) {
			for (Map.Entry<Cell, Block> entry : course.blocks().entrySet()) {
				level.setBlock(at(base, entry.getKey()), state(entry.getValue()), PLACE_FLAGS);
			}
		}
		placed = new Placed(course, base, level.dimension());

		prepare(level, player, course);
		Vec3 start = course.kind() == Kind.TERRAIN
			? origin.position()
			: Vec3.atLowerCornerOf(at(base, course.start())).add(0.5D, 0.0D, 0.5D);
		teleport(player, level, start, origin.yaw(), origin.pitch());
		return buildPayload(course, base);
	}

	private static void prepare(ServerLevel level, ServerPlayer player, NavigationCourse course) {
		level.getServer().setDifficulty(Difficulty.PEACEFUL, false);
		level.setDayTime(1000L);
		player.setGameMode(GameType.SURVIVAL);
		player.getInventory().clearContent();
		for (Map.Entry<String, Integer> entry : course.loadout().entrySet()) {
			Item item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(entry.getKey()))
				.orElseThrow(() -> new FixtureException("fixture_setup_failed", "Unknown loadout item " + entry.getKey()));
			player.getInventory().add(new ItemStack(item, entry.getValue()));
		}
		player.setHealth(player.getMaxHealth());
		player.getFoodData().setFoodLevel(20);
		player.getFoodData().setSaturation(5.0F);
		player.setAirSupply(player.getMaxAirSupply());
		player.clearFire();
		player.setDeltaMovement(Vec3.ZERO);
	}

	private void cleanup(ServerLevel currentLevel, ServerPlayer player) {
		clearPlaced(currentLevel);
		if (origin != null) {
			ServerLevel originLevel = currentLevel.getServer().getLevel(origin.world());
			if (originLevel != null) {
				teleport(player, originLevel, origin.position(), origin.yaw(), origin.pitch());
			}
		}
		origin = null;
	}

	private void clearPlaced(ServerLevel currentLevel) {
		Placed current = placed;
		placed = null;
		if (current == null || current.course().kind() != Kind.FIXTURE) {
			return;
		}
		ServerLevel level = currentLevel.getServer().getLevel(current.world());
		if (level == null) {
			return;
		}
		Box box = current.course().footprint();
		BlockPos min = at(current.base(), box.min());
		BlockPos max = at(current.base(), box.max()).above(CLEAR_HEADROOM);
		level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(Vec3.atLowerCornerOf(min), Vec3.atLowerCornerOf(max.offset(1, 1, 1))), entity -> true)
			.forEach(ItemEntity::discard);
		for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACE_FLAGS);
		}
	}

	private Map<String, Object> statusPayload(ServerPlayer player) {
		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("action", "status");
		payload.put("course", placed == null ? "" : placed.course().id());
		payload.put("player", Map.of("x", player.getX(), "y", player.getY(), "z", player.getZ()));
		payload.put("feet", blockPayload(player.blockPosition()));
		payload.put("health", player.getHealth());
		payload.put("maxHealth", player.getMaxHealth());
		if (placed != null) {
			BlockPos feet = player.blockPosition();
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
		return base.offset(cell.x(), cell.y(), cell.z());
	}

	private static BlockState state(Block block) {
		return switch (block) {
			case STONE -> Blocks.STONE.defaultBlockState();
			case DIRT -> Blocks.DIRT.defaultBlockState();
			case BARRIER -> Blocks.BARRIER.defaultBlockState();
			case WATER -> Blocks.WATER.defaultBlockState();
			case OAK_DOOR_LOWER -> door(DoubleBlockHalf.LOWER);
			case OAK_DOOR_UPPER -> door(DoubleBlockHalf.UPPER);
		};
	}

	private static BlockState door(DoubleBlockHalf half) {
		return Blocks.OAK_DOOR.defaultBlockState()
			.setValue(DoorBlock.HALF, half)
			.setValue(DoorBlock.FACING, Direction.EAST)
			.setValue(DoorBlock.OPEN, false);
	}

	private static void teleport(ServerPlayer player, ServerLevel level, Vec3 position, float yaw, float pitch) {
		if (!player.teleportTo(level, position.x, position.y, position.z, Set.<Relative>of(), yaw, pitch, true)) {
			throw new FixtureException("fixture_setup_failed", "Failed to teleport the navigation course player");
		}
	}

	record Request(String action, String course) {
	}

	private record Origin(ResourceKey<Level> world, Vec3 position, float yaw, float pitch) {
	}

	private record Placed(NavigationCourse course, BlockPos base, ResourceKey<Level> world) {
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
