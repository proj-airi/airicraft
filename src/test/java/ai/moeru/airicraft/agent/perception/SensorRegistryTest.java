package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SensorRegistryTest {
	private static final class Recording implements Sensor {
		private final String id;
		private final EnumSet<LifecycleBoundary> boundaries;
		private final List<String> log;

		Recording(String id, EnumSet<LifecycleBoundary> boundaries, List<String> log) {
			this.id = id;
			this.boundaries = boundaries;
			this.log = log;
		}

		@Override public String id() { return id; }
		@Override public EnumSet<LifecycleBoundary> boundaries() { return boundaries; }
		@Override public void sample(SensorContext context, PerceptSink sink) {
			log.add(id + "@" + context.tick());
			sink.publish(id + ".sampled", Map.of());
		}
		@Override public void onBoundary(LifecycleBoundary boundary, long tick) { log.add(id + ":" + boundary); }
	}

	private static final PerceptSink DISCARD = new PerceptSink() {
		@Override public void publish(String type, Map<String, Object> payload) {}
		@Override public void candidate(PerceptCandidate candidate) {}
	};

	@Test void samplesInRegistrationOrderAndRecordsTimings() {
		var log = new ArrayList<String>();
		var registry = new SensorRegistry();
		registry.register(new Recording("b", EnumSet.allOf(LifecycleBoundary.class), log));
		registry.register(new Recording("a", EnumSet.allOf(LifecycleBoundary.class), log));
		registry.sampleAll(new SensorContext(7, null, null), DISCARD);
		registry.sample("a", new SensorContext(8, null, null), DISCARD);
		assertEquals(List.of("b@7", "a@7", "a@8"), log);
		assertEquals(2L, registry.timings().get("a").get("samples"));
		assertEquals(1L, registry.timings().get("b").get("samples"));
		assertTrue(registry.timings().get("a").get("p99Nanos") >= 0);
	}

	@Test void boundariesReachOnlySensorsRegisteredForThem() {
		var log = new ArrayList<String>();
		var registry = new SensorRegistry();
		registry.register(new Recording("all", EnumSet.allOf(LifecycleBoundary.class), log));
		registry.register(new Recording("left", EnumSet.of(LifecycleBoundary.WORLD_LEFT), log));
		registry.onBoundary(LifecycleBoundary.RESPAWNED, 3);
		assertEquals(List.of("all:RESPAWNED"), log);
	}

	@Test void rejectsDuplicateAndUnknownIds() {
		var registry = new SensorRegistry();
		registry.register(new Recording("a", EnumSet.noneOf(LifecycleBoundary.class), new ArrayList<>()));
		assertThrows(IllegalArgumentException.class,
			() -> registry.register(new Recording("a", EnumSet.noneOf(LifecycleBoundary.class), new ArrayList<>())));
		assertThrows(IllegalArgumentException.class, () -> registry.sample("missing", new SensorContext(0, null, null), DISCARD));
	}

	@Test void candidatesAreImmutablePlainData() {
		var fields = new java.util.HashMap<String, Object>(Map.of("blockId", "minecraft:diamond_ore"));
		var candidate = new PerceptCandidate("block:x", "block", fields);
		fields.put("blockId", "changed");
		assertEquals("minecraft:diamond_ore", candidate.fields().get("blockId"));
		assertEquals(List.of("id", "kind", "blockId"), List.copyOf(candidate.toInput().keySet()));
		assertThrows(UnsupportedOperationException.class, () -> candidate.fields().put("x", 1));
	}
}
