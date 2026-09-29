package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.EnumSet;

/**
 * One source of percepts, sampled once per client tick within its budget. A sensor decides only what the player
 * could perceive; noticing sensors hand honest candidates to the salience rules instead of publishing percepts.
 */
public interface Sensor {
	/** Stable id; it is also the sensor's lifecycle participant id. */
	String id();

	void sample(SensorContext context, PerceptSink sink);

	/** Boundaries this sensor resets on. */
	default EnumSet<LifecycleBoundary> boundaries() {
		return EnumSet.allOf(LifecycleBoundary.class);
	}

	default void onBoundary(LifecycleBoundary boundary, long tick) {
	}
}
