package ai.moeru.airicraft.agent.durability;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolWearTest {
	private static ToolStack pickaxe(int slot, String id, int remaining, int maximum) {
		return new ToolStack(slot, id, "pickaxe", remaining, maximum);
	}

	@Test void lowIsATenthOrEightUses() {
		assertTrue(ToolWear.low(8, 59));
		assertFalse(ToolWear.low(9, 59));
		assertTrue(ToolWear.low(25, 250));
		assertFalse(ToolWear.low(26, 250));
		assertTrue(ToolWear.low(157, 1561));
	}

	@Test void durabilityIsCoarseUntilLow() {
		assertEquals("90%", ToolWear.describe(249, 250));
		assertEquals("50%", ToolWear.describe(125, 250));
		assertEquals("10%", ToolWear.describe(26, 250));
		assertEquals("low, 25/250 uses left", ToolWear.describe(25, 250));
		// Mining one block does not change the presented value.
		assertEquals(ToolWear.describe(140, 250), ToolWear.describe(139, 250));
	}

	@Test void presentListsOnlyDamagedItems() {
		var entries = ToolWear.present(List.of(pickaxe(0, "minecraft:iron_pickaxe", 250, 250), pickaxe(1, "minecraft:stone_pickaxe", 10, 131)));
		assertEquals(List.of(Map.of("itemId", "minecraft:stone_pickaxe", "slot", 1, "durability", "low, 10/131 uses left")), entries);
	}

	@Test void shortfallWarnsOnlyWhenToolsCannotLast() {
		var tools = List.of(pickaxe(0, "minecraft:iron_pickaxe", 40, 250), pickaxe(1, "minecraft:stone_pickaxe", 12, 131));
		assertTrue(ToolWear.shortfall(tools, false, 52).isEmpty());
		String warning = ToolWear.shortfall(tools, false, 64).orElseThrow();
		assertTrue(warning.contains("about 52 uses left"), warning);
		assertTrue(warning.contains("minecraft:iron_pickaxe 40/250"), warning);
		assertTrue(warning.contains("for 64 blocks"), warning);
		assertTrue(ToolWear.shortfall(tools, true, 64).isEmpty(), "an unbreakable eligible tool always lasts");
		assertTrue(ToolWear.shortfall(List.of(), false, 64).isEmpty(), "no eligible tool is the job's own failure");
	}

	@Test void lastToolCrossingLowWarnsOnce() {
		var tracker = new ToolWear.Tracker();
		assertEquals(List.of(), tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 27, 250))));
		assertEquals(List.of(), tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 26, 250))));
		var events = tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 25, 250)));
		assertEquals(1, events.size());
		assertEquals("tool.durability_low", events.getFirst().type());
		assertEquals(25, events.getFirst().payload().get("remaining"));
		assertEquals(0L, events.getFirst().payload().get("sparesOfKind"));
		assertEquals(List.of(), tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 24, 250))));
	}

	@Test void aHealthySpareSilencesLowButAWornOneDoesNot() {
		var tracker = new ToolWear.Tracker();
		tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 26, 250), pickaxe(1, "minecraft:stone_pickaxe", 100, 131)));
		assertEquals(List.of(), tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 25, 250), pickaxe(1, "minecraft:stone_pickaxe", 100, 131))));
		tracker.reset();
		tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 26, 250), pickaxe(1, "minecraft:stone_pickaxe", 3, 131)));
		assertEquals(1, tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 25, 250), pickaxe(1, "minecraft:stone_pickaxe", 3, 131))).size());
	}

	@Test void pickingUpOrMovingALowToolIsNotACrossing() {
		var tracker = new ToolWear.Tracker();
		tracker.update(List.of(pickaxe(3, "minecraft:iron_pickaxe", 20, 250)));
		assertEquals(List.of(), tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 20, 250))));
		assertEquals(List.of(), tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 20, 250), pickaxe(1, "minecraft:stone_pickaxe", 5, 131))));
	}

	@Test void aNearlyBrokenToolThatVanishesBroke() {
		var tracker = new ToolWear.Tracker();
		tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 1, 250), pickaxe(1, "minecraft:stone_pickaxe", 100, 131)));
		var events = tracker.update(List.of(pickaxe(1, "minecraft:stone_pickaxe", 100, 131)));
		assertEquals(1, events.size());
		var event = events.getFirst();
		assertEquals("tool.broken", event.type());
		assertEquals("minecraft:iron_pickaxe", event.payload().get("itemId"));
		assertEquals(1L, event.payload().get("sparesOfKind"));
		assertFalse(event.payload().containsKey("remaining"));
	}

	@Test void movingOrStoringAHealthyToolIsNotABreak() {
		var tracker = new ToolWear.Tracker();
		tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 1, 250)));
		assertEquals(List.of(), tracker.update(List.of(pickaxe(5, "minecraft:iron_pickaxe", 1, 250))), "moved within the inventory");
		tracker.update(List.of(pickaxe(0, "minecraft:iron_pickaxe", 100, 250)));
		assertEquals(List.of(), tracker.update(List.of()), "a healthy tool leaving is a drop or a transfer");
	}

	@Test void armorAndOtherItemsRaiseNoToolEvents() {
		var tracker = new ToolWear.Tracker();
		tracker.update(List.of(new ToolStack(38, "minecraft:iron_chestplate", ToolStack.OTHER, 1, 240)));
		assertEquals(List.of(), tracker.update(List.of()));
	}
}
