package ai.moeru.airicraft.agent.reflex.neural;

import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * Drives the real client player with the trained egt neural policy each combat
 * tick. Observation encoding mirrors the sim's privileged obs exactly
 * (globals + 8 nearest hostiles x 18 feats + type ids); actuation reuses the
 * reflex layer's actuators and enforces the same human-limit legality as the
 * sim's SimInputExecutor: min attack interval, reach + crosshair raycast.
 *
 * Config: config/airicraft/neural-policy.json =
 *   {"enabled": true, "spec": { ... spec_of_egt JSON ... }}
 * Missing file / disabled -> controller stays inert and the rule reflex runs.
 */
public final class NeuralCombatController {
	private static final double OBS_RADIUS = 20.0;
	private static final long ATTACK_INTERVAL_TICKS = 2;
	private static final double REACH_BLOCKS = 3.0;
	private static final double AIM_TOLERANCE = 0.1;
	private static final double[] DIST_CENTERS = {1.0, 3.0, 5.0, 8.0, 14.0};
	private static final double DIST_SIGMA = 2.0;
	private static final String CONFIG_FILENAME = "neural-policy.json";

	private final EgtNet policy = new EgtNet();
	private final Map<UUID, Integer> playerHits = new HashMap<>();
	private long lastAttackTick = -1000;
	private long lastHurtTick = -1000;
	private int prevHurtTime;
	private boolean shieldHeld;
	private Decision lastDecision;

	public record Decision(String targetUuid, boolean attack, boolean shield,
			boolean sprint, boolean jump, double mvX, double mvZ) {}

	public boolean active() {
		return policy.ready();
	}

	public Decision lastDecision() {
		return lastDecision;
	}

	public void reset() {
		policy.reset();
		playerHits.clear();
		lastAttackTick = -1000;
		lastHurtTick = -1000;
		prevHurtTime = 0;
		lastDecision = null;
	}

	/** Release actuators the controller may hold (offhand use key, GRU cleared via reset). */
	public void release(MinecraftClient client) {
		if (!shieldHeld) {
			return;
		}
		shieldHeld = false;
		if (client != null) {
			client.options.useKey.setPressed(false);
			ClientPlayerEntity player = client.player;
			if (client.interactionManager != null && player != null
					&& player.isUsingItem() && player.getActiveHand() == Hand.OFF_HAND) {
				client.interactionManager.stopUsingItem(player);
			}
		}
	}

	public void configure(Path configDir) {
		Path file = configDir.resolve(CONFIG_FILENAME);
		if (!Files.isRegularFile(file)) {
			return;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (root.has("enabled") && !root.get("enabled").getAsBoolean()) {
				return;
			}
			JsonObject spec = root.has("spec") ? root.getAsJsonObject("spec") : root;
			if (policy.configure(spec)) {
				reset();
			}
		}
		catch (Exception ignored) {
			// malformed config: stay inert, rule reflex continues to run
		}
	}

	private static Path configDir() {
		return FabricLoader.getInstance().getConfigDir().resolve("airicraft");
	}

	/** Convenience loader used by the reflex runtime ctor. */
	public static NeuralCombatController create() {
		NeuralCombatController c = new NeuralCombatController();
		try {
			c.configure(configDir());
		}
		catch (Throwable ignored) {
			// no Fabric runtime (unit tests) or unreadable config: stay inert
		}
		return c;
	}

	/**
	 * One combat tick: encode obs from the live client world, run the net,
	 * map its intent onto legal client actuators.
	 */
	public void tick(MinecraftClient client, ClientPlayerEntity player,
			MovementController movementController, CameraController cameraController,
			long tick) {
		if (player.hurtTime > prevHurtTime) {
			lastHurtTick = tick;
		}
		prevHurtTime = player.hurtTime;

		List<LivingEntity> hostiles = scanHostiles(player);
		int n = Math.min(hostiles.size(), EgtNet.EK);

		double[][] ef = new double[EgtNet.EK][EgtNet.EEF];
		int[] tid = new int[EgtNet.EK];
		java.util.Arrays.fill(tid, policy.typeId("__other__"));
		double[] g = new double[EgtNet.EGE];
		encode(player, hostiles, n, ef, tid, g, tick);

		EgtNet.Decision d = policy.decide(ef, tid, g, n);
		LivingEntity target = d.targetSlot() >= 0 && d.targetSlot() < hostiles.size()
				? hostiles.get(d.targetSlot()) : null;
		lastDecision = new Decision(
				target == null ? null : target.getUuidAsString(),
				d.attack(), d.shield(), d.sprint(), d.jump(), d.mvX(), d.mvZ());

		if (target != null) {
			cameraController.lookAt(client, target.getBoundingBox().getCenter());
		}
		applyMovement(client, player, movementController, d, tick);
		if (d.attack() && target != null) {
			tryAttack(client, player, target, tick);
		}
		applyShield(client, player, d.shield());
	}

