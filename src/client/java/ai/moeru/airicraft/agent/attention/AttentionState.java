package ai.moeru.airicraft.agent.attention;

/**
 * Runtime facts the attention policy reads, taken once per decision. Every field is plain data so the same
 * snapshot can later be handed to a sandboxed rule module.
 *
 * @param activeJobType the active job's type name, or {@code null} without a job
 * @param activeJobTargets block and item ids the running job works on (percept ownership)
 */
public record AttentionState(
	boolean evaluationSuppressed,
	boolean proactiveSocialMode,
	boolean reflexOwnsActuation,
	String activeJobType,
	boolean activeJobIdle,
	boolean activeJobTerminal,
	boolean pendingCraftToolResult,
	java.util.List<String> activeJobTargets
) {
	public AttentionState {
		activeJobTargets = activeJobTargets == null ? java.util.List.of() : java.util.List.copyOf(activeJobTargets);
	}

	public AttentionState(boolean evaluationSuppressed, boolean proactiveSocialMode, boolean reflexOwnsActuation,
		String activeJobType, boolean activeJobIdle, boolean activeJobTerminal, boolean pendingCraftToolResult) {
		this(evaluationSuppressed, proactiveSocialMode, reflexOwnsActuation, activeJobType, activeJobIdle, activeJobTerminal,
			pendingCraftToolResult, java.util.List.of());
	}

	public static AttentionState idle() {
		return new AttentionState(false, false, false, null, true, false, false);
	}

	/** A running job works on this block or item id: a percept about it belongs to the job. */
	boolean activeJobOwns(Object id) {
		return activeJobType != null && !activeJobTerminal && id != null && activeJobTargets.contains(String.valueOf(id));
	}

	boolean idleForNotices() {
		return activeJobType == null || activeJobIdle;
	}

	boolean activeJobRunning(String type) {
		return type.equals(activeJobType) && !activeJobTerminal;
	}
}
