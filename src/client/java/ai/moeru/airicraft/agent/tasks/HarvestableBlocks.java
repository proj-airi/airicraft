package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.CropBlock;

/** Generic resource acquisition preserves growing crops; exact terrain edits remain explicit. */
public final class HarvestableBlocks {
	private HarvestableBlocks() {}

	public static boolean ready(BlockState state) {
		return !(state.getBlock() instanceof CropBlock crop) || crop.isMaxAge(state);
	}
}
