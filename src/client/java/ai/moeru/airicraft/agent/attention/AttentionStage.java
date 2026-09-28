package ai.moeru.airicraft.agent.attention;

/**
 * Which part of the attention policy decided. {@code CONSTITUTION} is the fixed Java protection (Stage A),
 * {@code RULES} the judgement layer (Stage B), {@code CLAMP} a Stage C correction, and {@code FALLBACK} the
 * Java reference deciding in place of an unavailable rule engine.
 */
public enum AttentionStage {
	CONSTITUTION,
	RULES,
	CLAMP,
	FALLBACK
}
