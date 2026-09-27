package ai.moeru.airicraft.agent.tasks;

/** A bounded permission for extra ore breaks during an existing mining task. */
public record MiningOpportunityPolicy(boolean enabled, int maxExtraBlocks, int maxExtraTicks, long revision) {
	public MiningOpportunityPolicy {
		if (maxExtraBlocks < 0 || maxExtraBlocks > 32) throw new IllegalArgumentException("maxExtraBlocks must be 0..32");
		if (maxExtraTicks < 0 || maxExtraTicks > 1200) throw new IllegalArgumentException("maxExtraTicks must be 0..1200");
	}

	public static MiningOpportunityPolicy defaults() {
		return new MiningOpportunityPolicy(true, 6, 200, 0);
	}
}
