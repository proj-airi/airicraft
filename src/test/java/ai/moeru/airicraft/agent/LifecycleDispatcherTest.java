package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LifecycleDispatcherTest {
	@Test void itemUnavailablePreservesSlowMiningUntilPhysicalObservationIsUnavailable() throws Exception {
		try (var harness = new WakeScenarioHarness()) {
			var observer = new ai.moeru.airicraft.agent.events.SlowMiningObserver();
			var field = EmbodiedAgentRuntime.class.getDeclaredField("slowMiningObserver");
			field.setAccessible(true);
			field.set(harness.runtime, observer);
			var itemMethod = EmbodiedAgentRuntime.class.getDeclaredMethod("observeItemOffers", net.minecraft.client.MinecraftClient.class);
			itemMethod.setAccessible(true);
			var physicalMethod = EmbodiedAgentRuntime.class.getDeclaredMethod("observePhysicalEvents", net.minecraft.client.MinecraftClient.class);
			physicalMethod.setAccessible(true);

			observer.observe(1, "stone", 100);
			itemMethod.invoke(harness.runtime, new Object[] { null });
			assertTrue(observer.observe(41, "stone", 100));

			observer.observe(50, "granite", 100);
			physicalMethod.invoke(harness.runtime, new Object[] { null });
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

	private static void assertCalls(LifecycleDispatcher dispatcher, List<String> calls,
		LifecycleBoundary boundary, String... participants) {
		calls.clear();
		dispatcher.dispatch(boundary, 23);
		assertEquals(java.util.Arrays.stream(participants).map(id -> id + ":23").toList(), calls, boundary.name());
	}
}
