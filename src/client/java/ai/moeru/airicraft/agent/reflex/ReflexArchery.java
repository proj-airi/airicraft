package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.control.Actuator;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.agent.tasks.ArrowBallistics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Shoots back at a ranged mob the body cannot reach with a sword. A playtest skeleton on a ledge eleven blocks
 * below took the body from twenty to nine health one arrow at a time while the reflex, unable to path to it,
 * kept raising the shield and letting go. With a bow and arrows it now aims a ballistic arc (the one the dragon
 * fight uses) at the mob, leading its movement, draws fully and looses when lined up. Client thread only.
 */
final class ReflexArchery {
	/** Closer than this the sword is the better answer. */
	static final double MIN_DISTANCE = 5.0D;
	static final double MAX_DISTANCE = 28.0D;
	/** A full draw is 20 ticks; two more for the camera to settle. */
	static final int FULL_DRAW_TICKS = 22;
	private static final float ALIGNED_DEGREES = 2.0F;

	private long drawStartedTick = -1L;

	/** Whether to shoot at this mob rather than close in or wait. */
	static boolean worthShooting(boolean ranged, boolean lineOfSight, double distance, boolean hasBow, boolean hasArrows) {
		return ranged && lineOfSight && hasBow && hasArrows && distance >= MIN_DISTANCE && distance <= MAX_DISTANCE;
	}

	boolean drawing() {
		return drawStartedTick >= 0;
	}

	/**
	 * Aims, draws and looses at {@code target} for one tick. Returns false, having let go of any draw, when there is
	 * no clear arc, so the caller fights as before.
	 */
	boolean tick(Minecraft minecraft, LocalPlayer player, LivingEntity target, long tick, Actuator actuator,
		CameraController camera, MovementController movement, Predicate<net.minecraft.world.item.Item> select,
		List<SurvivalReflexEvent> events) {
		if (minecraft.level == null || minecraft.gameMode == null) return false;
		Vec3 launch = player.getEyePosition().subtract(0.0D, 0.1D, 0.0D);
		Vec3 aim = aimPoint(minecraft, player, launch, target);
		if (aim == null) {
			cancel(minecraft, actuator);
			return false;
		}
		if (!player.getMainHandItem().is(Items.BOW)) {
			cancel(minecraft, actuator);
			return select.test(Items.BOW);
		}
		movement.stop(minecraft);
		camera.lookAt(minecraft, aim);
		if (drawStartedTick < 0) {
			if (!actuator.holdUse()) return true;
			actuator.useItem(minecraft, player, InteractionHand.MAIN_HAND);
			drawStartedTick = tick;
			return true;
		}
		actuator.holdUse();
		if (tick - drawStartedTick >= FULL_DRAW_TICKS && camera.isLookingAt(minecraft, aim, ALIGNED_DEGREES)) {
			release(minecraft, actuator);
			events.add(new SurvivalReflexEvent("reflex.arrow_shot", Map.of(
				"tick", tick, "target", target.getStringUUID(), "distance", player.distanceTo(target))));
		}
		return true;
	}

	/** Lets go of a draw without counting it as a shot. */
	void cancel(Minecraft minecraft, Actuator actuator) {
		if (drawStartedTick < 0) return;
		release(minecraft, actuator);
	}

	private void release(Minecraft minecraft, Actuator actuator) {
		actuator.releaseUse(minecraft);
		if (minecraft.player != null && minecraft.gameMode != null && minecraft.player.isUsingItem()
			&& minecraft.player.getUseItem().is(Items.BOW)) minecraft.gameMode.releaseUsingItem(minecraft.player);
		drawStartedTick = -1L;
	}

	/** A point to look at that sends a full-draw arrow into the mob's body where it will be, or null with no clear arc. */
	private static Vec3 aimPoint(Minecraft minecraft, LocalPlayer player, Vec3 launch, LivingEntity target) {
		Vec3 body = target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D);
		Vec3 lead = body;
		ArrowBallistics.Solution solution = null;
		for (int pass = 0; pass < 2; pass++) {
			double horizontal = Math.hypot(lead.x - launch.x, lead.z - launch.z);
			if (horizontal < 1.0E-3D) return null;
			solution = ArrowBallistics.solve(horizontal, lead.y - launch.y, ArrowBallistics.FULL_DRAW_SPEED).orElse(null);
			if (solution == null) return null;
			Vec3 motion = target.getDeltaMovement();
			lead = body.add(motion.x * solution.flightTicks(), 0.0D, motion.z * solution.flightTicks());
		}
		double horizontal = Math.hypot(lead.x - launch.x, lead.z - launch.z);
		double dirX = (lead.x - launch.x) / horizontal, dirZ = (lead.z - launch.z) / horizontal;
		Vec3 previous = launch;
		for (double[] point : ArrowBallistics.path(solution.elevationDegrees(), horizontal, ArrowBallistics.FULL_DRAW_SPEED)) {
			double along = Math.min(point[0], horizontal - 0.5D);
			Vec3 next = new Vec3(launch.x + dirX * along, launch.y + point[1], launch.z + dirZ * along);
			if (next.distanceToSqr(previous) > 1.0E-6D && minecraft.level.clip(new ClipContext(previous, next,
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) return null;
			if (along < point[0]) break;
			previous = next;
		}
		double elevation = Math.toRadians(solution.elevationDegrees());
		Vec3 direction = new Vec3(dirX * Math.cos(elevation), Math.sin(elevation), dirZ * Math.cos(elevation));
		return player.getEyePosition().add(direction.scale(16.0D));
	}

	static boolean hasBow(LocalPlayer player) {
		return has(player, stack -> stack.is(Items.BOW));
	}

	static boolean hasArrows(LocalPlayer player) {
		return player.hasInfiniteMaterials()
			|| has(player, stack -> stack.is(Items.ARROW) || stack.is(Items.SPECTRAL_ARROW) || stack.is(Items.TIPPED_ARROW));
	}

	private static boolean has(LocalPlayer player, Predicate<ItemStack> matches) {
		for (int slot = 0; slot < Inventory.INVENTORY_SIZE; slot++) {
			if (matches.test(player.getInventory().getItem(slot))) return true;
		}
		return matches.test(player.getOffhandItem());
	}
}
