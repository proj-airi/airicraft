package ai.moeru.airicraft.agent.attention;

import java.util.List;
import java.util.Objects;

/**
 * A pending request for a planner decision. {@code path} is the wake-audit label (W1-W9) and stays stable across
 * the scheduler refactor. {@code eventRefs} are raw-log sequence numbers of the evidence that justified it.
 *
 * @param missionId the mission the wake belongs to, or {@code null}; a changed mission supersedes it
 * @param delivery {@code PREEMPT} may cancel a turn its safety change made stale; otherwise {@code IMMEDIATE}
 */
public record Wake(String path, Urgency urgency, long tick, List<Long> eventRefs, long guidanceRevision, String missionId,
	Delivery delivery) {
	public Wake {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(urgency, "urgency");
		eventRefs = List.copyOf(eventRefs);
		delivery = delivery == null ? Delivery.IMMEDIATE : delivery;
	}

	public Wake(String path, Urgency urgency, long tick, List<Long> eventRefs, long guidanceRevision, String missionId) {
		this(path, urgency, tick, eventRefs, guidanceRevision, missionId, Delivery.IMMEDIATE);
	}

	/** The same wake, allowed to preempt a turn that a newer safety epoch made stale. */
	public Wake preempting() {
		return new Wake(path, urgency, tick, eventRefs, guidanceRevision, missionId, Delivery.PREEMPT);
	}

	public boolean preempts() {
		return delivery == Delivery.PREEMPT;
	}

	/** A task wakeup (W2): work, task, reflex or blocked-goal evidence, queued behind earlier wakes. */
	public static Wake task(long tick, long eventSequence, long guidanceRevision, String missionId) {
		return new Wake("W2", Urgency.HIGH, tick, List.of(eventSequence), guidanceRevision, missionId);
	}

	/** Task attention (W3): placed ahead of queued wakes and not held back by accepted or queued work. */
	public static Wake attention(long tick, long eventSequence, long guidanceRevision) {
		return new Wake("W3", Urgency.HIGH, tick, List.of(eventSequence), guidanceRevision, null);
	}

	public boolean attention() {
		return "W3".equals(path);
	}

	/** The single evidence reference task wakes carry; 0 when there is none. */
	public long eventSequence() {
		return eventRefs.isEmpty() ? 0L : eventRefs.getFirst();
	}
}
