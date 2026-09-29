package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.EnumSet;

/** Dusk and dawn, weather, the biome and darkness at the feet, reported on transitions only (spec section 5). */
public final class EnvironmentSensor implements Sensor {
	public static final String ID = "environment";

	private final EnvironmentWatcher watcher = new EnvironmentWatcher();

	@Override public String id() {
		return ID;
	}

	@Override public EnumSet<LifecycleBoundary> boundaries() {
		return EnumSet.of(LifecycleBoundary.WORLD_LEFT, LifecycleBoundary.SHUTDOWN);
	}

	@Override public void sample(SensorContext context, PerceptSink sink) {
		var client = context.client();
		if (!Scopes.ready(client)) return;
		var world = client.world;
		var feet = client.player.getBlockPos();
		String biome = world.getBiome(feet).getKey().map(key -> key.getValue().toString()).orElse(null);
		var sample = new EnvironmentWatcher.Sample(world.getRegistryKey().getValue().toString(), world.getTimeOfDay(), world.isRaining(),
			world.isThundering(), biome, world.getLightLevel(feet));
		for (var candidate : watcher.sample(context.tick(), sample)) sink.candidate(candidate);
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		watcher.clear();
	}
}
