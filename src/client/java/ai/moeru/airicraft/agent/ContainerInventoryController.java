package ai.moeru.airicraft.agent;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Ordinary screen clicks only; no access to unopened block or entity inventories. */
final class ContainerInventoryController {

	static String close(Minecraft minecraft) {
		var menu = requireContainer(minecraft);
		if (!menu.getCarried().isEmpty()) throw new IllegalStateException("cursor_not_empty");
		minecraft.player.closeContainer();
		return "Tool result for close_container: closed syncId=" + menu.containerId;
	}

	static String inspect(Minecraft minecraft) {
		var menu = requireContainer(minecraft);
		var slots = slots(minecraft, menu);
		return "Tool result for inspect_container: syncId=" + menu.containerId
			+ " containerSlots=" + menu.getRowCount() * 9
			+ " cursorEmpty=" + menu.getCarried().isEmpty()
			+ " slots=" + slots.stream().filter(slot -> slot.count() > 0).map(slot ->
				"{slot=" + slot.id() + ", side=" + (slot.container() ? "container" : "inventory")
					+ ", itemId=" + slot.itemId() + ", count=" + slot.count() + "}").toList();
	}

	static String transfer(Minecraft minecraft, int syncId, String direction, List<TransferItem> items) {
		var menu = requireContainer(minecraft);
		if (menu.containerId != syncId) throw new IllegalStateException("container_changed inspect_container_again");
		if (!menu.getCarried().isEmpty()) throw new IllegalStateException("cursor_not_empty");
		if (minecraft.gameMode == null) throw new IllegalStateException("interaction_manager_unavailable");
		List<Move> moves = planBatch(slots(minecraft, menu), direction, items);
		for (Move move : moves) {
			int sourceCount = menu.getSlot(move.source()).getItem().getCount();
			minecraft.gameMode.handleInventoryMouseClick(syncId, move.source(), 0, ClickType.PICKUP, minecraft.player);
			if (move.count() == sourceCount) {
				minecraft.gameMode.handleInventoryMouseClick(syncId, move.target(), 0, ClickType.PICKUP, minecraft.player);
			} else {
				for (int i = 0; i < move.count(); i++)
					minecraft.gameMode.handleInventoryMouseClick(syncId, move.target(), 1, ClickType.PICKUP, minecraft.player);
			}
			if (!menu.getCarried().isEmpty())
				minecraft.gameMode.handleInventoryMouseClick(syncId, move.source(), 0, ClickType.PICKUP, minecraft.player);
		}
		if (!menu.getCarried().isEmpty()) throw new IllegalStateException("transfer_cursor_not_empty");
		return "Tool result for transfer_container: submitted syncId=" + syncId + " direction=" + direction
			+ " items=" + items
			+ ". Inspect container again to verify settled source/destination counts before reporting completion.";
	}

	private static ChestMenu requireContainer(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) throw new IllegalStateException("world_not_loaded");
		if (!(minecraft.player.containerMenu instanceof ChestMenu menu))
			throw new IllegalStateException("container_not_open use_block_for_chests_or_use_entity_for_chest_minecarts_first");
		return menu;
	}

	private static List<Slot> slots(Minecraft minecraft, ChestMenu menu) {
		List<Slot> result = new ArrayList<>();
		for (var slot : menu.slots) {
			boolean container = slot.index < menu.getRowCount() * 9;
			if (!container && (slot.container != minecraft.player.getInventory() || slot.getContainerSlot() >= 36)) continue;
			ItemStack stack = slot.getItem();
			result.add(new Slot(slot.index, container, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
				stack.getComponents(), stack.getCount(), stack.isEmpty() ? slot.getMaxStackSize() : slot.getMaxStackSize(stack)));
		}
		return result;
	}

	/** Reserve shared space for every item before sending any screen clicks. */
	static List<Move> planBatch(List<Slot> slots, String direction, List<TransferItem> items) {
		if (items.isEmpty() || items.size() > 36) throw new IllegalArgumentException("items must contain 1..36 entries");
		List<Slot> predicted = new ArrayList<>(slots);
		List<Move> moves = new ArrayList<>();
		for (TransferItem item : items) {
			List<Move> next = plan(predicted, direction, item.itemId(), item.quantity());
			for (Move move : next) {
				int sourceIndex = java.util.stream.IntStream.range(0, predicted.size())
					.filter(i -> predicted.get(i).id() == move.source()).findFirst().orElseThrow();
				int targetIndex = java.util.stream.IntStream.range(0, predicted.size())
					.filter(i -> predicted.get(i).id() == move.target()).findFirst().orElseThrow();
				Slot source = predicted.get(sourceIndex);
				Slot target = predicted.get(targetIndex);
				predicted.set(sourceIndex, new Slot(source.id(), source.container(), source.itemId(), source.components(),
					source.count() - move.count(), source.maxCount()));
				predicted.set(targetIndex, new Slot(target.id(), target.container(), source.itemId(), source.components(),
					target.count() + move.count(), Math.min(source.maxCount(), target.maxCount())));
			}
			moves.addAll(next);
		}
		return List.copyOf(moves);
	}

	record TransferItem(String itemId, int quantity) {}

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
