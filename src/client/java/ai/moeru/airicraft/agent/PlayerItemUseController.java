package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.tasks.OwnedKeyPress;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;

import java.util.Optional;

final class PlayerItemUseController {
	private static final long EAT_TIMEOUT_TICKS = 80L;

	private final OwnedKeyPress useKey = new OwnedKeyPress();
	private Eating eating;

	String equip(MinecraftClient client, String itemId) {
		ClientPlayerEntity player = requirePlayer(client);
		if ("minecraft:shield".equals(itemId)) {
			ItemStack offhand = player.getOffHandStack();
			int bestRemaining = offhand.isOf(net.minecraft.item.Items.SHIELD) ? offhand.getMaxDamage() - offhand.getDamage() : -1;
			int bestSlot = -1;
			for (var slot : player.currentScreenHandler.slots) {
				if (slot.inventory == player.getInventory() && slot.getIndex() < 36 && slot.getStack().isOf(net.minecraft.item.Items.SHIELD)) {
					int remaining = slot.getStack().getMaxDamage() - slot.getStack().getDamage();
					if (remaining > bestRemaining) {
						bestRemaining = remaining;
						bestSlot = slot.id;
					}
				}
			}
			if (bestSlot >= 0) {
				if (client.interactionManager == null) throw new IllegalStateException("interaction_manager_unavailable");
				client.interactionManager.clickSlot(player.currentScreenHandler.syncId, bestSlot, 40, SlotActionType.SWAP, player);
				return "Tool result for equip_item: accepted itemId=" + itemId + " equipmentSlot=offhand";
			}
			if (offhand.isOf(net.minecraft.item.Items.SHIELD))
				return "Tool result for equip_item: already_equipped itemId=" + itemId + " equipmentSlot=offhand";
			throw new IllegalArgumentException("item_not_found itemId=" + itemId);
		}
		ItemStack stack = selectItem(client, player, itemId);
		EquippableComponent equippable = stack.get(DataComponentTypes.EQUIPPABLE);
		if (equippable != null) {
			if (client.interactionManager == null) {
				throw new IllegalStateException("interaction_manager_unavailable");
			}
			client.interactionManager.interactItem(player, Hand.MAIN_HAND);
			return "Tool result for equip_item: accepted itemId=" + itemId + " equipmentSlot=" + equippable.slot().getName();
		}
		return "Tool result for equip_item: accepted itemId=" + itemId + " equipmentSlot=mainhand";
	}

	String eat(MinecraftClient client, String itemId, long tick) {
		if (eating != null) {
			throw new IllegalStateException("food_use_in_progress itemId=" + eating.itemId());
		}
		ClientPlayerEntity player = requirePlayer(client);
		ItemStack stack = selectItem(client, player, itemId);
		FoodComponent food = stack.get(DataComponentTypes.FOOD);
		if (food == null || stack.get(DataComponentTypes.CONSUMABLE) == null) {
			throw new IllegalArgumentException("item_not_food itemId=" + itemId);
		}
		int hunger = player.getHungerManager().getFoodLevel();
		if (!canStartEating(hunger, food.canAlwaysEat())) {
			throw new IllegalStateException("hunger_full itemId=" + itemId);
		}
		if (client.interactionManager == null) {
			throw new IllegalStateException("interaction_manager_unavailable");
		}
		eating = new Eating(itemId, hunger, inventoryCount(player, itemId), tick + EAT_TIMEOUT_TICKS);
		useKey.press(client.options.useKey);
		client.interactionManager.interactItem(player, Hand.MAIN_HAND);
		return "Tool result for eat_food: accepted itemId=" + itemId + " hunger=" + hunger;
	}

