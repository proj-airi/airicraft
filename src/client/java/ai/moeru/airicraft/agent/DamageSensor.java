package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.perception.PerceptSink;
import ai.moeru.airicraft.agent.perception.Sensor;
import ai.moeru.airicraft.agent.perception.SensorContext;
import java.util.EnumSet;

/**
 * Damage attribution. Health updates and damage sources arrive as client callbacks (the ingress, kept in the
 * runtime); each tick the sensor prunes stale attributions.
 */
final class DamageSensor implements Sensor {
	static final String ID = "damage";

	private final LocalDamageTracker tracker;

	DamageSensor(LocalDamageTracker tracker) {
		this.tracker = tracker;
	}

	@Override public String id() {
		return ID;
	}

	@Override public EnumSet<LifecycleBoundary> boundaries() {
		return EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.WORLD_LOADED, LifecycleBoundary.RESPAWNED,
			LifecycleBoundary.SHUTDOWN);
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		tracker.pruneStale(context.tick());
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		if (boundary == LifecycleBoundary.WORLD_LEFT || boundary == LifecycleBoundary.SHUTDOWN) tracker.clear();
		else tracker.onLifecycleReset(tick);
	}
}
