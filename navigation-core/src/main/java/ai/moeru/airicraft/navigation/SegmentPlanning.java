package ai.moeru.airicraft.navigation;

import java.util.function.BooleanSupplier;

/**
 * Long routes are planned in segments. A goal farther than {@link #SEGMENT_BLOCKS} is first
 * approached through a waypoint that far toward it; only when the waypoint cannot be reached does
 * the search aim at the goal itself and return its best partial path. A waypoint counts as reached only
 * where the head is out of water, since the body stops there to plan again. Searching straight for a goal
 * beyond loaded terrain would explore the whole snapshot before giving up. Both searches together
 * stay within one budget.
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
		if (!deepGoal(start, target)) return planLevel(terrain, policy, start, goal, target, budget, cancelled);
		// Through open caves the search finds a deep goal quickly; through rock it would only spend the budget.
		SearchBudget searchBudget = new SearchBudget(Math.max(1, budget.maxExpanded() / 3),
			Math.max(1, budget.timeoutNanos() / 3), budget.minPartialBlocks(), budget.heuristicWeight());
		SearchResult result = planLevel(terrain, policy, start, goal, target, searchBudget, cancelled);
		return descendWhenStuck(terrain, policy, start, target, result);
	}

	static boolean deepGoal(GridPos start, GridPos target) {
		return target != null && start.y() - target.y() >= Staircase.MIN_DROP;
	}

	/**
	 * A goal far below solid ground is out of the search's reach: every broken block costs, so the
	 * budget runs out near the surface. When the search did not get the body most of the way down,
	 * a dug {@link Staircase} toward the target comes back as the next segment instead.
	 */
	static SearchResult descendWhenStuck(TerrainView terrain, MovementPolicy policy, GridPos start, GridPos target,
		SearchResult result) {
		if (result instanceof SearchResult.Found || result instanceof SearchResult.Cancelled) return result;
		int searchedDescent = result instanceof SearchResult.Partial partial ? start.y() - partial.path().end().y() : 0;
		if (searchedDescent >= (start.y() - target.y()) / 2) return result;
		Path stairs = Staircase.build(terrain, policy, start, target, Staircase.MAX_STEPS);
		if (stairs == null || start.y() - stairs.end().y() <= searchedDescent) return result;
		return new SearchResult.Partial(stairs, SearchResult.Reason.SEGMENT, result.stats());
	}

	private static SearchResult planLevel(TerrainView terrain, MovementPolicy policy, GridPos start, Goal goal, GridPos target,
		SearchBudget budget, BooleanSupplier cancelled) {
		Goal waypoint = target == null ? null : waypoint(start, target.x(), target.z());
		if (waypoint == null) return PathSearch.search(terrain, policy, start, goal, budget, cancelled);
		SearchBudget segmentBudget = new SearchBudget(Math.min(budget.maxExpanded(), WAYPOINT_MAX_EXPANDED),
			budget.timeoutNanos() / 3, budget.minPartialBlocks(), budget.heuristicWeight());
		SearchResult toward = PathSearch.search(terrain, policy, start, waypoint, segmentBudget, cancelled, true);
		if (toward instanceof SearchResult.Found found) {
			return new SearchResult.Partial(found.path(), SearchResult.Reason.SEGMENT, toward.stats());
		}
		if (toward instanceof SearchResult.Cancelled) return toward;
		// The fallback gets what the waypoint search left, so both together stay within the budget.
		SearchResult.Stats spent = toward.stats();
		SearchBudget remaining = new SearchBudget(Math.max(1, budget.maxExpanded() - spent.expanded()),
			Math.max(1, budget.timeoutNanos() - spent.elapsedNanos()), budget.minPartialBlocks(), budget.heuristicWeight());
		SearchResult direct = PathSearch.search(terrain, policy, start, goal, remaining, cancelled);
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
