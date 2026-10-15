package ai.moeru.airicraft.agent.tasks;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SmeltingFuelCostTest {
	@Test void coalBurnsBeforeLogsEvenWhenItWastesMoreHeat() {
		var choice = SmeltingTaskExecutor.selectFuel(Map.of("minecraft:coal", 11, "minecraft:oak_log", 4),
			Map.of("minecraft:coal", 1600, "minecraft:oak_log", 300), "minecraft:raw_iron", 1, 200).orElseThrow();
		assertEquals("minecraft:coal", choice.itemId());
		assertEquals(1, choice.quantity());
	}

	@Test void logsBurnOnlyWhenNothingElseCovers() {
		var planks = SmeltingTaskExecutor.selectFuel(Map.of("minecraft:oak_planks", 4, "minecraft:oak_log", 4),
			Map.of("minecraft:oak_planks", 300, "minecraft:oak_log", 300), "minecraft:raw_iron", 1, 200).orElseThrow();
		assertEquals("minecraft:oak_planks", planks.itemId());
		var logs = SmeltingTaskExecutor.selectFuel(Map.of("minecraft:coal", 1, "minecraft:oak_log", 20),
			Map.of("minecraft:coal", 1600, "minecraft:oak_log", 300), "minecraft:raw_iron", 16, 3200).orElseThrow();
		assertEquals("minecraft:oak_log", logs.itemId());
	}

	@Test void amongSpareFuelsTheLeastWastedHeatWins() {
		assertTrue(SmeltingFuelCost.compare("minecraft:stick", 2, 100, "minecraft:coal", 1, 1600) < 0);
		assertTrue(SmeltingFuelCost.compare("minecraft:coal", 1, 1600, "minecraft:coal_block", 1, 16000) < 0);
	}
}
