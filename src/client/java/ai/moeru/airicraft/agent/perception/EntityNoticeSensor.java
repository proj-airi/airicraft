package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

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
		var minecraft = context.client();
		if (!Scopes.ready(minecraft)) return;
		var player = minecraft.player;
		Vec3 eye = player.getEyePosition();
		var budgets = context.budgets();
		var entities = new ArrayList<EntityNoticer.Entity>();
		double reach = budgets.entityExitRange() + 1;
		for (var entity : minecraft.level.entitiesForRendering()) {
			if (!(entity instanceof LivingEntity living) || entity == player || entity instanceof ArmorStand || !living.isAlive()
				|| entity.distanceToSqr(eye) > reach * reach) continue;
			var mainHand = living.getMainHandItem();
			entities.add(new EntityNoticer.Entity(entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
				entity.getName().getString(), entity.hasCustomName(), entity instanceof TamableAnimal tameable && tameable.isTame(),
				living.isBaby(), entity instanceof Enemy, entity.getX(), entity.getY(), entity.getZ(),
				mainHand.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString()));
		}
		EntityNoticer.Sight sight = seen -> {
			var target = minecraft.level.getEntity(seen.uuid());
			if (target == null) return false;
			return minecraft.level.clip(new ClipContext(eye, target.getEyePosition(), ClipContext.Block.VISUAL,
				ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
		};
		for (var candidate : noticer.sample(context.tick(), Scopes.of(minecraft), eye.x, eye.y, eye.z, budgets.entityEnterRange(),
			budgets.entityExitRange(), budgets.raycastsPerTick(), entities, sight, reflexTracked.get())) {
			sink.candidate(candidate);
		}
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		noticer.clear();
	}
}
