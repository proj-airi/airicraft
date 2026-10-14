package ai.moeru.airicraft.modded;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.moeru.airicraft.modded.ItemGroups.Group;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ItemGroupsTest {
	@AfterEach
	void reset() {
		ItemGroups.reset();
	}

	@Test
	void vanillaDefaultsWorkWithoutTags() {
		assertTrue(ItemGroups.contains(Group.LOGS, "minecraft:oak_log"));
		assertEquals("minecraft:cherry_planks", ItemGroups.plankFor("minecraft:cherry_log"));
		assertEquals("minecraft:cherry_log", ItemGroups.logFor("minecraft:cherry_planks"));
	}

	@Test
	void modTaggedLogsJoinAfterVanillaWithTheirPlanks() {
		ItemGroups.install(tags(Map.of(
			"minecraft:logs_that_burn", Set.of("minecraft:oak_log", "ex:maple_log", "ex:amber_stem"),
			"minecraft:planks", Set.of("minecraft:oak_planks", "ex:maple_planks", "ex:amber_planks",
				"minecraft:bamboo_planks", "minecraft:crimson_planks"))), Map.of());

		List<String> logs = ItemGroups.members(Group.LOGS);
		assertEquals("minecraft:oak_log", logs.get(0));
		assertTrue(logs.containsAll(List.of("ex:maple_log", "ex:amber_stem")));
		List<String> planks = ItemGroups.members(Group.PLANKS);
		assertTrue(planks.contains("ex:maple_planks") && planks.contains("ex:amber_planks"));
		assertFalse(planks.contains("minecraft:bamboo_planks"));
		assertFalse(planks.contains("minecraft:crimson_planks"));
		assertEquals("ex:maple_planks", ItemGroups.plankFor("ex:maple_log"));
		assertEquals("ex:amber_planks", ItemGroups.plankFor("ex:amber_stem"));
		assertEquals("ex:maple_log", ItemGroups.logFor("ex:maple_planks"));
		assertEquals("", ItemGroups.plankFor("minecraft:stone"));
	}

	@Test
	void userExtrasAreAppendedAndTagChangesRebuildTheCache() {
		ItemGroups.install(tags(Map.of()), Map.of("throwaway_blocks", List.of("ex:slate")));
		assertTrue(ItemGroups.contains(Group.THROWAWAY_BLOCKS, "ex:slate"));
		assertFalse(ItemGroups.contains(Group.THROWAWAY_BLOCKS, "ex:basalt"));

		ItemGroups.install(tags(Map.of("c:stones", Set.of("ex:basalt"))), Map.of());
		assertTrue(ItemGroups.contains(Group.THROWAWAY_BLOCKS, "ex:basalt"));
	}

	private static ItemGroups.TagSource tags(Map<String, Set<String>> byTag) {
		return tagId -> byTag.getOrDefault(tagId, Set.of());
	}
}
