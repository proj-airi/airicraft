package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleDispatcherTest {
	@Test void itemUnavailablePreservesSlowMiningUntilPhysicalObservationIsUnavailable() throws Exception {
		try (var harness = new WakeScenarioHarness()) {
			var observer = new ai.moeru.airicraft.agent.events.SlowMiningObserver();
			var field = EmbodiedAgentRuntime.class.getDeclaredField("slowMiningObserver");
			field.setAccessible(true);
			field.set(harness.runtime, observer);
			var sample = EmbodiedAgentRuntime.class.getDeclaredMethod("sampleSensor", String.class, net.minecraft.client.MinecraftClient.class);
			sample.setAccessible(true);

			observer.observe(1, "stone", 100);
			sample.invoke(harness.runtime, "item", null);
			assertTrue(observer.observe(41, "stone", 100));

			observer.observe(50, "granite", 100);
			sample.invoke(harness.runtime, "physical", null);
			assertFalse(observer.observe(90, "granite", 100));
		}
	}

	@Test void dispatchesOnlySubscribedParticipantsInRegistrationOrder() {
		var calls = new ArrayList<String>();
		var dispatcher = new LifecycleDispatcher();
		dispatcher.register("damage", EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.WORLD_LOADED,
			LifecycleBoundary.RESPAWNED, LifecycleBoundary.SHUTDOWN), (boundary, tick) -> calls.add("damage:" + tick));
		dispatcher.register("physical", EnumSet.allOf(LifecycleBoundary.class), (boundary, tick) -> calls.add("physical:" + tick));
		dispatcher.register("item", EnumSet.allOf(LifecycleBoundary.class), (boundary, tick) -> calls.add("item:" + tick));
		dispatcher.register("slow", EnumSet.allOf(LifecycleBoundary.class), (boundary, tick) -> calls.add("slow:" + tick));
		dispatcher.register("nearby", EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.SHUTDOWN),
			(boundary, tick) -> calls.add("nearby:" + tick));

		assertCalls(dispatcher, calls, LifecycleBoundary.WORLD_LEFT, "damage", "physical", "item", "slow", "nearby");
		assertCalls(dispatcher, calls, LifecycleBoundary.WORLD_LOADED, "damage", "physical", "item", "slow");
		assertCalls(dispatcher, calls, LifecycleBoundary.AWAITING_RESPAWN, "physical", "item", "slow");
		assertCalls(dispatcher, calls, LifecycleBoundary.RESPAWNED, "damage", "physical", "item", "slow");
		assertCalls(dispatcher, calls, LifecycleBoundary.WORLD_CHANGED, "physical", "item", "slow");
		assertCalls(dispatcher, calls, LifecycleBoundary.PLAYER_UNAVAILABLE, "physical", "item", "slow");
		assertCalls(dispatcher, calls, LifecycleBoundary.SHUTDOWN, "damage", "physical", "item", "slow", "nearby");
	}

	@Test void targetedDispatchDoesNotWidenAnObserverReset() {
		var calls = new ArrayList<String>();
		var dispatcher = new LifecycleDispatcher();
		dispatcher.register("physical", EnumSet.of(LifecycleBoundary.PLAYER_UNAVAILABLE),
			(boundary, tick) -> calls.add("physical"));
		dispatcher.register("item", EnumSet.of(LifecycleBoundary.PLAYER_UNAVAILABLE),
			(boundary, tick) -> calls.add("item"));
		dispatcher.register("slow", EnumSet.of(LifecycleBoundary.PLAYER_UNAVAILABLE),
			(boundary, tick) -> calls.add("slow"));
		dispatcher.dispatch(LifecycleBoundary.PLAYER_UNAVAILABLE, 7, Set.of("item"));
		assertEquals(List.of("item"), calls);
		calls.clear();
		dispatcher.dispatch(LifecycleBoundary.PLAYER_UNAVAILABLE, 7, Set.of("physical", "slow"));
		assertEquals(List.of("physical", "slow"), calls);
	}

	@Test void unsubscribedBoundaryDoesNothing() {
		var calls = new ArrayList<String>();
		var dispatcher = new LifecycleDispatcher();
		dispatcher.register("damage", EnumSet.of(LifecycleBoundary.WORLD_LEFT),
			(boundary, tick) -> calls.add("damage"));
		dispatcher.dispatch(LifecycleBoundary.AWAITING_RESPAWN, 7);
		assertEquals(List.of(), calls);
	}

	@Test void runtimeRegistersTheDocumentedParticipantTable() throws Exception {
		try (var harness = new WakeScenarioHarness()) {
			var field = EmbodiedAgentRuntime.class.getDeclaredField("lifecycleDispatcher");
			field.setAccessible(true);
			var table = ((LifecycleDispatcher) field.get(harness.runtime)).table();
			var all = Set.copyOf(EnumSet.allOf(LifecycleBoundary.class));
			var expected = new java.util.LinkedHashMap<String, Set<LifecycleBoundary>>();
			expected.put("damage", Set.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.WORLD_LOADED,
				LifecycleBoundary.RESPAWNED, LifecycleBoundary.SHUTDOWN));
			expected.put("physical", all);
			expected.put("item", all);
			expected.put("slow", all);
			expected.put("food", Set.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.SHUTDOWN));
			expected.put("salience", Set.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.AWAITING_RESPAWN, LifecycleBoundary.SHUTDOWN));
			expected.put("nearby", Set.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.SHUTDOWN));
			// Order matters: nearby publishes social.player_left_nearby and must stay last.
			assertEquals(List.copyOf(expected.keySet()), List.copyOf(table.keySet()));
			assertEquals(expected, table);
			// The migrated observers are sensors under the same participant ids.
			assertEquals(List.of("damage", "physical", "item", "nearby"), List.copyOf(harness.runtime.sensorTimings().keySet()));
		}
	}

	@Test void targetedDispatchRejectsUnknownOrNonHandlingTargets() {
		var calls = new ArrayList<String>();
		var dispatcher = new LifecycleDispatcher();
		dispatcher.register("physical", EnumSet.allOf(LifecycleBoundary.class), (boundary, tick) -> calls.add("physical"));
		dispatcher.register("nearby", EnumSet.of(LifecycleBoundary.WORLD_LEFT), (boundary, tick) -> calls.add("nearby"));
		assertThrows(IllegalArgumentException.class,
			() -> dispatcher.dispatch(LifecycleBoundary.WORLD_LEFT, 1, Set.of("physical", "phyiscal")));
		assertThrows(IllegalArgumentException.class,
			() -> dispatcher.dispatch(LifecycleBoundary.SHUTDOWN, 1, Set.of("nearby")));
		// Validation runs before any handler, so a rejected dispatch resets nothing.
		assertEquals(List.of(), calls);
	}

	@Test void duplicateParticipantIdIsRejected() {
		var dispatcher = new LifecycleDispatcher();
		dispatcher.register("item", EnumSet.allOf(LifecycleBoundary.class), (boundary, tick) -> { });
		assertThrows(IllegalArgumentException.class,
			() -> dispatcher.register("item", EnumSet.allOf(LifecycleBoundary.class), (boundary, tick) -> { }));
	}

	private static void assertCalls(LifecycleDispatcher dispatcher, List<String> calls,
		LifecycleBoundary boundary, String... participants) {
		calls.clear();
		dispatcher.dispatch(boundary, 23);
		assertEquals(java.util.Arrays.stream(participants).map(id -> id + ":23").toList(), calls, boundary.name());
	}
}
