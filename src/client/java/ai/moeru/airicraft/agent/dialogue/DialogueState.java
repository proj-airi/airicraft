package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.llm.LlmFailureType;

/**
 * Dialogue facts carried between planner results. {@code degradedEntries} counts degraded periods since the last
 * successful turn and sets the next cooldown; {@code degradedRecoverAtMillis} is when the current one ends and
 * {@code outageStartedAtMillis} when the first one began (wall clock, -1 if none: the game may be paused meanwhile).
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
	long degradedRecoverAtMillis,
	long outageStartedAtMillis
) {
	public static DialogueState initial() {
		return new DialogueState(null, false, null, false, 0, null, -1L, 0, -1L, -1L);
	}

	DialogueState withLastResponse(DialogueResponse response) {
		return new DialogueState(response, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverAtMillis, outageStartedAtMillis);
	}

	DialogueState withPendingReply(boolean replacement, String replacementReason) {
		return new DialogueState(lastResponse, replacement, replacement ? replacementReason : null, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverAtMillis, outageStartedAtMillis);
	}

	DialogueState withDegraded(boolean replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, replacement, consecutiveFailureCount, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverAtMillis, outageStartedAtMillis);
	}

	DialogueState withConsecutiveFailureCount(int replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, replacement, lastFailureType, lastFailureTick, degradedEntries, degradedRecoverAtMillis, outageStartedAtMillis);
	}

	DialogueState withLastFailureType(LlmFailureType replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, replacement, lastFailureTick, degradedEntries, degradedRecoverAtMillis, outageStartedAtMillis);
	}

	DialogueState withLastFailureTick(long replacement) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, replacement, degradedEntries, degradedRecoverAtMillis, outageStartedAtMillis);
	}

	DialogueState withDegradedRecovery(int entries, long recoverAtMillis, long outageStartedAt) {
		return new DialogueState(lastResponse, pendingReply, pendingReplyReason, degraded, consecutiveFailureCount, lastFailureType, lastFailureTick, entries, recoverAtMillis, outageStartedAt);
	}
}
