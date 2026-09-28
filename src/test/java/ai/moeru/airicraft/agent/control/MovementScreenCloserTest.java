package ai.moeru.airicraft.agent.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementScreenCloserTest {
	@Test
	void movementClosesAnOpenContainer() {
		int[] closes = {0};
		MovementScreenCloser.closeIfMoving(true, true, true, () -> closes[0]++);
		assertEquals(1, closes[0]);
	}

	@Test
	void stationaryCraftingAndSmeltingWaitsKeepTheirScreen() {
		int[] closes = {0};
		for (int tick = 0; tick < 400; tick++) {
			MovementScreenCloser.closeIfMoving(false, true, true, () -> closes[0]++);
		}
		assertEquals(0, closes[0]);
	}

	@Test
	void movementDefersClosingUntilCursorIsEmpty() {
		int[] closes = {0};
		MovementScreenCloser.closeIfMoving(true, true, false, () -> closes[0]++);
		assertEquals(0, closes[0]);
		MovementScreenCloser.closeIfMoving(true, true, true, () -> closes[0]++);
		assertEquals(1, closes[0]);
	}

	@Test
	void movementLeavesChatAndOtherNonContainerScreensAlone() {
		int[] closes = {0};
		MovementScreenCloser.closeIfMoving(true, false, true, () -> closes[0]++);
		assertEquals(0, closes[0]);
	}
}
