package ai.moeru.airicraft.modded;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mod-neutral item groups. Each group starts from the vanilla defaults, then adds every member of the
 * registry tags that describe the group (so a modded log tagged {@code minecraft:logs_that_burn} just
 * works), then ids from a user override. Vanilla ids stay first so existing behavior and ordering hold.
 */
public final class ItemGroups {
	public enum Group {
		/** Overworld logs that burn and grow on trees. */
		LOGS(List.of("minecraft:logs_that_burn"), List.of(
			"minecraft:oak_log", "minecraft:spruce_log", "minecraft:birch_log", "minecraft:jungle_log",
			"minecraft:acacia_log", "minecraft:dark_oak_log", "minecraft:mangrove_log", "minecraft:cherry_log",
			"minecraft:pale_oak_log")),
		/** Planks whose log is in {@link #LOGS} (see {@link #plankFor}); nether and bamboo planks are excluded. */
		PLANKS(List.of("minecraft:planks"), List.of(
			"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks",
			"minecraft:acacia_planks", "minecraft:dark_oak_planks", "minecraft:mangrove_planks",
			"minecraft:cherry_planks", "minecraft:pale_oak_planks")),
		/** Cheap blocks navigation may place for bridges and pillars. */
		THROWAWAY_BLOCKS(List.of("c:cobblestones", "c:stones", "c:netherracks", "c:end_stones"), List.of(
			"minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:netherrack",
			"minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:granite", "minecraft:tuff",
			"minecraft:blackstone", "minecraft:end_stone", "minecraft:deepslate")),
		/** Food that is safe to eat automatically when hungry. */
		COOKED_FOOD(List.of(), List.of(
			"minecraft:bread", "minecraft:baked_potato", "minecraft:cooked_beef", "minecraft:cooked_porkchop",
			"minecraft:cooked_mutton", "minecraft:cooked_chicken", "minecraft:cooked_rabbit",
			"minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:mushroom_stew",
			"minecraft:beetroot_soup", "minecraft:rabbit_stew", "minecraft:pumpkin_pie", "minecraft:cookie")),
		/** Raw or plain food eaten only when the food policy allows any safe food. */
		OTHER_SAFE_FOOD(List.of(), List.of(
			"minecraft:apple", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot", "minecraft:melon_slice",
			"minecraft:sweet_berries", "minecraft:glow_berries", "minecraft:dried_kelp", "minecraft:beef",
			"minecraft:porkchop", "minecraft:mutton", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon"));

		private final List<String> tags;
		private final List<String> vanilla;

		Group(List<String> tags, List<String> vanilla) {
			this.tags = tags;
			this.vanilla = vanilla;
		}

		public List<String> tags() {
			return tags;
		}

		/** The override key, for example {@code throwaway_blocks}. */
		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	/** Resolves a registry tag id such as {@code c:gems/diamond} to item ids; empty when unknown. */
	public interface TagSource {
		Set<String> members(String tagId);

		/** Changes whenever tag data may have changed, so cached groups are rebuilt. */
		default Object generation() {
			return this;
		}
	}

	private static final TagSource NO_TAGS = tagId -> Set.of();
	private static final String[] LOG_SUFFIXES = {"_log", "_stem", "_wood", "_hyphae"};

	private static volatile State state = new State(NO_TAGS, Map.of());

	private ItemGroups() {
	}

	/** Installs the live tag source and the user's extra ids per group key; either may be null. */
	public static void install(TagSource tags, Map<String, List<String>> extras) {
		state = new State(tags == null ? NO_TAGS : tags, extras == null ? Map.of() : Map.copyOf(extras));
	}

	public static void reset() {
		state = new State(NO_TAGS, Map.of());
	}

	public static List<String> members(Group group) {
		return state.group(group);
	}

	public static boolean contains(Group group, String itemId) {
		return state.group(group).contains(itemId);
	}

	/** Item ids in a registry tag, or empty. */
	public static Set<String> tagMembers(String tagId) {
		return state.tags.members(tagId);
	}

	/** The planks crafted from a log, derived from its name (oak_log to oak_planks); empty when none is known. */
	public static String plankFor(String logId) {
		String base = baseName(logId, LOG_SUFFIXES);
		if (base == null) {
			return "";
		}
		String candidate = namespace(logId) + ":" + base + "_planks";
		return members(Group.PLANKS).contains(candidate) ? candidate : "";
	}

	/** The log a plank type comes from, the reverse of {@link #plankFor}; empty when none is known. */
	public static String logFor(String plankId) {
		String base = baseName(plankId, new String[] {"_planks"});
		if (base == null) {
			return "";
		}
		for (String log : members(Group.LOGS)) {
			if (plankId.equals(plankFor(log))) {
				return log;
			}
		}
		return "";
	}

	private static String baseName(String id, String[] suffixes) {
		if (id == null) {
			return null;
		}
		String path = id.substring(id.indexOf(':') + 1);
		for (String suffix : suffixes) {
			if (path.endsWith(suffix) && path.length() > suffix.length()) {
				return path.substring(0, path.length() - suffix.length());
			}
		}
		return null;
	}

	private static String namespace(String id) {
		int colon = id.indexOf(':');
		return colon < 0 ? "minecraft" : id.substring(0, colon);
	}

	private static final class State {
		private final TagSource tags;
		private final Map<String, List<String>> extras;
		private final java.util.EnumMap<Group, List<String>> cache = new java.util.EnumMap<>(Group.class);
		private Object cachedGeneration;

		State(TagSource tags, Map<String, List<String>> extras) {
			this.tags = tags;
			this.extras = extras;
		}

		synchronized List<String> group(Group group) {
			Object generation = tags.generation();
			if (!java.util.Objects.equals(generation, cachedGeneration)) {
				cache.clear();
				cachedGeneration = generation;
			}
			List<String> cached = cache.get(group);
			if (cached == null) {
				cached = build(group);
				cache.put(group, cached);
			}
			return cached;
		}

		private List<String> build(Group group) {
			LinkedHashSet<String> ids = new LinkedHashSet<>(group.vanilla);
			TreeSet<String> tagged = new TreeSet<>();
			for (String tag : group.tags) {
				tagged.addAll(tags.members(tag));
			}
			if (group == Group.PLANKS) {
				// Only planks that some overworld-style log produces; keeps nether and bamboo planks out.
				Set<String> logs = new LinkedHashSet<>(Group.LOGS.vanilla);
				for (String tag : Group.LOGS.tags) {
					logs.addAll(tags.members(tag));
				}
				logs.addAll(extras.getOrDefault(Group.LOGS.key(), List.of()));
				TreeSet<String> pairable = new TreeSet<>();
				for (String log : logs) {
					String base = baseName(log, LOG_SUFFIXES);
					if (base != null) {
						pairable.add(namespace(log) + ":" + base + "_planks");
					}
				}
				tagged.retainAll(pairable);
			}
			ids.addAll(tagged);
			ids.addAll(extras.getOrDefault(group.key(), List.of()));
			return List.copyOf(new ArrayList<>(ids));
		}
	}
}
