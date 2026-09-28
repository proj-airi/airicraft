package ai.moeru.airicraft.agent.attention;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Owns pending wakes and decides when one is released to the planner. Facts about the planner and the
 * dialogue come from a {@link TaskWakeHost}; delivery, audit records and supersession evidence are the host's.
 */
public final class WakeScheduler {
	/** Facts and effects the scheduler needs from the dialogue runtime that owns the active planner. */
	public interface TaskWakeHost {
		boolean externalDriverActive();

		boolean plannerInFlight();

		/** Degraded or unconfigured: queued task wakes are discarded, as no planner can take them. */
		boolean plannerUnavailable();

		/** Accepted {@code run_policy} work without a safety hold holds ordinary task wakes back. */
		boolean runPolicyHold();

		boolean queuedToolWork();

		/** The active role's incorporated event cursor has passed every reference (satisfaction). */
		boolean satisfied(Wake wake);

		/** A blocked goal ignores task wakes whose evidence is neither relevant to the block nor supervisory. */
		boolean irrelevantToBlockedGoal(Wake wake);

		long guidanceRevision();

		String currentMissionId();

		void audit(Wake wake, String kind, String gate);

		void superseded(Wake wake, String reason, String currentMissionId);

		/** Submits the wake to the planner; the host records its final audit outcome. */
		void deliver(Wake wake);
	}

	private final Deque<Wake> taskWakes = new ArrayDeque<>();
	private Wake lastDeferredAudit;
	private String lastDeferredAuditGate;

	public void offerTask(Wake wake) {
		taskWakes.addLast(Objects.requireNonNull(wake, "wake"));
	}

	public void offerAttention(Wake wake) {
		taskWakes.addFirst(Objects.requireNonNull(wake, "wake"));
	}

	public boolean hasTaskWakes() {
		return !taskWakes.isEmpty();
	}

	public void clearTaskWakes() {
		taskWakes.clear();
	}

	/** Removes every pending task wake, oldest first, so the caller can record each as superseded. */
	public List<Wake> takeTaskWakes() {
		var taken = new ArrayList<>(taskWakes);
		taskWakes.clear();
		return taken;
	}

	/**
	 * Releases at most one task wake. Wakes that are satisfied, irrelevant to a blocked goal, or superseded by
	 * new guidance or a changed mission are dropped on the way. Returns whether a wake was delivered.
	 */
	public boolean releaseTaskWake(TaskWakeHost host) {
		if (host.externalDriverActive() || taskWakes.isEmpty() || host.plannerInFlight()) return false;
		if (host.plannerUnavailable()) {
			taskWakes.clear();
			return false;
		}
		while (!taskWakes.isEmpty()) {
			Wake head = taskWakes.peekFirst();
			if (!head.attention() && host.runPolicyHold()) {
				auditDeferred(host, head, "G5.run_policy");
				return false;
			}
			if (!head.attention() && host.queuedToolWork()) {
				auditDeferred(host, head, "G5.queued_tool_work");
				return false;
			}
			Wake wake = taskWakes.removeFirst();
			if (host.satisfied(wake)) {
				audit(host, wake, "dropped", "G5.incorporated");
				continue;
			}
			if (!wake.attention() && host.irrelevantToBlockedGoal(wake)) {
				audit(host, wake, "dropped", "G5.blocked_irrelevant");
				continue;
			}
			String currentMissionId = host.currentMissionId();
			String superseded = wake.guidanceRevision() != host.guidanceRevision() ? "new_user_guidance"
				: wake.missionId() != null && currentMissionId != null && !Objects.equals(wake.missionId(), currentMissionId)
					? "mission_changed" : null;
			if (superseded != null) {
				host.superseded(wake, superseded, currentMissionId);
				continue;
			}
			host.deliver(wake);
			return true;
		}
		return false;
	}

	/**
	 * Called when any other outcome is recorded for a task wake (delivery, supersession): the next retained
	 * gate is audited again even for the same head wake.
	 */
	public void outcomeRecorded() {
		lastDeferredAudit = null;
		lastDeferredAuditGate = null;
	}

	private void audit(TaskWakeHost host, Wake wake, String kind, String gate) {
		outcomeRecorded();
		host.audit(wake, kind, gate);
	}

	/** These gates retain the head wake. Audit its transition once, not every poll. */
	private void auditDeferred(TaskWakeHost host, Wake wake, String gate) {
		if (wake == lastDeferredAudit && Objects.equals(gate, lastDeferredAuditGate)) return;
		lastDeferredAudit = wake;
		lastDeferredAuditGate = gate;
		host.audit(wake, "dropped", gate);
	}
}
