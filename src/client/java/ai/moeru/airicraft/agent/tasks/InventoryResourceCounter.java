package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

public final class InventoryResourceCounter {
	public int count(Iterable<ItemStack> stacks, TaskResourceKind resourceKind) {
		int total = 0;
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			if (accepts(resourceKind, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())) {
				total += stack.getCount();
			}
		}
		return total;
	}

	static boolean accepts(TaskResourceKind resourceKind, String itemId) {
		return ResourceGatheringCatalog.accepts(resourceKind, itemId);
	}
}
