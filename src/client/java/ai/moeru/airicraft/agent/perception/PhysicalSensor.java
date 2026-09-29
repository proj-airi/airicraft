package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import ai.moeru.airicraft.agent.events.PhysicalEventObserver;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.multiplayer.ClientLevel;

/** Falls, landings, water, fire and low air, from the local player each tick ({@code player.physical}). */
public final class PhysicalSensor implements Sensor {
	public static final String ID = "physical";
	/** The slow-mining monitor shares this sensor's world tracking. */
	public static final Set<String> WORLD_PARTICIPANTS = Set.of(ID, "slow");

	private final PhysicalEventObserver observer = new PhysicalEventObserver();
	private final Supplier<Map<String, Object>> taskContext;
	private final IntSupplier lowAirTicks;
	private final BoundarySignal boundaries;
	private ClientLevel level;

	public PhysicalSensor(Supplier<Map<String, Object>> taskContext, IntSupplier lowAirTicks, BoundarySignal boundaries) {
		this.taskContext = taskContext;
		this.lowAirTicks = lowAirTicks;
		this.boundaries = boundaries;
	}

	@Override public String id() {
		return ID;
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		var minecraft = context.client();
		if (minecraft == null || minecraft.player == null || minecraft.level == null || !minecraft.player.isAlive()) {
			boundaries.signal(LifecycleBoundary.PLAYER_UNAVAILABLE, WORLD_PARTICIPANTS);
			level = null;
			return;
		}
		if (level != minecraft.level) {
			boundaries.signal(LifecycleBoundary.WORLD_CHANGED, WORLD_PARTICIPANTS);
			level = minecraft.level;
		}
		var player = minecraft.player;
		var position = player.position();
		var velocity = player.getDeltaMovement();
		var input = player.input == null ? net.minecraft.world.entity.player.Input.EMPTY : player.input.keyPresses;
		boolean directional = input.forward() || input.backward() || input.left() || input.right() || input.jump() || input.shift();
		var sample = new PhysicalEventObserver.Sample(
			context.tick(), minecraft.level.dimension().location().toString(),
			new PhysicalEventObserver.Position(position.x, position.y, position.z),
			new PhysicalEventObserver.Position(velocity.x, velocity.y, velocity.z),
			player.onGround(), player.isInWater(), player.isUnderWater(), player.onClimbable(),
			player.getAbilities().flying || player.isFallFlying() || player.isPassenger(), directional, player.isOnFire(),
			player.isUnderWater() && player.getAirSupply() <= lowAirTicks.getAsInt(), player.getAirSupply(), player.getHealth(), taskContext.get());
		for (var event : observer.observe(sample)) sink.publish("player.physical", event.payload());
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		observer.reset();
		// Leaving the world forgets it; other boundaries keep it, so the next sample does not re-signal a change.
		if (boundary == LifecycleBoundary.WORLD_LEFT || boundary == LifecycleBoundary.SHUTDOWN) level = null;
	}
}
