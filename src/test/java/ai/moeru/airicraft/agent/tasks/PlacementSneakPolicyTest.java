package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacementSneakPolicyTest {
	@Test
	void stationaryFullySupportedStandingPlacementAgainstKnownInertStoneDoesNotNeedSneak() {
		for (String support : List.of("minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate", "minecraft:cobbled_deepslate")) {
			assertFalse(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions(support, true, true, 0D, 0D)), support);
		}
	}

	@Test
	void commonVanillaDirtAndPlankSupportsDoNotNeedSneakInASafeStance() {
		for (String support : List.of("dirt", "grass_block", "oak_planks", "spruce_planks", "birch_planks",
			"jungle_planks", "acacia_planks", "dark_oak_planks", "mangrove_planks", "cherry_planks",
			"pale_oak_planks", "bamboo_planks", "crimson_planks", "warped_planks")) {
			assertFalse(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions("minecraft:" + support, true, true, 0D, 0D)), support);
		}
	}

	@Test
	void interactiveSupportsIncludingBlocksWithoutMenusStillNeedSneak() {
		for (String support : List.of("minecraft:chest", "minecraft:barrel", "minecraft:crafting_table", "minecraft:oak_door", "minecraft:stone_button", "minecraft:lever")) {
			assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions(support, true, true, 0D, 0D)), support);
		}
	}

	@Test
	void unknownModdedAndUnclassifiedVanillaSupportsFailClosed() {
		for (String support : List.of("mod:stone", "mod:oak_planks", "minecraft:new_stone", "minecraft:copper_block", "")) {
			assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions(support, true, true, 0D, 0D)), support);
		}
		assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions(null, true, true, 0D, 0D)));
	}

	@Test
	void unsupportedOrUnstableStanceStillNeedsSneak() {
		assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions("minecraft:stone", true, false, 0D, 0D)));
		assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions("minecraft:stone", false, true, 0D, 0D)));
	}

	@Test
	void anyResidualHorizontalMotionStillNeedsSneak() {
		for (double velocity : new double[] {-0.1D, 0.1D, -1.0E-12D, 1.0E-12D}) {
			assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions("minecraft:stone", true, true, velocity, 0D)));
			assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions("minecraft:stone", true, true, 0D, velocity)));
		}
	}

	@Test
	void invalidMotionFailsClosed() {
		for (double velocity : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions("minecraft:stone", true, true, velocity, 0D)));
			assertTrue(PlacementSneakPolicy.requiresSneak(new PlacementSneakPolicy.Conditions("minecraft:stone", true, true, 0D, velocity)));
		}
	}

	@Test
	void centeredFootprintOnlyNeedsItsFloorBlock() {
		assertEquals(new PlacementSneakPolicy.SupportRegion(0, 63, 0, 0, 0),
			PlacementSneakPolicy.supportRegion(0.2D, 64D, 0.2D, 0.8D, 0.8D).orElseThrow());
	}

	@Test
	void footprintNearAnEdgeRequiresTheAdjacentFloorBlockAsWell() {
		assertEquals(new PlacementSneakPolicy.SupportRegion(-1, 63, 0, 0, 0),
			PlacementSneakPolicy.supportRegion(0.04D, 64D, 0.2D, 0.64D, 0.8D).orElseThrow());
		assertEquals(new PlacementSneakPolicy.SupportRegion(0, 63, 0, 1, 1),
			PlacementSneakPolicy.supportRegion(0.36D, 64D, 0.36D, 0.96D, 0.96D).orElseThrow());
	}

	@Test
	void negativeCoordinatesUseFloorRatherThanTruncation() {
		assertEquals(new PlacementSneakPolicy.SupportRegion(-2, -1, -3, -2, -3),
			PlacementSneakPolicy.supportRegion(-1.8D, 0D, -2.8D, -1.2D, -2.2D).orElseThrow());
	}

	@Test
	void nonFullBlockHeightAndInvalidFootprintsFailClosed() {
		assertTrue(PlacementSneakPolicy.supportRegion(0.2D, 64.5D, 0.2D, 0.8D, 0.8D).isEmpty());
		assertTrue(PlacementSneakPolicy.supportRegion(0.2D, 64.0625D, 0.2D, 0.8D, 0.8D).isEmpty());
		assertTrue(PlacementSneakPolicy.supportRegion(Double.NaN, 64D, 0.2D, 0.8D, 0.8D).isEmpty());
		assertTrue(PlacementSneakPolicy.supportRegion(0.8D, 64D, 0.2D, 0.2D, 0.8D).isEmpty());
	}

	@Test
	void activeVisibilityNavigationMustContinueUntilTheCurrentSupportFaceIsVisible() {
		assertTrue(PlacementSneakPolicy.continueNavigationBeforePreparing(true, false));
		assertFalse(PlacementSneakPolicy.continueNavigationBeforePreparing(true, true));
		assertFalse(PlacementSneakPolicy.continueNavigationBeforePreparing(false, false));
		assertFalse(PlacementSneakPolicy.continueNavigationBeforePreparing(false, true));
	}
}
