package ai.moeru.airicraft.navigation;

import java.util.List;

/**
 * A set of feet cells to reach. Search stops on the first node in the goal, and path following
 * reports arrival with the same predicate.
 */
public sealed interface Goal permits Goal.Block, Goal.XZ, Goal.Near, Goal.AnyOf {
	boolean isGoal(int x, int y, int z);

	/** Estimated cost to the goal; used to order search, so it should not overestimate much. */
	double heuristic(int x, int y, int z);

	default boolean isGoal(GridPos pos) {
		return isGoal(pos.x(), pos.y(), pos.z());
	}

	/** Exactly this feet cell. */
	record Block(int x, int y, int z) implements Goal {
		@Override public boolean isGoal(int px, int py, int pz) { return px == x && py == y && pz == z; }
		@Override public double heuristic(int px, int py, int pz) { return horizontal(x - px, z - pz) + vertical(y, py); }
	}

	/** Any height in this column. */
	record XZ(int x, int z) implements Goal {
		@Override public boolean isGoal(int px, int py, int pz) { return px == x && pz == z; }
		@Override public double heuristic(int px, int py, int pz) { return horizontal(x - px, z - pz); }
	}

	/** Within a Euclidean radius of a cell. */
	record Near(int x, int y, int z, int radius) implements Goal {
		public Near {
			if (radius < 0) throw new IllegalArgumentException("negative radius");
		}

		@Override
		public boolean isGoal(int px, int py, int pz) {
			long dx = px - x, dy = py - y, dz = pz - z;
			return dx * dx + dy * dy + dz * dz <= (long) radius * radius;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			return horizontal(x - px, z - pz) + vertical(y, py);
		}
	}

	/** Any of several goals, such as the work positions around an interaction target. */
	record AnyOf(List<Goal> goals) implements Goal {
		public AnyOf {
			goals = List.copyOf(goals);
			if (goals.isEmpty()) throw new IllegalArgumentException("no goals");
		}

		@Override
		public boolean isGoal(int px, int py, int pz) {
			for (Goal goal : goals) if (goal.isGoal(px, py, pz)) return true;
			return false;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			double best = Double.POSITIVE_INFINITY;
			for (Goal goal : goals) best = Math.min(best, goal.heuristic(px, py, pz));
			return best;
		}
	}

	/** Octile distance: diagonal steps cost the square root of two. */
	static double horizontal(int dx, int dz) {
		int x = Math.abs(dx), z = Math.abs(dz);
		int straight = Math.abs(x - z), diagonal = Math.min(x, z);
		return (straight + diagonal * Math.sqrt(2)) * Costs.HEURISTIC_PER_BLOCK;
	}

	static double vertical(int goalY, int y) {
		if (y > goalY) return Costs.fallTicks(2) / 2 * (y - goalY);
		if (y < goalY) return (goalY - y) * Costs.JUMP_ONE_BLOCK;
		return 0;
	}
}
