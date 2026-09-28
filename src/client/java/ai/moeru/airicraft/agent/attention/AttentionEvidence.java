package ai.moeru.airicraft.agent.attention;

/**
 * Per-event facts that depend on live world state or chat parsing. The host computes them so the policy, and
 * later a rule module, only ever reads data. Fields that do not apply to an event are {@code false}.
 */
public record AttentionEvidence(boolean addressedToAgent, boolean resetCommand, boolean senderWithinChatDistance) {
	public static final AttentionEvidence NONE = new AttentionEvidence(false, false, false);
}
