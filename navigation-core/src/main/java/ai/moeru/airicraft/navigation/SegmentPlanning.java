package ai.moeru.airicraft.navigation;

import java.util.function.BooleanSupplier;

/**
 * Long routes are planned in segments. A goal farther than {@link #SEGMENT_BLOCKS} is first
 * approached through a waypoint that far toward it; only when the waypoint cannot be reached does
 * the search aim at the goal itself and return its best partial path. Searching straight for a goal
 * beyond loaded terrain would explore the whole snapshot before giving up.
 */
public final class SegmentPlanning {
	public static final int SEGMENT_BLOCKS = 64;
	public static final int WAYPOINT_RADIUS = 8;
	private static final int WAYPOINT_MAX_EXPANDED = 60_000;

	private SegmentPlanning() {
	}

	/** A waypoint goal {@link #SEGMENT_BLOCKS} toward the target column, or null when the target is closer. */
	public static Goal waypoint(GridPos start, int targetX, int targetZ) {
		double dx = targetX - start.x(), dz = targetZ - start.z();
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance <= SEGMENT_BLOCKS + WAYPOINT_RADIUS) return null;
		double scale = SEGMENT_BLOCKS / distance;
		return new Goal.NearXZ((int) Math.round(start.x() + dx * scale), (int) Math.round(start.z() + dz * scale), WAYPOINT_RADIUS);
	}

	/**
	 * Plans toward {@code goal}, whose representative column is {@code target}. A waypoint segment
	 * comes back as {@link SearchResult.Partial} with {@link SearchResult.Reason#SEGMENT}.
	 */
	public static SearchResult plan(TerrainView terrain, MovementPolicy policy, GridPos start, Goal goal, GridPos target,
		SearchBudget budget, BooleanSupplier cancelled) {
		Goal waypoint = target == null ? null : waypoint(start, target.x(), target.z());
		if (waypoint == null) return PathSearch.search(terrain, policy, start, goal, budget, cancelled);
		SearchBudget segmentBudget = new SearchBudget(Math.min(budget.maxExpanded(), WAYPOINT_MAX_EXPANDED),
			budget.timeoutNanos() / 3, budget.minPartialBlocks(), budget.heuristicWeight());
		SearchResult toward = PathSearch.search(terrain, policy, start, waypoint, segmentBudget, cancelled);
		if (toward instanceof SearchResult.Found found) {
			return new SearchResult.Partial(found.path(), SearchResult.Reason.SEGMENT, toward.stats());
		}
		if (toward instanceof SearchResult.Cancelled) return toward;
		SearchResult direct = PathSearch.search(terrain, policy, start, goal, budget, cancelled);
		SearchResult.Stats first = toward.stats(), second = direct.stats();
		SearchResult.Stats both = new SearchResult.Stats(first.expanded() + second.expanded(),
			first.elapsedNanos() + second.elapsedNanos(), first.touchedUnloaded() || second.touchedUnloaded());
		return switch (direct) {
			case SearchResult.Found found -> new SearchResult.Found(found.path(), both);
			case SearchResult.Partial partial -> new SearchResult.Partial(partial.path(), partial.reason(), both);
			case SearchResult.Unreachable unreachable -> new SearchResult.Unreachable(unreachable.reason(), both);
			case SearchResult.Cancelled cancelledResult -> new SearchResult.Cancelled(both);
		};
	}
}
