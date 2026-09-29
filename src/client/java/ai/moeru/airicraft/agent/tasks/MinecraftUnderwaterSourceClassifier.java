package ai.moeru.airicraft.agent.tasks;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Optional;

final class MinecraftUnderwaterSourceClassifier {
	private MinecraftUnderwaterSourceClassifier() {
	}

	static Optional<UnderwaterHarvestPolicy.SourceEnvironment> classify(
		Minecraft minecraft,
		BlockPos sourcePos,
		BlockState sourceState
	) {
		if (minecraft == null || minecraft.level == null || sourcePos == null || sourceState == null) {
			return Optional.empty();
		}
		boolean sourceContainsFluid = sourceState.getFluidState().is(FluidTags.WATER);
		boolean adjacentFluid = false;
		for (Direction direction : Direction.values()) {
			BlockPos adjacent = sourcePos.relative(direction);
			if (minecraft.level.hasChunkAt(adjacent)
				&& minecraft.level.getFluidState(adjacent).is(FluidTags.WATER)) {
				adjacentFluid = true;
				break;
			}
		}
		return UnderwaterHarvestPolicy.classify(
			sourceContainsFluid,
			hasDryStandingApproach(minecraft, sourcePos),
			adjacentFluid
		);
	}

	private static boolean hasDryStandingApproach(Minecraft minecraft, BlockPos sourcePos) {
		if (!hasDryExposedFace(minecraft, sourcePos)) {
			return false;
		}
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos adjacent = sourcePos.relative(direction);
			for (int yOffset = -1; yOffset <= 1; yOffset++) {
				if (isDryStandingPosition(minecraft, adjacent.offset(0, yOffset, 0))) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean hasDryExposedFace(Minecraft minecraft, BlockPos sourcePos) {
		for (Direction direction : Direction.values()) {
			BlockPos exposedPos = sourcePos.relative(direction);
			if (!minecraft.level.isInWorldBounds(exposedPos) || !minecraft.level.hasChunkAt(exposedPos)) {
				continue;
			}
			BlockState exposed = minecraft.level.getBlockState(exposedPos);
			if (exposed.getCollisionShape(minecraft.level, exposedPos).isEmpty()
				&& minecraft.level.getFluidState(exposedPos).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isDryStandingPosition(Minecraft minecraft, BlockPos feetPos) {
		BlockPos headPos = feetPos.above();
		BlockPos supportPos = feetPos.below();
		if (!minecraft.level.isInWorldBounds(feetPos)
			|| !minecraft.level.isInWorldBounds(headPos)
			|| !minecraft.level.isInWorldBounds(supportPos)
			|| !minecraft.level.hasChunkAt(feetPos)
			|| !minecraft.level.hasChunkAt(headPos)
			|| !minecraft.level.hasChunkAt(supportPos)) {
			return false;
		}
		BlockState feet = minecraft.level.getBlockState(feetPos);
		BlockState head = minecraft.level.getBlockState(headPos);
		BlockState support = minecraft.level.getBlockState(supportPos);
		boolean collisionFree = feet.getCollisionShape(minecraft.level, feetPos).isEmpty()
			&& head.getCollisionShape(minecraft.level, headPos).isEmpty();
		boolean dry = minecraft.level.getFluidState(feetPos).isEmpty()
			&& minecraft.level.getFluidState(headPos).isEmpty();
		if (collisionFree && dry
			&& support.isFaceSturdy(minecraft.level, supportPos, Direction.UP)) {
			return true;
		}
		return false;
	}
}
