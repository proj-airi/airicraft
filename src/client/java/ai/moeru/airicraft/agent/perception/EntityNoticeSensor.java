package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Living entities entering perception range with line of sight, with hysteresis (spec section 5). */
public final class EntityNoticeSensor implements Sensor {
	public static final String ID = "entities";

	private final EntityNoticer noticer = new EntityNoticer();
	private final Supplier<Set<String>> reflexTracked;

	public EntityNoticeSensor(Supplier<Set<String>> reflexTracked) {
		this.reflexTracked = reflexTracked;
	}

	@Override public String id() {
		return ID;
	}

	@Override public EnumSet<LifecycleBoundary> boundaries() {
		return EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.AWAITING_RESPAWN, LifecycleBoundary.SHUTDOWN);
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		var client = context.client();
		if (!Scopes.ready(client)) return;
		var player = client.player;
		Vec3d eye = player.getEyePos();
		var budgets = context.budgets();
		var entities = new ArrayList<EntityNoticer.Entity>();
		double reach = budgets.entityExitRange() + 1;
		for (var entity : client.world.getEntities()) {
			if (!(entity instanceof LivingEntity living) || entity == player || entity instanceof ArmorStandEntity || !living.isAlive()
				|| entity.squaredDistanceTo(eye) > reach * reach) continue;
			var mainHand = living.getMainHandStack();
			entities.add(new EntityNoticer.Entity(entity.getUuid(), Registries.ENTITY_TYPE.getId(entity.getType()).toString(),
				entity.getName().getString(), entity.hasCustomName(), entity instanceof TameableEntity tameable && tameable.isTamed(),
				living.isBaby(), entity instanceof Monster, entity.getX(), entity.getY(), entity.getZ(),
				mainHand.isEmpty() ? null : Registries.ITEM.getId(mainHand.getItem()).toString()));
		}
		EntityNoticer.Sight sight = seen -> {
			var target = client.world.getEntity(seen.uuid());
			if (target == null) return false;
			return client.world.raycast(new RaycastContext(eye, target.getEyePos(), RaycastContext.ShapeType.VISUAL,
				RaycastContext.FluidHandling.NONE, player)).getType() == HitResult.Type.MISS;
		};
		for (var candidate : noticer.sample(context.tick(), Scopes.of(client), eye.x, eye.y, eye.z, budgets.entityEnterRange(),
			budgets.entityExitRange(), budgets.raycastsPerTick(), entities, sight, reflexTracked.get())) {
			sink.candidate(candidate);
		}
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		noticer.clear();
	}
}
