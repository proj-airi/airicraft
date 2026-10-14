package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.tasks.StrongholdTriangulation.Ray;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StrongholdTriangulationTest {
	@Test void bearingsAreCompassDegreesClockwiseFromNorth() {
		assertEquals(0.0D, new Ray(0, 0, 0, -1).bearingDegrees(), 1e-9);
		assertEquals(90.0D, new Ray(0, 0, 1, 0).bearingDegrees(), 1e-9);
		assertEquals(180.0D, new Ray(0, 0, 0, 1).bearingDegrees(), 1e-9);
		assertEquals(270.0D, new Ray(0, 0, -1, 0).bearingDegrees(), 1e-9);
		assertEquals(45.0D, new Ray(0, 0, 1, -1).bearingDegrees(), 1e-9);
	}

	@Test void turningIsClockwiseOnTheCompass() {
		Ray east = new Ray(5, 5, 0, -1).turned(90);
		assertEquals(1.0D, east.dx(), 1e-9);
		assertEquals(0.0D, east.dz(), 1e-9);
		assertEquals(225.0D, new Ray(0, 0, 0, 1).turned(45).bearingDegrees(), 1e-9);
	}

	@Test void aRayNeedsTheEyeToHaveMovedSideways() {
		assertTrue(StrongholdTriangulation.ray(10, 10, 10.2, 10.1, 0.5).isEmpty());
		Ray ray = StrongholdTriangulation.ray(10, 10, 22, 10, 0.5).orElseThrow();
		assertEquals(1.0D, ray.dx(), 1e-9);
		assertEquals(90.0D, ray.bearingDegrees(), 1e-9);
	}

	@Test void twoCrossingBearingsMeetAtTheStronghold() {
		double sx = 1200, sz = -800;
		Ray first = toward(0, 0, sx, sz);
		Ray second = toward(141, 141, sx, sz);
		var estimate = StrongholdTriangulation.intersect(List.of(first, second)).orElseThrow();
		assertEquals(sx, estimate.x(), 1e-6);
		assertEquals(sz, estimate.z(), 1e-6);
		assertEquals(0.0D, estimate.residual(), 1e-6);
		assertTrue(estimate.crossingDegrees() > 5.0D, "a 200-block walk at 45 degrees crosses wide enough at 1400 blocks");
	}

	@Test void noisyBearingsStillLandNearTheStronghold() {
		double sx = -1500, sz = 2100;
		Ray a = toward(0, 0, sx, sz).turned(0.2);
		Ray b = toward(141, 141, sx, sz).turned(-0.2);
		Ray c = toward(-600, 1100, sx, sz).turned(0.3);
		var estimate = StrongholdTriangulation.intersect(List.of(a, b, c)).orElseThrow();
		assertTrue(Math.hypot(estimate.x() - sx, estimate.z() - sz) < 60, estimate.toString());
	}

	@Test void parallelBearingsAndOneBearingDoNotEstimate() {
		assertTrue(StrongholdTriangulation.intersect(List.of(new Ray(0, 0, 1, 0))).isEmpty());
		assertTrue(StrongholdTriangulation.intersect(List.of(new Ray(0, 0, 1, 0), new Ray(0, 50, 1, 0))).isEmpty());
		assertTrue(StrongholdTriangulation.intersect(List.of(new Ray(0, 0, 1, 0), new Ray(0, 50, 1, 0.001))).isEmpty(),
			"well under a degree apart");
	}

	@Test void bearingsPointingApartAreInconsistent() {
		// They cross behind both throw points.
		Ray a = new Ray(0, 0, 1, 1);
		Ray b = new Ray(100, 0, -1, 1);
		assertTrue(StrongholdTriangulation.intersect(List.of(a, b)).isPresent());
		Ray c = new Ray(0, 0, -1, -1);
		Ray d = new Ray(100, 0, 1, -1);
		assertTrue(StrongholdTriangulation.intersect(List.of(c, d)).isEmpty());
	}

	private static Ray toward(double x, double z, double tx, double tz) {
		return new Ray(x, z, tx - x, tz - z);
	}
}
