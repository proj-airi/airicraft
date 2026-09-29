package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.Optional;

public final class SurfaceMemory {
	private static final int NEAREST_SURFACE_RADIUS = 16;
	private static final int NEAREST_SURFACE_UP = 48;
	private static final int NEAREST_SURFACE_DOWN = 96;
	private static final int MIN_SURFACE_ESCAPE_DIRECTIONS = 2;
	static final long NEAREST_SURFACE_FAST_REFRESH_TICKS = 10L;
	static final long NEAREST_SURFACE_REFRESH_TICKS = 40L;
	static final double NEAREST_SURFACE_MOVE_REFRESH_DISTANCE_SQUARED = 16.0;

	private SurfaceTarget lastGround;
	private SurfaceTarget lastSurface;
	private SurfaceTarget nearestSurface;
	private BlockPos lastNearestSurfaceScanOrigin;
	private long lastNearestSurfaceScanTick = Long.MIN_VALUE;

	public void clear() {
		lastGround = null;
		lastSurface = null;
		nearestSurface = null;
		lastNearestSurfaceScanOrigin = null;
		lastNearestSurfaceScanTick = Long.MIN_VALUE;
	}

	public void tick(Minecraft minecraft, long tick) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.level == null || player == null) {
			clear();
			return;
		}
		BlockPos playerPos = player.blockPosition();
		if (player.onGround() && isSafeStandingPosition(minecraft, playerPos)) {
			lastGround = new SurfaceTarget(toGoalPosition(playerPos), "last_ground", tick);
			if (isSurfaceStandingPosition(minecraft, playerPos)) {
				lastSurface = new SurfaceTarget(toGoalPosition(playerPos), "last_surface", tick);
			}
		}
		if (shouldRefreshNearestSurface(nearestSurface != null, lastNearestSurfaceScanOrigin, lastNearestSurfaceScanTick, playerPos, tick)) {
			lastNearestSurfaceScanOrigin = playerPos;
			lastNearestSurfaceScanTick = tick;
			SurfaceTarget nearby = findNearestSurface(minecraft, playerPos, tick).orElse(null);
			if (nearby != null) {
				nearestSurface = nearby;
			}
		}
	}

	public Optional<SurfaceTarget> bestTarget() {
		if (nearestSurface != null) {
			return Optional.of(nearestSurface);
		}
		if (lastSurface != null) {
			return Optional.of(lastSurface);
		}
		return Optional.ofNullable(lastGround);
	}

	public Optional<SurfaceTarget> lastGround() {
		return Optional.ofNullable(lastGround);
	}

	public Optional<SurfaceTarget> lastSurface() {
		return Optional.ofNullable(lastSurface);
	}

	public Optional<SurfaceTarget> nearestSurface() {
		return Optional.ofNullable(nearestSurface);
	}

	public static boolean isSkyVisible(Minecraft minecraft, BlockPos pos) {
		return minecraft != null
			&& minecraft.level != null
			&& pos != null
			&& minecraft.level.hasChunkAt(pos)
			&& minecraft.level.canSeeSky(pos.above());
	}

	private static Optional<SurfaceTarget> findNearestSurface(Minecraft minecraft, BlockPos origin, long tick) {
		if (minecraft == null || minecraft.level == null || origin == null) {
			return Optional.empty();
		}
		SurfaceTarget best = null;
		double bestDistance = Double.MAX_VALUE;
		int minY = Math.max(minecraft.level.getMinY() + 1, origin.getY() - NEAREST_SURFACE_DOWN);
		int maxY = Math.min(minecraft.level.getMaxY() - 2, origin.getY() + NEAREST_SURFACE_UP);
		for (int dx = -NEAREST_SURFACE_RADIUS; dx <= NEAREST_SURFACE_RADIUS; dx++) {
			for (int dz = -NEAREST_SURFACE_RADIUS; dz <= NEAREST_SURFACE_RADIUS; dz++) {
				BlockPos column = origin.offset(dx, 0, dz);
				if (!minecraft.level.hasChunkAt(column)) {
					continue;
				}
				for (int y = maxY; y >= minY; y--) {
					BlockPos candidate = new BlockPos(column.getX(), y, column.getZ());
					if (!isSurfaceStandingPosition(minecraft, candidate)) {
						continue;
					}
					double distance = candidate.distSqr(origin);
					if (distance < bestDistance) {
						bestDistance = distance;
						best = new SurfaceTarget(toGoalPosition(candidate), "nearest_surface", tick);
					}
					break;
				}
			}
		}
		return Optional.ofNullable(best);
	}

	public static boolean isSurfaceStandingPosition(Minecraft minecraft, BlockPos feetPos) {
		return isSkyVisible(minecraft, feetPos)
			&& isSafeStandingPosition(minecraft, feetPos)
			&& hasEnoughSurfaceEscapeDirections(surfaceEscapeDirections(minecraft, feetPos));
	}

	static boolean shouldRefreshNearestSurface(
		boolean hasNearestSurface,
		BlockPos lastScanOrigin,
		long lastScanTick,
		BlockPos currentOrigin,
		long tick
	) {
		if (!hasNearestSurface || lastScanOrigin == null || currentOrigin == null || lastScanTick == Long.MIN_VALUE) {
			return true;
		}
		long elapsed = tick - lastScanTick;
		if (elapsed < NEAREST_SURFACE_FAST_REFRESH_TICKS) {
			return false;
		}
		if (lastScanOrigin.distSqr(currentOrigin) >= NEAREST_SURFACE_MOVE_REFRESH_DISTANCE_SQUARED) {
			return true;
		}
		return elapsed >= NEAREST_SURFACE_REFRESH_TICKS;
	}

	private static boolean isSafeStandingPosition(Minecraft minecraft, BlockPos feetPos) {
		if (minecraft == null || minecraft.level == null || feetPos == null) {
			return false;
		}
		if (!minecraft.level.hasChunkAt(feetPos) || !minecraft.level.hasChunkAt(feetPos.below())) {
			return false;
		}
		BlockState feet = minecraft.level.getBlockState(feetPos);
		BlockState head = minecraft.level.getBlockState(feetPos.above());
		BlockState support = minecraft.level.getBlockState(feetPos.below());
		return (feet.isAir() || feet.canBeReplaced())
			&& (head.isAir() || head.canBeReplaced())
			&& support.isFaceSturdy(minecraft.level, feetPos.below(), Direction.UP);
	}

	private static int surfaceEscapeDirections(Minecraft minecraft, BlockPos feetPos) {
		int openDirections = 0;
		for (Direction direction : Direction.Plane.HORIZONTAL) {
			BlockPos adjacent = feetPos.relative(direction);
			if (isSkyVisible(minecraft, adjacent) && isSafeStandingPosition(minecraft, adjacent)) {
				openDirections++;
			}
		}
		return openDirections;
	}

	static boolean hasEnoughSurfaceEscapeDirections(int openDirections) {
		return openDirections >= MIN_SURFACE_ESCAPE_DIRECTIONS;
	}

	private static GoalPosition toGoalPosition(BlockPos pos) {
		return new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), false);
	}

	public record SurfaceTarget(GoalPosition position, String kind, long tick) {
	}
}
