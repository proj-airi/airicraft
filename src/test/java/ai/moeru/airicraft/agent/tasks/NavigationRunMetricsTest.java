package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NavigationRunMetricsTest {
	@Test
	void summarizesTravelAgainstTheTargetFloorCenter() {
		NavigationRunMetrics metrics = new NavigationRunMetrics(100L);
		metrics.observe(100L, sample(0.5D, 64.0D, 0.5D));
		metrics.observe(101L, sample(3.5D, 64.0D, 0.5D));
		metrics.observe(105L, sample(3.5D, 64.0D, 4.5D));
		metrics.replanned();

		Map<String, Object> summary = metrics.summary(new GoalPosition(3, 64, 4, true), false);

		assertEquals(5L, summary.get("elapsedTicks"));
		assertEquals(3L, summary.get("activeTicks"));
		assertEquals(1, summary.get("replans"));
		assertEquals(false, summary.get("stalled"));
		assertEquals(7.0D, summary.get("pathLength"));
		assertEquals(5.0D, summary.get("startDistance"));
		assertEquals(0.0D, summary.get("endDistance"));
	}

	@Test
	void horizontalTargetsIgnoreHeight() {
		NavigationRunMetrics metrics = new NavigationRunMetrics(0L);
		metrics.observe(0L, sample(0.5D, 90.0D, 0.5D));

		Map<String, Object> summary = metrics.summary(new GoalPosition(0, 64, 3, false), true);

		assertEquals(3.0D, summary.get("startDistance"));
		assertEquals(true, summary.get("stalled"));
	}

	@Test
	void ticksWithoutAPlayerCountButReportNoDistances() {
		NavigationRunMetrics metrics = new NavigationRunMetrics(0L);
		metrics.observe(0L, null);
		metrics.observe(1L, null);

		Map<String, Object> summary = metrics.summary(new GoalPosition(1, 64, 1, true), false);

		assertEquals(2L, summary.get("activeTicks"));
		assertFalse(summary.containsKey("pathLength"));
		assertFalse(summary.containsKey("endDistance"));
	}

	private static WaterStallRecovery.Sample sample(double x, double y, double z) {
		return new WaterStallRecovery.Sample(false, x, y, z);
	}
}
