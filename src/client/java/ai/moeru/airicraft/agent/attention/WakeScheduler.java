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

		/**
		 * Asks the active planner to cancel its turn if a newer safety epoch made it stale and it has externalized
		 * nothing ({@code PREEMPT}). Returns the gate naming the outcome: {@code preempted}, or why not.
		 */
		default String preemptInFlight() {
			return "nothing_in_flight";
		}
	}

	/** Facts and delivery for trigger wakes (W1, W4-W7). */
	public interface TriggerHost {
		/** An active planner goal is blocked: only direct guidance wakes it outside the task-wake path. */
		boolean blockedGoal();

		/** Accepted or queued tool work consumes routine progress while no safety hold or reflex is active. */
		boolean workHoldsRoutineWakes();

		/** The active role's decision cursor has passed {@code seqNo}: an observation already carried it. */
		boolean incorporated(long seqNo);

		void audit(PlannerTrigger trigger, String kind, String gate);

		/** Submits the triggers to the planner as one batch; the host records each one's final audit outcome. */
		void deliver(List<PlannerTrigger> triggers);

		/** As {@link #deliver(List)}; {@code supersede} lets direct guidance replace the running turn. */
		default void deliver(List<PlannerTrigger> triggers, boolean supersede) {
			deliver(triggers);
		}

		/** Direct guidance delivered now would supersede a replaceable running turn. */
		default boolean canSupersede() {
			return false;
		}

		/** Triggers queued behind the running or superseded turn: the coalesce window grows with them. */
		default int queuedTriggerCount() {
			return 1;
		}
	}

	/** A debounced wake is released once no other has arrived for this many ticks (spec section 5). */
	public static final int DEBOUNCE_QUIET_TICKS = 10;
	/** ...or once the oldest held wake has waited this long. */
	public static final int DEBOUNCE_MAX_HOLD_TICKS = 100;

	private record Held(PlannerTrigger trigger, long heldAt) {}

	/** At most this many supersedes within {@link #SUPERSEDE_WINDOW_TICKS}; later direct guidance queues (spec 4.7). */
	public static final int SUPERSEDE_BUDGET = 3;
	public static final int SUPERSEDE_WINDOW_TICKS = 600;
	private static final long MILLIS_PER_TICK = 50L;

	private long coalesceStepMillis = 10L;
	private long coalesceMinMillis = 10L;
	private long coalesceMaxMillis = 100L;
	/** The tick the coalesce window after a supersede ends, or -1 without one. */
	private volatile long coalesceReadyAt = -1L;
	private final Deque<Long> supersedes = new ConcurrentLinkedDeque<>();

	/** Routine progress that accepted or queued work already consumes (G4). */
	private static final Set<PlannerTriggerType> ROUTINE_PROGRESS =
		Set.of(PlannerTriggerType.CRAFT, PlannerTriggerType.PICKUP, PlannerTriggerType.IDLE_THINK);

	/** Concurrent so debug readers on other threads can copy it while the tick thread schedules. */
	private final Deque<Wake> taskWakes = new ConcurrentLinkedDeque<>();
	/** Debounced trigger wakes waiting for quiet; concurrent for the same reason. */
	private final Deque<Held> debounced = new ConcurrentLinkedDeque<>();
	private long lastDebouncedAt;
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

	/** Clears every pending wake: task wakes and held debounced wakes (resets, degradation, world boundaries). */
	public void clearTaskWakes() {
		taskWakes.clear();
		debounced.clear();
		supersedes.clear();
	}

	/** The coalesce window's settings from {@code agent.yml}, in milliseconds; timed here in ticks, rounded up. */
	public void configureCoalescing(long stepMillis, long minMillis, long maxMillis) {
		coalesceStepMillis = Math.max(0L, stepMillis);
		coalesceMinMillis = Math.max(0L, minMillis);
		coalesceMaxMillis = Math.max(coalesceMinMillis, maxMillis);
	}

	/** A supersede's coalesce window is open: the superseded planner is holding its queued triggers. */
	public boolean coalescing() {
		return coalesceReadyAt >= 0L;
	}

	/**
	 * Ends the coalesce window once its tick has come. Returns whether the planner should now start the combined
	 * turn ({@code releaseCoalesceHold}).
	 */
	public boolean releaseCoalesce(long tick) {
		if (coalesceReadyAt < 0L || tick < coalesceReadyAt) return false;
		coalesceReadyAt = -1L;
		return true;
	}

	public boolean hasDebouncedWakes() {
		return !debounced.isEmpty();
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
		if (host.externalDriverActive() || taskWakes.isEmpty()) return false;
		if (host.plannerInFlight() && !preempt(host)) return false;
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
	 * A {@code PREEMPT} wake at the head asks the planner to cancel a turn that its safety change made stale. Whether
	 * that works is audited once per outcome; the wake keeps waiting when the turn cannot be preempted.
	 */
	private boolean preempt(TaskWakeHost host) {
		Wake head = taskWakes.peekFirst();
		if (head == null || !head.preempts()) return false;
		String outcome = host.preemptInFlight();
		if (!"preempted".equals(outcome)) {
			auditDeferred(host, head, "preempt." + outcome);
			return false;
		}
		audit(host, head, "preempted", "preempt.safety_epoch");
		return !host.plannerInFlight();
	}

	/**
	 * Admits a trigger wake (G4) and delivers it. Direct guidance is never held back here; a blocked goal holds
	 * every other trigger, and accepted or queued work holds routine progress and idle think. A debounced wake is
	 * held until quiet ({@link #releaseDebounced}); any wake delivered meanwhile takes the held ones with it, so they
	 * share one batch.
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
		if (!direct && debounces(trigger)) {
			debounced.addLast(new Held(trigger, trigger.tick()));
			lastDebouncedAt = trigger.tick();
			host.audit(trigger, "debounced", "debounce.hold");
			return;
		}
		var batch = takeDebounced(host, "debounce.piggyback");
		batch.add(trigger);
		boolean supersede = false;
		if (direct && !coalescing() && host.canSupersede()) {
			if (withinSupersedeBudget(trigger.tick())) supersede = true;
			else host.audit(trigger, "queued", "supersede.budget");
		}
		host.deliver(batch, supersede);
		// Every batch delivered into an open window (or opening one) re-arms it from this tick, as it grows.
		if (supersede) supersedes.addLast(trigger.tick());
		if (supersede || coalescing()) armCoalesce(trigger.tick(), host.queuedTriggerCount());
	}

	private boolean withinSupersedeBudget(long tick) {
		while (!supersedes.isEmpty() && tick - supersedes.peekFirst() >= SUPERSEDE_WINDOW_TICKS) supersedes.removeFirst();
		return supersedes.size() < SUPERSEDE_BUDGET;
	}

	/** {@code clamp((n-1) x step, min, max)} from the settings, rounded up to whole ticks. */
	private void armCoalesce(long tick, int queued) {
		long window = Math.max(coalesceMinMillis, Math.min(coalesceMaxMillis, (long) Math.max(0, queued - 1) * coalesceStepMillis));
		coalesceReadyAt = tick + (window + MILLIS_PER_TICK - 1) / MILLIS_PER_TICK;
	}

	/**
	 * Releases the held debounced wakes as one batch once {@link #DEBOUNCE_QUIET_TICKS} passed without a new one, or
	 * the oldest waited {@link #DEBOUNCE_MAX_HOLD_TICKS}. Wakes whose event an observation already carried, and
	 * wakes a newly blocked goal holds, are dropped on the way. Returns whether anything was delivered.
	 */
	public boolean releaseDebounced(long tick, TriggerHost host) {
		if (debounced.isEmpty()) return false;
		boolean quiet = tick - lastDebouncedAt >= DEBOUNCE_QUIET_TICKS;
		if (!quiet && tick - debounced.peekFirst().heldAt() < DEBOUNCE_MAX_HOLD_TICKS) return false;
		var batch = takeDebounced(host, quiet ? "debounce.quiet" : "debounce.max_hold");
		if (batch.isEmpty()) return false;
		host.deliver(batch);
		return true;
	}

	/** Protected urgencies are never delayed, whatever a rule asked for (Stage C). */
	private static boolean debounces(PlannerTrigger trigger) {
		var wake = trigger.wake();
		if (wake == null || !wake.debounced()) return false;
		return !List.of("critical", "direct", "high").contains(wake.urgency());
	}

	private List<PlannerTrigger> takeDebounced(TriggerHost host, String gate) {
		var batch = new ArrayList<PlannerTrigger>();
		while (!debounced.isEmpty()) {
			PlannerTrigger held = debounced.removeFirst().trigger();
			if (held.wake() != null && held.wake().seqNo() > 0 && host.incorporated(held.wake().seqNo())) {
				host.audit(held, "dropped", "debounce.incorporated");
			}
			else if (host.blockedGoal()) {
				host.audit(held, "dropped", "G4.blocked_goal");
			}
			else {
				host.audit(held, "released", gate);
				batch.add(held);
			}
		}
		return batch;
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
			entry.put("delivery", wake.delivery().name());
			pending.add(entry);
		}
		var held = new ArrayList<Map<String, Object>>();
		for (Held entry : debounced) {
			var item = new LinkedHashMap<String, Object>();
			item.put("heldAt", entry.heldAt());
			item.put("seqNo", entry.trigger().wake() == null ? 0L : entry.trigger().wake().seqNo());
			item.put("type", entry.trigger().wake() == null ? null : entry.trigger().wake().type());
			held.add(item);
		}
		var state = new LinkedHashMap<String, Object>();
		state.put("pending", pending);
		state.put("debounced", held);
		state.put("coalesceReadyAt", coalesceReadyAt);
		state.put("recentSupersedes", List.copyOf(supersedes));
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
