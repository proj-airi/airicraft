package ai.moeru.airicraft.navigation;

/** The outcome of one {@link PathSearch}. */
public sealed interface SearchResult permits SearchResult.Found, SearchResult.Partial, SearchResult.Unreachable,
	SearchResult.Cancelled {
	Stats stats();

	/** Why a search stopped short of the goal. */
	enum Reason {
		/** Every reachable node was explored. */
		NO_ROUTE,
		/** Exploration reached unloaded terrain; more may load as the player moves. */
		UNLOADED_FRONTIER,
		NODE_BUDGET,
		TIME_BUDGET,
		/** A waypoint on the way to a goal farther than one segment; plan again from its end. */
		SEGMENT
	}

	/**
	 * @param expanded       nodes taken from the open set
	 * @param elapsedNanos   wall time spent searching
	 * @param touchedUnloaded whether any lookup hit unloaded terrain
	 */
	record Stats(int expanded, long elapsedNanos, boolean touchedUnloaded) {
		public double millis() {
			return elapsedNanos / 1_000_000.0;
		}
	}

	/** A complete path into the goal. */
	record Found(Path path, Stats stats) implements SearchResult { }

	/** The best prefix toward the goal: it ends at the explored node with the lowest heuristic. */
	record Partial(Path path, Reason reason, Stats stats) implements SearchResult { }

	/** No move leaves the start toward the goal. */
	record Unreachable(Reason reason, Stats stats) implements SearchResult { }

	record Cancelled(Stats stats) implements SearchResult { }

	default String outcome() {
		return switch (this) {
			case Found found -> "found";
			case Partial partial -> "partial";
			case Unreachable unreachable -> "unreachable";
			case Cancelled cancelled -> "cancelled";
		};
	}
}
