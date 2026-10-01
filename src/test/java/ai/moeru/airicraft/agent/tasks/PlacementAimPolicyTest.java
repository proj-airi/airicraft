package ai.moeru.airicraft.agent.tasks;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class PlacementAimPolicyTest {
	private static final BlockPos SUPPORT = new BlockPos(0, 64, 3);
	private static final BlockPos TARGET = SUPPORT.north();
	private static final Vec3 FEET = new Vec3(0.5D, 64D, 0.5D);
	private static final BlockHitResult PLANNED = hit(new Vec3(0.5D, 64.5D, 3D), Direction.NORTH, SUPPORT, false);
	private static final BlockHitResult OFF_CENTER = hit(new Vec3(0.8D, 64.8D, 3D), Direction.NORTH, SUPPORT, false);

	@BeforeAll
	static void bootstrapVanillaItemProperties() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void ordinaryItemCohortHasPlainPropertyFreeFullCubeVanillaPlacement() {
		for (var item : List.of(Items.STONE, Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.DIRT,
			Items.OAK_PLANKS, Items.SPRUCE_PLANKS, Items.BIRCH_PLANKS, Items.JUNGLE_PLANKS,
			Items.ACACIA_PLANKS, Items.DARK_OAK_PLANKS, Items.MANGROVE_PLANKS, Items.CHERRY_PLANKS,
			Items.PALE_OAK_PLANKS, Items.BAMBOO_PLANKS, Items.CRIMSON_PLANKS, Items.WARPED_PLANKS)) {
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
			assertTrue(PlacementAimPolicy.usesCurrentViewRay(WorldTaskType.PLACE_BLOCK, id, item), id);
			assertFalse(PlacementAimPolicy.usesCurrentViewRay(WorldTaskType.USE_BLOCK, id, item), id);
		}
	}

	@Test
	void directionalPartialInteractiveAndUnknownItemsKeepPreciseAim() {
		for (var item : List.of(Items.DEEPSLATE, Items.STONE_SLAB, Items.OAK_STAIRS, Items.OAK_LOG,
			Items.FURNACE, Items.CHEST, Items.GRASS_BLOCK, Items.COPPER_BLOCK, Items.WATER_BUCKET)) {
			String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
			assertFalse(PlacementAimPolicy.usesCurrentViewRay(WorldTaskType.PLACE_BLOCK, id, item), id);
		}
		assertFalse(PlacementAimPolicy.usesCurrentViewRay(WorldTaskType.PLACE_BLOCK, "mod:stone", Items.STONE));
		assertFalse(PlacementAimPolicy.usesCurrentViewRay(WorldTaskType.PLACE_BLOCK, "minecraft:stone", Items.DEEPSLATE));
		assertFalse(PlacementAimPolicy.usesCurrentViewRay(WorldTaskType.PLACE_BLOCK, "minecraft:stone", Items.STONE_SLAB));
		assertFalse(PlacementAimPolicy.usesCurrentViewRay(WorldTaskType.PLACE_BLOCK, null, Items.STONE));
	}

	@Test
	void offCenterCurrentHitIsReadyBeforeExactAlignmentAndIsPassedThroughUnchanged() {
		assertSame(OFF_CENTER, select(true, false, TARGET, OFF_CENTER, FEET).orElseThrow());
		assertSame(OFF_CENTER, select(true, true, TARGET, OFF_CENTER, FEET).orElseThrow());
	}

	@Test
	void preciseFallbackWaitsForExactAlignmentAndKeepsPlannedHit() {
		assertTrue(select(false, false, TARGET, OFF_CENTER, FEET).isEmpty());
		assertSame(PLANNED, select(false, true, TARGET, OFF_CENTER, FEET).orElseThrow());
		assertSame(PLANNED, select(false, true, TARGET, null, FEET).orElseThrow());
	}

	@Test
	void currentRayMustHitTheExactPlannedSupportAndFace() {
		assertTrue(select(true, false, TARGET, hit(OFF_CENTER.getLocation(), Direction.NORTH, SUPPORT.east(), false), FEET).isEmpty());
		assertTrue(select(true, false, TARGET, hit(OFF_CENTER.getLocation(), Direction.UP, SUPPORT, false), FEET).isEmpty());
		// Even a hit that offsets to the destination must match the specific planned support/face.
		assertTrue(select(true, false, SUPPORT.east().north(),
			hit(OFF_CENTER.getLocation().add(1D, 0D, 0D), Direction.NORTH, SUPPORT.east(), false), FEET).isEmpty());
		assertTrue(select(true, false, SUPPORT.above(),
			hit(new Vec3(0.5D, 65D, 3.5D), Direction.UP, SUPPORT, false), FEET).isEmpty());
	}

	@Test
	void clickedFaceMustOffsetToTheRequestedDestination() {
		assertTrue(select(true, false, TARGET.above(), OFF_CENTER, FEET).isEmpty());
	}

	@Test
	void currentRayMissAndInsideHitDoNotPermitEarlyActuation() {
		BlockHitResult miss = BlockHitResult.miss(OFF_CENTER.getLocation(), Direction.NORTH, SUPPORT);
		assertTrue(select(true, false, TARGET, miss, FEET).isEmpty());
		assertTrue(select(true, false, TARGET, hit(OFF_CENTER.getLocation(), Direction.NORTH, SUPPORT, true), FEET).isEmpty());
		assertTrue(select(true, false, TARGET, null, FEET).isEmpty());
	}

	@Test
	void lowDistantFloorFaceKeepsLegacyPreciselyAlignedPathWhenEyeRayCannotReachIt() {
		BlockPos floor = new BlockPos(0, 63, 4);
		Vec3 point = new Vec3(0.5D, 64D, 4.9D);
		Vec3 eye = FEET.add(0D, 1.62D, 0D);
		BlockHitResult planned = hit(point, Direction.UP, floor, false);
		Vec3 end = PlacementAimPolicy.currentViewRayEnd(eye, point.subtract(eye).normalize(), 4.5D).orElseThrow();
		assertTrue(FEET.distanceToSqr(point) < 20.25D);
		assertTrue(eye.distanceToSqr(point) > 20.25D);
		assertNull(AABB.clip(List.of(new AABB(0, 0, 0, 1, 1, 1)), eye, end, floor));
		BlockHitResult miss = BlockHitResult.miss(end, Direction.UP, floor);
		assertTrue(PlacementAimPolicy.selectHit(true, false, floor.above(), planned, miss, FEET, 20.25D).isEmpty());
		assertSame(planned, PlacementAimPolicy.selectHit(true, true, floor.above(), planned, miss, FEET, 20.25D).orElseThrow());
	}

	@Test
	void actualHitMustRespectExistingFeetReachEvenIfPlannedPointIsCloser() {
		Vec3 feet = new Vec3(0.5D, 64.5D, -1.49D);
		assertTrue(feet.distanceToSqr(PLANNED.getLocation()) < 20.25D);
		assertTrue(select(true, false, TARGET, OFF_CENTER, feet).isEmpty());
		Vec3 exactlyInRange = OFF_CENTER.getLocation().add(0D, 0D, -4.5D);
		assertSame(OFF_CENTER, select(true, false, TARGET, OFF_CENTER, exactlyInRange).orElseThrow());
	}

	@Test
	void nonFiniteHitOrFeetGeometryFailsClosed() {
		for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertTrue(select(true, false, TARGET, hit(new Vec3(invalid, 64.8D, 3D), Direction.NORTH, SUPPORT, false), FEET).isEmpty());
			assertTrue(select(true, false, TARGET, OFF_CENTER, new Vec3(invalid, 64D, 0.5D)).isEmpty());
		}
	}

	@Test
	void currentViewRayUsesFreshEyeDirectionAndVanillaReach() {
		assertEquals(new Vec3(0.5D, 65.62D, 5D), PlacementAimPolicy.currentViewRayEnd(
			new Vec3(0.5D, 65.62D, 0.5D), new Vec3(0D, 0D, 1D), 4.5D).orElseThrow());
	}

	@Test
	void vanillaLookupTableViewVectorsAreNormalizedInsteadOfRejected() {
		Vec3 eye = new Vec3(0.5D, 152.62D, 0.5D);
		for (float[] angles : new float[][] {{33.72794F, 30.440891F}, {37.579777F, 12.295284F}}) {
			Vec3 view = vanillaViewVector(angles[0], angles[1]);
			assertTrue(Math.abs(view.lengthSqr() - 1D) > 1.0E-6D, "fixture must reproduce vanilla lookup-table rounding");
			Vec3 end = PlacementAimPolicy.currentViewRayEnd(eye, view, 4.5D).orElseThrow();
			assertEquals(4.5D, eye.distanceTo(end), 1.0E-12D);
			assertEquals(1D, end.subtract(eye).normalize().dot(view.normalize()), 1.0E-12D);
		}
	}

	@Test
	void finiteScaledViewDirectionDoesNotExtendVanillaReach() {
		Vec3 eye = new Vec3(0.5D, 65.62D, 0.5D);
		assertEquals(new Vec3(0.5D, 65.62D, 5D), PlacementAimPolicy.currentViewRayEnd(
			eye, new Vec3(0D, 0D, 2D), 4.5D).orElseThrow());
	}

	@Test
	void invalidCurrentRayGeometryFailsClosedBeforeClipping() {
		Vec3 eye = new Vec3(0.5D, 65.62D, 0.5D);
		Vec3 view = new Vec3(0D, 0D, 1D);
		for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
			assertTrue(PlacementAimPolicy.currentViewRayEnd(new Vec3(invalid, 0D, 0D), view, 4.5D).isEmpty());
			assertTrue(PlacementAimPolicy.currentViewRayEnd(eye, new Vec3(invalid, 0D, 0D), 4.5D).isEmpty());
			assertTrue(PlacementAimPolicy.currentViewRayEnd(eye, view, invalid).isEmpty());
		}
		assertTrue(PlacementAimPolicy.currentViewRayEnd(eye, view, 0D).isEmpty());
		assertTrue(PlacementAimPolicy.currentViewRayEnd(eye, view, -1D).isEmpty());
		assertTrue(PlacementAimPolicy.currentViewRayEnd(eye, Vec3.ZERO, 4.5D).isEmpty());
		assertTrue(PlacementAimPolicy.currentViewRayEnd(eye, new Vec3(Double.MAX_VALUE, 0D, 0D), 4.5D).isEmpty());
		assertTrue(PlacementAimPolicy.currentViewRayEnd(eye, new Vec3(Double.MIN_VALUE, 0D, 0D), 4.5D).isEmpty());
	}

	private static Vec3 vanillaViewVector(float pitch, float yaw) {
		// Entity.calculateViewVector uses these float operations and Mth's trigonometric lookup table.
		float pitchRadians = pitch * ((float) Math.PI / 180F);
		float yawRadians = -yaw * ((float) Math.PI / 180F);
		float yawCos = Mth.cos(yawRadians);
		float yawSin = Mth.sin(yawRadians);
		float pitchCos = Mth.cos(pitchRadians);
		float pitchSin = Mth.sin(pitchRadians);
		return new Vec3(yawSin * pitchCos, -pitchSin, yawCos * pitchCos);
	}

	private static Optional<BlockHitResult> select(boolean currentView, boolean aligned, BlockPos target, BlockHitResult currentHit, Vec3 feet) {
		return PlacementAimPolicy.selectHit(currentView, aligned, target, PLANNED, currentHit, feet, 20.25D);
	}

	private static BlockHitResult hit(Vec3 point, Direction face, BlockPos support, boolean inside) {
		return new BlockHitResult(point, face, support, inside);
	}
}
