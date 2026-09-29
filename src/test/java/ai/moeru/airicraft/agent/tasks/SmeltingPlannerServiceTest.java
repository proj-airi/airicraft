package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmeltingPlannerServiceTest {
	@Test void smallBatchUsesPlanksButBulkBatchCanUseCoal() {
		var fuel = new SmeltingPlannerService.FuelInventorySummary(Map.of(
			"minecraft:coal", new SmeltingPlannerService.FuelItemSummary("minecraft:coal", 1, 1600),
			"minecraft:spruce_planks", new SmeltingPlannerService.FuelItemSummary("minecraft:spruce_planks", 8, 300)));
		assertEquals("minecraft:spruce_planksx1", fuel.bestFuelFor("minecraft:spruce_log", 1, 200));
		assertEquals("minecraft:spruce_planksx2", fuel.bestFuelFor("minecraft:spruce_log", 2, 200));
		assertEquals("minecraft:coalx1", fuel.bestFuelFor("minecraft:raw_iron", 8, 200));
	}

	@Test void equalBurnTimePreservesWholeLogsWhenPlanksAreAvailable() {
		var fuel = new SmeltingPlannerService.FuelInventorySummary(Map.of(
			"minecraft:oak_log", new SmeltingPlannerService.FuelItemSummary("minecraft:oak_log", 2, 300),
			"minecraft:oak_planks", new SmeltingPlannerService.FuelItemSummary("minecraft:oak_planks", 2, 300)));
		assertEquals("minecraft:oak_planksx1", fuel.bestFuelFor("minecraft:raw_iron", 1, 200));
	}

	@Test void charcoalPreviewReservesLogsBeforeChoosingFuel() {
		var fuel = new SmeltingPlannerService.FuelInventorySummary(Map.of(
			"log", new SmeltingPlannerService.FuelItemSummary("log", 6, 300),
			"planks", new SmeltingPlannerService.FuelItemSummary("planks", 12, 300)));
		assertEquals("planksx4", fuel.bestFuelFor("log", 6, 200));
		assertEquals("logx2", fuel.bestFuelFor("log", 3, 200));
	}

	@Test void charcoalPreviewCannotCountTheOnlyLogTwice() {
		var fuel = new SmeltingPlannerService.FuelInventorySummary(Map.of(
			"log", new SmeltingPlannerService.FuelItemSummary("log", 1, 300)));
		assertEquals("missing", fuel.bestFuelFor("log", 1, 200));
	}

	@Test
	void effectiveCookTimeKeepsFurnaceRecipesAtFullFurnaceDuration() {
		assertEquals(200, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.FURNACE));
		assertEquals(100, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.BLAST_FURNACE));
		assertEquals(100, SmeltingPlannerService.effectiveCookTimeTicks(100, SmeltingStationKind.SMOKER));
	}

	@Test
	void furnacePlacementCandidatesIncludeSimpleVerticalOffsets() {
		BlockPos origin = new BlockPos(15, 62, 10);
		List<BlockPos> candidates = SmeltingPlannerService.furnacePlacementCandidatePositions(origin);

		assertEquals(new BlockPos(15, 62, 9), candidates.get(0));
		assertTrue(candidates.contains(new BlockPos(15, 61, 9)));
		assertTrue(candidates.contains(new BlockPos(15, 63, 9)));
	}

	@Test
	void furnacePlacementFindsReachableLedgeAboveMiningHole() {
		// Paused playtest at 5,125,4: the nearby feet/head cells are occupied
		// by the player; the first usable air with solid support is this ledge.
		var origin = new BlockPos(5, 125, 4);
		var ledge = new BlockPos(4, 127, 4);
		assertTrue(SmeltingPlannerService.furnacePlacementCandidatePositions(origin).stream()
			.anyMatch(ledge::equals), "reachable supported ledge must be considered for a carried furnace");
	}

	@Test
	void knownRecipeMergeKeepsGenericSmeltingRecipesAndDeduplicatesByOptionId() {
		SmeltingRecipeKnowledge gold = recipe(
			"inferred:minecraft_raw_gold_to_minecraft_gold_ingot",
			"minecraft:raw_gold",
			"minecraft:gold_ingot"
		);
		SmeltingRecipeKnowledge duplicateGold = recipe(
			"inferred:minecraft_raw_gold_to_minecraft_gold_ingot",
			"minecraft:raw_gold",
			"minecraft:gold_ingot"
		);
		SmeltingRecipeKnowledge glass = recipe(
			"inferred:minecraft_sand_to_minecraft_glass",
			"minecraft:sand",
			"minecraft:glass"
		);

		List<SmeltingRecipeKnowledge> merged = SmeltingPlannerService.mergeKnownSmelts(
			List.of(gold),
			List.of(duplicateGold, glass)
		);

		assertEquals(List.of(gold, glass), merged);
	}

	@Test
	void hiddenClientSlotsAreRenderedAsUnavailableInsteadOfEmpty() {
		SmeltingStationObservation observation = new SmeltingStationObservation(
			new SmeltingStationKey("minecraft:overworld", 1, 64, 1),
			SmeltingStationKind.FURNACE,
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, true),
			false,
			1.0D,
			false
		);

		assertEquals("unavailable burning=true", SmeltingPlannerService.slotSummary(observation));
	}

	private static SmeltingRecipeKnowledge recipe(String optionId, String inputItemId, String outputItemId) {
		return new SmeltingRecipeKnowledge(
			optionId,
			inputItemId,
			outputItemId,
			1,
			64,
			200,
			"minecraft:furnace",
			1
		);
	}
}
