package ai.moeru.airicraft.sim.input;

/** Pure angle-math helpers for the human-limit turn-rate clamp. Unit-testable. */
public final class LookClamp {
	private LookClamp() {}

	/** Clamps {@code delta} into {@code [-maxDelta, +maxDelta]}. */
	public static float clampDelta(float delta, float maxDelta) {
		return Math.max(-maxDelta, Math.min(maxDelta, delta));
	}

	/** Wraps an angle into {@code (-180, 180]}. */
	public static float wrapDegrees(float angle) {
		float wrapped = angle % 360.0f;
		if (wrapped > 180.0f) {
			wrapped -= 360.0f;
		} else if (wrapped <= -180.0f) {
			wrapped += 360.0f;
		}
		return wrapped;
	}

	/** Moves {@code current} toward {@code target} by at most {@code maxDeltaPerTick} (angle-aware). */
	public static float moveToward(float current, float target, float maxDeltaPerTick) {
		float delta = wrapDegrees(target - current);
		return wrapDegrees(current + clampDelta(delta, maxDeltaPerTick));
	}

	/** Minecraft yaw (deg) pointing from (px,pz) toward (tx,tz). MC yaw: 0 = +Z, positive = clockwise from above. */
	public static float yawTo(double px, double pz, double tx, double tz) {
		return (float) Math.toDegrees(Math.atan2(-(tx - px), tz - pz));
	}

	/** Minecraft pitch (deg) from eye height {@code dy} over horizontal distance {@code horizontal}. */
	public static float pitchTo(double dy, double horizontal) {
		return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
	}
}
