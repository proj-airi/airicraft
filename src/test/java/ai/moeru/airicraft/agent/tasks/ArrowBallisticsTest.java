package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArrowBallisticsTest {
	@Test void solutionHitsCrystalsOnPillarsFromTheGround() {
		// Pillar tops sit 12..40 blocks above the island; shooting spots are 10..30 blocks out.
		for (double horizontal : new double[] {10, 18, 30}) {
			for (double vertical : new double[] {12, 25, 40}) {
				var solution = ArrowBallistics.solve(horizontal, vertical, ArrowBallistics.FULL_DRAW_SPEED).orElseThrow();
				assertTrue(solution.elevationDegrees() > Math.toDegrees(Math.atan2(vertical, horizontal)),
					"the arc must aim above the straight line");
				assertEquals(0.0D, closestApproach(solution.elevationDegrees(), horizontal, vertical), 0.05D,
					"h=" + horizontal + " v=" + vertical);
				assertTrue(solution.flightTicks() > 0 && solution.flightTicks() < 40);
			}
		}
	}

	@Test void picksTheFlatArcAndNegativePitchLooksUp() {
		var solution = ArrowBallistics.solve(20, 0, ArrowBallistics.FULL_DRAW_SPEED).orElseThrow();
		assertTrue(solution.elevationDegrees() > 0 && solution.elevationDegrees() < 10, "flat arc, not the lob");
		assertEquals((float) -solution.elevationDegrees(), solution.pitch());
	}

	@Test void theHighArcComesDownOntoTheTarget() {
		for (double horizontal : new double[] {10, 18, 30}) {
			for (double vertical : new double[] {12, 25, 40}) {
				var flat = ArrowBallistics.solve(horizontal, vertical, ArrowBallistics.FULL_DRAW_SPEED).orElseThrow();
				var high = ArrowBallistics.solveHigh(horizontal, vertical, ArrowBallistics.FULL_DRAW_SPEED).orElseThrow();
				assertTrue(high.elevationDegrees() > flat.elevationDegrees() + 5, "h=" + horizontal + " v=" + vertical);
				assertTrue(high.flightTicks() > flat.flightTicks());
				assertEquals(0.0D, closestApproach(high.elevationDegrees(), horizontal, vertical), 0.05D,
					"h=" + horizontal + " v=" + vertical);
				var path = ArrowBallistics.path(high.elevationDegrees(), horizontal, ArrowBallistics.FULL_DRAW_SPEED);
				assertTrue(path.getLast()[1] < path.get(path.size() - 2)[1], "descending at the target");
			}
		}
		assertTrue(ArrowBallistics.solveHigh(150, 60, ArrowBallistics.FULL_DRAW_SPEED).isEmpty());
	}

	@Test void outOfRangeTargetsHaveNoSolution() {
		assertTrue(ArrowBallistics.solve(150, 60, ArrowBallistics.FULL_DRAW_SPEED).isEmpty());
		assertTrue(ArrowBallistics.solve(0, 10, ArrowBallistics.FULL_DRAW_SPEED).isEmpty());
		assertTrue(ArrowBallistics.solve(10, Double.NaN, ArrowBallistics.FULL_DRAW_SPEED).isEmpty());
	}

	@Test void pathEndsAtTheTargetDistance() {
		var path = ArrowBallistics.path(30, 25, ArrowBallistics.FULL_DRAW_SPEED);
		assertEquals(0.0D, path.getFirst()[0]);
		assertEquals(25.0D, path.getLast()[0], 1.0E-9D);
	}

	/** Independent replay of AbstractArrow.tick with Vec3 maths: move, 0.99 drag, 0.05 gravity. */
	private static double closestApproach(double elevationDegrees, double horizontal, double vertical) {
		double radians = Math.toRadians(elevationDegrees);
		Vec3 position = Vec3.ZERO;
		Vec3 velocity = new Vec3(Math.cos(radians), Math.sin(radians), 0).scale(ArrowBallistics.FULL_DRAW_SPEED);
		Vec3 target = new Vec3(horizontal, vertical, 0);
		double best = Double.MAX_VALUE;
		for (int tick = 0; tick < 200; tick++) {
			Vec3 next = position.add(velocity);
			Vec3 segment = next.subtract(position);
			double t = Math.max(0, Math.min(1, target.subtract(position).dot(segment) / segment.lengthSqr()));
			best = Math.min(best, position.add(segment.scale(t)).distanceTo(target));
			position = next;
			velocity = velocity.scale(0.99D).subtract(0, 0.05D, 0);
		}
		return best;
	}
}
