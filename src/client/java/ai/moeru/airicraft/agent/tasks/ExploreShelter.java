package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * A night shelter for a body far from any base: dig three blocks straight down and put a block over the head, as a
 * player caught out at dusk does. The body ends with its feet at the bottom, solid walls around its feet and head, and
 * the roof one block over its head, so nothing can reach, see or fall in on it. Sand walls are fine (they rest on the
 * ground under them) but a sand roof would fall onto the head, so the roof takes a block that does not fall; without one
 * there is no shelter. Pure, so it is tested without a world.
 */
final class ExploreShelter {
	static final int DEPTH = 3;
	/** Slowest block the dig accepts, in ticks with the best carried tool: sand or dirt by hand is 15, sandstone 80. */
	static final int MAX_BREAK_TICKS = 100;
	/** How far from the feet a shelter column may be. */
	static final int SEARCH_RADIUS = 3;

	interface Cells {
		/** A full block the body cannot pass. */
		boolean solid(BlockPos pos);
		/** Ticks to break with the best carried tool; negative when it cannot be broken (or holds a block entity). */
		int breakTicks(BlockPos pos);
		boolean fluid(BlockPos pos);
		boolean falling(BlockPos pos);
		/** Room for the body: no collision and no fluid. */
		boolean open(BlockPos pos);
	}

	/** Where the body stands to start, the cells to dig in order, and where the roof goes against which block. */
	record Plan(BlockPos top, List<BlockPos> dig, BlockPos roof, BlockPos roofSupport) {
		BlockPos bottom() { return dig.getLast(); }
	}

	/** The nearest column around {@code feet} where a shelter fits, or null. */
	static Plan near(Cells cells, BlockPos feet) {
		Plan best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (int dy = 1; dy >= -1; dy--) {
			for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
				for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
					int distance = dx * dx + dz * dz + dy * dy;
					if (distance >= bestDistance) continue;
					Plan plan = plan(cells, feet.offset(dx, dy, dz));
					if (plan == null) continue;
					best = plan;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/** A shelter whose top cell (where the body stands to start) is {@code top}, or null when the column is unsafe or slow. */
	static Plan plan(Cells cells, BlockPos top) {
		if (!cells.open(top) || !cells.open(top.above()) || cells.falling(top.above(2))) return null;
		List<BlockPos> dig = new ArrayList<>();
		for (int depth = 1; depth <= DEPTH; depth++) dig.add(top.below(depth));
		BlockPos floor = top.below(DEPTH + 1);
		if (!cells.solid(floor) || cells.fluid(floor)) return null;
		for (BlockPos cell : dig) {
			int ticks = cells.breakTicks(cell);
			if (ticks < 0 || ticks > MAX_BREAK_TICKS || cells.fluid(cell)) return null;
			for (Direction side : Direction.Plane.HORIZONTAL) {
				BlockPos wall = cell.relative(side);
				if (cells.fluid(wall)) return null;
				// The two cells the body ends in are closed on every side.
				if (!cell.equals(dig.getFirst()) && !cells.solid(wall)) return null;
			}
		}
		BlockPos roof = dig.getFirst();
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos support = roof.relative(side);
			if (cells.solid(support)) return new Plan(top, List.copyOf(dig), roof, support);
		}
		return null;
	}

	enum Step { DIG, WAIT, ROOF, CLOSED }

	/** The next thing to do, from the body's feet height and whether the roof is in place. */
	static Step step(Plan plan, int feetY, boolean onGround, boolean roofSolid) {
		if (feetY <= plan.bottom().getY() && roofSolid) return Step.CLOSED;
		if (feetY > plan.bottom().getY()) return onGround ? Step.DIG : Step.WAIT;
		return onGround ? Step.ROOF : Step.WAIT;
	}
}
