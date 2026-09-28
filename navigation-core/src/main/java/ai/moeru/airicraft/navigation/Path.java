package ai.moeru.airicraft.navigation;

import java.util.ArrayList;
import java.util.List;

/** An ordered chain of steps; empty when the start already satisfies the goal. */
public record Path(GridPos start, List<Step> steps, double cost) {
	public Path {
		steps = List.copyOf(steps);
		GridPos expected = start;
		for (Step step : steps) {
			if (!step.from().equals(expected)) throw new IllegalArgumentException("disconnected path at " + step.from());
			expected = step.to();
		}
	}

	public GridPos end() {
		return steps.isEmpty() ? start : steps.getLast().to();
	}

	/** Feet cells from start to end. */
	public List<GridPos> positions() {
		List<GridPos> positions = new ArrayList<>(steps.size() + 1);
		positions.add(start);
		for (Step step : steps) positions.add(step.to());
		return positions;
	}

	/** Euclidean length through the cell centres, in blocks. */
	public double length() {
		double length = 0;
		for (Step step : steps) {
			length += Math.sqrt(step.dx() * step.dx() + step.dy() * step.dy() + step.dz() * step.dz());
		}
		return length;
	}

	public int breaks() {
		int count = 0;
		for (Step step : steps) count += step.breaks().size();
		return count;
	}

	public int places() {
		int count = 0;
		for (Step step : steps) if (step.place() != null) count++;
		return count;
	}
}
