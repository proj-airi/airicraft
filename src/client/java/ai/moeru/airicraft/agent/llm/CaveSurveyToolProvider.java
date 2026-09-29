package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.spatial.VisibleSurfaceSampler;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Sparse first-hit rays, not a scan of hidden loaded terrain. */
public final class CaveSurveyToolProvider implements PlannerToolProvider {
	private final Consumer<List<BlockPos>> observed;
	public CaveSurveyToolProvider(Consumer<List<BlockPos>> observed) { this.observed = observed; }
	@Override public String id() { return "cave_survey"; }
	@Override public boolean handles(String name) { return "survey_cave".equals(normalizeName(name)); }
	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(toolForProvider("survey_cave",
			"Survey surrounding visible cave surfaces using sparse first-hit rays. Returns up to eight visible standing candidates, exposed ore and hazards, and current light. Does not find hidden ore or prove routes/absence. No terrain changes.",
			propertiesForProvider(propForProvider("radius", Map.of("type", "integer", "description", "Ray distance, default 12, 4..24 blocks."))), List.of()));
	}
	@Override public void validateArguments(String name, JsonObject args) { radius(args); }
	@Override public String promptInstructions() {
		return "For cave exploration, prepare shield, food, usable pickaxes, torches and free inventory space. Remember the chosen entrance and home before descending. Use survey_cave for compact visible passage/ore observations; select short standing waypoints, navigate and survey again. Configure pathfinding allowBreak=false and allowPlace=false during passage exploration so a blocked route cannot turn into strip mining or bridging. Restore previous settings when leaving this exploration intent. Standing candidates are observations, not guaranteed reachable paths. Remember junctions, tried branches and return waypoints with place memory to avoid repeating dead ends. Maintain lighting; stop at unlit or hazardous drops, replenish supplies and return along remembered waypoints. For ore, use mine_blocks or collect_resource with constraints.visibleOnly=true and a small fixed scope so System 1 approaches visible sources, mines newly exposed faces and collects drops. Keep scope around the chosen vein. Use break_blocks for exact edits already in reach. visibleOnly uses the same sparse first-hit rays as this survey; it does not discover buried sources or change pathfinding terrain permissions. Do not use hidden-block scans or unrestricted mining to select veins. Logbook stock helps plan resupply, but reopen containers to confirm it. A survey can miss small surfaces; no reported ore or waypoint is not proof of absence.";
	}
	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		CompletableFuture<String> result = new CompletableFuture<>();
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.execute(() -> {
			try { result.complete(survey(minecraft, radius(call.arguments()))); }
			catch (RuntimeException exception) { result.complete("TOOL_ERROR: survey_cave " + exception.getMessage()); }
		});
		return result;
	}
	private String survey(Minecraft minecraft, int radius) {
		if (minecraft.level == null || minecraft.player == null) throw new IllegalStateException("world_not_loaded");
		var player = minecraft.player;
		var level = minecraft.level;
		Vec3 eye = player.getEyePosition();
		Map<BlockPos, String> resources = new LinkedHashMap<>();
		Map<BlockPos, String> hazards = new LinkedHashMap<>();
		List<BlockPos> floors = new ArrayList<>();
		for (var hit : VisibleSurfaceSampler.sample(eye, radius, (start, end) ->
			level.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player)))) {
			if (hit.getType() != HitResult.Type.BLOCK || !level.hasChunkAt(hit.getBlockPos())) continue;
			BlockPos pos = hit.getBlockPos().immutable();
			var state = level.getBlockState(pos);
			String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			if (id.endsWith("_ore")) resources.put(pos, id);
			if (!state.getFluidState().isEmpty() || dangerous(id)) hazards.put(pos, id);
			if (hit.getDirection() != Direction.UP || dangerous(id) || !state.getFluidState().isEmpty()
				|| !state.isFaceSturdy(level, pos, Direction.UP)) continue;
			BlockPos feet = pos.above();
			if (dangerous(BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet).getBlock()).toString())
				|| dangerous(BuiltInRegistries.BLOCK.getKey(level.getBlockState(feet.above()).getBlock()).toString())
				|| !level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
				|| !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
				|| !level.getFluidState(feet).isEmpty() || !level.getFluidState(feet.above()).isEmpty()) continue;
			if (clearRay(minecraft, eye, Vec3.atBottomCenterOf(feet).add(0, 0.2, 0))
				&& clearRay(minecraft, eye, Vec3.atBottomCenterOf(feet).add(0, 1.62, 0))) floors.add(feet);
		}
		List<BlockPos> waypoints = selectWaypoints(player.blockPosition(), floors);
		List<Map<String, Object>> ores = records(resources, 16), dangers = records(hazards, 12);
		List<BlockPos> evidence = new ArrayList<>(resources.keySet().stream().limit(16).toList());
		evidence.addAll(hazards.keySet().stream().limit(12).toList());
		evidence.addAll(waypoints);
		observed.accept(List.copyOf(evidence));
		Map<String, Object> output = new LinkedHashMap<>();
		output.put("position", position(player.blockPosition()));
		output.put("dimension", level.dimension().location().toString());
		output.put("radius", radius);
		output.put("sampledRays", 495);
		output.put("lightAtPlayer", level.getMaxLocalRawBrightness(player.blockPosition()));
		output.put("standingCandidates", waypoints.stream().map(pos -> Map.of("position", position(pos), "light", level.getMaxLocalRawBrightness(pos))).toList());
		output.put("exposedOre", ores);
		output.put("hazards", dangers);
		output.put("routeValidated", false);
		return "Tool result for survey_cave: " + new Gson().toJson(output);
	}
	static List<BlockPos> selectWaypoints(BlockPos origin, List<BlockPos> floors) {
		Map<Integer, BlockPos> sectors = new TreeMap<>();
		for (BlockPos pos : floors) {
			int dx = pos.getX() - origin.getX(), dz = pos.getZ() - origin.getZ();
			if (dx * dx + dz * dz < 4) continue;
			int sector = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
			BlockPos previous = sectors.get(sector);
			if (previous == null || pos.distSqr(origin) > previous.distSqr(origin)) sectors.put(sector, pos.immutable());
		}
		return List.copyOf(sectors.values());
	}
	private static boolean clearRay(Minecraft minecraft, Vec3 eye, Vec3 end) {
		return minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
			ClipContext.Fluid.ANY, minecraft.player)).getType() == HitResult.Type.MISS;
	}
	private static boolean dangerous(String id) {
		return Set.of("minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:magma_block", "minecraft:campfire",
			"minecraft:soul_campfire", "minecraft:cactus", "minecraft:powder_snow").contains(id);
	}
	private static Map<String, Integer> position(BlockPos pos) { return Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ()); }
	private static List<Map<String, Object>> records(Map<BlockPos, String> blocks, int limit) {
		return blocks.entrySet().stream().limit(limit).map(entry -> Map.<String, Object>of("position", position(entry.getKey()), "blockId", entry.getValue())).toList();
	}
	private static int radius(JsonObject args) {
		if (!args.has("radius")) return 12;
		try {
			int value = args.get("radius").getAsBigDecimal().intValueExact();
			if (!args.getAsJsonPrimitive("radius").isNumber() || value < 4 || value > 24) throw new ArithmeticException();
			return value;
		} catch (RuntimeException exception) { throw new JsonParseException("radius must be integer 4..24"); }
	}
}
