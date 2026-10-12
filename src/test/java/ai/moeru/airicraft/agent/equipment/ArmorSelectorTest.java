package ai.moeru.airicraft.agent.equipment;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArmorSelectorTest {
	private static ArmorSelector.Piece carried(String slot, int index, String id, double armor, double toughness, int remaining) {
		return new ArmorSelector.Piece(slot, index, id, armor, toughness, remaining, false);
	}

	private static ArmorSelector.Piece worn(String slot, String id, double armor) {
		return new ArmorSelector.Piece(slot, -1, id, armor, 0, 100, false);
	}

	@Test
	void wearsEveryCarriedPieceIntoEmptySlots() {
		var swaps = ArmorSelector.choose(List.of(
			carried("head", 3, "minecraft:iron_helmet", 2, 0, 165),
			carried("chest", 12, "minecraft:iron_chestplate", 6, 0, 240),
			carried("legs", 13, "minecraft:iron_leggings", 5, 0, 225),
			carried("feet", 14, "minecraft:iron_boots", 2, 0, 195)));
		assertEquals(List.of("head", "chest", "legs", "feet"), swaps.stream().map(ArmorSelector.Swap::slot).toList());
		assertEquals(null, swaps.getFirst().replacedItemId());
	}

	@Test
	void upgradesOnlyWhenTheCarriedPieceProtectsMore() {
		var swaps = ArmorSelector.choose(List.of(
			worn("chest", "minecraft:leather_chestplate", 3),
			carried("chest", 9, "minecraft:diamond_chestplate", 8, 2, 528),
			carried("chest", 10, "minecraft:iron_chestplate", 6, 0, 240),
			worn("head", "minecraft:diamond_helmet", 3),
			carried("head", 11, "minecraft:iron_helmet", 2, 0, 165)));
		assertEquals(1, swaps.size());
		assertEquals(new ArmorSelector.Swap("chest", 9, "minecraft:diamond_chestplate", "minecraft:leather_chestplate"), swaps.getFirst());
	}

	@Test
	void skipsWearablesWithoutArmorCursedPiecesAndNearlyBrokenOnes() {
		assertTrue(ArmorSelector.choose(List.of(
			carried("head", 4, "minecraft:carved_pumpkin", 0, 0, Integer.MAX_VALUE),
			carried("chest", 5, "minecraft:elytra", 0, 0, 432),
			new ArmorSelector.Piece("legs", 6, "minecraft:iron_leggings", 5, 0, 225, true),
			carried("feet", 7, "minecraft:iron_boots", 2, 0, 2))).isEmpty());
	}

	@Test
	void neverReplacesAWornPieceThatCannotComeOff() {
		assertTrue(ArmorSelector.choose(List.of(
			new ArmorSelector.Piece("chest", -1, "minecraft:leather_chestplate", 3, 0, 80, true),
			carried("chest", 9, "minecraft:diamond_chestplate", 8, 2, 528))).isEmpty());
	}

	@Test
	void prefersTheLessWornOfTwoEqualPieces() {
		var swaps = ArmorSelector.choose(List.of(
			carried("feet", 20, "minecraft:iron_boots", 2, 0, 40),
			carried("feet", 21, "minecraft:iron_boots", 2, 0, 190)));
		assertEquals(21, swaps.getFirst().inventorySlot());
	}

	@Test
	void mapsInventoryAndArmorSlotsToTheInventoryMenu() {
		assertEquals(InventoryMenu.USE_ROW_SLOT_START, ArmorAutoEquip.menuSlot(0));
		assertEquals(InventoryMenu.USE_ROW_SLOT_START + 8, ArmorAutoEquip.menuSlot(8));
		assertEquals(9, ArmorAutoEquip.menuSlot(9));
		assertEquals(5, ArmorAutoEquip.armorMenuSlot(EquipmentSlot.HEAD));
		assertEquals(6, ArmorAutoEquip.armorMenuSlot(EquipmentSlot.CHEST));
		assertEquals(7, ArmorAutoEquip.armorMenuSlot(EquipmentSlot.LEGS));
		assertEquals(8, ArmorAutoEquip.armorMenuSlot(EquipmentSlot.FEET));
	}
}