	Optional<Result> tick(MinecraftClient client, long tick) {
		if (eating == null) {
			return Optional.empty();
		}
		ClientPlayerEntity player = client == null ? null : client.player;
		if (player == null || player.isDead()) {
			return Optional.of(finish(client, false, "player_unavailable"));
		}
		int hunger = player.getHungerManager().getFoodLevel();
		int count = inventoryCount(player, eating.itemId());
		if (consumptionCompleted(eating.initialHunger(), hunger, eating.initialCount(), count)) {
			return Optional.of(finish(client, true, "consumed"));
		}
		if (tick >= eating.deadlineTick()) {
			return Optional.of(finish(client, false, "consume_timeout"));
		}
		useKey.press(client.options.useKey);
		return Optional.empty();
	}

	boolean eating() {
		return eating != null;
	}

	void reset(MinecraftClient client) {
		if (eating != null && client != null && client.player != null && client.interactionManager != null
			&& client.player.isUsingItem() && client.player.getActiveHand() == Hand.MAIN_HAND
			&& eating.itemId().equals(itemId(client.player.getActiveItem()))) {
			client.interactionManager.stopUsingItem(client.player);
		}
		useKey.release(client == null ? null : client.options.useKey);
		eating = null;
	}

	static boolean canStartEating(int hunger, boolean alwaysEdible) {
		return hunger < 20 || alwaysEdible;
	}

	static boolean consumptionCompleted(int initialHunger, int currentHunger, int initialCount, int currentCount) {
		return currentHunger > initialHunger || currentCount < initialCount;
	}

	private Result finish(MinecraftClient client, boolean completed, String reason) {
		String itemId = eating.itemId();
		reset(client);
		return new Result(itemId, completed, reason);
	}

	private static ClientPlayerEntity requirePlayer(MinecraftClient client) {
		if (client == null || client.player == null || client.player.isDead()) {
			throw new IllegalStateException("player_unavailable");
		}
		return client.player;
	}

	private static ItemStack selectItem(MinecraftClient client, ClientPlayerEntity player, String itemId) {
		if (itemId == null || itemId.isBlank()) {
			throw new IllegalArgumentException("itemId is required");
		}
		ScreenHandler handler = player.currentScreenHandler;
		int sourceSlot = findInventorySlot(handler, itemId);
		if (sourceSlot < 0) {
			throw new IllegalArgumentException("item_not_found itemId=" + itemId);
		}
		int hotbarSlot;
		if (sourceSlot >= PlayerScreenHandler.HOTBAR_START && sourceSlot < PlayerScreenHandler.HOTBAR_END) {
			hotbarSlot = sourceSlot - PlayerScreenHandler.HOTBAR_START;
		}
		else {
			if (client.interactionManager == null) {
				throw new IllegalStateException("interaction_manager_unavailable");
			}
			hotbarSlot = player.getInventory().getSelectedSlot();
			client.interactionManager.clickSlot(handler.syncId, sourceSlot, hotbarSlot, SlotActionType.SWAP, player);
		}
		player.getInventory().setSelectedSlot(hotbarSlot);
		if (client.getNetworkHandler() != null) {
			client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(hotbarSlot));
		}
		ItemStack selected = player.getInventory().getSelectedStack();
		if (selected.isEmpty() || !itemId.equals(itemId(selected))) {
			throw new IllegalStateException("item_equip_failed itemId=" + itemId);
		}
		return selected;
	}

	private static int findInventorySlot(ScreenHandler handler, String itemId) {
		if (handler == null) {
			return -1;
		}
		for (int slot = PlayerScreenHandler.INVENTORY_START; slot < PlayerScreenHandler.HOTBAR_END; slot++) {
			ItemStack stack = handler.getSlot(slot).getStack();
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				return slot;
			}
		}
		return -1;
	}

	private static int inventoryCount(ClientPlayerEntity player, String itemId) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static String itemId(ItemStack stack) {
		return Registries.ITEM.getId(stack.getItem()).toString();
	}

	record Result(String itemId, boolean completed, String reason) {
	}

	private record Eating(String itemId, int initialHunger, int initialCount, long deadlineTick) {
	}
}
