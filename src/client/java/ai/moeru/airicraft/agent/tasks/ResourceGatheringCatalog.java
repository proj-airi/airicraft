package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.modded.ItemGroups;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

public final class ResourceGatheringCatalog {
	private static final Map<TaskResourceKind, ResourceEntry> BY_KIND = buildByKind();

	private ResourceGatheringCatalog() {
	}

	public static List<TaskResourceKind> supportedKinds() {
		return List.copyOf(BY_KIND.keySet());
	}

	public static List<String> supportedKindNames() {
		return supportedKinds().stream()
			.map(TaskResourceKind::name)
			.toList();
	}

	/** Convention tags (Fabric's {@code c:} namespace) whose items also count toward a resource kind. */
	private static final Map<TaskResourceKind, List<String>> EXTRA_ITEM_TAGS = Map.of(
		TaskResourceKind.COAL, List.of("c:coal"),
		TaskResourceKind.RAW_IRON, List.of("c:raw_materials/iron"),
		TaskResourceKind.RAW_COPPER, List.of("c:raw_materials/copper"),
		TaskResourceKind.RAW_GOLD, List.of("c:raw_materials/gold"),
		TaskResourceKind.DIAMOND, List.of("c:gems/diamond"),
		TaskResourceKind.EMERALD, List.of("c:gems/emerald"),
		TaskResourceKind.REDSTONE, List.of("c:dusts/redstone"),
		TaskResourceKind.LAPIS_LAZULI, List.of("c:gems/lapis"),
		TaskResourceKind.QUARTZ, List.of("c:gems/quartz")
	);

	/** The entry for a kind, widened with the live registry's modded members of its tags and groups. */
	public static Optional<ResourceEntry> entry(TaskResourceKind kind) {
		ResourceEntry base = BY_KIND.get(kind);
		if (base == null) {
			return Optional.empty();
		}
		LinkedHashSet<String> accepted = new LinkedHashSet<>(base.acceptedItemIds());
		if (kind == TaskResourceKind.WOOD_LOGS) {
			accepted.addAll(ItemGroups.members(ItemGroups.Group.LOGS));
		}
		TreeSet<String> tagged = new TreeSet<>();
		for (String tag : EXTRA_ITEM_TAGS.getOrDefault(kind, List.of())) {
			tagged.addAll(ItemGroups.tagMembers(tag));
		}
		accepted.addAll(tagged);
		return Optional.of(new ResourceEntry(kind, List.copyOf(accepted), base.primaryItemId(), base.aggregate()));
	}

	public static Optional<ResourceEntry> entry(String resourceKind) {
		if (resourceKind == null || resourceKind.isBlank()) {
			return Optional.empty();
		}
		try {
			return entry(TaskResourceKind.valueOf(resourceKind.trim().toUpperCase(java.util.Locale.ROOT)));
		}
		catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}

	public static boolean accepts(TaskResourceKind kind, String itemId) {
		return entry(kind)
			.map(entry -> entry.acceptedItemIds().contains(normalize(itemId)))
			.orElse(false);
	}

	private static Map<TaskResourceKind, ResourceEntry> buildByKind() {
		EnumMap<TaskResourceKind, ResourceEntry> entries = new EnumMap<>(TaskResourceKind.class);
		put(entries, aggregate(TaskResourceKind.WOOD_LOGS, List.of(
			"minecraft:oak_log",
			"minecraft:birch_log",
			"minecraft:spruce_log",
			"minecraft:jungle_log",
			"minecraft:acacia_log",
			"minecraft:dark_oak_log",
			"minecraft:mangrove_log",
			"minecraft:cherry_log",
			"minecraft:pale_oak_log"
		)));
		put(entries, itemBacked(TaskResourceKind.DIRT, "minecraft:dirt"));
		put(entries, itemBacked(TaskResourceKind.COBBLESTONE, "minecraft:cobblestone"));
		put(entries, itemBacked(TaskResourceKind.COAL, "minecraft:coal"));
		put(entries, itemBacked(TaskResourceKind.RAW_IRON, "minecraft:raw_iron"));
		put(entries, itemBacked(TaskResourceKind.RAW_COPPER, "minecraft:raw_copper"));
		put(entries, itemBacked(TaskResourceKind.RAW_GOLD, "minecraft:raw_gold"));
		put(entries, itemBacked(TaskResourceKind.DIAMOND, "minecraft:diamond"));
		put(entries, itemBacked(TaskResourceKind.EMERALD, "minecraft:emerald"));
		put(entries, itemBacked(TaskResourceKind.REDSTONE, "minecraft:redstone"));
		put(entries, itemBacked(TaskResourceKind.LAPIS_LAZULI, "minecraft:lapis_lazuli"));
		put(entries, itemBacked(TaskResourceKind.QUARTZ, "minecraft:quartz"));
		return Collections.unmodifiableMap(entries);
	}

	private static ResourceEntry aggregate(TaskResourceKind kind, List<String> acceptedItemIds) {
		return new ResourceEntry(kind, acceptedItemIds, "", true);
	}

	private static ResourceEntry itemBacked(TaskResourceKind kind, String primaryItemId) {
		return new ResourceEntry(kind, List.of(primaryItemId), primaryItemId, false);
	}

	private static void put(Map<TaskResourceKind, ResourceEntry> entries, ResourceEntry entry) {
		entries.put(entry.kind(), entry);
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim();
	}

	public record ResourceEntry(
		TaskResourceKind kind,
		List<String> acceptedItemIds,
		String primaryItemId,
		boolean aggregate
	) {
		public ResourceEntry {
			acceptedItemIds = List.copyOf(acceptedItemIds == null ? List.of() : acceptedItemIds);
			primaryItemId = primaryItemId == null ? "" : primaryItemId;
		}
	}
}
