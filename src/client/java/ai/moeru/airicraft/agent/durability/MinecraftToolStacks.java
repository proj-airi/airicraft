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
	 * The {@link ToolWear#shortfall} warning for breaking {@code blocksNeeded} of {@code blockIds}. Each block that drops
	 * nothing without the correct tool is judged on the tools that can harvest it, and the one with the least tool life
	 * left decides, since the job may have to mine only that block. Blocks a bare hand harvests never warn, and a block no
	 * carried tool can harvest is left to the job's own failure.
	 */
	public static Optional<String> miningShortfall(Inventory inventory, List<String> blockIds, int blocksNeeded) {
		Optional<String> tightest = Optional.empty();
		int tightestUses = Integer.MAX_VALUE;
		for (String blockId : blockIds) {
			ResourceLocation id = ResourceLocation.tryParse(blockId);
			if (id == null) continue;
			var block = BuiltInRegistries.BLOCK.getOptional(id);
			if (block.isEmpty()) continue;
			BlockState state = block.get().defaultBlockState();
			if (!state.requiresCorrectToolForDrops()) continue;
			var eligible = new ArrayList<ToolStack>();
			boolean unbreakable = false;
			for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (stack.isEmpty() || !stack.isCorrectToolForDrops(state)) continue;
				if (!stack.isDamageableItem()) unbreakable = true;
				else eligible.add(new ToolStack(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), kind(stack),
					stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage()));
			}
			int uses = eligible.stream().mapToInt(ToolStack::remaining).sum();
			var warning = ToolWear.shortfall(eligible, unbreakable, blocksNeeded);
			if (warning.isPresent() && uses < tightestUses) {
				tightestUses = uses;
				tightest = warning.map(text -> blockId + ": " + text);
			}
		}
		return tightest;
	}
}
