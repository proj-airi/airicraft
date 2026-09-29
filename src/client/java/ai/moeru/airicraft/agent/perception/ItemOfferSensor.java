package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import ai.moeru.airicraft.agent.events.ItemOfferObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Vec3d;

/** Possible item offers inferred from spawn geometry ({@code social.item_offered}); never a confirmed pickup. */
public final class ItemOfferSensor implements Sensor {
	public static final String ID = "item";

	private final ItemOfferObserver observer = new ItemOfferObserver();
	private final BoundarySignal boundaries;
	private ClientWorld world;

	public ItemOfferSensor(BoundarySignal boundaries) {
		this.boundaries = boundaries;
	}

	@Override public String id() {
		return ID;
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		MinecraftClient client = context.client();
		if (client == null || client.world == null || client.player == null || !client.player.isAlive()) {
			boundaries.signal(LifecycleBoundary.PLAYER_UNAVAILABLE, Set.of(ID));
			world = null;
			return;
		}
		if (world != client.world) {
			boundaries.signal(LifecycleBoundary.WORLD_CHANGED, Set.of(ID));
			world = client.world;
		}
		var players = client.world.getPlayers().stream().filter(player -> player.isAlive() && !player.isSpectator())
			.map(player -> new ItemOfferObserver.Player(player.getUuid(), player.getName().getString(),
				player.getEyePos().add(0, -.3, 0), player.getRotationVec(1.0F))).toList();
		List<ItemOfferObserver.Item> items = new ArrayList<>();
		for (var entity : client.world.getEntities()) {
			if (entity instanceof net.minecraft.entity.ItemEntity item && !item.isRemoved()) {
				var stack = item.getStack();
				items.add(new ItemOfferObserver.Item(item.getUuid(), Registries.ITEM.getId(stack.getItem()).toString(),
					stack.getCount(), new Vec3d(item.getX(), item.getY(), item.getZ()), item.getVelocity(), item.age));
			}
		}
		for (var payload : observer.observe(context.tick(), client.world.getRegistryKey().getValue().toString(),
			client.player.getUuid(), new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ()), players, items)) {
			sink.publish("social.item_offered", payload);
		}
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		observer.reset();
	}
}
