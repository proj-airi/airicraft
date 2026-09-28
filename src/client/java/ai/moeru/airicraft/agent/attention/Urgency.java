package ai.moeru.airicraft.agent.attention;

/** Ordered importance of a wake, most urgent first. Independent of how it is delivered. */
public enum Urgency {
	CRITICAL,
	DIRECT,
	HIGH,
	NORMAL,
	LOW,
	SELF
}
