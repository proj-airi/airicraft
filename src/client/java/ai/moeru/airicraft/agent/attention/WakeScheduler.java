package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

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

	/** Facts and delivery for trigger wakes (W1, W4-W7), which carry their prompt text until Phase 3. */
	public interface TriggerHost {
		/** An active planner goal is blocked: only direct guidance wakes it outside the task-wake path. */
		boolean blockedGoal();

		/** Accepted or queued tool work consumes routine progress while no safety hold or reflex is active. */
		boolean workHoldsRoutineWakes();

		void audit(PlannerTrigger trigger, String kind, String gate);

		/** Submits the trigger to the planner; the host records its final audit outcome. */
		void deliver(PlannerTrigger trigger);
	}

	/** Routine progress that accepted or queued work already consumes (G4). */
	private static final Set<PlannerTriggerType> ROUTINE_PROGRESS =
		Set.of(PlannerTriggerType.CRAFT, PlannerTriggerType.PICKUP, PlannerTriggerType.IDLE_THINK);

	/** Concurrent so debug readers on other threads can copy it while the tick thread schedules. */
	private final Deque<Wake> taskWakes = new ConcurrentLinkedDeque<>();
	private Wake lastDeferredAudit;
	private volatile String lastDeferredAuditGate;

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
	 * Admits a trigger wake (G4) and delivers it at once. Direct guidance is never held back here; a blocked goal
	 * holds every other trigger, and accepted or queued work holds routine progress and idle think.
	 */
	public void offerTrigger(PlannerTrigger trigger, TriggerHost host) {
		if (trigger == null) return;
		boolean direct = trigger.maySupersedeLaunchedTurn();
		if (!direct && host.blockedGoal()) {
			host.audit(trigger, "dropped", "G4.blocked_goal");
			return;
		}
		// Accepted work already consumes these observations. Retain the evidence in the
		// event buffer, but do not launch a competing turn for ordinary progress.
		if (!direct && ROUTINE_PROGRESS.contains(trigger.type()) && host.workHoldsRoutineWakes()) {
			host.audit(trigger, "dropped", "G4.accepted_work");
			return;
		}
		host.deliver(trigger);
	}

	/** Pending task wakes in release order and the gate retaining the head, for debug state and the dashboard. */
	public Map<String, Object> debugState() {
		var pending = new ArrayList<Map<String, Object>>();
		for (Wake wake : taskWakes) {
			var entry = new LinkedHashMap<String, Object>();
			entry.put("path", wake.path());
			entry.put("urgency", wake.urgency().name());
			entry.put("tick", wake.tick());
			entry.put("eventRefs", wake.eventRefs());
			entry.put("guidanceRevision", wake.guidanceRevision());
			entry.put("missionId", wake.missionId());
			pending.add(entry);
		}
		var state = new LinkedHashMap<String, Object>();
		state.put("pending", pending);
		String gate = lastDeferredAuditGate;
		state.put("retainedBy", pending.isEmpty() || gate == null ? null : gate);
		return state;
	}

	/** The wake-audit path label of a trigger wake. */
	public static String triggerPath(PlannerTrigger trigger) {
		return trigger.type() == PlannerTriggerType.IDLE_THINK ? "W5"
			: "planner_goal".equals(trigger.coalescingKey()) ? "W4"
			: "delegation".equals(trigger.coalescingKey()) ? "W6"
			: "evaluation".equals(trigger.speaker()) ? "W7" : "W1";
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
