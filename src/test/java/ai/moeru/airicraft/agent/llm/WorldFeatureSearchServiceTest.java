package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldFeatureSearchServiceTest {
	private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

	@Test
	void surfaceSearchSkipsUndergroundPoolsBeforeLimitingResults() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		access.surfaceY = 63;
		addWaterPool(access, 2, 40, 0, 4, 2);
		addWaterPool(access, 30, 63, 0, 4, 2);
		access.standable(new BlockPos(1, 41, 0));
		access.standable(new BlockPos(29, 64, 0));
		JsonObject args = args("water_body");
		args.addProperty("surfaceOnly", true);
		args.addProperty("limit", 1);
		var result = WorldFeatureSearchService.search(access, new BlockPos(0,40,0), args);
		assertTrue(result.text().contains("targetPos=30,63,0"));
		assertTrue(result.text().contains("standPos=29,64,0"));
	}

	@Test
	void nearestForAWalkingSearchTakesOnlySurfaceWater() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		access.surfaceY = 63;
		addWaterPool(access, 2, 40, 0, 4, 2);
		addWaterPool(access, 30, 63, 0, 4, 2);
		access.standable(new BlockPos(29, 64, 0));
		var found = WorldFeatureSearchService.nearest(access, new BlockPos(0, 40, 0), "water_body", 128).orElseThrow();
		assertEquals(new BlockPos(30, 63, 0), found.targetPos());
		assertEquals(new BlockPos(29, 64, 0), found.standPos());
		assertTrue(found.summary().contains("kind=water_body"), found.summary());
		assertTrue(WorldFeatureSearchService.nearest(access, new BlockPos(0, 40, 0), "water_body", 16).isEmpty(),
			"the surface pool is out of range and the cave pool does not count");
	}

	@Test
	void surfacePoolDoesNotOfferAnUndergroundStandingPosition() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		access.surfaceY = 63;
		addWaterPool(access, 10, 63, 0, 4, 2);
		access.standable(new BlockPos(9, 62, 0));
		access.standable(new BlockPos(9, 64, 0));
		JsonObject args = args("water_body");
		args.addProperty("surfaceOnly", true);
		var result = WorldFeatureSearchService.search(access, new BlockPos(0,40,0), args);
		assertTrue(result.text().contains("standPos=9,64,0"));
	}

	@Test
	void waterBodyRequiresEightConnectedSourcesByDefault() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		addWaterPool(access, 10, 63, 0, 4, 2);
		access.standable(new BlockPos(9, 64, 0));
		JsonObject args = args("water_body");

		WorldFeatureSearchService.WorldFeatureSearchResult result = WorldFeatureSearchService.search(access, ORIGIN, args);

		assertTrue(result.text().contains("returned=1"));
		assertTrue(result.text().contains("kind=water_body"));
		assertTrue(result.text().contains("connectedWaterSources=8"));
		assertTrue(result.text().contains("targetPos=10,63,0"));
		assertTrue(result.text().contains("standPos=9,64,0"));
		assertTrue(result.observedPositions().contains(new BlockPos(10, 63, 0)));
		assertTrue(result.observedPositions().contains(new BlockPos(9, 64, 0)));
	}

	@Test
	void waterBodyRejectsSevenConnectedSources() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		addWaterPool(access, 10, 63, 0, 7, 1);
		JsonObject args = args("water_body");

		WorldFeatureSearchService.WorldFeatureSearchResult result = WorldFeatureSearchService.search(access, ORIGIN, args);

		assertTrue(result.text().contains("returned=0"));
		assertTrue(result.text().contains("features=none"));
	}

	@Test
	void waterBodyDoesNotMergeDisconnectedPools() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		addWaterPool(access, 10, 63, 0, 4, 1);
		addWaterPool(access, 30, 63, 0, 4, 1);
		JsonObject args = args("water_body");

		WorldFeatureSearchService.WorldFeatureSearchResult result = WorldFeatureSearchService.search(access, ORIGIN, args);

		assertTrue(result.text().contains("returned=0"));
	}

	@Test
	void directionFiltersWaterBodiesByCompassOctant() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		addWaterPool(access, 12, 63, 0, 4, 2);
		addWaterPool(access, 0, 63, -20, 4, 2);
		JsonObject eastArgs = args("water_body");
		eastArgs.addProperty("direction", "east");
		JsonObject northArgs = args("water_body");
		northArgs.addProperty("direction", "north");
		JsonObject anyArgs = args("water_body");

		WorldFeatureSearchService.WorldFeatureSearchResult east = WorldFeatureSearchService.search(access, ORIGIN, eastArgs);
		WorldFeatureSearchService.WorldFeatureSearchResult north = WorldFeatureSearchService.search(access, ORIGIN, northArgs);
		WorldFeatureSearchService.WorldFeatureSearchResult any = WorldFeatureSearchService.search(access, ORIGIN, anyArgs);

		assertTrue(east.text().contains("returned=1"));
		assertTrue(east.text().contains("direction=east"));
		assertTrue(north.text().contains("returned=1"));
		assertTrue(north.text().contains("direction=north"));
		assertTrue(any.text().contains("returned=2"));
	}

	@Test
	void forestRequiresLeafBackedTreeCluster() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		for (int x = 20; x < 26; x++) {
			access.log(new BlockPos(x, 64, 0));
			access.leaves(new BlockPos(x, 68, 1));
		}
		access.standable(new BlockPos(19, 64, 0));
		JsonObject args = args("forest");

		WorldFeatureSearchService.WorldFeatureSearchResult result = WorldFeatureSearchService.search(access, ORIGIN, args);

		assertTrue(result.text().contains("returned=1"));
		assertTrue(result.text().contains("kind=forest"));
		assertTrue(result.text().contains("treeStems=6"));
		assertTrue(result.text().contains("leafBacked=true"));
		assertTrue(result.text().contains("targetPos=20,64,0"));
	}

	@Test
	void forestRejectsLeaflessLogs() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		for (int x = 20; x < 26; x++) {
			access.log(new BlockPos(x, 64, 0));
		}
		JsonObject args = args("forest");

		WorldFeatureSearchService.WorldFeatureSearchResult result = WorldFeatureSearchService.search(access, ORIGIN, args);

		assertTrue(result.text().contains("returned=0"));
	}

	@Test
	void fortressSearchFindsExposedBricksAtAnyDepthAndStandsOnTheBridge() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		// A bridge deck three wide and forty long, far below the search origin.
		for (int x = 30; x < 70; x++) for (int z = -1; z <= 1; z++) access.exposed.add(new BlockPos(x, 64, z));
		access.standable(new BlockPos(31, 65, 0));
		JsonObject args = args("nether_fortress");

		var result = WorldFeatureSearchService.search(access, new BlockPos(0, 100, 0), args);

		assertTrue(result.text().contains("returned=1"), result.text());
		assertTrue(result.text().contains("kind=nether_fortress"), result.text());
		assertTrue(result.text().contains("exposedBlocks=120"), result.text());
		assertTrue(result.text().contains("standPos=31,65,0"), result.text());
	}

	@Test
	void aFewStrayBricksAreNotAFortress() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		for (int x = 30; x < 40; x++) access.exposed.add(new BlockPos(x, 64, 0));
		var result = WorldFeatureSearchService.search(access, ORIGIN, args("nether_fortress"));
		assertTrue(result.text().contains("returned=0"), result.text());
	}

	@Test
	void separateLavaPoolsAreSeparateFeatures() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		for (int x = 10; x < 13; x++) for (int z = 0; z < 2; z++) access.exposed.add(new BlockPos(x, 20, z));
		for (int x = 60; x < 63; x++) for (int z = 0; z < 2; z++) access.exposed.add(new BlockPos(x, 20, z));
		access.standable(new BlockPos(9, 21, 0));
		var result = WorldFeatureSearchService.search(access, ORIGIN, args("lava_pool"));
		assertTrue(result.text().contains("returned=2"), result.text());
		assertTrue(result.text().contains("targetPos=10,20,0"), result.text());
		assertTrue(result.text().contains("standPos=9,21,0"), result.text());
	}

	@Test
	void endPortalFramesDeepUnderTheSurfaceAreOnePortal() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		// The twelve frames of a portal room: three on each side of the 3x3 opening at x 10..12, z 10..12.
		for (int i = 10; i <= 12; i++) {
			access.exposed.add(new BlockPos(i, 30, 9));
			access.exposed.add(new BlockPos(i, 30, 13));
			access.exposed.add(new BlockPos(9, 30, i));
			access.exposed.add(new BlockPos(13, 30, i));
		}
		access.standable(new BlockPos(10, 31, 8));

		var result = WorldFeatureSearchService.search(access, ORIGIN, args("end_portal"));

		assertTrue(result.text().contains("returned=1"), result.text());
		assertTrue(result.text().contains("kind=end_portal"), result.text());
		assertTrue(result.text().contains("exposedBlocks=12"), result.text());
		assertTrue(result.text().contains("standPos=10,31,8"), result.text());
	}

	@Test
	void aFewStrayFramesAreNotAPortal() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		access.exposed.add(new BlockPos(10, 30, 9));
		access.exposed.add(new BlockPos(11, 30, 9));
		var result = WorldFeatureSearchService.search(access, ORIGIN, args("end_portal"));
		assertTrue(result.text().contains("returned=0"), result.text());
	}

	@Test
	void aSingleExposedBlazeSpawnerIsAFeature() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		// The fake hands out exposed spawners the Minecraft access has already checked to spin a blaze.
		access.exposed.add(new BlockPos(20, 70, -6));
		access.standable(new BlockPos(20, 71, -6));

		var result = WorldFeatureSearchService.search(access, ORIGIN, args("blaze_spawner"));

		assertTrue(result.text().contains("returned=1"), result.text());
		assertTrue(result.text().contains("kind=blaze_spawner"), result.text());
		assertTrue(result.text().contains("targetPos=20,70,-6"), result.text());
		assertEquals(new BlockPos(20, 70, -6), WorldFeatureSearchService.nearest(access, ORIGIN, "blaze_spawner", 64).orElseThrow().targetPos());
	}

	@Test
	void twoBlazeSpawnersFarApartAreTwoFeaturesNearestFirst() {
		FakeWorldFeatureAccess access = new FakeWorldFeatureAccess();
		access.exposed.add(new BlockPos(60, 70, 0));
		access.exposed.add(new BlockPos(-20, 66, 0));
		var result = WorldFeatureSearchService.search(access, ORIGIN, args("blaze_spawner"));
		assertTrue(result.text().contains("returned=2"), result.text());
		assertEquals(new BlockPos(-20, 66, 0), WorldFeatureSearchService.nearest(access, ORIGIN, "blaze_spawner", 128).orElseThrow().targetPos());
	}

	private static JsonObject args(String featureKind) {
		JsonObject args = new JsonObject();
		args.addProperty("featureKind", featureKind);
		return args;
	}

	private static void addWaterPool(FakeWorldFeatureAccess access, int x, int y, int z, int width, int depth) {
		for (int dx = 0; dx < width; dx++) {
			for (int dz = 0; dz < depth; dz++) {
				access.water(new BlockPos(x + dx, y, z + dz));
			}
		}
	}

	private static final class FakeWorldFeatureAccess implements WorldFeatureSearchService.WorldFeatureAccess {
		private int surfaceY = Integer.MIN_VALUE;
		public boolean isSurface(BlockPos pos) { return pos.getY() >= surfaceY; }
		private final HashMap<BlockPos, WorldFeatureSearchService.SampledBlock> samples = new HashMap<>();
		private final HashSet<BlockPos> standable = new HashSet<>();
		private final HashSet<BlockPos> exposed = new HashSet<>();

		@Override
		public void forEachExposedBlock(BlockPos origin, int maxDistanceBlocks, WorldFeatureSearchService.FeatureKind featureKind,
			Consumer<BlockPos> consumer) {
			exposed.stream().sorted(Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getZ)).forEach(consumer);
		}

		private void water(BlockPos pos) {
			put(pos, new WorldFeatureSearchService.SampledBlock(true, false, false));
		}

		private void log(BlockPos pos) {
			put(pos, new WorldFeatureSearchService.SampledBlock(false, true, false));
		}

		private void leaves(BlockPos pos) {
			put(pos, new WorldFeatureSearchService.SampledBlock(false, false, true));
		}

		private void standable(BlockPos pos) {
			standable.add(pos.immutable());
		}

		private void put(BlockPos pos, WorldFeatureSearchService.SampledBlock block) {
			samples.put(pos.immutable(), block);
		}

		@Override
		public void forEachCandidatePosition(
			BlockPos origin,
			int maxDistanceBlocks,
			Optional<WorldFeatureSearchService.SearchDirection> direction,
			WorldFeatureSearchService.FeatureKind featureKind,
			Consumer<BlockPos> consumer
		) {
			samples.keySet().stream()
				.sorted(Comparator
					.<BlockPos>comparingInt(BlockPos::getX)
					.thenComparingInt(BlockPos::getY)
					.thenComparingInt(BlockPos::getZ))
				.forEach(consumer);
		}

		@Override
		public boolean isLoaded(BlockPos pos) {
			BlockPos immutable = pos.immutable();
			return samples.containsKey(immutable) || standable.contains(immutable);
		}

		@Override
		public WorldFeatureSearchService.SampledBlock sample(BlockPos pos) {
			return samples.getOrDefault(pos.immutable(), WorldFeatureSearchService.SampledBlock.EMPTY);
		}

		@Override
		public boolean isStandable(BlockPos pos) {
			return standable.contains(pos.immutable());
		}
	}
}
