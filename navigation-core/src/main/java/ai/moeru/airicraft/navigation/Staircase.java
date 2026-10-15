package ai.moeru.airicraft.navigation;

import java.util.ArrayList;
import java.util.List;

/**
 * A dug staircase straight down toward a goal far below. A* pays every broken block, so a goal twenty
 * or more blocks under solid stone exhausts its budget long before it reaches the depth, and returns a
 * partial path that never goes down. The staircase is what a player digs instead: one step forward and
 * one down, built from the move catalog's own dig-down moves, so each step carries the same edits and
 * safety checks as a searched one (no breaking next to fluids or under falling blocks, no landing in
 * lava). It turns toward the target column, and around anything it cannot dig.
 */
public final class Staircase {
	/** Planning only switches to stairs for goals at least this far below the feet. */
	public static final int MIN_DROP = 8;
	/** Steps per segment; the body plans again from the bottom, as with horizontal segments. */
	public static final int MAX_STEPS = 32;
	private static final int[] DX = {0, 0, 1, -1};
	private static final int[] DZ = {-1, 1, 0, 0};
	/** {@link Moves#evaluate} indexes of the dig-down moves, in the same direction order. */
	private static final int DESCEND_MOVE = 12;

	private Staircase() {
	}

	/**
	 * Digs down from {@code start} toward {@code target} until its level, at most {@code maxSteps}
	 * steps. Returns null when not even one step can be dug.
	 */
	public static Path build(TerrainView terrain, MovementPolicy policy, GridPos start, GridPos target, int maxSteps) {
		if (!policy.allowBreak() || target.y() >= start.y()) return null;
		Moves moves = new Moves(terrain, policy);
		Moves.Result result = new Moves.Result();
		Moves.Overlay overlay = null;
		List<Step> steps = new ArrayList<>();
		double cost = 0;
		GridPos current = start;
		int heading = -1;
		while (steps.size() < maxSteps && current.y() > target.y()) {
			moves.overlay(overlay);
			int kind = moves.probe(current.x(), current.y(), current.z());
			double base = moves.probeBase();
			if (kind == Moves.KIND_INVALID) {
				if (!steps.isEmpty()) break;
				kind = Moves.KIND_STAND;
				base = current.y();
			}
			if (kind != Moves.KIND_STAND) break;
			int chosen = -1;
			for (int direction : directions(current, target, heading)) {
				if (!moves.evaluate(DESCEND_MOVE + direction, current.x(), current.y(), current.z(), kind, base, result)) continue;
				if (result.type != MoveType.DESCEND || result.y != current.y() - 1) continue;
				if (moves.submergedAt(result.x, result.y, result.z)) continue;
				chosen = direction;
				break;
			}
			if (chosen < 0) break;
			Step step = result.toStep(current);
			overlay = result.overlay(overlay);
			steps.add(step);
			cost += step.cost();
			current = step.to();
			heading = chosen;
		}
		return steps.isEmpty() ? null : new Path(start, steps, cost);
	}

	/**
	 * Directions to try, best first: keep the current heading while it still closes the horizontal
	 * distance (or the target is straight below), otherwise whichever closes it most.
	 */
	static int[] directions(GridPos from, GridPos target, int heading) {
		Integer[] order = {0, 1, 2, 3};
		java.util.Arrays.sort(order, (a, b) -> {
			int scoreA = score(from, target, heading, a), scoreB = score(from, target, heading, b);
			return Integer.compare(scoreA, scoreB);
		});
		int[] result = new int[4];
		for (int i = 0; i < 4; i++) result[i] = order[i];
		return result;
	}

	private static int score(GridPos from, GridPos target, int heading, int direction) {
		int before = Math.abs(target.x() - from.x()) + Math.abs(target.z() - from.z());
		int after = Math.abs(target.x() - from.x() - DX[direction]) + Math.abs(target.z() - from.z() - DZ[direction]);
		int closing = after - before;
		// Straight below, or already past it on this axis: a turn costs nothing, but keep going straight.
		int keep = direction == heading ? 0 : 1;
		return closing * 2 + keep;
	}
}
