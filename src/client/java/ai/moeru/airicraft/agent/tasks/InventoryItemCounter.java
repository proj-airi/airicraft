package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.HashMap;
import java.util.Map;

public final class InventoryItemCounter {
	public Map<String, Integer> count(Inventory inventory) {
		Map<String, Integer> counts = new HashMap<>();
		if (inventory == null) {
			return Map.of();
		}
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			counts.merge(itemId, stack.getCount(), Integer::sum);
		}
		return Map.copyOf(counts);
	}
}
