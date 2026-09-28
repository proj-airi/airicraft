package ai.moeru.airicraft.agent.attention;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdleHookTest {
	private static IdleHook.Named generator(String id, boolean handles, List<String> calls) {
		return new IdleHook.Named(id, new IdleHook.Generator() {
			@Override public boolean poll(long tick) { calls.add(id + ":poll:" + tick); return handles; }
			@Override public void reset() { calls.add(id + ":reset"); }
		});
	}

	@Test void aHandlingGeneratorResetsOnlyTheLowerPriorityOnes() {
		var calls = new ArrayList<String>();
		IdleHook.run(7, true, List.of(generator("goal", false, calls), generator("delegation", true, calls), generator("idle", false, calls)));
		assertEquals(List.of("goal:poll:7", "delegation:poll:7", "idle:reset"), calls);
	}

	@Test void everyGeneratorPollsInOrderWhenNoneHandles() {
		var calls = new ArrayList<String>();
		IdleHook.run(3, true, List.of(generator("goal", false, calls), generator("idle", false, calls)));
		assertEquals(List.of("goal:poll:3", "idle:poll:3"), calls);
	}

	@Test void ineligibleTicksResetEverythingWithoutPolling() {
		var calls = new ArrayList<String>();
		IdleHook.run(3, false, List.of(generator("goal", true, calls), generator("idle", false, calls)));
		assertEquals(List.of("goal:reset", "idle:reset"), calls);
	}
}
