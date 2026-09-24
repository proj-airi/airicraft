package ai.moeru.airicraft;

public enum PlannerConversationView {
	/** Append-only event log: triggers, replies, tool exchanges, markers. Never rewritten. */
	CHRONICLE,
	/** The mutable context the model sees: shrinks on compaction, drops superseded turns. */
	CONTEXT
}
