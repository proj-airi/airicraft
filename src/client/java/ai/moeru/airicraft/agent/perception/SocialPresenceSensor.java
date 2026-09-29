package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import ai.moeru.airicraft.agent.events.EventPublisher;
import ai.moeru.airicraft.agent.social.NearbyPlayerTracker;
import java.util.EnumSet;

/** Players joining and leaving the nearby radius; the tracker publishes under its own source. */
public final class SocialPresenceSensor implements Sensor {
	public static final String ID = "nearby";

	private final NearbyPlayerTracker tracker;
	private final EventPublisher events;

	public SocialPresenceSensor(NearbyPlayerTracker tracker, EventPublisher events) {
		this.tracker = tracker;
		this.events = events;
	}

	@Override public String id() {
		return ID;
	}

	@Override public EnumSet<LifecycleBoundary> boundaries() {
		return EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.SHUTDOWN);
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		tracker.poll(context.client(), context.tick(), events);
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		tracker.clear(tick, events);
	}
}
