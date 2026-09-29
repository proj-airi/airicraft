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
		var minecraft = context.client();
		if (!Scopes.ready(minecraft)) return;
		var level = minecraft.level;
		var feet = minecraft.player.blockPosition();
		String biome = level.getBiome(feet).unwrapKey().map(key -> key.location().toString()).orElse(null);
		var sample = new EnvironmentWatcher.Sample(level.dimension().location().toString(), level.getDayTime(), level.isRaining(),
			level.isThundering(), biome, level.getMaxLocalRawBrightness(feet));
		for (var candidate : watcher.sample(context.tick(), sample)) sink.candidate(candidate);
	}

	@Override public void onBoundary(LifecycleBoundary boundary, long tick) {
		watcher.clear();
	}
}
