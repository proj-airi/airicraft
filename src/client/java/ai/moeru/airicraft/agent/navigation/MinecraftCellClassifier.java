package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.CellInfo;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Classifies block states for search from their collision shapes, not block lists. One instance
 * belongs to one thread: a snapshot's worker or the client thread. Break times use the tools
 * carried when the classifier was made.
 */
public final class MinecraftCellClassifier {
	private static final Set<Block> BODY_HAZARDS = Set.of(Blocks.COBWEB, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
		Blocks.WITHER_ROSE, Blocks.NETHER_PORTAL, Blocks.END_PORTAL, Blocks.END_GATEWAY, Blocks.CACTUS, Blocks.LAVA_CAULDRON);
	private final Map<BlockState, CellInfo> cache = new IdentityHashMap<>();
	private final List<ItemStack> tools;

	private MinecraftCellClassifier(List<ItemStack> tools) {
		this.tools = tools;
	}

	/** Copies the player's inventory so break times can be computed off the client thread. */
	public static MinecraftCellClassifier forPlayer(LocalPlayer player) {
		List<ItemStack> tools = new ArrayList<>();
		tools.add(ItemStack.EMPTY);
		if (player != null) {
			var inventory = player.getInventory();
			for (int slot = 0; slot < 36; slot++) {
				ItemStack stack = inventory.getItem(slot);
				if (!stack.isEmpty()) tools.add(stack.copy());
			}
		}
		return new MinecraftCellClassifier(List.copyOf(tools));
	}

	public CellInfo classify(BlockState state) {
		CellInfo cell = cache.get(state);
		if (cell == null) {
			cell = build(state, true);
			cache.put(state, cell);
		}
		return cell;
	}

	private CellInfo build(BlockState state, boolean withToggle) {
		Block block = state.getBlock();
		CellInfo.Builder builder = CellInfo.builder(BuiltInRegistries.BLOCK.getKey(block).toString());
		for (net.minecraft.world.phys.AABB box : state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).toAabbs()) {
			builder.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
		}
		FluidState fluid = state.getFluidState();
		if (fluid.is(FluidTags.WATER)) builder.fluid(CellInfo.Fluid.WATER, fluid.isSource());
		else if (fluid.is(FluidTags.LAVA)) builder.fluid(CellInfo.Fluid.LAVA, fluid.isSource());
		if (state.is(BlockTags.CLIMBABLE)) builder.climbable();
		CellInfo.Openable openable = openable(state);
		if (openable != CellInfo.Openable.NONE) {
			builder.openable(openable, withToggle ? build(state.cycle(BlockStateProperties.OPEN), false) : null);
		}
		if (state.is(BlockTags.FIRE) || BODY_HAZARDS.contains(block)) builder.hazards(CellInfo.HAZARD_BODY);
		if (block == Blocks.MAGMA_BLOCK || state.is(BlockTags.CAMPFIRES)) builder.hazards(CellInfo.HAZARD_FLOOR);
		float velocity = block.getSpeedFactor();
		if (velocity > 0 && velocity < 1) builder.speedFactor(velocity);
		// Storage, beds and doors are never dug through: doors are opened instead.
		if (!state.hasBlockEntity() && openable == CellInfo.Openable.NONE) builder.breakTicks(breakTicks(state));
		if (state.canBeReplaced()) builder.replaceable();
		if (block instanceof FallingBlock) builder.falling();
		return builder.build();
	}

	private static CellInfo.Openable openable(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof DoorBlock door && door.type().canOpenByHand()) return CellInfo.Openable.DOOR;
		if (block instanceof FenceGateBlock) return CellInfo.Openable.FENCE_GATE;
		// Iron trapdoors need redstone; every other trapdoor opens by hand.
		if (block instanceof TrapDoorBlock && block != Blocks.IRON_TRAPDOOR) return CellInfo.Openable.TRAPDOOR;
		return CellInfo.Openable.NONE;
	}

	/** Vanilla break time with the fastest carried tool, ignoring enchantments and effects. */
	private int breakTicks(BlockState state) {
		float hardness = state.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
		if (hardness < 0) return CellInfo.UNBREAKABLE;
		if (hardness == 0) return 0;
		float best = 0;
		for (ItemStack tool : tools) {
			float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
			boolean harvests = !state.requiresCorrectToolForDrops() || !tool.isEmpty() && tool.isCorrectToolForDrops(state);
			best = Math.max(best, speed / hardness / (harvests ? 30.0F : 100.0F));
		}
		return best <= 0 ? CellInfo.UNBREAKABLE : (int) Math.ceil(1.0F / best);
	}
}
