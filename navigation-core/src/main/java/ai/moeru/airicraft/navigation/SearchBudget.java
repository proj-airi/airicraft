package ai.moeru.airicraft.navigation;

/**
 * Limits for one search.
 *
 * @param maxExpanded      nodes taken from the open set before returning the best partial path
 * @param timeoutNanos     wall time before returning the best partial path
 * @param minPartialBlocks a partial path shorter than this, from start to end, counts as unreachable
 * @param heuristicWeight  weight on the heuristic; above 1 trades path cost for far fewer expansions,
 *                         and a path costs at most about this factor more than the cheapest
 */
public record SearchBudget(int maxExpanded, long timeoutNanos, double minPartialBlocks, double heuristicWeight) {
	/** Measured on rough terrain: about 6% more path cost for roughly fifty times fewer expansions than 1.0. */
	public static final double DEFAULT_HEURISTIC_WEIGHT = 1.5;

	public SearchBudget {
		if (maxExpanded < 1 || timeoutNanos < 1 || minPartialBlocks < 0 || !(heuristicWeight >= 1)) {
			throw new IllegalArgumentException("invalid budget");
		}
	}

	public SearchBudget(int maxExpanded, long timeoutNanos, double minPartialBlocks) {
		this(maxExpanded, timeoutNanos, minPartialBlocks, DEFAULT_HEURISTIC_WEIGHT);
	}

	public static SearchBudget defaults() {
		return new SearchBudget(250_000, 1_000_000_000L, 2.0);
	}

	public static SearchBudget ofMillis(int maxExpanded, long millis) {
		return new SearchBudget(maxExpanded, millis * 1_000_000L, 2.0);
	}

	public SearchBudget withHeuristicWeight(double weight) {
		return new SearchBudget(maxExpanded, timeoutNanos, minPartialBlocks, weight);
	}
}
