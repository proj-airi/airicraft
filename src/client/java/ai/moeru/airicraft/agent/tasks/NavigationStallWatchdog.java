package ai.moeru.airicraft.agent.tasks;

/** Movement measured in active game ticks, so debug pauses do not consume the budget. */
final class NavigationStallWatchdog {
	private static final long STALL_TICKS = 100;
	private static final double MIN_MOVEMENT_SQUARED = 0.75 * 0.75;
	private long anchorTick = -1;
	private double anchorX, anchorY, anchorZ;
	private double supportedY = Double.NaN;
	private final java.util.Map<String, Float> breakingProgressByTarget = new java.util.HashMap<>();

	boolean observe(long tick, ai.moeru.airicraft.agent.baritone.BaritoneFacade.NavigationProgress sample) {
		if (Double.isNaN(supportedY) || sample.supported()) supportedY = sample.y();
		boolean stalled = observe(tick, sample.x(), supportedY, sample.z());
		// Brief interruptions and target switching must not count the same partial
		// break twice. Keep high-water marks until the player actually moves.
		if (sample.breakingTarget() != null
			&& sample.breakingProgress() > breakingProgressByTarget.getOrDefault(sample.breakingTarget(), 0f)) {
			breakingProgressByTarget.put(sample.breakingTarget(), sample.breakingProgress());
			anchorTick = tick;
			return false;
		}
		return stalled;
	}

	boolean observe(long tick, double x, double y, double z) {
		double dx = x - anchorX, dy = y - anchorY, dz = z - anchorZ;
		if (anchorTick < 0 || tick < anchorTick || dx * dx + dy * dy + dz * dz >= MIN_MOVEMENT_SQUARED) {
			breakingProgressByTarget.clear();
			anchorTick = tick;
			anchorX = x;
			anchorY = y;
			anchorZ = z;
		}
		return tick - anchorTick >= STALL_TICKS;
	}

	void clear() { anchorTick = -1; supportedY = Double.NaN; breakingProgressByTarget.clear(); }
}
