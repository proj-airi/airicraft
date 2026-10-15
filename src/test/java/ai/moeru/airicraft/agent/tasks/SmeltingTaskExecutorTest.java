package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SmeltingTaskExecutorTest {
	@Test void reservesOnlyInputsStillNeededFromInventory() {
		assertEquals(0, SmeltingTaskExecutor.fuelCountAfterReservingInput("log", 1, "log", 1));
		assertEquals(4, SmeltingTaskExecutor.fuelCountAfterReservingInput("log", 10, "log", 6));
		assertEquals(12, SmeltingTaskExecutor.fuelCountAfterReservingInput("planks", 12, "log", 6));
		int reserved = SmeltingTaskExecutor.remainingItemsToMove("log", 2, "log", 3);
		assertEquals(1, SmeltingTaskExecutor.fuelCountAfterReservingInput("log", 2, "log", reserved));
	}

	@Test void confirmedOpenFurnaceRequiresTheSameScreenAndDimension() {
		var key = new SmeltingStationKey("minecraft:overworld#open_screen", 4, 0, 0);
		assertEquals(true, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:overworld", 4, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:overworld", 5, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:the_nether", 4, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(key, "minecraft:overworld", 4, false));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(null, "minecraft:overworld", 4, true));
		assertEquals(false, SmeltingTaskExecutor.canCollectFromCurrentScreen(new SmeltingStationKey("minecraft:overworld", 4, 0, 0), "minecraft:overworld", 4, true));
	}

	@Test
	void onlyAnEmptyFurnaceTheAgentPlacedIsPickedUpWithAPickaxe() {
		assertEquals(SmeltingTaskExecutor.FurnaceReclaim.RECLAIM,
			SmeltingTaskExecutor.furnaceReclaimDecision(true, true, false, true, true));
		assertEquals(SmeltingTaskExecutor.FurnaceReclaim.NOT_PLACED_BY_AGENT,
			SmeltingTaskExecutor.furnaceReclaimDecision(false, true, false, true, true));
		assertEquals(SmeltingTaskExecutor.FurnaceReclaim.NOT_PLACED_BY_AGENT,
			SmeltingTaskExecutor.furnaceReclaimDecision(true, false, false, true, true));
		assertEquals(SmeltingTaskExecutor.FurnaceReclaim.STATION_IN_USE,
			SmeltingTaskExecutor.furnaceReclaimDecision(true, true, true, true, true));
		assertEquals(SmeltingTaskExecutor.FurnaceReclaim.LEAVE_NOT_EMPTY,
			SmeltingTaskExecutor.furnaceReclaimDecision(true, true, false, false, true));
		assertEquals(SmeltingTaskExecutor.FurnaceReclaim.LEAVE_NO_PICKAXE,
			SmeltingTaskExecutor.furnaceReclaimDecision(true, true, false, true, false));
		assertEquals("no_pickaxe", SmeltingTaskExecutor.FurnaceReclaim.LEAVE_NO_PICKAXE.reason());
		assertEquals("furnace_not_empty", SmeltingTaskExecutor.FurnaceReclaim.LEAVE_NOT_EMPTY.reason());
	}

	@Test
	void furnaceCountsAsEmptyOnlyWithInputFuelAndOutputEmpty() {
		assertEquals(true, SmeltingTaskExecutor.furnaceSlotsEmpty(
			new SmeltingSlotSnapshot(null, 0, null, 0, null, 0, 0, 200, true)));
		assertEquals(false, SmeltingTaskExecutor.furnaceSlotsEmpty(
			new SmeltingSlotSnapshot(null, 0, "minecraft:coal", 1, null, 0, 0, 200, false)));
		assertEquals(false, SmeltingTaskExecutor.furnaceSlotsEmpty(
			new SmeltingSlotSnapshot("minecraft:raw_iron", 2, null, 0, null, 0, 0, 200, false)));
		assertEquals(false, SmeltingTaskExecutor.furnaceSlotsEmpty(
			new SmeltingSlotSnapshot(null, 0, null, 0, "minecraft:iron_ingot", 1, 0, 200, false)));
	}

	@Test
	void fuelItemsNeededRoundsUpCookTime() {
		assertEquals(0, SmeltingTaskExecutor.fuelItemsNeeded(0, 1600));
		assertEquals(1, SmeltingTaskExecutor.fuelItemsNeeded(200, 1600));
		assertEquals(2, SmeltingTaskExecutor.fuelItemsNeeded(1800, 1600));
	}

	@Test
	void fuelQuantityMustCoverFullCookTime() {
		assertEquals(false, SmeltingTaskExecutor.fuelQuantityCoversCookTime(2, 200, 300, 1));
		assertEquals(true, SmeltingTaskExecutor.fuelQuantityCoversCookTime(1, 200, 300, 1));
		assertEquals(false, SmeltingTaskExecutor.fuelQuantityCoversCookTime(1, 200, 100, 1));
		assertEquals(true, SmeltingTaskExecutor.fuelQuantityCoversCookTime(1, 200, 100, 2));
		assertEquals(true, SmeltingTaskExecutor.fuelQuantityCoversCookTime(3, 200, 1600, 1));
	}

	@Test
	void remainingItemsToMoveCountsMatchingTargetSlotContents() {
		assertEquals(3, SmeltingTaskExecutor.remainingItemsToMove(null, 0, "minecraft:raw_iron", 3));
		assertEquals(1, SmeltingTaskExecutor.remainingItemsToMove("minecraft:raw_iron", 2, "minecraft:raw_iron", 3));
		assertEquals(0, SmeltingTaskExecutor.remainingItemsToMove("minecraft:raw_iron", 3, "minecraft:raw_iron", 3));
		assertEquals(-1, SmeltingTaskExecutor.remainingItemsToMove("minecraft:coal", 1, "minecraft:raw_iron", 3));
	}
}
