package ai.moeru.airicraft.agent;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordinary screen clicks only; no access to unopened block or entity inventories. */
final class ContainerInventoryController {

	static String close(MinecraftClient client, Integer expectedSyncId) {
		var handler = requireContainer(client);
		if (expectedSyncId != null && handler.syncId != expectedSyncId) throw new IllegalStateException("container_changed inspect_container_again");
		if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("cursor_not_empty");
		client.player.closeHandledScreen();
		return "Tool result for close_container: closed syncId=" + handler.syncId;
	}

	static String inspect(MinecraftClient client) {
		var handler = requireContainer(client);
		var slots = slots(client, handler);
		return "Tool result for inspect_container: syncId=" + handler.syncId
			+ " containerSlots=" + handler.getRows() * 9
			+ " cursorEmpty=" + handler.getCursorStack().isEmpty()
			+ " slots=" + slots.stream().filter(slot -> slot.count() > 0).map(slot ->
				"{slot=" + slot.id() + ", side=" + (slot.container() ? "container" : "inventory")
					+ ", itemId=" + slot.itemId() + ", count=" + slot.count() + "}").toList();
	}

	static String transfer(MinecraftClient client, int syncId, String direction, String itemId, int quantity) {
		var handler = requireContainer(client);
		if (handler.syncId != syncId) throw new IllegalStateException("container_changed inspect_container_again");
		if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("cursor_not_empty");
		if (client.interactionManager == null) throw new IllegalStateException("interaction_manager_unavailable");
		List<Move> moves = plan(slots(client, handler), direction, itemId, quantity);
		for (Move move : moves) {
			int sourceCount = handler.getSlot(move.source()).getStack().getCount();
			client.interactionManager.clickSlot(syncId, move.source(), 0, SlotActionType.PICKUP, client.player);
			if (move.count() == sourceCount) {
				client.interactionManager.clickSlot(syncId, move.target(), 0, SlotActionType.PICKUP, client.player);
			} else {
				for (int i = 0; i < move.count(); i++)
					client.interactionManager.clickSlot(syncId, move.target(), 1, SlotActionType.PICKUP, client.player);
			}
			if (!handler.getCursorStack().isEmpty())
				client.interactionManager.clickSlot(syncId, move.source(), 0, SlotActionType.PICKUP, client.player);
		}
		if (!handler.getCursorStack().isEmpty()) throw new IllegalStateException("transfer_cursor_not_empty");
		return "Tool result for transfer_container: submitted syncId=" + syncId + " direction=" + direction
			+ " itemId=" + itemId + " quantity=" + quantity
			+ ". Inspect container again to verify settled source/destination counts before reporting completion.";
	}

	private static GenericContainerScreenHandler requireContainer(MinecraftClient client) {
		if (client == null || client.world == null || client.player == null) throw new IllegalStateException("world_not_loaded");
		if (!(client.player.currentScreenHandler instanceof GenericContainerScreenHandler handler))
			throw new IllegalStateException("container_not_open use_block_for_chests_or_use_entity_for_chest_minecarts_first");
		return handler;
	}

	private static List<Slot> slots(MinecraftClient client, GenericContainerScreenHandler handler) {
		List<Slot> result = new ArrayList<>();
		for (var slot : handler.slots) {
			boolean container = slot.id < handler.getRows() * 9;
			if (!container && (slot.inventory != client.player.getInventory() || slot.getIndex() >= 36)) continue;
			ItemStack stack = slot.getStack();
			result.add(new Slot(slot.id, container, Registries.ITEM.getId(stack.getItem()).toString(),
				stack.getComponents(), stack.getCount(), stack.isEmpty() ? slot.getMaxItemCount() : slot.getMaxItemCount(stack)));
		}
		return result;
	}

	/** Preflight the whole request, preserving component variants and respecting partial stacks. */
	static List<Move> plan(List<Slot> slots, String direction, String itemId, int quantity) {
		if (!List.of("deposit", "withdraw").contains(direction) || quantity < 1 || quantity > 2304)
			throw new IllegalArgumentException("invalid_transfer_request");
		boolean sourceContainer = direction.equals("withdraw");
		List<Slot> sources = slots.stream().filter(slot -> slot.container() == sourceContainer && slot.itemId().equals(itemId)).toList();
		if (sources.stream().mapToInt(Slot::count).sum() < quantity) throw new IllegalStateException("insufficient_source_items");
		List<Slot> targets = new ArrayList<>(slots.stream().filter(slot -> slot.container() != sourceContainer).toList());
		List<Move> moves = new ArrayList<>();
		int remaining = quantity;
		for (Slot source : sources) {
			int available = Math.min(source.count(), remaining);
			// Fill compatible partial stacks before consuming empty slots.
			for (boolean empty : List.of(false, true)) {
				for (int index = 0; index < targets.size() && available > 0; index++) {
					Slot target = targets.get(index);
					if ((target.count() == 0) != empty) continue;
					if (!empty && (!target.itemId().equals(source.itemId()) || !Objects.equals(target.components(), source.components()))) continue;
					int maxCount = Math.min(source.maxCount(), target.maxCount());
					int moved = Math.min(available, maxCount - target.count());
					if (moved <= 0) continue;
					moves.add(new Move(source.id(), target.id(), moved));
					targets.set(index, new Slot(target.id(), target.container(), source.itemId(), source.components(), target.count() + moved, maxCount));
					available -= moved;
					remaining -= moved;
				}
			}
			if (remaining == 0) return List.copyOf(moves);
		}
		throw new IllegalStateException("insufficient_destination_space");
	}

	record Slot(int id, boolean container, String itemId, Object components, int count, int maxCount) {}
	record Move(int source, int target, int count) {}
}
