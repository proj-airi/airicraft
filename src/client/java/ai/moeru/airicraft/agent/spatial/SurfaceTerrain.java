package ai.moeru.airicraft.agent.spatial;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.HugeMushroomBlock;
import net.minecraft.tags.BlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.Level;

/** Shared surface definition for observations and acquisition constraints. */
public final class SurfaceTerrain {
	private SurfaceTerrain() {}

	public static boolean isSurfacePosition(int y, int groundY, boolean standing, boolean waterSurface) {
		// Swimming feet and floating drops occupy the top water block, not the air above it.
		return y >= groundY + (standing && !waterSurface ? 1 : 0);
	}

	public static int groundY(Level level, BlockPos column) {
		int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ()) - 1;
		while (y > level.getMinY()) {
			BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
			BlockState state = level.getBlockState(pos);
			if (!state.getFluidState().isEmpty()) break;
			if (!state.is(BlockTags.LOGS) && !state.is(BlockTags.LEAVES) && !(state.getBlock() instanceof HugeMushroomBlock)
				&& !state.getCollisionShape(level, pos).isEmpty()) break;
			y--;
		}
		return y;
	}
}
