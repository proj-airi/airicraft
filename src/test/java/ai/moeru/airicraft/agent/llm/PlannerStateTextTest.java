package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerStateTextTest {
	private static final Map<String, String> EMPTY_EQUIPMENT = Map.of("head", "minecraft:air", "chest", "minecraft:air",
		"legs", "minecraft:air", "feet", "minecraft:air", "offhand", "minecraft:air");

	private static List<PlannerStateText.HotbarSlot> hotbar(PlannerStateText.HotbarSlot... occupied) {
		var slots = new ArrayList<PlannerStateText.HotbarSlot>();
		for (int i = 0; i < 9; i++) slots.add(new PlannerStateText.HotbarSlot(i, "minecraft:air", 0));
		for (var slot : occupied) slots.set(slot.index(), slot);
		return slots;
	}

	@Test void describesSparseArrangementRatherThanSerializingEmptySlots() {
		assertEquals("Nothing in hotbar.", PlannerStateText.hotbar(hotbar(), 0));
		assertEquals("Hotbar: only dirt in first slot (selected).", PlannerStateText.hotbar(hotbar(new PlannerStateText.HotbarSlot(0, "minecraft:dirt", 1)), 0));
		assertEquals("Hotbar: only 32 dirt in first slot. Selected third slot is empty.",
			PlannerStateText.hotbar(hotbar(new PlannerStateText.HotbarSlot(0, "minecraft:dirt", 32)), 2));
		assertEquals("Hotbar: stone_pickaxe in first slot (selected); 8 torch in third slot. Other slots empty.",
			PlannerStateText.hotbar(hotbar(new PlannerStateText.HotbarSlot(0, "minecraft:stone_pickaxe", 1), new PlannerStateText.HotbarSlot(2, "minecraft:torch", 8)), 0));
	}

	@Test void incompleteObservationNeverImpliesEmptyUnobservedSlotsOrArmor() {
		assertEquals("Hotbar unknown.", PlannerStateText.hotbar(List.of(), -1));
		String partial = PlannerStateText.hotbar(List.of(new PlannerStateText.HotbarSlot(0, "minecraft:dirt", 1)), 2);
		assertFalse(partial.contains("only"));
		assertTrue(partial.contains("Unobserved slots unknown"));
		assertTrue(partial.contains("Selected slot 2 unobserved"));
		String equipment = PlannerStateText.equipment(null, Map.of());
		assertTrue(equipment.contains("Armor unknown"));
		assertTrue(equipment.contains("Main hand unknown"));
		assertTrue(equipment.contains("offhand unknown"));
		assertFalse(equipment.contains("empty"));
	}

	@Test void distinguishesArmorAndBothHandsWithoutDroppingModdedIdentity() {
		assertEquals("Wearing and holding nothing.", PlannerStateText.equipment("minecraft:air", EMPTY_EQUIPMENT));
		assertEquals("No armor; Main hand: iron_pickaxe; offhand empty.", PlannerStateText.equipment("minecraft:iron_pickaxe", EMPTY_EQUIPMENT));
		var gear = new java.util.HashMap<>(EMPTY_EQUIPMENT);
		gear.put("chest", "example:jetpack"); gear.put("offhand", "minecraft:shield");
		assertEquals("Wearing chest example:jetpack; Other armor slots empty; Main hand empty; offhand: shield.", PlannerStateText.equipment("minecraft:air", gear));
	}

	@Test void keepsCountsIdentityDamageAndAbnormalVitalsExact() {
		assertEquals("Carrying 2 example:dirt, 64 dirt, stone_pickaxe.", PlannerStateText.inventory(Map.of("minecraft:stone_pickaxe", 1, "minecraft:dirt", 64, "example:dirt", 2)));
		assertEquals("stone_pickaxe 1/131 (inventory slot 2)", PlannerStateText.durability(2, "minecraft:stone_pickaxe", 1, 131));
		assertEquals("Health 7.5/40; food 6/20; saturation 0; air 12/300.", PlannerStateText.vitals(Map.of("health", 7.5, "maxHealth", 40, "food", 6, "saturation", 0, "air", 12, "maxAir", 300)));
		assertEquals("Health 20/20; food full; air full.", PlannerStateText.vitals(Map.of("health", 20.0, "maxHealth", 20, "food", 20, "air", 300, "maxAir", 300)));
		assertEquals("Health unknown; food unknown.", PlannerStateText.vitals(Map.of()));
	}

	@Test void legacyEvidenceAdapterPreservesIdentityCountsAndPartialObservation() {
		var slots = PlannerStateText.hotbarEvidence(List.of("0=example:pickaxex4x1", "1=empty", "2=minecraft:torchx64"));
		assertEquals(new PlannerStateText.HotbarSlot(0, "example:pickaxex4", 1), slots.getFirst());
		assertEquals("Hotbar: example:pickaxex4 in first slot; 64 torch in third slot (selected). Unobserved slots unknown.", PlannerStateText.hotbar(slots, 2));
	}

	@Test void decisionProjectionChangesOnlyPresentationAndRetainsEventEvidence() {
		var events = new SemanticEventBuffer(8);
		events.append(4, "inventory.changed", Map.of("itemId", "minecraft:dirt", "count", 4));
		var inventory = Map.of("minecraft:dirt", 4);
		var vitals = Map.of("health", 20, "maxHealth", 20, "food", 20);
		var context = new PlannerDecisionContext("world", 4, 4, "controller", "idle", Map.of("inventory", inventory, "vitals", vitals), events.query(null));
		String message = PlannerInputText.observation(context.observation(0));
		assertTrue(message.contains("Carrying 4 dirt."));
		assertTrue(message.contains("Health 20/20; food full."));
		assertTrue(message.contains("\"itemId\":\"minecraft:dirt\""));
		assertSame(inventory, context.current().get("inventory"));
		assertSame(vitals, context.current().get("vitals"));
	}
}
