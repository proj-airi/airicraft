package ai.moeru.airicraft.agent.work;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Progress observer. Executors retain ownership; input activity alone is not progress.
 * A first notice is advisory; work that stays stalled for {@code HARD_FACTOR} windows gets one
 * hard notice, which the runtime turns into a cancel so nothing can hang forever.
 * <p>
 * Movement is progress only when it gets somewhere. While navigation is heading for a destination,
 * that means coming a block closer to it than before; reaching cells never stood in before does not
 * count, or a route circling or drifting in water would look busy forever. Without a destination,
 * a new cell counts unless the body is swimming.
 */
public final class WorkProgressWatchdog {
	public static final int DEFAULT_STALL_TICKS = 200;
	public static final int HARD_FACTOR = 3;
	private static final int HISTORY_LIMIT = 256;
	/** Blocks a body must come closer to a destination than its last credited distance. */
	static final double APPROACH_BLOCKS = 1.0;
	/** Within this many blocks of a destination the body has arrived; the next approach to it starts over. */
	static final double ARRIVED_BLOCKS = 1.5;
	private static final int DESTINATION_LIMIT = 64;
	/**
	 * @param destination where navigation is heading and how far away it is, or null when nothing is navigating
	 * @param swimming    whether the body is in water
	 */
	public record Sample(double x, double y, double z, Map<String, Double> progress, boolean input,
		Destination destination, boolean swimming) {
		public Sample(double x, double y, double z, Map<String, Double> progress, boolean input) {
			this(x, y, z, progress, input, null, false);
		}
	}
	/** A navigation target by a stable key (one per target cell) and the body's distance to it in blocks. */
	public record Destination(String key, double distance) {}
	public record Notice(String workId, String reason, int stalledTicks, boolean hard) {}
	private record Cell(long x, long y, long z) {}
	private static final class Episode {
		final LinkedHashMap<Cell, Boolean> cells = new LinkedHashMap<>();
		final LinkedHashMap<String, Double> highWater = new LinkedHashMap<>();
		/** Per destination, the distance last credited as progress. */
		final LinkedHashMap<String, Double> approached = new LinkedHashMap<>();
		int ticks;
		int total;
		boolean notified;
		boolean hardNotified;
		boolean input;
		/** Whether navigation was heading somewhere during this stall. */
		boolean navigating;
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
				|| w.label().equals("ASK_USER") || w.label().equals("run_policy")
				// Waiting for the dragon to perch is the fight; the task has its own no-progress deadline.
				|| w.label().equals("FIGHT_ENDER_DRAGON")
				// Mining one obsidian stands still for 188 ticks; the task has its own no-progress deadline.
				|| w.label().equals("COLLECT_OBSIDIAN")
				// An eye of ender flies for 80 ticks while the body stands still; the task has its own deadlines.
				|| w.label().equals("LOCATE_STRONGHOLD")
				// Waiting at the spawner for blazes stands still; the task has its own no-blaze and overall deadlines.
				|| w.label().equals("COLLECT_BLAZE_RODS")
				// Placing and breaking gravel in place stands still; the task has its own step and overall deadlines.
				|| w.label().equals("COLLECT_FLINT")
				// A night in a shelter changes nothing for minutes; the task has its own wait cap and exit deadline.
				|| w.label().equals("SHELTER_FOR_NIGHT")
				// Waiting in a safe spot for endermen to come stands still; the task has its own no-enderman and overall deadlines.
				|| w.label().equals("COLLECT_ENDER_PEARLS")
				// Waiting for monsters to clear and lying in bed stand still; the task has its own monster wait and overall deadline.
				|| w.label().equals("SLEEP_IN_BED")
				// Waiting for animals to wander into sight and for the furnace to cook stand still; the task has its own
				// no-animal, per-animal and overall deadlines.
				|| w.label().equals("HUNT_FOOD")
				// Aiming eyes at frames, waiting for the portal to light and for the End to load stand still; the task has its own
				// per-frame, lighting and overall deadlines.
				|| w.label().equals("ENTER_END_PORTAL")) continue;
			var episode = episodes.computeIfAbsent(w.handle().id(), ignored -> new Episode());
			// Spatial novelty catches bounded back-and-forth motion rather than resetting on every step.
			// Cells are recorded while navigating too, so a restart between attempts finds nothing new.
			var cell = new Cell(Math.round(sample.x()), Math.round(sample.y()), Math.round(sample.z()));
			boolean novel = episode.cells.putIfAbsent(cell, true) == null;
			trim(episode.cells);
			boolean progress = sample.destination() != null ? approached(episode, sample.destination()) : novel && !sample.swimming();
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
				episode.total = 0;
				episode.notified = false;
				episode.hardNotified = false;
				episode.input = false;
				episode.navigating = false;
			} else {
				if (episode.ticks < stallTicks) episode.ticks++;
				if (episode.total < Integer.MAX_VALUE) episode.total++;
			}
			episode.input |= sample.input();
			episode.navigating |= sample.destination() != null;
			if (episode.total >= stallTicks * HARD_FACTOR && !episode.hardNotified) {
				episode.hardNotified = true;
				notices.add(new Notice(w.handle().id(), reason(episode), episode.total, true));
				continue;
			}
			if (episode.ticks >= stallTicks && !episode.notified) {
				episode.notified = true;
				notices.add(new Notice(w.handle().id(), reason(episode), episode.ticks, false));
			}
		}
		return List.copyOf(notices);
	}

	private static String reason(Episode episode) {
		if (!episode.input) return "no_input";
		return episode.navigating ? "not_nearing_destination" : "input_without_progress";
	}

	/**
	 * Whether the body came a block closer to the destination than ever since it last arrived there.
	 * A destination seen for the first time is not progress, so switching targets alone gains nothing.
	 */
	private static boolean approached(Episode episode, Destination destination) {
		Double credited = episode.approached.get(destination.key());
		if (destination.distance() <= ARRIVED_BLOCKS) {
			episode.approached.remove(destination.key());
			return credited != null;
		}
		if (credited != null && destination.distance() > credited - APPROACH_BLOCKS) return false;
		episode.approached.put(destination.key(), destination.distance());
		while (episode.approached.size() > DESTINATION_LIMIT) episode.approached.remove(episode.approached.keySet().iterator().next());
		return credited != null;
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
			episode.navigating = false;
		}
	}

	public void reset() { episodes.clear(); }
}
