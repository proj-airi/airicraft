package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns sensor order and measures each sensor's per-tick cost. Lifecycle resets reach a sensor through its own
 * participant id in the runtime's lifecycle dispatcher, so the existing participant table does not change.
 */
public final class SensorRegistry {
	private static final int WINDOW = 1024;

	private final List<Sensor> sensors = new ArrayList<>();
	private final Map<String, Timing> timings = new LinkedHashMap<>();

	public void register(Sensor sensor) {
		if (timings.containsKey(sensor.id())) throw new IllegalArgumentException("Duplicate sensor: " + sensor.id());
		sensors.add(sensor);
		timings.put(sensor.id(), new Timing());
	}

	public List<Sensor> sensors() {
		return List.copyOf(sensors);
	}

	/** Samples every sensor in registration order. */
	public void sampleAll(SensorContext context, PerceptSink sink) {
		for (Sensor sensor : sensors) sample(sensor, context, sink);
	}

	/** Samples one sensor, for callers that interleave sensors with other tick work. */
	public void sample(String id, SensorContext context, PerceptSink sink) {
		for (Sensor sensor : sensors) {
			if (sensor.id().equals(id)) {
				sample(sensor, context, sink);
				return;
			}
		}
		throw new IllegalArgumentException("Unknown sensor: " + id);
	}

	public void onBoundary(LifecycleBoundary boundary, long tick) {
		for (Sensor sensor : sensors) if (sensor.boundaries().contains(boundary)) sensor.onBoundary(boundary, tick);
	}

	/** Per-sensor cost over the recent window: {@code samples, meanNanos, p99Nanos, maxNanos}. */
	public Map<String, Map<String, Long>> timings() {
		var result = new LinkedHashMap<String, Map<String, Long>>();
		timings.forEach((id, timing) -> result.put(id, timing.snapshot()));
		return result;
	}

	private void sample(Sensor sensor, SensorContext context, PerceptSink sink) {
		long started = System.nanoTime();
		try {
			sensor.sample(context, sink);
		}
		finally {
			timings.get(sensor.id()).record(System.nanoTime() - started);
		}
	}

	private static final class Timing {
		private final long[] window = new long[WINDOW];
		private long samples;
		private long max;

		void record(long nanos) {
			window[(int) (samples % WINDOW)] = nanos;
			samples++;
			max = Math.max(max, nanos);
		}

		Map<String, Long> snapshot() {
			int count = (int) Math.min(samples, WINDOW);
			long[] recent = java.util.Arrays.copyOf(window, count);
			java.util.Arrays.sort(recent);
			long total = 0;
			for (long value : recent) total += value;
			var result = new LinkedHashMap<String, Long>();
			result.put("samples", samples);
			result.put("meanNanos", count == 0 ? 0 : total / count);
			result.put("p99Nanos", count == 0 ? 0 : recent[Math.min(count - 1, (int) Math.ceil(count * .99) - 1)]);
			result.put("maxNanos", max);
			return result;
		}
	}
}
