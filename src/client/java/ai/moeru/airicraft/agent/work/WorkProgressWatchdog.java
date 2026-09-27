package ai.moeru.airicraft.agent.work;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Advisory observer. Executors retain ownership; input activity alone is not progress. */
public final class WorkProgressWatchdog {
	public static final int DEFAULT_STALL_TICKS = 200;
	private static final int HISTORY_LIMIT = 256;
	public record Sample(double x, double y, double z, Map<String, Double> progress, boolean input) {}
	public record Notice(String workId, String reason, int stalledTicks) {}
	private record Cell(long x, long y, long z) {}
	private static final class Episode {
		final LinkedHashMap<Cell, Boolean> cells = new LinkedHashMap<>();
		final LinkedHashMap<String, Double> highWater = new LinkedHashMap<>();
		int ticks;
		boolean notified;
		boolean input;
	}
	private final Map<String, Episode> episodes = new HashMap<>();
	private final int stallTicks;

	public WorkProgressWatchdog(int stallTicks) { this.stallTicks = stallTicks; }

	/** Call once per client tick. Disabled ticks preserve the budget without consuming it. */
	public List<Notice> observe(List<WorkSnapshot> work, Sample sample, boolean enabled) {
		var present = work.stream().filter(w -> !w.state().terminal()).map(w -> w.handle().id()).collect(Collectors.toSet());
		episodes.keySet().retainAll(present);
		if (!enabled) return List.of();
		var parents = work.stream().filter(w -> !w.state().terminal()).map(WorkSnapshot::parentWorkId).collect(Collectors.toSet());
		var notices = new ArrayList<Notice>();
		for (var w : work) {
			if (!w.foreground() || w.state() != WorkSnapshot.State.RUNNING || parents.contains(w.handle().id())
				|| w.label().equals("ASK_USER") || w.label().equals("run_policy")) continue;
			var episode = episodes.computeIfAbsent(w.handle().id(), ignored -> new Episode());
			// Spatial novelty catches bounded back-and-forth motion rather than resetting on every step.
			var cell = new Cell(Math.round(sample.x()), Math.round(sample.y()), Math.round(sample.z()));
			boolean progress = episode.cells.putIfAbsent(cell, true) == null;
			trim(episode.cells);
			var metrics = new HashMap<>(sample.progress());
			if (w.details().get("collected") instanceof Number collected) metrics.put("work:collected", collected.doubleValue());
			for (var metric : metrics.entrySet()) {
				Double previous = episode.highWater.get(metric.getKey());
				if (previous == null || metric.getValue() > previous + 0.0001) {
					episode.highWater.put(metric.getKey(), metric.getValue());
					progress |= previous != null || metric.getValue() > 0;
				}
			}
			trim(episode.highWater);
			if (progress) {
				episode.ticks = 0;
				episode.notified = false;
				episode.input = false;
			} else if (episode.ticks < stallTicks) episode.ticks++;
			episode.input |= sample.input();
			if (episode.ticks >= stallTicks && !episode.notified) {
				episode.notified = true;
				notices.add(new Notice(w.handle().id(), episode.input ? "input_without_progress" : "no_input", episode.ticks));
			}
		}
		return List.copyOf(notices);
	}

	private static void trim(LinkedHashMap<?, ?> history) {
		while (history.size() > HISTORY_LIMIT) history.remove(history.keySet().iterator().next());
	}

	public void continueTrying() {
		for (var episode : episodes.values()) {
			if (!episode.notified) continue;
			episode.ticks = 0;
			episode.notified = false;
			episode.input = false;
		}
	}

	public void reset() { episodes.clear(); }
}
