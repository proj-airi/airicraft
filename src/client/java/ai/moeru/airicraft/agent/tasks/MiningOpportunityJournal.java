package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Observed optional mining work, handed to the planner feed on the client tick. */
public final class MiningOpportunityJournal {
	public enum Stage { STARTED, BROKEN, MATCHING_ITEM_GAIN_OBSERVED, PICKUP_UNCONFIRMED, ABANDONED }

	public record Notice(String taskId, String jobId, List<String> requestedBlockIds, String blockId,
		List<String> matchingItemIds,
		GoalPosition position, boolean afterRequestedQuota, Stage stage, int observedItemGain, String reason) {
		public Notice {
			requestedBlockIds = List.copyOf(requestedBlockIds);
			matchingItemIds = List.copyOf(matchingItemIds);
		}

		public Map<String, Object> payload() {
			var payload = new LinkedHashMap<String, Object>();
			payload.put("taskId", taskId);
			payload.put("jobId", jobId);
			payload.put("requestedBlockIds", requestedBlockIds);
			payload.put("blockId", blockId);
			payload.put("matchingItemIds", matchingItemIds);
			payload.put("position", Map.of("x", position.x(), "y", position.y(), "z", position.z()));
			payload.put("kind", afterRequestedQuota ? "vein_cleanup" : "side_ore_detour");
			payload.put("stage", stage.name());
			if (observedItemGain > 0) payload.put("observedItemGain", observedItemGain);
			if (reason != null && !reason.isBlank()) payload.put("reason", reason);
			return payload;
		}
	}

	private final List<Notice> pending = new ArrayList<>();

	public void record(Notice notice) { pending.add(notice); }

	public List<Notice> drain() {
		List<Notice> result = List.copyOf(pending);
		pending.clear();
		return result;
	}

	public void clear() { pending.clear(); }
}
