package ai.moeru.airicraft.agent.attention;

/** How a wake is scheduled. {@code NONE} keeps the event as evidence without waking the planner. */
public enum Delivery {
	PREEMPT,
	IMMEDIATE,
	DEBOUNCE,
	NONE
}
