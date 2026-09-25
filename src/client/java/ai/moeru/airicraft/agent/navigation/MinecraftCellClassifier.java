package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.CellInfo;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

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
	public static MinecraftCellClassifier forPlayer(ClientPlayerEntity player) {
		List<ItemStack> tools = new ArrayList<>();
		tools.add(ItemStack.EMPTY);
		if (player != null) {
			var inventory = player.getInventory();
			for (int slot = 0; slot < 36; slot++) {
				ItemStack stack = inventory.getStack(slot);
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
		CellInfo.Builder builder = CellInfo.builder(Registries.BLOCK.getId(block).toString());
		for (net.minecraft.util.math.Box box : state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).getBoundingBoxes()) {
			builder.box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
		}
		FluidState fluid = state.getFluidState();
		if (fluid.isIn(FluidTags.WATER)) builder.fluid(CellInfo.Fluid.WATER, fluid.isStill());
		else if (fluid.isIn(FluidTags.LAVA)) builder.fluid(CellInfo.Fluid.LAVA, fluid.isStill());
		if (state.isIn(BlockTags.CLIMBABLE)) builder.climbable();
		CellInfo.Openable openable = openable(state);
		if (openable != CellInfo.Openable.NONE) {
			builder.openable(openable, withToggle ? build(state.cycle(Properties.OPEN), false) : null);
		}
		if (state.isIn(BlockTags.FIRE) || BODY_HAZARDS.contains(block)) builder.hazards(CellInfo.HAZARD_BODY);
		if (block == Blocks.MAGMA_BLOCK || state.isIn(BlockTags.CAMPFIRES)) builder.hazards(CellInfo.HAZARD_FLOOR);
		float velocity = block.getVelocityMultiplier();
		if (velocity > 0 && velocity < 1) builder.speedFactor(velocity);
		// Storage, beds and doors are never dug through: doors are opened instead.
		if (!state.hasBlockEntity() && openable == CellInfo.Openable.NONE) builder.breakTicks(breakTicks(state));
		if (state.isReplaceable()) builder.replaceable();
		if (block instanceof FallingBlock) builder.falling();
		return builder.build();
	}

	private static CellInfo.Openable openable(BlockState state) {
		Block block = state.getBlock();
		if (block instanceof DoorBlock door && door.getBlockSetType().canOpenByHand()) return CellInfo.Openable.DOOR;
		if (block instanceof FenceGateBlock) return CellInfo.Openable.FENCE_GATE;
		return CellInfo.Openable.NONE;
	}

	/** Vanilla break time with the fastest carried tool, ignoring enchantments and effects. */
	private int breakTicks(BlockState state) {
		float hardness = state.getHardness(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
		if (hardness < 0) return CellInfo.UNBREAKABLE;
		if (hardness == 0) return 0;
		float best = 0;
		for (ItemStack tool : tools) {
			float speed = tool.isEmpty() ? 1.0F : tool.getMiningSpeedMultiplier(state);
			boolean harvests = !state.isToolRequired() || !tool.isEmpty() && tool.isSuitableFor(state);
			best = Math.max(best, speed / hardness / (harvests ? 30.0F : 100.0F));
		}
		return best <= 0 ? CellInfo.UNBREAKABLE : (int) Math.ceil(1.0F / best);
	}
}
