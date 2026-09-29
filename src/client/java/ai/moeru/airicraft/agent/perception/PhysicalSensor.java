package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import ai.moeru.airicraft.agent.events.PhysicalEventObserver;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.world.ClientWorld;

/** Falls, landings, water, fire and low air, from the local player each tick ({@code player.physical}). */
public final class PhysicalSensor implements Sensor {
	public static final String ID = "physical";
	/** The slow-mining monitor shares this sensor's world tracking. */
	public static final Set<String> WORLD_PARTICIPANTS = Set.of(ID, "slow");

	private final PhysicalEventObserver observer = new PhysicalEventObserver();
	private final Supplier<Map<String, Object>> taskContext;
	private final IntSupplier lowAirTicks;
	private final BoundarySignal boundaries;
	private ClientWorld world;

	public PhysicalSensor(Supplier<Map<String, Object>> taskContext, IntSupplier lowAirTicks, BoundarySignal boundaries) {
		this.taskContext = taskContext;
		this.lowAirTicks = lowAirTicks;
		this.boundaries = boundaries;
	}

	@Override public String id() {
		return ID;
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		var client = context.client();
		if (client == null || client.player == null || client.world == null || !client.player.isAlive()) {
			boundaries.signal(LifecycleBoundary.PLAYER_UNAVAILABLE, WORLD_PARTICIPANTS);
			world = null;
			return;
		}
		if (world != client.world) {
			boundaries.signal(LifecycleBoundary.WORLD_CHANGED, WORLD_PARTICIPANTS);
			world = client.world;
		}
		var player = client.player;
		var position = player.getPos();
		var velocity = player.getVelocity();
		var input = player.input == null ? net.minecraft.util.PlayerInput.DEFAULT : player.input.playerInput;
		boolean directional = input.forward() || input.backward() || input.left() || input.right() || input.jump() || input.sneak();
		var sample = new PhysicalEventObserver.Sample(
			context.tick(), client.world.getRegistryKey().getValue().toString(),
			new PhysicalEventObserver.Position(position.x, position.y, position.z),
			new PhysicalEventObserver.Position(velocity.x, velocity.y, velocity.z),
			player.isOnGround(), player.isTouchingWater(), player.isSubmergedInWater(), player.isClimbing(),
			player.getAbilities().flying || player.isGliding() || player.hasVehicle(), directional, player.isOnFire(),
			player.isSubmergedInWater() && player.getAir() <= lowAirTicks.getAsInt(), player.getAir(), player.getHealth(), taskContext.get());
		for (var event : observer.observe(sample)) sink.publish("player.physical", event.payload());
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		observer.reset();
		// Leaving the world forgets it; other boundaries keep it, so the next sample does not re-signal a change.
		if (boundary == LifecycleBoundary.WORLD_LEFT || boundary == LifecycleBoundary.SHUTDOWN) world = null;
	}
}
