package ai.moeru.airicraft.agent.attention;

import java.util.Objects;

/** The runtime facts a policy read for one decision, kept so a recorded decision can be replayed. */
public record AttentionInputs(AttentionState state, AttentionEvidence evidence) {
	public AttentionInputs {
		Objects.requireNonNull(state, "state");
		Objects.requireNonNull(evidence, "evidence");
	}
}
