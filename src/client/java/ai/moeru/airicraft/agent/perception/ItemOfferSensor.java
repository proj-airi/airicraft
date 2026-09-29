package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import ai.moeru.airicraft.agent.events.ItemOfferObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.Vec3;

/** Possible item offers inferred from spawn geometry ({@code social.item_offered}); never a confirmed pickup. */
public final class ItemOfferSensor implements Sensor {
	public static final String ID = "item";

	private final ItemOfferObserver observer = new ItemOfferObserver();
	/** Item entities reported as offers, so noticing does not report the same drop again. */
	private final java.util.LinkedHashSet<java.util.UUID> offered = new java.util.LinkedHashSet<>();
	private final BoundarySignal boundaries;
	private ClientLevel level;

	public ItemOfferSensor(BoundarySignal boundaries) {
		this.boundaries = boundaries;
	}

	@Override public String id() {
		return ID;
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		Minecraft minecraft = context.client();
		if (minecraft == null || minecraft.level == null || minecraft.player == null || !minecraft.player.isAlive()) {
			boundaries.signal(LifecycleBoundary.PLAYER_UNAVAILABLE, Set.of(ID));
			level = null;
			return;
		}
		if (level != minecraft.level) {
			boundaries.signal(LifecycleBoundary.WORLD_CHANGED, Set.of(ID));
			level = minecraft.level;
		}
		var players = minecraft.level.players().stream().filter(player -> player.isAlive() && !player.isSpectator())
			.map(player -> new ItemOfferObserver.Player(player.getUUID(), player.getName().getString(),
				player.getEyePosition().add(0, -.3, 0), player.getViewVector(1.0F))).toList();
		List<ItemOfferObserver.Item> items = new ArrayList<>();
		for (var entity : minecraft.level.entitiesForRendering()) {
			if (entity instanceof net.minecraft.world.entity.item.ItemEntity item && !item.isRemoved()) {
				var stack = item.getItem();
				items.add(new ItemOfferObserver.Item(item.getUUID(), BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
					stack.getCount(), new Vec3(item.getX(), item.getY(), item.getZ()), item.getDeltaMovement(), item.tickCount));
			}
		}
		for (var payload : observer.observe(context.tick(), minecraft.level.dimension().location().toString(),
			minecraft.player.getUUID(), new Vec3(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()), players, items)) {
			sink.publish("social.item_offered", payload);
			offered.add(java.util.UUID.fromString(String.valueOf(payload.get("itemEntityUuid"))));
			while (offered.size() > 256) offered.remove(offered.iterator().next());
		}
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		observer.reset();
		if (boundary == LifecycleBoundary.WORLD_LEFT || boundary == LifecycleBoundary.SHUTDOWN) offered.clear();
	}

	public Set<java.util.UUID> offeredItems() {
		return Set.copyOf(offered);
	}
}
