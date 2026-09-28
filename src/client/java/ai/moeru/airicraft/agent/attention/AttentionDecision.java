package ai.moeru.airicraft.agent.attention;

/**
 * One recorded attention decision. {@code wakeProduced} is whether a planner trigger resulted; it can be false
 * for a waking decision when the trigger factory rejects an invalid payload.
 */
public record AttentionDecision(
	long seqNo,
	long tick,
	String type,
	boolean emitSemantic,
	Delivery delivery,
	Urgency urgency,
	AttentionStage stage,
	String ruleId,
	String reason,
	boolean wakeProduced
) {
}