	// ------------------------------------------------------------- encode --

	private List<LivingEntity> scanHostiles(ClientPlayerEntity player) {
		Box area = player.getBoundingBox().expand(OBS_RADIUS);
		List<LivingEntity> out = new ArrayList<>();
		for (LivingEntity e : player.clientWorld.getEntitiesByClass(
				LivingEntity.class, area,
				e -> e != player && e.isAlive()
						&& (e instanceof HostileEntity || e instanceof Monster
								|| (e instanceof MobEntity m && m.getTarget() == player)))) {
			out.add(e);
		}
		out.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(player)));
		return out;
	}

	private void encode(ClientPlayerEntity p, List<LivingEntity> hostiles, int n,
			double[][] ef, int[] tid, double[] g, long tick) {
		double px = p.getX(), py = p.getY(), pz = p.getZ();
		Vec3d pv = p.getVelocity();
		double yaw = Math.toRadians(p.getYaw());
		double fx = -Math.sin(yaw), fz = Math.cos(yaw);

		double ux = 0, uz = 0, lit = 0, aiming = 0, targeting = 0;
		double hpSum = 0, nd = 99.0;
		for (int k = 0; k < n; k++) {
			LivingEntity e = hostiles.get(k);
			double dx = e.getX() - px;
			double dz = e.getZ() - pz;
			double d = Math.max(Math.sqrt(e.squaredDistanceTo(p)), 1e-6);
			double ex = dx / d, ez = dz / d;
			ux += ex;
			uz += ez;
			hpSum += e.getHealth();
			if (d < nd) {
				nd = d;
			}
			String type = e.getType().toString();
			String base = type.substring(type.lastIndexOf('.') + 1);
			double fuse = e instanceof CreeperEntity c
					? clamp(c.getLerpedFuseTime(1.0f), 0, 1) : 0.0;
			double aim = e.isUsingItem()
					&& e.getActiveItem().getItem() instanceof RangedWeaponItem ? 1 : 0;
			double tgt = e instanceof MobEntity m && m.getTarget() == p ? 1 : 0;
			if (fuse > 0.4) {
				lit++;
			}
			aiming += aim;
			targeting += tgt;

			double cosb = fx * ex + fz * ez;
			double sinb = fx * ez - fz * ex;
			Vec3d ev = e.getVelocity();
			double rvx = ev.x - pv.x;
			double rvz = ev.z - pv.z;
			double vrad = rvx * ex + rvz * ez;
			double vtan = rvx * ez - rvz * ex;
			double spd = Math.hypot(ev.x, ev.z);

			double[] row = ef[k];
			row[0] = clamp(d / 20.0, 0, 1);
			for (int i = 0; i < DIST_CENTERS.length; i++) {
				double dd = d - DIST_CENTERS[i];
				row[1 + i] = Math.exp(-(dd * dd) / (2 * DIST_SIGMA * DIST_SIGMA));
			}
			row[6] = sinb;
			row[7] = cosb;
			row[8] = clamp((e.getY() - py) / 4.0, -1, 1);
			row[9] = clamp(vrad / 5.0, -1, 1);
			row[10] = clamp(vtan / 5.0, -1, 1);
			row[11] = clamp(spd / 5.0, 0, 1);
			row[12] = clamp(e.getHealth() / Math.max(e.getMaxHealth(), 1.0f), 0, 1);
			row[13] = clamp(playerHits.getOrDefault(e.getUuid(), 0) / 6.0, 0, 1);
			row[14] = tgt;
			row[15] = isRanged(base) ? 1 : 0;
			row[16] = fuse;
			row[17] = aim;
			tid[k] = policy.typeId(base);
		}

		double encirc = n > 0 ? 1.0 - Math.hypot(ux, uz) / n : 0.0;
		ItemStack off = p.getOffHandStack();
		g[0] = clamp(p.getHealth() / 20.0, 0, 1);
		g[1] = clamp(p.getAttackCooldownProgress(0.0f), 0, 1);
		g[2] = clamp((tick - lastHurtTick) / 60.0, 0, 1);
		g[3] = p.isUsingItem() ? 1 : 0;
		g[4] = clamp((p.isUsingItem() ? p.getItemUseTime() : 0) / 40.0, 0, 1);
		g[5] = off.isDamageable() ? clamp(1.0 - (double) off.getDamage() / off.getMaxDamage(), 0, 1) : 1;
		g[6] = p.isOnGround() ? 1 : 0;
		g[7] = clamp(p.getHungerManager().getFoodLevel() / 20.0, 0, 1);
		g[8] = clamp(hostiles.size() / 9.0, 0, 1);
		g[9] = clamp(encirc, 0, 1);
		g[10] = clamp(lit / 3.0, 0, 1);
		g[11] = clamp(aiming / 4.0, 0, 1);
		g[12] = clamp(targeting / 9.0, 0, 1);
		g[13] = clamp(hpSum / 200.0, 0, 1);
		g[14] = clamp(nd / 20.0, 0, 1);
		g[15] = p.isSprinting() ? 1 : 0;
	}

	private static boolean isRanged(String base) {
		return base.equals("skeleton") || base.equals("stray")
				|| base.equals("pillager") || base.equals("witch")
				|| base.equals("blaze") || base.equals("breeze");
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ------------------------------------------------------------ actuate --

	private void applyMovement(MinecraftClient client, ClientPlayerEntity player,
			MovementController movementController, EgtNet.Decision d, long tick) {
		float forward = 0;
		float sideways = 0;
		if (d.mvX() != 0.0 || d.mvZ() != 0.0) {
			double yawRad = Math.toRadians(player.getYaw());
			double fwdX = -Math.sin(yawRad);
			double fwdZ = Math.cos(yawRad);
			double rightX = Math.cos(yawRad);
			double rightZ = Math.sin(yawRad);
			double len = Math.hypot(d.mvX(), d.mvZ());
			double nx = d.mvX() / len;
			double nz = d.mvZ() / len;
			forward = (float) clamp(nx * fwdX + nz * fwdZ, -1, 1);
			sideways = (float) clamp(nx * rightX + nz * rightZ, -1, 1);
		}
		boolean fwd = forward > 0.3;
		boolean back = forward < -0.3;
		boolean left = sideways < -0.3;
		boolean right = sideways > 0.3;
		boolean sprint = d.sprint() && fwd;
		boolean jump = d.jump() && player.isOnGround();
		if (!fwd && !back && !left && !right && !jump) {
			movementController.stop(client);
			return;
		}
		movementController.moveDirectional(client, fwd, back, left, right,
				sprint, jump, false, tick);
	}

	private void tryAttack(MinecraftClient client, ClientPlayerEntity player,
			LivingEntity target, long tick) {
		if (client.interactionManager == null
				|| tick - lastAttackTick < ATTACK_INTERVAL_TICKS) {
			return;
		}
		if (!canHit(player, target)) {
			return;
		}
		// Current facing first so server knockback matches the client camera.
		player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
				player.getYaw(), player.getPitch(), player.isOnGround(),
				player.horizontalCollision));
		client.interactionManager.attackEntity(player, target);
		player.swingHand(Hand.MAIN_HAND);
		lastAttackTick = tick;
		playerHits.merge(target.getUuid(), 1, Integer::sum);
	}

	/** Legal attack = crosshair ray hits the target hitbox within reach. */
	private boolean canHit(ClientPlayerEntity player, LivingEntity target) {
		Vec3d eye = player.getEyePos();
		Vec3d reachEnd = eye.add(player.getRotationVector().multiply(REACH_BLOCKS));
		Box box = target.getBoundingBox().expand(AIM_TOLERANCE);
		return box.raycast(eye, reachEnd).isPresent()
				&& player.canInteractWithEntity(target, AIM_TOLERANCE);
	}

	private void applyShield(MinecraftClient client, ClientPlayerEntity player,
			boolean want) {
		if (want && !player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD)) {
			want = false;
		}
		if (want && client.interactionManager == null) {
			want = false;
		}
		if (want && !shieldHeld) {
			client.options.useKey.setPressed(true);
			if (client.interactionManager != null) {
				client.interactionManager.interactItem(player, Hand.OFF_HAND);
			}
			shieldHeld = true;
		}
		else if (!want && shieldHeld) {
			client.options.useKey.setPressed(false);
			if (client.interactionManager != null && player.isUsingItem()
					&& player.getActiveHand() == Hand.OFF_HAND) {
				client.interactionManager.stopUsingItem(player);
			}
			shieldHeld = false;
		}
	}
}
