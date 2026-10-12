package ai.moeru.airicraft.agent.durability;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Reads damageable inventory items into {@link ToolStack}s; tool kinds come from item tags, so modded tools count. */
public final class MinecraftToolStacks {
	private MinecraftToolStacks() {}

	/** Every damageable item in main inventory, armor and offhand slots. */
	public static List<ToolStack> read(Inventory inventory) {
		var stacks = new ArrayList<ToolStack>();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || !stack.isDamageableItem()) continue;
			stacks.add(new ToolStack(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), kind(stack),
				stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
		}
		return stacks;
	}

	static String kind(ItemStack stack) {
		if (stack.is(ItemTags.PICKAXES)) return "pickaxe";
		if (stack.is(ItemTags.AXES)) return "axe";
		if (stack.is(ItemTags.SHOVELS)) return "shovel";
		if (stack.is(ItemTags.HOES)) return "hoe";
		if (stack.is(ItemTags.SWORDS)) return "sword";
		if (stack.is(Items.SHEARS)) return "shears";
		return ToolStack.OTHER;
	}

	/**
	 * The {@link ToolWear#shortfall} warning for breaking {@code blocksNeeded} of {@code blockIds}, counting every tool that
	 * can harvest at least one of the blocks that drop nothing without the correct tool. Blocks a bare hand harvests never warn.
	 */
	public static Optional<String> miningShortfall(Inventory inventory, List<String> blockIds, int blocksNeeded) {
		var states = new ArrayList<BlockState>();
		for (String blockId : blockIds) {
			ResourceLocation id = ResourceLocation.tryParse(blockId);
			if (id == null) continue;
			var block = BuiltInRegistries.BLOCK.getOptional(id);
			if (block.isEmpty()) continue;
			BlockState state = block.get().defaultBlockState();
			if (state.requiresCorrectToolForDrops()) states.add(state);
		}
		if (states.isEmpty()) return Optional.empty();
		var eligible = new ArrayList<ToolStack>();
		boolean unbreakable = false;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || states.stream().noneMatch(stack::isCorrectToolForDrops)) continue;
			if (!stack.isDamageableItem()) unbreakable = true;
			else eligible.add(new ToolStack(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), kind(stack),
				stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
		}
		return ToolWear.shortfall(eligible, unbreakable, blocksNeeded);
	}
}
