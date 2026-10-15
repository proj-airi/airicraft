package ai.moeru.airicraft.agent.tasks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Where a body can stand out of a shooter's sight: the cell tests the blaze fight's cover and fighting spots use, and
 * the survival reflex uses to hide an unarmed body from archers. Client thread only.
 */
public final class CoverCells {
	private CoverCells() { }

	/** Solid floor (not magma), room for the body, no fluid or fire in it. */
	public static boolean standable(Level level, BlockPos cell) {
		BlockPos below = cell.below();
		BlockState floor = level.getBlockState(below);
		if (!floor.isFaceSturdy(level, below, Direction.UP) || floor.is(Blocks.MAGMA_BLOCK) || !floor.getFluidState().isEmpty()) return false;
		for (BlockPos body : List.of(cell, cell.above())) {
			BlockState state = level.getBlockState(body);
			if (!state.getCollisionShape(level, body).isEmpty() || !state.getFluidState().isEmpty() || state.is(BlockTags.FIRE)
				|| state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)) return false;
		}
		return true;
	}

	/** Lava, fire or magma within {@code radius} blocks to the side, two below or one above. */
	public static boolean hazardNear(Level level, BlockPos cell, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(cell.offset(-radius, -2, -radius), cell.offset(radius, 1, radius))) {
			BlockState state = level.getBlockState(pos);
			if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK)) return true;
		}
		return false;
	}

	/** A neighbouring open cell whose floor is more than {@code maxSafeFall} below: a knockback off the edge. */
	public static boolean edgeDrop(Level level, BlockPos cell, int maxSafeFall) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) continue;
				BlockPos side = cell.offset(dx, 0, dz);
				if (!level.getBlockState(side).getCollisionShape(level, side).isEmpty()
					|| !level.getBlockState(side.above()).getCollisionShape(level, side.above()).isEmpty()) continue;
				boolean floor = false;
				for (int down = 1; down <= maxSafeFall + 1 && !floor; down++) {
					BlockPos below = side.below(down);
					BlockState state = level.getBlockState(below);
					if (state.getFluidState().is(FluidTags.LAVA)) break;
					floor = state.isFaceSturdy(level, below, Direction.UP);
				}
				if (!floor) return true;
			}
		}
		return false;
	}

	/** Solid blocks beside the head (one each) and over it (two): how much a cell shields a body standing in it. */
	public static int cover(Level level, BlockPos cell) {
		int cover = 0;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			BlockPos head = cell.above().relative(side);
			if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) cover++;
		}
		BlockPos roof = cell.above(2);
		if (!level.getBlockState(roof).getCollisionShape(level, roof).isEmpty()) cover += 2;
		return cover;
	}

	/** Whether nothing solid stands between the points; a hit on {@code allowed} (a spawner, say) still counts as seen. */
	public static boolean clear(Level level, Entity context, Vec3 from, Vec3 to, BlockPos allowed) {
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, context));
		return hit.getType() == HitResult.Type.MISS || allowed != null && hit.getBlockPos().equals(allowed);
	}
}
