package ai.moeru.airicraft.agent.perception;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Environment transitions only (spec section 5): dusk and dawn, rain and thunder starting and stopping, the biome at
 * the feet (after it held for {@link #BIOME_HOLD_TICKS}), and darkness at the feet with hysteresis. The first sample
 * in a dimension is a baseline and reports nothing; dimension changes stay {@code session.*} events.
 */
public final class EnvironmentWatcher {
	public static final int BIOME_HOLD_TICKS = 100;
	public static final int LIGHT_HOLD_TICKS = 40;
	public static final int DARK_AT_MOST = 3;
	public static final int LIGHT_AT_LEAST = 8;

	public record Sample(String dimension, long timeOfDay, boolean raining, boolean thundering, String biome, int light) {}

	enum Phase { DAY, DUSK, NIGHT, DAWN }

	private Sample baseline;
	private Phase phase;
	private String biome;
	private String pendingBiome;
	private long pendingBiomeSince;
	private boolean dark;
	private long lightFlipSince = -1;

	public List<PerceptCandidate> sample(long tick, Sample sample) {
		var candidates = new ArrayList<PerceptCandidate>();
		if (baseline == null || !baseline.dimension().equals(sample.dimension())) {
			baseline = sample;
			phase = phase(sample.timeOfDay());
			biome = sample.biome();
			pendingBiome = null;
			dark = sample.light() <= DARK_AT_MOST;
			lightFlipSince = -1;
			return candidates;
		}
		Phase next = phase(sample.timeOfDay());
		if (next != phase) {
			if (next == Phase.DUSK) candidates.add(change(tick, "dusk", Map.of("timeOfDay", sample.timeOfDay() % 24_000L)));
			if (next == Phase.DAWN) candidates.add(change(tick, "dawn", Map.of("timeOfDay", sample.timeOfDay() % 24_000L)));
			phase = next;
		}
		if (sample.raining() != baseline.raining()) candidates.add(change(tick, sample.raining() ? "rain_started" : "rain_stopped", Map.of()));
		if (sample.thundering() != baseline.thundering()) {
			candidates.add(change(tick, sample.thundering() ? "thunder_started" : "thunder_stopped", Map.of()));
		}
		if (sample.biome() != null && !sample.biome().equals(biome)) {
			if (!sample.biome().equals(pendingBiome)) {
				pendingBiome = sample.biome();
				pendingBiomeSince = tick;
			}
			else if (tick - pendingBiomeSince >= BIOME_HOLD_TICKS) {
				candidates.add(change(tick, "biome_changed", Map.of("from", String.valueOf(biome), "to", sample.biome())));
				biome = sample.biome();
				pendingBiome = null;
			}
		}
		else pendingBiome = null;
		boolean flips = dark ? sample.light() >= LIGHT_AT_LEAST : sample.light() <= DARK_AT_MOST;
		if (!flips) lightFlipSince = -1;
		else if (lightFlipSince < 0) lightFlipSince = tick;
		else if (tick - lightFlipSince >= LIGHT_HOLD_TICKS) {
			dark = !dark;
			lightFlipSince = -1;
			candidates.add(change(tick, dark ? "dark" : "light", Map.of("lightLevel", sample.light())));
		}
		baseline = sample;
		return candidates;
	}

	public void clear() {
		baseline = null;
	}

	static Phase phase(long timeOfDay) {
		long time = Math.floorMod(timeOfDay, 24_000L);
		return time < 12_000L ? Phase.DAY : time < 13_800L ? Phase.DUSK : time < 22_200L ? Phase.NIGHT : Phase.DAWN;
	}

	private static PerceptCandidate change(long tick, String change, Map<String, Object> details) {
		var fields = new LinkedHashMap<String, Object>();
		fields.put("change", change);
		fields.putAll(details);
		return new PerceptCandidate("environment:" + change + "@" + tick, "environment", fields);
	}
}
