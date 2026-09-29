package ai.moeru.airicraft.agent.spatial;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VisibleSurfaceSamplerTest {
	private static final Vec3 EYE = new Vec3(0.5, 0.5, 0.5);
	private static final BlockPos ORE = new BlockPos(4, 0, 0);
	private static final BlockPos WALL = new BlockPos(2, 0, 0);

	@Test void occluderHidesOreUntilTheNextSampleAfterRemoval() {
		var ore = new Surface(ORE, new AABB(ORE));
		var wall = new Surface(WALL, new AABB(2, -3, -3, 3, 4, 4));
		var hidden = sample(8, List.of(wall, ore));
		assertTrue(hidden.contains(WALL));
		assertFalse(hidden.contains(ORE));
		assertTrue(sample(8, List.of(ore)).contains(ORE));
	}

	@Test void boundsDistanceAndRayCount() {
		assertFalse(sample(2, List.of(new Surface(ORE, new AABB(ORE)))).contains(ORE));
		AtomicInteger calls = new AtomicInteger();
		assertTrue(VisibleSurfaceSampler.sample(EYE, 24, (start, end) -> {
			calls.incrementAndGet();
			assertEquals(24, start.distanceTo(end), 0.000001);
			return BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
		}).isEmpty());
		assertEquals(495, calls.get());
	}

	private static List<BlockPos> sample(double radius, List<Surface> surfaces) {
		return VisibleSurfaceSampler.sample(EYE, radius, (start, end) -> {
			BlockHitResult nearest = BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
			double distance = start.distanceToSqr(end);
			for (Surface surface : surfaces) {
				var hit = surface.box().clip(start, end);
				if (hit.isPresent() && start.distanceToSqr(hit.get()) < distance) {
					distance = start.distanceToSqr(hit.get());
					nearest = new BlockHitResult(hit.get(), Direction.WEST, surface.pos(), false);
				}
			}
			return nearest;
		}).stream().map(BlockHitResult::getBlockPos).toList();
	}

	private record Surface(BlockPos pos, AABB box) {}
}
