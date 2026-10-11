package ai.moeru.airicraft.agent.llm;

/** Who the planner is. Each mode has its own system prompt. */
public enum PlannerMode {
	/** The planner is the whole character. This is the mode without AIRI. */
	STANDALONE,
	/** The planner is the body of the AIRI character. AIRI is the self and sends commands. */
	AIRI_BODY
}
