package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Where the Ender Dragon's damage lands. Breath and fireball impacts leave lingering clouds that apply
 * Instant Damage, which armour does not reduce, so the only defence is not standing in them. A fireball
 * in flight is dangerous where it is about to land.
 */
public final class DragonHazards {
	/** Clearance kept beyond a cloud's edge; clouds grow, and the player's box is 0.6 wide. */
	static final double CLOUD_MARGIN = 2.0D;
	/** A fireball impact makes a 3-block cloud that grows; stay this far from the predicted path. */
	static final double FIREBALL_CLEARANCE = 5.0D;
	static final int FIREBALL_LOOKAHEAD_TICKS = 30;

	private DragonHazards() {
	}

	public record Cloud(Vec3 center, double radius) {}
	public record Fireball(Vec3 position, Vec3 velocity) {}

	/** Whether a player standing at {@code feet} is in, or about to be in, a cloud or a fireball impact. */
	public static boolean threatened(Vec3 feet, List<Cloud> clouds, List<Fireball> fireballs) {
		return insideCloud(feet, clouds, CLOUD_MARGIN) || fireballs.stream().anyMatch(f -> fireballThreatens(feet, f));
	}

	/** Clouds hit entities whose box meets their thin disc, by horizontal distance from its centre. */
	public static boolean insideCloud(Vec3 feet, List<Cloud> clouds, double margin) {
		for (Cloud cloud : clouds) {
			double dy = feet.y - cloud.center().y;
			if (dy < -2.5D || dy > 3.0D) continue;
			double dx = feet.x - cloud.center().x, dz = feet.z - cloud.center().z;
			double reach = cloud.radius() + margin;
			if (dx * dx + dz * dz <= reach * reach) return true;
		}
		return false;
	}

	/** Closest approach of the fireball's straight path to the player's body within the look-ahead. */
	static boolean fireballThreatens(Vec3 feet, Fireball fireball) {
		Vec3 body = feet.add(0.0D, 0.9D, 0.0D);
		Vec3 relative = body.subtract(fireball.position());
		Vec3 velocity = fireball.velocity();
		double speedSquared = velocity.lengthSqr();
		if (speedSquared < 1.0E-6D) return relative.length() <= FIREBALL_CLEARANCE;
		double t = Math.max(0.0D, Math.min(FIREBALL_LOOKAHEAD_TICKS, relative.dot(velocity) / speedSquared));
		return relative.subtract(velocity.scale(t)).length() <= FIREBALL_CLEARANCE;
	}

	/**
	 * The nearest standable position that is safe from every hazard, preferring ones away from
	 * {@code avoid} (the dragon's head) when it is known.
	 */
	public static Optional<Vec3> escape(Vec3 feet, List<Vec3> candidates, List<Cloud> clouds, List<Fireball> fireballs,
		Vec3 avoid, Predicate<Vec3> standable) {
		return candidates.stream()
			.filter(candidate -> !threatened(candidate, clouds, fireballs))
			.filter(standable)
			.min(Comparator.comparingDouble(candidate -> escapeCost(feet, candidate, avoid)));
	}

	static double escapeCost(Vec3 feet, Vec3 candidate, Vec3 avoid) {
		double cost = candidate.distanceTo(feet) + Math.abs(candidate.y - feet.y) * 2.0D;
		if (avoid != null) {
			double toHead = Math.hypot(candidate.x - avoid.x, candidate.z - avoid.z);
			if (toHead < 8.0D) cost += (8.0D - toHead) * 1.5D;
		}
		return cost;
	}
}
