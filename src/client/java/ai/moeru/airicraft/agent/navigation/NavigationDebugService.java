package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.HighlightManager;
import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.Goal;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.Path;
import ai.moeru.airicraft.navigation.SearchBudget;
import ai.moeru.airicraft.navigation.SearchResult;
import ai.moeru.airicraft.navigation.Step;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dry-run plans for `airicraft agent debug navigation plan`: nothing actuates; the path is highlighted. */
public final class NavigationDebugService {
	private static final SearchBudget BUDGET = new SearchBudget(250_000, 2_000_000_000L, 2.0);
	private static final int MAX_HIGHLIGHTS = 256;
	private static final int PATH_COLOR = 0x6633CC66;
	private static final int BREAK_COLOR = 0x88DD3333;
	private static final int PLACE_COLOR = 0x883366DD;
	private static final int DOOR_COLOR = 0x88DDAA22;

	private NavigationDebugService() {
	}

	/** Snapshots the terrain and starts the search. Client thread. */
	public static Planning start(MinecraftClient client, int x, int y, int z, boolean exactY) {
		if (client.player == null || client.world == null) throw new BridgeUnavailableException("world_not_loaded", "No world is loaded");
		MovementPolicy policy = NavigationPolicies.forPlayer(client, MovementPolicy.defaults().waterPenalty());
		if (policy == null) throw new BridgeUnavailableException("travel_policy_unavailable", "The travel policy could not be loaded");
		var player = client.player;
		GridPos start = new BodyState(player.getX(), player.getY(), player.getZ(), 0, true, false, false, false, 0).feet();
		Goal goal = exactY ? new Goal.Block(x, y, z) : new Goal.XZ(x, z);
		GridPos target = new GridPos(x, exactY ? y : start.y(), z);
		WorldTerrainSnapshot snapshot = WorldTerrainSnapshot.capture(client.world, start, target, exactY,
			MinecraftCellClassifier.forPlayer(player));
		return new Planning(NavigationPlanner.shared().submit(snapshot, policy, start, goal, target, BUDGET), snapshot, policy, goal);
	}

	public record Planning(NavigationPlanner.Pending pending, WorldTerrainSnapshot snapshot, MovementPolicy policy, Goal goal) { }

	/** Describes a finished plan and highlights it. Client thread. */
	public static Map<String, Object> describe(Planning planning, SearchResult result, HighlightManager highlights, int highlightSeconds) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("outcome", result.outcome());
		if (result instanceof SearchResult.Partial partial) payload.put("reason", partial.reason().name());
		if (result instanceof SearchResult.Unreachable unreachable) payload.put("reason", unreachable.reason().name());
		payload.put("start", planning.pending().start().toString());
		payload.put("goal", planning.goal().toString());
		payload.put("expanded", result.stats().expanded());
		payload.put("searchMillis", round(result.stats().millis()));
		payload.put("captureMillis", round(planning.snapshot().captureMillis()));
		payload.put("snapshotCells", planning.snapshot().cells());
		payload.put("placeableBlocks", planning.policy().placeableBlocks());
		Path path = result instanceof SearchResult.Found found ? found.path()
			: result instanceof SearchResult.Partial partial ? partial.path() : null;
		if (path == null) return payload;
		payload.put("end", path.end().toString());
		payload.put("cost", round(path.cost()));
		payload.put("length", round(path.length()));
		payload.put("breaks", path.breaks());
		payload.put("places", path.places());
		List<String> steps = new ArrayList<>();
		for (Step step : path.steps()) steps.add(describe(step));
		payload.put("steps", steps);
		payload.put("highlighted", highlightSeconds > 0 ? highlight(path, highlights, highlightSeconds) : 0);
		return payload;
	}

	private static String describe(Step step) {
		StringBuilder text = new StringBuilder(step.type().name()).append(" -> ").append(step.to());
		if (!step.breaks().isEmpty()) text.append(" break ").append(step.breaks());
		if (step.place() != null) text.append(" place ").append(step.place()).append(" against ").append(step.placeAgainst());
		if (!step.doors().isEmpty()) text.append(" doors ").append(step.doors());
		return text.toString();
	}

	private static int highlight(Path path, HighlightManager highlights, int seconds) {
		long duration = seconds * 1000L;
		int count = 0;
		for (Step step : path.steps()) {
			if (count >= MAX_HIGHLIGHTS) break;
			for (GridPos cell : step.breaks()) {
				highlights.addBlock(pos(cell), BREAK_COLOR, duration, "break");
				count++;
			}
			if (step.place() != null) {
				highlights.addBlock(pos(step.place()), PLACE_COLOR, duration, "place");
				count++;
			}
			for (GridPos door : step.doors()) {
				highlights.addBlock(pos(door), DOOR_COLOR, duration, "door");
				count++;
			}
			highlights.addBlock(pos(step.to()).down(), PATH_COLOR, duration, null);
			count++;
		}
		return count;
	}

	private static BlockPos pos(GridPos cell) {
		return new BlockPos(cell.x(), cell.y(), cell.z());
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}
}
