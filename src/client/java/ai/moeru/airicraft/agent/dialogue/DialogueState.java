package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;

/**
 * Dialogue facts carried between planner results. {@code degradedEntries} counts degraded periods since the last
 * successful turn and sets the next cooldown; {@code degradedRecoverTick} is when the current one ends (-1 if none).
 */
public record DialogueState(
	DialogueResponse lastResponse,
	boolean pendingReply,
	String pendingReplyReason,
	boolean degraded,
	int consecutiveFailureCount,
	LlmFailureType lastFailureType,
	long lastFailureTick,
	int degradedEntries,
	long degradedRecoverTick
) {
	public static DialogueState initial() {
		return new DialogueState(null, false, null, false, 0, null, -1L, 0, -1L);
	}

	DialogueState withLastResponse(DialogueResponse response) {
		return new DialogueState(response, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverTick);
	}

	DialogueState withPendingReply(boolean replacement, String replacementReason) {
		return new DialogueState(lastResponse, replacement, replacement ? replacementReason : null, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverTick);
	}

	DialogueState withDegraded(boolean replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, replacement, consecutiveFailureCount, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverTick);
	}

	DialogueState withConsecutiveFailureCount(int replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, replacement, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverTick);
	}

	DialogueState withLastFailureType(LlmFailureType replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, replacement, lastFailureTick, degradedEntries, degradedRecoverTick);
	}

	DialogueState withLastFailureTick(long replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, replacement, degradedEntries, degradedRecoverTick);
	}

	DialogueState withDegradedRecovery(int entries, long recoverTick) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick, entries, recoverTick);
	}
}
