package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Handwritten melee baseline, ported from the client reflex heuristics:
 * engage the nearest hostile at 2.5m, strafe-orbit when close, back off when
 * surrounded. Tunable constants are exposed so CMA-ES can treat them as the
 * initial parameter vector.
 */
public final class BaselineMeleePolicy implements CombatPolicy {
	// Tunable parameters (CMA-ES initial vector).
	public double engageDistance = 2.5;
	public double engageSlack = 0.4;
	public double sprintBeyond = 4.0;
	public double crowdRadius = 3.5;
	public int crowdThreshold = 3;
	public double attackRange = 3.0;
	public int minLastAttackTicks = 10;
	public int strafeFlipTicks = 30;

	private int tick;
	private int strafeSign = 1;

	@Override
	public String id() {
		return "baseline-melee";
	}

	@Override
	public void reset() {
		tick = 0;
		strafeSign = 1;
	}

	@Override
	public Intent decide(JsonObject obs) {
		tick++;
		if (tick % strafeFlipTicks == 0) {
			strafeSign = -strafeSign;
		}
		JsonObject player = obs.getAsJsonObject("player");
		JsonArray entities = obs.getAsJsonArray("entities");
		JsonObject nearest = null;
		double nearestDist = Double.MAX_VALUE;
		int crowd = 0;
		double crowdCx = 0;
		double crowdCz = 0;
		for (JsonElement el : entities) {
			JsonObject e = el.getAsJsonObject();
			boolean hostile = e.has("hostile") && e.get("hostile").getAsBoolean();
			boolean targeting = e.has("targetingPlayer") && e.get("targetingPlayer").getAsBoolean();
			if (!e.has("health") || (!hostile && !targeting)) {
				continue;
			}
			double dist = e.get("dist").getAsDouble();
			if (dist < crowdRadius) {
				crowd++;
				crowdCx += e.getAsJsonObject("pos").get("x").getAsDouble();
				crowdCz += e.getAsJsonObject("pos").get("z").getAsDouble();
			}
			if (dist < nearestDist) {
				nearestDist = dist;
				nearest = e;
			}
		}
		if (nearest == null) {
			return Intent.IDLE;
		}

		double px = player.getAsJsonObject("pos").get("x").getAsDouble();
		double pz = player.getAsJsonObject("pos").get("z").getAsDouble();
		double tx = nearest.getAsJsonObject("pos").get("x").getAsDouble();
		double tz = nearest.getAsJsonObject("pos").get("z").getAsDouble();
		int targetId = nearest.get("id").getAsInt();
		boolean cooldownReady = player.get("lastAttackedTicks").getAsInt() >= minLastAttackTicks;

		Intent.Builder b = Intent.builder().lookEntity(targetId);

		if (crowd >= crowdThreshold) {
			// Surrounded: move away from the crowd centroid while still facing the nearest target.
			double cx = crowdCx / crowd;
			double cz = crowdCz / crowd;
			double ax = px - cx;
			double az = pz - cz;
			double len = Math.hypot(ax, az);
			if (len > 1e-6) {
				b.moveDir(ax / len, az / len);
			}
			b.sprint(true);
		} else if (nearestDist > engageDistance + engageSlack) {
			double dx = tx - px;
			double dz = tz - pz;
			double len = Math.hypot(dx, dz);
			if (len > 1e-6) {
				b.moveDir(dx / len, dz / len);
			}
			b.sprint(nearestDist > sprintBeyond);
		} else if (nearestDist < engageDistance - engageSlack) {
			// Too close: orbit rather than push into the hitbox.
			double dx = tx - px;
			double dz = tz - pz;
			double len = Math.hypot(dx, dz);
			if (len > 1e-6) {
				b.moveDir(-dz / len * strafeSign - dx / len * 0.4, dx / len * strafeSign - dz / len * 0.4);
			}
		} else {
			// In band: strafe orbit.
			double dx = tx - px;
			double dz = tz - pz;
			double len = Math.hypot(dx, dz);
			if (len > 1e-6) {
				b.moveDir(-dz / len * strafeSign, dx / len * strafeSign);
			}
		}

		b.attack(cooldownReady && nearestDist <= attackRange);
		return b.build();
	}
}
