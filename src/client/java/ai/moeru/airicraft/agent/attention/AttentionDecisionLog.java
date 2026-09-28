package ai.moeru.airicraft.agent.attention;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded record of attention decisions, one per routed event, answering "why did or didn't it wake". */
public final class AttentionDecisionLog {
	public static final int DEFAULT_CAPACITY = 1024;

	private final int capacity;
	private final ArrayDeque<AttentionDecision> decisions = new ArrayDeque<>();
	private final Map<String, Long> countsByRule = new TreeMap<>();
	private long recorded;
	private long dropped;

	public AttentionDecisionLog() {
		this(DEFAULT_CAPACITY);
	}

	public AttentionDecisionLog(int capacity) {
		if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
		this.capacity = capacity;
	}

	public synchronized void record(AttentionDecision decision) {
		if (decisions.size() == capacity) {
			decisions.removeFirst();
			dropped++;
		}
		decisions.addLast(decision);
		countsByRule.merge(decision.ruleId(), 1L, Long::sum);
		recorded++;
	}

	/** Decisions for events after {@code sinceSeqNo}; {@code truncated} when older decisions were evicted. */
	public synchronized Query query(Long sinceSeqNo) {
		long since = sinceSeqNo == null ? Long.MIN_VALUE : sinceSeqNo;
		var matches = new ArrayList<AttentionDecision>();
		for (var decision : decisions) if (decision.seqNo() > since) matches.add(decision);
		boolean truncated = sinceSeqNo != null && dropped > 0 && !decisions.isEmpty() && decisions.getFirst().seqNo() > since + 1;
		return new Query(truncated, List.copyOf(matches));
	}

	public synchronized List<AttentionDecision> latest(int count) {
		var all = new ArrayList<>(decisions);
		return List.copyOf(all.subList(Math.max(0, all.size() - count), all.size()));
	}

	/** Totals and the latest decisions, for the bridge debug state and the dashboard. */
	public synchronized Map<String, Object> debugState(int latest) {
		var state = new LinkedHashMap<String, Object>();
		state.put("recorded", recorded);
		state.put("dropped", dropped);
		state.put("countsByRule", Map.copyOf(countsByRule));
		state.put("latest", latest(latest).stream().map(AttentionDecision::withoutInputs).toList());
		return state;
	}

	public synchronized void clear() {
		decisions.clear();
		countsByRule.clear();
		recorded = 0;
		dropped = 0;
	}

	public record Query(boolean truncated, List<AttentionDecision> decisions) {
	}
}
