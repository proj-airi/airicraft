package ai.moeru.airicraft.sim.input;

import ai.moeru.airicraft.sim.fake.FakePlayerEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Translates policy {@link Intent}s into strictly-legal vanilla inputs, enforcing
 * the human-limit invariants from {@link ActionProfile}:
 * turn rate clamp, attack press rate limit, reach + crosshair-on-hitbox checks.
 * Everything the vanilla server enforces (cooldown, sweep, line of sight) stays
 * in the vanilla layer — this only adds what a real client physically cannot do.
 */
public final class SimInputExecutor {
	private final ActionProfile profile;
	private Intent intent = Intent.IDLE;
	private long lastAttackTick = -1000;
	private long currentTick;
	private final List<String> events = new ArrayList<>();

	public SimInputExecutor(ActionProfile profile) {
		this.profile = profile;
	}

	public void setIntent(Intent intent) {
		this.intent = intent == null ? Intent.IDLE : intent;
	}

	public void tick(long tick) {
		this.currentTick = tick;
	}

	/** Drains rejection/acceptance events for the episode log. */
	public List<String> drainEvents() {
		List<String> out = new ArrayList<>(events);
		events.clear();
		return out;
	}

	public void reset() {
		intent = Intent.IDLE;
		lastAttackTick = -1000;
		events.clear();
	}

	/** Called at the head of {@link FakePlayerEntity#tick()} before the vanilla tick. */
	public void apply(FakePlayerEntity player) {
		Intent in = this.intent;
		applyLook(player, in);
		applyMovement(player, in);
		if (in.attack()) {
			tryAttack(player, in);
		}
		applyUse(player, in);
	}

	private void applyLook(FakePlayerEntity player, Intent in) {
		Vec3d target = resolveLookTarget(player, in);
		if (target == null) {
			return;
		}
		Vec3d eye = player.getEyePos();
		double dx = target.x - eye.x;
		double dz = target.z - eye.z;
		float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float desiredPitch = (float) -Math.toDegrees(Math.atan2(target.y - eye.y, Math.hypot(dx, dz)));
		float yaw = LookClamp.moveToward(player.getYaw(), desiredYaw, profile.maxTurnDegPerTick());
		float pitch = LookClamp.moveToward(player.getPitch(), desiredPitch, profile.maxTurnDegPerTick());
		player.setYaw(yaw);
		player.setPitch(pitch);
		player.setHeadYaw(yaw);
	}

	private Vec3d resolveLookTarget(FakePlayerEntity player, Intent in) {
		if (in.lookEntityId() != null) {
			Entity entity = player.getWorld().getEntityById(in.lookEntityId());
			if (entity == null || entity.isRemoved()) {
				return null;
			}
			return entity.getBoundingBox().getCenter();
		}
		if (in.lookPos() != null) {
			double[] p = in.lookPos();
			return new Vec3d(p[0], p[1], p[2]);
		}
		return null;
	}

	private static void applyMovement(FakePlayerEntity player, Intent in) {
		float forward = 0;
		float sideways = 0;
		double[] dir = in.moveDir();
		if (dir != null && (dir[0] != 0.0 || dir[1] != 0.0)) {
			double yawRad = Math.toRadians(player.getYaw());
			// MC look: (-sin(yaw), +cos(yaw)); right: (+cos(yaw), +sin(yaw))
			double fwdX = -Math.sin(yawRad);
			double fwdZ = Math.cos(yawRad);
			double rightX = Math.cos(yawRad);
			double rightZ = Math.sin(yawRad);
			double len = Math.hypot(dir[0], dir[1]);
			double nx = dir[0] / len;
			double nz = dir[1] / len;
			forward = (float) Math.max(-1.0, Math.min(1.0, nx * fwdX + nz * fwdZ));
			sideways = (float) Math.max(-1.0, Math.min(1.0, nx * rightX + nz * rightZ));
		}
		player.applyMovementInput(forward, sideways, 0.0f, in.jump());
		player.setSprinting(in.sprint() && forward > 0.0f);
		player.setSneaking(in.sneak());
		player.setPlayerInput(new PlayerInput(forward > 0.3, forward < -0.3, sideways < -0.3, sideways > 0.3,
				in.jump(), in.sneak(), in.sprint()));
	}

	private void tryAttack(FakePlayerEntity player, Intent in) {
		if (currentTick - lastAttackTick < profile.attackIntervalTicks()) {
			events.add("attack_rejected:rate_limit");
			return;
		}
		Entity target = pickAttackTarget(player, in.lookEntityId());
		if (target == null) {
			events.add("attack_rejected:no_legal_target");
			return;
		}
		player.attack(target);
		lastAttackTick = currentTick;
		events.add("attack_accepted:" + target.getId());
	}

	/** Legal attack target = entity the player's current crosshair ray actually hits within reach. */
	private Entity pickAttackTarget(FakePlayerEntity player, Integer preferredId) {
		ServerWorld world = player.getWorld();
		Vec3d eye = player.getEyePos();
		Vec3d reachEnd = eye.add(player.getRotationVector().multiply(profile.reachBlocks()));
		List<Entity> candidates = new ArrayList<>();
		if (preferredId != null) {
			Entity preferred = world.getEntityById(preferredId);
			if (preferred != null) {
				candidates.add(preferred);
			}
		}
		for (Entity entity : world.getEntitiesByClass(
				Entity.class,
				player.getBoundingBox().expand(profile.reachBlocks()),
				e -> e != player && e instanceof LivingEntity && e.isAlive())) {
			if (!candidates.contains(entity)) {
				candidates.add(entity);
			}
		}
		Entity best = null;
		double bestDist = Double.MAX_VALUE;
		for (Entity candidate : candidates) {
			Vec3d hit = crosshairHit(player, eye, reachEnd, candidate);
			if (hit == null) {
				continue;
			}
			double dist = eye.distanceTo(hit);
			if (dist < bestDist) {
				bestDist = dist;
				best = candidate;
			}
		}
		return best;
	}

	private Vec3d crosshairHit(FakePlayerEntity player, Vec3d eye, Vec3d reachEnd, Entity entity) {
		Box box = entity.getBoundingBox().expand(profile.aimToleranceBlocks());
		Optional<Vec3d> hit = box.raycast(eye, reachEnd);
		if (hit.isEmpty() || !player.canInteractWithEntity(entity, profile.aimToleranceBlocks())) {
			return null;
		}
		return hit.get();
	}

	private void applyUse(FakePlayerEntity player, Intent in) {
		if (in.useHand() != null) {
			Hand hand = "off".equals(in.useHand()) ? Hand.OFF_HAND : Hand.MAIN_HAND;
			if (!player.isUsingItem() || player.getActiveHand() != hand) {
				player.setCurrentHand(hand);
			}
		} else if (in.stopUsing() && player.isUsingItem()) {
			player.stopUsingItem();
		}
	}
}
