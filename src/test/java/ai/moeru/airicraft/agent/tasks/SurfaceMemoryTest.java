package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceMemoryTest {
	@Test
	void refreshesWhenNoNearestSurfaceIsKnown() {
		assertTrue(SurfaceMemory.shouldRefreshNearestSurface(
			false,
			new BlockPos(0, 64, 0),
			100,
			new BlockPos(0, 64, 0),
			101
		));
	}

	@Test
	void skipsExpensiveScanBeforeFastRefreshWindow() {
		assertFalse(SurfaceMemory.shouldRefreshNearestSurface(
			true,
			new BlockPos(0, 64, 0),
			100,
			new BlockPos(3, 64, 0),
			109
		));
	}

	@Test
	void refreshesAfterMovementOnceFastRefreshWindowPasses() {
		assertTrue(SurfaceMemory.shouldRefreshNearestSurface(
			true,
			new BlockPos(0, 64, 0),
			100,
			new BlockPos(4, 64, 0),
			110
		));
	}

	@Test
	void refreshesPeriodicallyWithoutMovement() {
		assertTrue(SurfaceMemory.shouldRefreshNearestSurface(
			true,
			new BlockPos(0, 64, 0),
			100,
			new BlockPos(0, 64, 0),
			140
		));
	}

	@Test
	void rejectsSkyVisibleShaftsWithTooFewEscapeDirections() {
		assertFalse(SurfaceMemory.hasEnoughSurfaceEscapeDirections(0));
		assertFalse(SurfaceMemory.hasEnoughSurfaceEscapeDirections(1));
	}

	@Test
	void acceptsOpenSurfaceWithMultipleEscapeDirections() {
		assertTrue(SurfaceMemory.hasEnoughSurfaceEscapeDirections(2));
		assertTrue(SurfaceMemory.hasEnoughSurfaceEscapeDirections(4));
	}
}
