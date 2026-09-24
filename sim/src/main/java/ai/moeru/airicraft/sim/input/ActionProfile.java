package ai.moeru.airicraft.sim.input;

/**
 * Human-limit constraints enforced by {@link SimInputExecutor}. The optimizer may
 * tune the policy, never these limits.
 *
 * @param maxTurnDegPerTick   max yaw/pitch change per tick (45 deg = 900 deg/s: fast human flick)
 * @param attackIntervalTicks min ticks between accepted attack presses (2 = 10 clicks/s)
 * @param reachBlocks         melee reach used for attack legality (vanilla player attack range)
 * @param aimToleranceBlocks  expansion applied to target hitbox for the crosshair raycast
 * @param obsDelayTicks       observation staleness fed to the policy (human reaction time)
 */
public record ActionProfile(
		float maxTurnDegPerTick,
		int attackIntervalTicks,
		double reachBlocks,
		double aimToleranceBlocks,
		int obsDelayTicks) {

	public static ActionProfile defaults() {
		return new ActionProfile(45.0f, 2, 3.0, 0.1, 3);
	}
}
