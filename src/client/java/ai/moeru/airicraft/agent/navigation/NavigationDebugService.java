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
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

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
	public static Planning start(Minecraft minecraft, int x, int y, int z, boolean exactY) {
		if (minecraft.player == null || minecraft.level == null) throw new BridgeUnavailableException("world_not_loaded", "No world is loaded");
		MovementPolicy policy = NavigationPolicies.forPlayer(minecraft, MovementPolicy.defaults().waterPenalty());
		if (policy == null) throw new BridgeUnavailableException("travel_policy_unavailable", "The travel policy could not be loaded");
		var player = minecraft.player;
		GridPos start = new BodyState(player.getX(), player.getY(), player.getZ(), 0, true, false, false, false, 0).feet();
		Goal goal = exactY ? new Goal.Block(x, y, z) : new Goal.XZ(x, z);
		GridPos target = new GridPos(x, exactY ? y : start.y(), z);
		WorldTerrainSnapshot snapshot = WorldTerrainSnapshot.capture(minecraft.level, start, target, exactY,
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

	/** Highlights the path, its edits and doors, at most {@link #MAX_HIGHLIGHTS} in all. */
	private static int highlight(Path path, HighlightManager highlights, int seconds) {
		long duration = seconds * 1000L;
		List<Mark> marks = new ArrayList<>();
		for (Step step : path.steps()) {
			for (GridPos cell : step.breaks()) marks.add(new Mark(pos(cell), BREAK_COLOR, "break"));
			if (step.place() != null) marks.add(new Mark(pos(step.place()), PLACE_COLOR, "place"));
			for (GridPos door : step.doors()) marks.add(new Mark(pos(door), DOOR_COLOR, "door"));
			marks.add(new Mark(pos(step.to()).below(), PATH_COLOR, null));
		}
		int count = Math.min(MAX_HIGHLIGHTS, marks.size());
		for (Mark mark : marks.subList(0, count)) highlights.addBlock(mark.pos(), mark.color(), duration, mark.label());
		return count;
	}

	private record Mark(BlockPos pos, int color, String label) { }

	private static BlockPos pos(GridPos cell) {
		return new BlockPos(cell.x(), cell.y(), cell.z());
	}

	private static double round(double value) {
		return Math.round(value * 100) / 100.0;
	}
}
