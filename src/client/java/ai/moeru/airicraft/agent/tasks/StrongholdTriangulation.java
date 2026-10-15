package ai.moeru.airicraft.agent.tasks;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Horizontal geometry for eyes of ender. An eye flies from where it was thrown toward the stronghold, so each throw gives a
 * ray: its origin and a unit direction on the x/z plane. Two or more rays that are not near parallel meet at the
 * stronghold; with measurement noise they are intersected by least squares.
 */
final class StrongholdTriangulation {
	/** Rays closer to parallel than this cannot place the meeting point along them. */
	static final double MIN_CROSSING_DEGREES = 1.0D;
	/** An estimate may sit this far behind a ray's origin before the rays are called inconsistent. */
	static final double BEHIND_SLACK_BLOCKS = 24.0D;

	private StrongholdTriangulation() {}

	/** A bearing thrown from {@code (x, z)}: {@code (dx, dz)} is a unit vector. */
	record Ray(double x, double z, double dx, double dz) {
		Ray {
			double length = Math.hypot(dx, dz);
			if (!(length > 0.0D)) throw new IllegalArgumentException("ray direction must not be zero");
			dx /= length;
			dz /= length;
		}

		/** Compass bearing in degrees clockwise from north (-z): east is 90, south 180, west 270. */
		double bearingDegrees() {
			double degrees = Math.toDegrees(Math.atan2(dx, -dz));
			return degrees < 0.0D ? degrees + 360.0D : degrees;
		}

		/** Signed distance of a point along the ray from its origin. */
		double along(double px, double pz) {
			return (px - x) * dx + (pz - z) * dz;
		}

		/** Distance of a point from the ray's line. */
		double offset(double px, double pz) {
			return Math.abs((px - x) * dz - (pz - z) * dx);
		}

		/** The same origin, the direction turned by {@code degrees} (positive turns clockwise on the compass). */
		Ray turned(double degrees) {
			double radians = Math.toRadians(degrees);
			double cos = Math.cos(radians), sin = Math.sin(radians);
			// Compass clockwise is from -z toward +x: in x/z coordinates that is (dx, dz) -> (dx cos - dz sin, dx sin + dz cos).
			return new Ray(x, z, dx * cos - dz * sin, dx * sin + dz * cos);
		}

		String describe() {
			return String.format(Locale.ROOT, "%d,%d@%.1fdeg", (long) Math.floor(x), (long) Math.floor(z), bearingDegrees());
		}
	}

	/** Where the rays meet, how wide the widest pair of them crosses, and the largest distance from a ray's line. */
	record Estimate(double x, double z, double crossingDegrees, double residual) {
		long blockX() { return Math.round(Math.floor(x)); }
		long blockZ() { return Math.round(Math.floor(z)); }

		double distanceFrom(double px, double pz) {
			return Math.hypot(x - px, z - pz);
		}
	}

	/** The ray from a throw point through where the eye flew, or empty when it barely moved sideways. */
	static Optional<Ray> ray(double fromX, double fromZ, double toX, double toZ, double minLength) {
		double dx = toX - fromX, dz = toZ - fromZ;
		if (Math.hypot(dx, dz) < minLength) return Optional.empty();
		return Optional.of(new Ray(fromX, fromZ, dx, dz));
	}

	/**
	 * The least-squares meeting point of the rays: the point with the smallest summed squared distance from their lines.
	 * Empty with fewer than two rays, when every pair is closer to parallel than {@link #MIN_CROSSING_DEGREES}, or when the
	 * point lies behind a ray's origin (the rays point apart, so a bearing was misread).
	 */
	static Optional<Estimate> intersect(List<Ray> rays) {
		if (rays.size() < 2) return Optional.empty();
		double crossing = 0.0D;
		for (int i = 0; i < rays.size(); i++) for (int j = i + 1; j < rays.size(); j++) {
			Ray a = rays.get(i), b = rays.get(j);
			double sin = Math.abs(a.dx() * b.dz() - a.dz() * b.dx());
			crossing = Math.max(crossing, Math.toDegrees(Math.asin(Math.min(1.0D, sin))));
		}
		if (crossing < MIN_CROSSING_DEGREES) return Optional.empty();
		// Sum over rays of (I - d d^T) x = (I - d d^T) p, a 2x2 system.
		double a11 = 0, a12 = 0, a22 = 0, b1 = 0, b2 = 0;
		for (Ray ray : rays) {
			double m11 = 1.0D - ray.dx() * ray.dx(), m12 = -ray.dx() * ray.dz(), m22 = 1.0D - ray.dz() * ray.dz();
			a11 += m11;
			a12 += m12;
			a22 += m22;
			b1 += m11 * ray.x() + m12 * ray.z();
			b2 += m12 * ray.x() + m22 * ray.z();
		}
		double determinant = a11 * a22 - a12 * a12;
		if (Math.abs(determinant) < 1e-9) return Optional.empty();
		double x = (b1 * a22 - b2 * a12) / determinant;
		double z = (a11 * b2 - a12 * b1) / determinant;
		double residual = 0.0D;
		for (Ray ray : rays) {
			if (ray.along(x, z) < -BEHIND_SLACK_BLOCKS) return Optional.empty();
			residual = Math.max(residual, ray.offset(x, z));
		}
		return Optional.of(new Estimate(x, z, crossing, residual));
	}
}
