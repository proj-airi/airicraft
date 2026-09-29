package ai.moeru.airicraft.agent.perception;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enter and exit ranges for tracked things (Cortico's proximity map): a thing enters only within {@code enterRange}
 * and with line of sight, and stays tracked until it is gone or beyond {@code exitRange}, so walking at the edge of
 * range does not flicker.
 */
public final class Hysteresis<K> {
	public record Sample(double distance, boolean lineOfSight) {}

	public record Update<K>(List<K> entered, List<K> exited) {}

	private final double enterRange;
	private final double exitRange;
	private final Map<K, Double> tracked = new HashMap<>();

	public Hysteresis(double enterRange, double exitRange) {
		if (enterRange <= 0 || exitRange < enterRange) throw new IllegalArgumentException("need 0 < enter <= exit");
		this.enterRange = enterRange;
		this.exitRange = exitRange;
	}

	/** One observation of everything currently sampled; absent keys count as gone. */
	public Update<K> update(Map<K, Sample> samples) {
		var entered = new ArrayList<K>();
		var exited = new ArrayList<K>();
		for (var iterator = tracked.entrySet().iterator(); iterator.hasNext();) {
			var entry = iterator.next();
			Sample sample = samples.get(entry.getKey());
			if (sample == null || sample.distance() > exitRange) {
				exited.add(entry.getKey());
				iterator.remove();
			}
			else entry.setValue(sample.distance());
		}
		for (var entry : samples.entrySet()) {
			Sample sample = entry.getValue();
			if (!tracked.containsKey(entry.getKey()) && sample.lineOfSight() && sample.distance() <= enterRange) {
				tracked.put(entry.getKey(), sample.distance());
				entered.add(entry.getKey());
			}
		}
		return new Update<>(List.copyOf(entered), List.copyOf(exited));
	}

	/** The same tracked things under new ranges; the next update exits those beyond the new exit range. */
	public Hysteresis<K> withRanges(double enterRange, double exitRange) {
		var moved = new Hysteresis<K>(enterRange, exitRange);
		moved.tracked.putAll(tracked);
		return moved;
	}

	public boolean ranges(double enterRange, double exitRange) {
		return this.enterRange == enterRange && this.exitRange == exitRange;
	}

	public boolean tracking(K key) {
		return tracked.containsKey(key);
	}

	public Set<K> tracked() {
		return Set.copyOf(tracked.keySet());
	}

	public void clear() {
		tracked.clear();
	}
}
