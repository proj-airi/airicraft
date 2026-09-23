package ai.moeru.airicraft.sim.observe;

import ai.moeru.airicraft.sim.fake.FakePlayerEntity;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.RangedWeaponItem;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/** Privileged-state observation: everything a side server can see, no vision needed. */
public final class ObservationSnapshot {
	private ObservationSnapshot() {}

	public static JsonObject build(ServerWorld world, FakePlayerEntity player, Set<UUID> trackedMobs,
			double radius, long episodeTick) {
		JsonObject root = new JsonObject();
		root.addProperty("tick", episodeTick);
		root.add("player", playerState(player));
		JsonArray entities = new JsonArray();
		Vec3d pos = player.getPos();
		Box area = new Box(pos.x - radius, pos.y - radius, pos.z - radius,
				pos.x + radius, pos.y + radius, pos.z + radius);
		List<Entity> nearby = world.getEntitiesByClass(Entity.class, area,
				e -> e != player && e.isAlive());
		nearby.sort(Comparator.comparingDouble(e -> e.squaredDistanceTo(player)));
		for (Entity entity : nearby) {
			entities.add(entityState(entity, player, trackedMobs));
		}
		root.add("entities", entities);
		return root;
	}

	private static JsonObject playerState(FakePlayerEntity p) {
		JsonObject o = new JsonObject();
		o.add("pos", vec(p.getPos()));
		o.add("vel", vec(p.getVelocity()));
		o.addProperty("health", p.getHealth());
		o.addProperty("maxHealth", p.getMaxHealth());
		o.addProperty("food", p.getHungerManager().getFoodLevel());
		o.addProperty("saturation", p.getHungerManager().getSaturationLevel());
		o.addProperty("yaw", p.getYaw());
		o.addProperty("pitch", p.getPitch());
		o.addProperty("onGround", p.isOnGround());
		o.addProperty("attackCooldown", p.getAttackCooldownProgress(0.0f));
		o.addProperty("lastAttackedTicks", p.getLastAttackedTicks());
		o.addProperty("forwardSpeed", p.forwardSpeed);
		o.addProperty("sidewaysSpeed", p.sidewaysSpeed);
		o.addProperty("usingItem", p.isUsingItem());
		o.addProperty("sprinting", p.isSprinting());
		o.addProperty("mainHand", p.getMainHandStack().getItem().toString());
		o.addProperty("offHand", p.getOffHandStack().getItem().toString());
		ItemStack off = p.getOffHandStack();
		o.addProperty("offhandPct", off.isDamageable()
				? 1.0 - (double) off.getDamage() / off.getMaxDamage() : 1.0);
		JsonObject dbg = new JsonObject();
		dbg.addProperty("canMoveVoluntarily", p.canMoveVoluntarily());
		dbg.addProperty("canActVoluntarily", p.canActVoluntarily());
		dbg.addProperty("isImmobile", p.isImmobileForSim());
		dbg.addProperty("isInterpolating", p.getInterpolator().isInterpolating());
		dbg.addProperty("hasVehicle", p.hasVehicle());
		dbg.addProperty("isSleeping", p.isSleeping());
		dbg.addProperty("isSpectator", p.isSpectator());
		dbg.addProperty("isSwimming", p.isSwimming());
		dbg.addProperty("jumpingCooldown", -1);
		o.add("debug", dbg);
		return o;
	}

	private static JsonObject entityState(Entity e, FakePlayerEntity player, Set<UUID> trackedMobs) {
		JsonObject o = new JsonObject();
		o.addProperty("id", e.getId());
		o.addProperty("type", e.getType().toString());
		o.add("pos", vec(e.getPos()));
		o.add("vel", vec(e.getVelocity()));
		o.addProperty("dist", Math.sqrt(e.squaredDistanceTo(player)));
		o.addProperty("tracked", trackedMobs.contains(e.getUuid()));
		o.addProperty("hostile", e instanceof HostileEntity);
		if (e instanceof LivingEntity living) {
			o.addProperty("health", living.getHealth());
			o.addProperty("maxHealth", living.getMaxHealth());
		}
		if (e instanceof MobEntity mob) {
			o.addProperty("targetingPlayer", mob.getTarget() == player);
		}
		if (e instanceof CreeperEntity creeper) {
			o.addProperty("fuse", Math.max(0, Math.min(1,
					creeper.getLerpedFuseTime(1.0f))));
		}
		if (e instanceof LivingEntity living
				&& living.isUsingItem()
				&& living.getActiveItem().getItem() instanceof RangedWeaponItem) {
			o.addProperty("aiming", true);
		}
		return o;
	}

	private static JsonObject vec(Vec3d v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		return o;
	}
}
