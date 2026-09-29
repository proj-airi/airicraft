package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.entity.ItemEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Dropped items in line of sight within radius, once per item entity (spec section 5). */
public final class DroppedItemSensor implements Sensor {
	public static final String ID = "dropped_items";

	private final DroppedItemNoticer noticer = new DroppedItemNoticer();
	private final Supplier<Set<UUID>> offered;
	private final Supplier<Predicate<DroppedItemNoticer.Item>> ownMiningDrop;

	public DroppedItemSensor(Supplier<Set<UUID>> offered, Supplier<Predicate<DroppedItemNoticer.Item>> ownMiningDrop) {
		this.offered = offered;
		this.ownMiningDrop = ownMiningDrop;
	}

	@Override public String id() {
		return ID;
	}

	@Override public EnumSet<LifecycleBoundary> boundaries() {
		return EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.SHUTDOWN);
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		var client = context.client();
		if (!Scopes.ready(client)) return;
		var player = client.player;
		Vec3d eye = player.getEyePos();
		var items = new ArrayList<DroppedItemNoticer.Item>();
		double reach = context.budgets().radius() + 1;
		for (var entity : client.world.getEntities()) {
			if (!(entity instanceof ItemEntity item) || item.isRemoved() || item.squaredDistanceTo(eye) > reach * reach) continue;
			var stack = item.getStack();
			items.add(new DroppedItemNoticer.Item(item.getUuid(), Registries.ITEM.getId(stack.getItem()).toString(), stack.getCount(),
				item.getX(), item.getY(), item.getZ(), item.age));
		}
		if (items.isEmpty()) return;
		DroppedItemNoticer.Sight sight = item -> {
			var target = new Vec3d(item.x(), item.y() + .125, item.z());
			return client.world.raycast(new RaycastContext(eye, target, RaycastContext.ShapeType.VISUAL,
				RaycastContext.FluidHandling.NONE, player)).getType() == HitResult.Type.MISS;
		};
		for (var candidate : noticer.sample(context.tick(), Scopes.of(client), eye.x, eye.y, eye.z, context.budgets().radius(),
			context.budgets().raycastsPerTick(), items, sight, offered.get(), ownMiningDrop.get())) {
			sink.candidate(candidate);
		}
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		noticer.clear();
	}
}
