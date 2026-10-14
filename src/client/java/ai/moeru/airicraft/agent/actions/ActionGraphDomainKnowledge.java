package ai.moeru.airicraft.agent.actions;

import ai.moeru.airicraft.modded.ItemGroups;

import java.util.List;

final class ActionGraphDomainKnowledge {
	private ActionGraphDomainKnowledge() {
	}

	static List<String> plankItemIds() {
		return ItemGroups.members(ItemGroups.Group.PLANKS);
	}

	static List<String> logItemIds() {
		return ItemGroups.members(ItemGroups.Group.LOGS);
	}

}
