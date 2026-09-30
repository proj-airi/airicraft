package ai.moeru.airicraft.control;

/** Who may take a channel from whom. A higher priority revokes a lower one at once. */
public enum Priority {
	/** Lighting, idle eating: anything that may yield to the current job. */
	BACKGROUND,
	/** The one active executor, policy or action graph. */
	FOREGROUND,
	/** The survival reflex. */
	REFLEX
}
