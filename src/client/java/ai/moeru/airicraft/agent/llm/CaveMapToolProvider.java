package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.spatial.CaveRouteMap;
import ai.moeru.airicraft.agent.spatial.CaveRouteMap.Cell;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Snapshot on the client thread; connected-space and route calculations use immutable geometry. */
public final class CaveMapToolProvider implements PlannerToolProvider {
	@Override public String id() { return "cave_map"; }
	@Override public boolean handles(String name) { return "map_cave".equals(normalizeName(name)); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("map_cave",
			"Map bounded loaded connected cave geometry, including around corners. No ore identities. Floods air, then routes over supported floors, reversible one-block steps and one-cell gaps. Excludes deeper drops that need footholds to return. Favors broad floor space. Returns a short-waypoint route and alternative destinations. Geometry is not a live path guarantee; unmapped space is unknown.",
			propertiesForProvider(
				propForProvider("radius", Map.of("type", "integer", "description", "Horizontal snapshot radius, default24, 8..32.")),
				propForProvider("verticalRadius", Map.of("type", "integer", "description", "Vertical snapshot radius, default16, 4..24.")),
				propForProvider("opennessWeight", Map.of("type", "number", "description", "Broad-floor preference, default1, 0..2. Zero minimizes route distance.")),
				propForProvider("target", Map.of("type", "object", "additionalProperties", false,
					"description", "Optional exact feet coordinate. Omit to suggest an open frontier or chamber.",
					"properties", Map.of("x", Map.of("type", "integer"), "y", Map.of("type", "integer"), "z", Map.of("type", "integer")),
					"required", List.of("x", "y", "z")))), List.of()));
	}
	@Override public String promptInstructions() {
		return "For cave route planning use map_cave to understand connected walkable space and favor broad passages when torches permit. It reads loaded geometry around corners but supplies no ore knowledge; keep survey_cave for visible ore observations. Follow the returned short waypoints in order, with digging/placement disabled for passage travel. Reject a segment that strays from the mapped corridor or stalls and remap; do not replace the route with one distant navigate_to. Geometry can change through mining or combat; remap afterward. Unknown, truncated or unreachable space is not a dead end. Remember chosen branches/return points to avoid revisits. Map defaults do not account for current mobs, food or torch reserves; check these before following it.";
	}
	@Override public void validateArguments(String name, JsonObject args) {
		integer(args, "radius", 24, 8, 32); integer(args, "verticalRadius", 16, 4, 24); weight(args); target(args);
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		var result = new CompletableFuture<String>();
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			try {
				if (minecraft.level == null || minecraft.player == null) throw new IllegalStateException("world_not_loaded");
				var args = call.arguments();
				int radius = integer(args, "radius", 24, 8, 32), vertical = integer(args, "verticalRadius", 16, 4, 24);
				double preference = weight(args);
				BlockPos destination = target(args), origin = minecraft.player.blockPosition().immutable();
				String dimension = minecraft.level.dimension().location().toString();
				long started = System.nanoTime();
				var level = minecraft.level;
				var hazards = Set.of(Blocks.LAVA, Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS, Blocks.POWDER_SNOW);
				var grid = new CaveRouteMap.Grid(origin, radius, vertical, p -> {
					if (p.getY() < level.getMinY() || p.getY() > level.getMaxY() || !level.hasChunkAt(p)) return Cell.UNKNOWN;
					var state = level.getBlockState(p);
					if (state.isAir()) return Cell.OPEN;
					if (hazards.contains(state.getBlock())) return Cell.HAZARD;
					if (!state.getFluidState().isEmpty()) return Cell.WATER;
					var shape = state.getCollisionShape(level, p);
					if (shape.isEmpty() || shape.max(Direction.Axis.Y) <= .125) return Cell.OPEN;
					return state.isFaceSturdy(level, p, Direction.UP) ? Cell.SUPPORT : Cell.BLOCKED;
				});
				long captureMs = (System.nanoTime() - started) / 1_000_000;
				CompletableFuture.supplyAsync(() -> CaveRouteMap.map(grid, origin, destination, preference)).whenComplete((map, error) -> {
					if (error != null) { result.complete("TOOL_ERROR: map_cave " + error.getMessage()); return; }
					Map<String, Object> out = new LinkedHashMap<>();
					out.put("status", map.status()); out.put("origin", position(origin)); out.put("dimension", dimension);
					out.put("knowledge", "loaded_connected_geometry_not_line_of_sight");
					out.put("radius", radius); out.put("verticalRadius", vertical); out.put("opennessWeight", preference);
					out.put("sampledCells", grid.sampledCells()); out.put("airCells", map.airCells()); out.put("floorCells", map.floorCells());
					out.put("reachableCells", map.reachableCells()); out.put("truncated", map.truncated());
					out.put("captureMillis", captureMs); out.put("totalMillis", (System.nanoTime() - started) / 1_000_000);
					out.put("destinations", map.destinations().stream().map(d -> Map.of("position", position(d.position()), "localFloorArea", d.localFloorArea(), "frontier", d.frontier(), "cost", d.cost(), "steps", d.steps())).toList());
					out.put("route", map.route().stream().map(CaveMapToolProvider::position).toList());
					out.put("waypoints", CaveRouteMap.waypoints(map.route()).stream().map(CaveMapToolProvider::position).toList());
					out.put("routeValidatedLive", false);
					result.complete("Tool result for map_cave: " + new Gson().toJson(out));
				});
			} catch (RuntimeException e) { result.complete("TOOL_ERROR: map_cave " + e.getMessage()); }
		});
		return result;
	}
	private static Map<String, Integer> position(BlockPos p) { return Map.of("x", p.getX(), "y", p.getY(), "z", p.getZ()); }
	private static int integer(JsonObject args, String name, int fallback, int min, int max) {
		if (!args.has(name)) return fallback;
		try {
			int n = args.get(name).getAsBigDecimal().intValueExact();
			if (!args.getAsJsonPrimitive(name).isNumber() || n < min || n > max) throw new ArithmeticException();
			return n;
		} catch (RuntimeException e) { throw new JsonParseException(name + " must be integer " + min + ".." + max); }
	}
	private static BlockPos target(JsonObject args) {
		if (!args.has("target")) return null;
		if (!args.get("target").isJsonObject()) throw new JsonParseException("target must be coordinates");
		JsonObject target = args.getAsJsonObject("target");
		for (String axis : List.of("x", "y", "z")) if (!target.has(axis)) throw new JsonParseException("target requires " + axis);
		return new BlockPos(integer(target, "x", 0, -30000000, 30000000), integer(target, "y", 0, -2048, 2048), integer(target, "z", 0, -30000000, 30000000));
	}
	private static double weight(JsonObject args) {
		if (!args.has("opennessWeight")) return 1;
		try {
			double n = args.get("opennessWeight").getAsDouble();
			if (!args.getAsJsonPrimitive("opennessWeight").isNumber() || !Double.isFinite(n) || n < 0 || n > 2) throw new ArithmeticException();
			return n;
		} catch (RuntimeException e) { throw new JsonParseException("opennessWeight must be number 0..2"); }
	}
}
