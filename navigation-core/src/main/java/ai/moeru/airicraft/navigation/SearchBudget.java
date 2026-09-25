package ai.moeru.airicraft.navigation;

/**
 * Limits for one search.
 *
 * @param maxExpanded      nodes taken from the open set before returning the best partial path
 * @param timeoutNanos     wall time before returning the best partial path
 * @param minPartialBlocks a partial path shorter than this, from start to end, counts as unreachable
 */
public record SearchBudget(int maxExpanded, long timeoutNanos, double minPartialBlocks) {
	public SearchBudget {
		if (maxExpanded < 1 || timeoutNanos < 1 || minPartialBlocks < 0) throw new IllegalArgumentException("invalid budget");
	}

	public static SearchBudget defaults() {
		return new SearchBudget(250_000, 1_000_000_000L, 2.0);
	}

	public static SearchBudget ofMillis(int maxExpanded, long millis) {
		return new SearchBudget(maxExpanded, millis * 1_000_000L, 2.0);
	}
}
