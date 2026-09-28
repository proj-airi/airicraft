package ai.moeru.airicraft.agent.wakes;

import java.time.*;

public final class MutableClock extends Clock {
	private volatile Instant now = Instant.parse("2026-09-26T00:00:00Z");
	public void advance(Duration duration) { now = now.plus(duration); }
	@Override public ZoneId getZone() { return ZoneOffset.UTC; }
	@Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
	@Override public Instant instant() { return now; }
}
