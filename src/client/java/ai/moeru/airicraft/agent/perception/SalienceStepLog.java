package ai.moeru.airicraft.agent.perception;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Recent salience steps with a running sequence, drained by the flight recorder for replay (Phase 4, P13). */
public final class SalienceStepLog {
	public static final int CAPACITY = 256;

	public record Entry(long sequence, SaliencePolicy.StepRecord step) {}

	public record Query(boolean truncated, List<Entry> entries) {}

	private final ArrayDeque<Entry> entries = new ArrayDeque<>();
	private long nextSequence = 1;

	public synchronized void record(SaliencePolicy.StepRecord step) {
		entries.addLast(new Entry(nextSequence++, step));
		while (entries.size() > CAPACITY) entries.removeFirst();
	}

	/** Entries after {@code since}; truncated when older entries were already evicted. */
	public synchronized Query query(Long since) {
		long after = since == null ? 0 : since;
		boolean truncated = !entries.isEmpty() && entries.peekFirst().sequence() > after + 1;
		var result = new ArrayList<Entry>();
		for (Entry entry : entries) if (entry.sequence() > after) result.add(entry);
		return new Query(truncated, List.copyOf(result));
	}
}
