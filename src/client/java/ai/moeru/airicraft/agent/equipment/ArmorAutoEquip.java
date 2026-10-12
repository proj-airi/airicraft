package ai.moeru.airicraft.agent.equipment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Wears the best carried armor, as a player does without thinking about it. Works through the
 * player's own inventory menu, so it waits while any container screen is open. Client thread only.
 */
public final class ArmorAutoEquip {
	private static final long CHECK_INTERVAL_TICKS = 20L;
	private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

	private long nextCheckTick;

	public void reset() {
		nextCheckTick = 0L;
	}

	/** Puts on at most one piece per check; empty when nothing was worth changing or the menu is busy. */
	public Optional<ArmorSelector.Swap> tick(Minecraft minecraft, long tick) {
		if (tick < nextCheckTick) return Optional.empty();
		nextCheckTick = tick + CHECK_INTERVAL_TICKS;
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null || minecraft.gameMode == null || player.isDeadOrDying() || player.isSpectator()
			|| player.getAbilities().instabuild) return Optional.empty();
		InventoryMenu menu = player.inventoryMenu;
		if (player.containerMenu != menu || !menu.getCarried().isEmpty()) return Optional.empty();
		var swaps = ArmorSelector.choose(pieces(player));
		if (swaps.isEmpty()) return Optional.empty();
		ArmorSelector.Swap swap = swaps.getFirst();
		int source = menuSlot(swap.inventorySlot());
		int target = armorMenuSlot(slot(swap.slot()));
		// Pick up, swap onto the armor slot, and drop whatever was worn into the emptied slot.
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, player);
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, target, 0, ClickType.PICKUP, player);
		if (!menu.getCarried().isEmpty()) {
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, source, 0, ClickType.PICKUP, player);
		}
		if (!swap.itemId().equals(itemId(player.getItemBySlot(slot(swap.slot()))))) return Optional.empty();
		return Optional.of(swap);
	}

	static List<ArmorSelector.Piece> pieces(LocalPlayer player) {
		List<ArmorSelector.Piece> pieces = new ArrayList<>();
		for (EquipmentSlot slot : SLOTS) {
			ItemStack worn = player.getItemBySlot(slot);
			if (!worn.isEmpty()) pieces.add(piece(slot, -1, worn));
		}
		for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
			ItemStack stack = player.getInventory().getItem(index);
			var equippable = stack.isEmpty() ? null : stack.get(DataComponents.EQUIPPABLE);
			if (equippable == null || !SLOTS.contains(equippable.slot())) continue;
			pieces.add(piece(equippable.slot(), index, stack));
		}
		return pieces;
	}

	private static ArmorSelector.Piece piece(EquipmentSlot slot, int inventorySlot, ItemStack stack) {
		double[] totals = new double[2];
		stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY).forEach(slot, (attribute, modifier) -> {
			if (modifier.operation() != AttributeModifier.Operation.ADD_VALUE) return;
			if (attribute.value() == Attributes.ARMOR.value()) totals[0] += modifier.amount();
			else if (attribute.value() == Attributes.ARMOR_TOUGHNESS.value()) totals[1] += modifier.amount();
		});
		int remaining = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
		boolean locked = EnchantmentHelper.has(stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
		return new ArmorSelector.Piece(slot.getName(), inventorySlot, itemId(stack), totals[0], totals[1], remaining, locked);
	}

	private static EquipmentSlot slot(String name) {
		return EquipmentSlot.byName(name);
	}

	/** Backing inventory index to the player inventory menu's slot index. */
	static int menuSlot(int inventoryIndex) {
		return inventoryIndex < Inventory.getSelectionSize()
			? InventoryMenu.USE_ROW_SLOT_START + inventoryIndex
			: inventoryIndex;
	}

	/** The inventory menu lists armor head first, starting at ARMOR_SLOT_START. */
	static int armorMenuSlot(EquipmentSlot slot) {
		return InventoryMenu.ARMOR_SLOT_START + (3 - slot.getIndex());
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}
}
