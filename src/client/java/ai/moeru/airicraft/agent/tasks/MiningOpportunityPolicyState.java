package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.actions.BlockAcquisitionIndex;
import java.util.List;

/** Shared policy state for the planner and the active acquisition executor. */
public final class MiningOpportunityPolicyState {
	private volatile MiningOpportunityPolicy policy = MiningOpportunityPolicy.defaults();
	private volatile BlockAcquisitionIndex acquisitions = BlockAcquisitionIndex.empty();

	public MiningOpportunityPolicy policy() { return policy; }

	public MiningOpportunityPolicy configure(boolean enabled, int maxExtraBlocks, int maxExtraTicks) {
		MiningOpportunityPolicy next = new MiningOpportunityPolicy(enabled, maxExtraBlocks, maxExtraTicks, policy.revision() + 1);
		policy = next;
		return next;
	}

	public void updateAcquisitions(BlockAcquisitionIndex index) {
		acquisitions = index == null ? BlockAcquisitionIndex.empty() : index;
	}

	public List<String> matchingItems(String blockId) {
		List<String> outputs = acquisitions.matchingOutputItemIds(List.of(blockId)).stream().sorted().toList();
		return outputs.isEmpty() ? List.of(blockId) : outputs;
	}

	public void reset() {
		policy = MiningOpportunityPolicy.defaults();
		acquisitions = BlockAcquisitionIndex.empty();
	}
}
