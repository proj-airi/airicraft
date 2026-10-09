package ai.moeru.airicraft.agent.tasks;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

public record CraftingOpportunity(
	String recipeId,
	String outputItemId,
	int outputCount,
	List<String> inputItemIds,
	CraftingGridKind gridKind
) {
	public CraftingOpportunity(String recipeId, String outputItemId, int outputCount, List<String> inputItemIds) {
		this(recipeId, outputItemId, outputCount, inputItemIds, CraftingGridKind.PLAYER_2X2);
	}

	public CraftingOpportunity {
		if (recipeId == null || recipeId.isBlank()) {
			throw new IllegalArgumentException("recipeId must not be blank");
		}
		if (outputItemId == null || outputItemId.isBlank()) {
			throw new IllegalArgumentException("outputItemId must not be blank");
		}
		if (outputCount <= 0) {
			throw new IllegalArgumentException("outputCount must be positive");
		}
		if (inputItemIds == null || inputItemIds.isEmpty()) {
			throw new IllegalArgumentException("inputItemIds must not be empty");
		}
		for (String inputItemId : inputItemIds) {
			if (inputItemId == null || inputItemId.isBlank()) {
				throw new IllegalArgumentException("inputItemIds must not contain blank ids");
			}
		}
		inputItemIds = List.copyOf(inputItemIds);
		gridKind = gridKind == null ? CraftingGridKind.PLAYER_2X2 : gridKind;
	}

	public String compactDescription() {
		return "[From {" + formatInputSummary(inputItemIds) + "} to " + outputCount + "*" + displayItemId(outputItemId) + "]: " + recipeId;
	}

	static String displayItemId(String itemId) {
		if (itemId == null) {
			return "";
		}
		return itemId.startsWith("minecraft:") ? itemId.substring("minecraft:".length()) : itemId;
	}

	static String recipeIdSegment(String itemId) {
		String display = displayItemId(itemId).toLowerCase(java.util.Locale.ROOT);
		// Recipe enumeration calls this frequently. Collapse separators without
		// compiling patterns and allocating matchers for every candidate.
		var segment = new StringBuilder(display.length());
		boolean separator = false;
		for (int i = 0; i < display.length(); i++) {
			char c = display.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
				if (separator && !segment.isEmpty()) segment.append('_');
				segment.append(c);
				separator = false;
			} else {
				separator = true;
			}
		}
		return segment.toString();
	}

	private static String formatInputSummary(List<String> inputItemIds) {
		Map<String, Integer> counts = new TreeMap<>();
		for (String itemId : inputItemIds) {
			counts.merge(itemId, 1, Integer::sum);
		}
		return counts.entrySet().stream()
			.map(entry -> entry.getValue() + "*" + displayItemId(entry.getKey()))
			.collect(Collectors.joining(","));
	}
}
