package ai.moeru.airicraft.agent.perception;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HysteresisTest {
	private static Hysteresis.Sample at(double distance, boolean lineOfSight) {
		return new Hysteresis.Sample(distance, lineOfSight);
	}

	@Test void entersOnlyWithinEnterRangeAndWithLineOfSight() {
		var hysteresis = new Hysteresis<String>(16, 20);
		var update = hysteresis.update(Map.of("near", at(10, true), "hidden", at(5, false), "far", at(18, true)));
		assertEquals(List.of("near"), update.entered());
		assertTrue(update.exited().isEmpty());
	}

	@Test void staysTrackedBetweenEnterAndExitRangeEvenWithoutLineOfSight() {
		var hysteresis = new Hysteresis<String>(16, 20);
		hysteresis.update(Map.of("a", at(15, true)));
		var update = hysteresis.update(Map.of("a", at(19, false)));
		assertTrue(update.entered().isEmpty());
		assertTrue(update.exited().isEmpty());
		assertTrue(hysteresis.tracking("a"));
	}

	@Test void exitsBeyondExitRangeOrWhenAbsentAndCanReenter() {
		var hysteresis = new Hysteresis<String>(16, 20);
		hysteresis.update(Map.of("a", at(15, true), "b", at(10, true)));
		var update = hysteresis.update(Map.of("a", at(21, true)));
		assertEquals(List.of("a", "b"), update.exited().stream().sorted().toList());
		assertEquals(List.of("a"), hysteresis.update(Map.of("a", at(12, true))).entered());
	}

	@Test void rejectsExitRangeBelowEnterRange() {
		assertThrows(IllegalArgumentException.class, () -> new Hysteresis<String>(10, 5));
	}
}
