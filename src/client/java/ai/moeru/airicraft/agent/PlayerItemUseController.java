package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.tasks.OwnedKeyPress;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.InteractionHand;

import java.util.Optional;

final class PlayerItemUseController {
	private static final long EAT_TIMEOUT_TICKS = 80L;

	private final OwnedKeyPress useKey = new OwnedKeyPress();
	private Eating eating;

	String equip(Minecraft minecraft, String itemId) {
		LocalPlayer player = requirePlayer(minecraft);
		if ("minecraft:shield".equals(itemId)) {
			ItemStack offhand = player.getOffhandItem();
			int bestRemaining = offhand.is(net.minecraft.world.item.Items.SHIELD) ? offhand.getMaxDamage() - offhand.getDamageValue() : -1;
			int bestSlot = -1;
			for (var slot : player.containerMenu.slots) {
				if (slot.container == player.getInventory() && slot.getContainerSlot() < 36 && slot.getItem().is(net.minecraft.world.item.Items.SHIELD)) {
					int remaining = slot.getItem().getMaxDamage() - slot.getItem().getDamageValue();
					if (remaining > bestRemaining) {
						bestRemaining = remaining;
						bestSlot = slot.index;
					}
				}
			}
			if (bestSlot >= 0) {
				if (minecraft.gameMode == null) throw new IllegalStateException("interaction_manager_unavailable");
				minecraft.gameMode.handleInventoryMouseClick(player.containerMenu.containerId, bestSlot, 40, ClickType.SWAP, player);
				return "Tool result for equip_item: accepted itemId=" + itemId + " equipmentSlot=offhand";
			}
			if (offhand.is(net.minecraft.world.item.Items.SHIELD))
				return "Tool result for equip_item: already_equipped itemId=" + itemId + " equipmentSlot=offhand";
			throw new IllegalArgumentException("item_not_found itemId=" + itemId);
		}
		ItemStack stack = selectItem(minecraft, player, itemId);
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable != null) {
			if (minecraft.gameMode == null) {
				throw new IllegalStateException("interaction_manager_unavailable");
			}
			minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
			return "Tool result for equip_item: accepted itemId=" + itemId + " equipmentSlot=" + equippable.slot().getName();
		}
		return "Tool result for equip_item: accepted itemId=" + itemId + " equipmentSlot=mainhand";
	}

	String eat(Minecraft minecraft, String itemId, long tick) {
		if (eating != null) {
			throw new IllegalStateException("food_use_in_progress itemId=" + eating.itemId());
		}
		LocalPlayer player = requirePlayer(minecraft);
		ItemStack stack = selectItem(minecraft, player, itemId);
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food == null || stack.get(DataComponents.CONSUMABLE) == null) {
			throw new IllegalArgumentException("item_not_food itemId=" + itemId);
		}
		int hunger = player.getFoodData().getFoodLevel();
		if (!canStartEating(hunger, food.canAlwaysEat())) {
			throw new IllegalStateException("hunger_full itemId=" + itemId);
		}
		if (minecraft.gameMode == null) {
			throw new IllegalStateException("interaction_manager_unavailable");
		}
		eating = new Eating(itemId, hunger, inventoryCount(player, itemId), tick + EAT_TIMEOUT_TICKS);
		useKey.press(minecraft.options.keyUse);
		minecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
		return "Tool result for eat_food: accepted itemId=" + itemId + " hunger=" + hunger;
	}

	Optional<Result> tick(Minecraft minecraft, long tick) {
		if (eating == null) {
			return Optional.empty();
		}
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null || player.isDeadOrDying()) {
			return Optional.of(finish(minecraft, false, "player_unavailable"));
		}
		int hunger = player.getFoodData().getFoodLevel();
		int count = inventoryCount(player, eating.itemId());
		if (consumptionCompleted(eating.initialHunger(), hunger, eating.initialCount(), count)) {
			return Optional.of(finish(minecraft, true, "consumed"));
		}
		if (tick >= eating.deadlineTick()) {
			return Optional.of(finish(minecraft, false, "consume_timeout"));
		}
		useKey.press(minecraft.options.keyUse);
		return Optional.empty();
	}

	boolean eating() {
		return eating != null;
	}

	void reset(Minecraft minecraft) {
		if (eating != null && minecraft != null && minecraft.player != null && minecraft.gameMode != null
			&& minecraft.player.isUsingItem() && minecraft.player.getUsedItemHand() == InteractionHand.MAIN_HAND
			&& eating.itemId().equals(itemId(minecraft.player.getUseItem()))) {
			minecraft.gameMode.releaseUsingItem(minecraft.player);
		}
		useKey.release(minecraft == null ? null : minecraft.options.keyUse);
		eating = null;
	}

	static boolean canStartEating(int hunger, boolean alwaysEdible) {
		return hunger < 20 || alwaysEdible;
	}

	static boolean consumptionCompleted(int initialHunger, int currentHunger, int initialCount, int currentCount) {
		return currentHunger > initialHunger || currentCount < initialCount;
	}

	private Result finish(Minecraft minecraft, boolean completed, String reason) {
		String itemId = eating.itemId();
		reset(minecraft);
		return new Result(itemId, completed, reason);
	}

	private static LocalPlayer requirePlayer(Minecraft minecraft) {
		if (minecraft == null || minecraft.player == null || minecraft.player.isDeadOrDying()) {
			throw new IllegalStateException("player_unavailable");
		}
		return minecraft.player;
	}

	private static ItemStack selectItem(Minecraft minecraft, LocalPlayer player, String itemId) {
		if (itemId == null || itemId.isBlank()) {
			throw new IllegalArgumentException("itemId is required");
		}
		AbstractContainerMenu menu = player.containerMenu;
		int sourceSlot = findInventorySlot(menu, itemId);
		if (sourceSlot < 0) {
			throw new IllegalArgumentException("item_not_found itemId=" + itemId);
		}
		int hotbarSlot;
		if (sourceSlot >= InventoryMenu.USE_ROW_SLOT_START && sourceSlot < InventoryMenu.USE_ROW_SLOT_END) {
			hotbarSlot = sourceSlot - InventoryMenu.USE_ROW_SLOT_START;
		}
		else {
			if (minecraft.gameMode == null) {
				throw new IllegalStateException("interaction_manager_unavailable");
			}
			hotbarSlot = player.getInventory().getSelectedSlot();
			minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlot, hotbarSlot, ClickType.SWAP, player);
		}
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (minecraft.getConnection() != null) {
			minecraft.getConnection().send(new ServerboundSetCarriedItemPacket(hotbarSlot));
		}
		ItemStack selected = player.getInventory().getSelectedItem();
		if (selected.isEmpty() || !itemId.equals(itemId(selected))) {
			throw new IllegalStateException("item_equip_failed itemId=" + itemId);
		}
		return selected;
	}

	private static int findInventorySlot(AbstractContainerMenu menu, String itemId) {
		if (menu == null) {
			return -1;
		}
		for (int slot = InventoryMenu.INV_SLOT_START; slot < InventoryMenu.USE_ROW_SLOT_END; slot++) {
			ItemStack stack = menu.getSlot(slot).getItem();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private static int inventoryCount(LocalPlayer player, String itemId) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	record Result(String itemId, boolean completed, String reason) {
	}

	private record Eating(String itemId, int initialHunger, int initialCount, long deadlineTick) {
	}
}
