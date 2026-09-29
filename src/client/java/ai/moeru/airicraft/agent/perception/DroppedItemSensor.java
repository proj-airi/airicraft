package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

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
		var minecraft = context.client();
		if (!Scopes.ready(minecraft)) return;
		var player = minecraft.player;
		Vec3 eye = player.getEyePosition();
		var items = new ArrayList<DroppedItemNoticer.Item>();
		double reach = context.budgets().radius() + 1;
		for (var entity : minecraft.level.entitiesForRendering()) {
			if (!(entity instanceof ItemEntity item) || item.isRemoved() || item.distanceToSqr(eye) > reach * reach) continue;
			var stack = item.getItem();
			items.add(new DroppedItemNoticer.Item(item.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
				item.getX(), item.getY(), item.getZ(), item.tickCount));
		}
		if (items.isEmpty()) return;
		DroppedItemNoticer.Sight sight = item -> {
			var target = new Vec3(item.x(), item.y() + .125, item.z());
			return minecraft.level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL,
				ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS;
		};
		for (var candidate : noticer.sample(context.tick(), Scopes.of(minecraft), eye.x, eye.y, eye.z, context.budgets().radius(),
			context.budgets().raycastsPerTick(), items, sight, offered.get(), ownMiningDrop.get())) {
			sink.candidate(candidate);
		}
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		noticer.clear();
	}
}
