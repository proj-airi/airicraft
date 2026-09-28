package ai.moeru.airicraft.agent.attention;

/**
 * Runtime facts the attention policy reads, taken once per decision. Every field is plain data so the same
 * snapshot can later be handed to a sandboxed rule module.
 *
 * @param activeJobType the active job's type name, or {@code null} without a job
 */
public record AttentionState(
	boolean evaluationSuppressed,
	boolean proactiveSocialMode,
	boolean reflexOwnsActuation,
	String activeJobType,
	boolean activeJobIdle,
	boolean activeJobTerminal,
	boolean pendingCraftToolResult
) {
	public static AttentionState idle() {
		return new AttentionState(false, false, false, null, true, false, false);
	}

	boolean activeJobRunning(String type) {
		return type.equals(activeJobType) && !activeJobTerminal;
	}
}
