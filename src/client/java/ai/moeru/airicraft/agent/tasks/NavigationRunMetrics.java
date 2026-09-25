package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;

import java.util.LinkedHashMap;
import java.util.Map;

/** Measures one follow or navigate task for backend baselines; reported on the debug timeline, not to the planner. */
final class NavigationRunMetrics {
	private final long startTick;
	private long lastTick;
	private long activeTicks;
	private int replans;
	private boolean sampled;
	private double startX, startY, startZ;
	private double lastX, lastY, lastZ;
	private double pathLength;

	NavigationRunMetrics(long startTick) {
		this.startTick = startTick;
		this.lastTick = startTick;
	}

	/** One active tick; the position is optional because a missing player still consumes the budget. */
	void observe(long tick, WaterStallRecovery.Sample position) {
		activeTicks++;
		lastTick = Math.max(lastTick, tick);
		if (position == null) {
			return;
		}
		if (sampled) {
			pathLength += distance(lastX, lastY, lastZ, position.x(), position.y(), position.z());
		}
		else {
			startX = position.x();
			startY = position.y();
			startZ = position.z();
			sampled = true;
		}
		lastX = position.x();
		lastY = position.y();
		lastZ = position.z();
	}

	void replanned() {
		replans++;
	}

	Map<String, Object> summary(GoalPosition target, boolean stalled) {
		LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
		summary.put("elapsedTicks", lastTick - startTick);
		summary.put("activeTicks", activeTicks);
		summary.put("replans", replans);
		summary.put("stalled", stalled);
		if (!sampled) {
			return summary;
		}
		summary.put("pathLength", round(pathLength));
		if (target != null) {
			summary.put("startDistance", round(distanceTo(target, startX, startY, startZ)));
			summary.put("endDistance", round(distanceTo(target, lastX, lastY, lastZ)));
		}
		return summary;
	}

	/** Feet distance to the target cell's floor center; horizontal-only targets ignore height. */
	private static double distanceTo(GoalPosition target, double x, double y, double z) {
		double targetY = target.exactY() ? target.y() : y;
		return distance(x, y, z, target.x() + 0.5D, targetY, target.z() + 0.5D);
	}

	private static double distance(double x1, double y1, double z1, double x2, double y2, double z2) {
		double dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static double round(double value) {
		return Math.round(value * 100.0D) / 100.0D;
	}
}
