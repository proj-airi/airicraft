package ai.moeru.airicraft.agent.llm;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CurrentWorldQueryServiceTest {
	@Test
	void areaResultBoundsOutputAndOnlyAuthorizesReturnedPositions() {
		BlockPos nearest = new BlockPos(1, 64, 1);
		BlockPos second = new BlockPos(2, 64, 2);
		BlockPos omitted = new BlockPos(3, 64, 3);
		List<CurrentWorldQueryService.BlockRecord> records = List.of(
			record(omitted, 3),
			record(nearest, 1),
			record(second, 2)
		);

		CurrentWorldQueryService.WorldQueryResult result = CurrentWorldQueryService.areaResult(
			new CurrentWorldQueryService.QueryBounds("center", new BlockPos(-1, 63, -1), new BlockPos(3, 65, 3)),
			75,
			records,
			2
		);

		assertTrue(result.text().contains("scanned=75 matched=3 returned=2"), result.text());
		assertTrue(result.text().contains("pos=1,64,1"), result.text());
		assertTrue(result.text().contains("pos=2,64,2"), result.text());
		assertFalse(result.text().contains("pos=3,64,3"), result.text());
		assertEquals(List.of(nearest, second), result.observedPositions());
	}

	@Test
	void movingInspectionCenterChangesTheLimitedNeighborhood() {
		BlockPos player = new BlockPos(0, 133, 4);
		var centers = List.of(new BlockPos(0, 134, 4), new BlockPos(-2, 133, 6));
		var results = new java.util.ArrayList<CurrentWorldQueryService.WorldQueryResult>();
		for (BlockPos center : centers) {
			var bounds = new CurrentWorldQueryService.QueryBounds("center", center.offset(-5, -5, -5), center.offset(5, 5, 5));
			var records = bounds.positions().stream().map(pos -> record(pos, Math.max(
				Math.max(Math.abs(pos.getX() - player.getX()), Math.abs(pos.getY() - player.getY())),
				Math.abs(pos.getZ() - player.getZ())))).toList();
			var result = CurrentWorldQueryService.areaResult(bounds, records.size(), records, 32, true);
			assertEquals(center, result.observedPositions().getFirst());
			assertEquals(32, result.observedPositions().size());
			assertTrue(result.text().contains("truncated=true"), result.text());
			results.add(result);
		}
		assertFalse(results.get(0).observedPositions().equals(results.get(1).observedPositions()));
		// Distances still describe reach from the player, not distance from the requested center.
		assertTrue(results.get(1).text().contains("pos=-2,133,6, id=stone, loaded=true, replaceable=false, air=false, fluid=false, distance=2"));
	}

	private static CurrentWorldQueryService.BlockRecord record(BlockPos pos, int distance) {
		return new CurrentWorldQueryService.BlockRecord(
			pos,
			"minecraft:stone",
			Map.of(),
			true,
			false,
			false,
			false,
			distance
		);
	}

	@Test
	void smallBoxLayersKeepCoordinatesStatesAndUnknownCellsDistinct() {
		var bounds = new CurrentWorldQueryService.QueryBounds("box", new BlockPos(0, 134, 0), new BlockPos(1, 135, 1));
		var records = new java.util.ArrayList<CurrentWorldQueryService.BlockRecord>();
		records.add(new CurrentWorldQueryService.BlockRecord(new BlockPos(0, 134, 0), "minecraft:air", Map.of(), true, true, true, false, 0));
		records.add(new CurrentWorldQueryService.BlockRecord(new BlockPos(0, 134, 1), "minecraft:snow", Map.of("layers", "1"), true, true, false, false, 0));
		records.add(new CurrentWorldQueryService.BlockRecord(new BlockPos(0, 135, 0), "minecraft:oak_door", Map.of("half", "upper", "open", "false"), true, false, false, false, 0));
		records.add(CurrentWorldQueryService.BlockRecord.unloaded(new BlockPos(0, 135, 1), 0));
		for (int y = 134; y <= 135; y++) for (int z = 0; z <= 1; z++) records.add(record(new BlockPos(1, y, z), 2));

		var result = CurrentWorldQueryService.areaResult(bounds, 8, records, 4);
		assertTrue(result.text().contains("columns X: 0 1\nY=134\nZ=0: 0 ?\nZ=1: 2 ?\nY=135\nZ=0: 1 ?\nZ=1: 3 ?"), result.text());
		assertTrue(result.text().contains("layers=1"));
		assertTrue(result.text().contains("half=upper"));
		assertTrue(result.text().contains("open=false"));
		assertTrue(result.text().contains("id=unloaded, loaded=false"));
		assertTrue(result.text().contains("?=omitted, not observed"));
		assertTrue(result.text().contains("truncated=true"));
		assertEquals(4, result.observedPositions().size());
		assertTrue(result.observedPositions().stream().allMatch(pos -> pos.getX() == 0));
	}

	@Test
	void repetitiveShelterLayerUsesLessTextWithoutLosingObservedCoordinates() {
		var bounds = new CurrentWorldQueryService.QueryBounds("box", new BlockPos(-4, 136, 2), new BlockPos(0, 136, 6));
		var records = bounds.positions().stream().map(pos -> record(pos, 1)).toList();
		var result = CurrentWorldQueryService.areaResult(bounds, 25, records, 64);
		int verboseLength = records.stream().mapToInt(record -> record.compact().length()).sum();
		assertTrue(result.text().length() < verboseLength / 2, result.text());
		assertEquals(25, result.observedPositions().size());
		assertTrue(result.observedPositions().containsAll(bounds.positions()));
		assertTrue(result.text().contains("5x5 horizontal patch at block Y=136, X=-4..0, Z=2..6: stone"), result.text());
		assertTrue(result.text().contains("truncated=false"));
	}
	@Test void patchSummaryKeepsExactBoundsAndDetailedRecordsRemainAvailable() {
		var bounds = new CurrentWorldQueryService.QueryBounds("box", new BlockPos(10, 63, 20), new BlockPos(14, 63, 24));
		var records = bounds.positions().stream().map(pos -> new CurrentWorldQueryService.BlockRecord(pos, "minecraft:dirt", Map.of(), true, false, false, false, pos.getX() - 10)).toList();
		var summary = CurrentWorldQueryService.areaResult(bounds, 25, records, 64);
		var detailed = CurrentWorldQueryService.areaResult(bounds, 25, records, 64, true);
		assertTrue(summary.text().contains("5x5 horizontal patch at block Y=63, X=10..14, Z=20..24: dirt"), summary.text());
		assertTrue(summary.text().contains("distance 0..4"));
		assertTrue(summary.text().contains("No clearance or route inferred"));
		assertTrue(summary.text().contains("detail=blocks"));
		assertEquals(summary.observedPositions(), detailed.observedPositions());
		for (var pos : bounds.positions()) assertTrue(detailed.text().contains("pos=" + pos.getX() + ",63," + pos.getZ()));
		assertTrue(summary.text().length() < detailed.text().length() / 3);
	}

	@Test void patchMergingDoesNotBridgeUnloadedCellsDifferentStatesOrOmittedCells() {
		var bounds = new CurrentWorldQueryService.QueryBounds("box", new BlockPos(0, 63, 0), new BlockPos(4, 63, 4));
		var records = new java.util.ArrayList<>(bounds.positions().stream().map(pos -> new CurrentWorldQueryService.BlockRecord(pos, "minecraft:dirt", Map.<String,String>of(), true, false, false, false, 0)).toList());
		records.removeIf(record -> record.pos().equals(new BlockPos(2, 63, 2)));
		records.add(CurrentWorldQueryService.BlockRecord.unloaded(new BlockPos(2, 63, 2), 0));
		var result = CurrentWorldQueryService.areaResult(bounds, 25, records, 64);
		assertFalse(result.text().contains("5x5 horizontal patch"));
		assertTrue(result.text().contains("unloaded"));
		records.removeIf(record -> record.pos().equals(new BlockPos(2, 63, 2)));
		records.add(new CurrentWorldQueryService.BlockRecord(new BlockPos(2, 63, 2), "example:slab", Map.of("type", "bottom", "waterlogged", "true"), true, false, false, true, 0));
		result = CurrentWorldQueryService.areaResult(bounds, 25, records, 64);
		assertFalse(result.text().contains("5x5 horizontal patch"));
		assertTrue(result.text().contains("example:slab"));
		assertTrue(result.text().contains("type=bottom"));
		assertTrue(result.text().contains("waterlogged=true"));
		result = CurrentWorldQueryService.areaResult(bounds, 25, records, 20);
		assertFalse(result.text().contains("5x5 horizontal patch"));
		assertTrue(result.text().contains("truncated=true"));
		assertEquals(20, result.observedPositions().size());
	}

	@Test void placementSitesFactorOnlySharedFactsAndKeepReversedDoorStateAndReachExceptions() {
		var first = new CurrentWorldQueryService.PlacementSite(new BlockPos(0, 64, 0), "minecraft:air", Map.of(), new BlockPos(0, 63, 0), "minecraft:dirt", Map.of(), 1, new BlockPos(1, 64, 0), null, true);
		var second = new CurrentWorldQueryService.PlacementSite(new BlockPos(0, 64, 1), "minecraft:air", Map.of(), new BlockPos(0, 63, 1), "minecraft:grass_block", Map.of("snowy", "false"), 2, new BlockPos(1, 64, 1), null, true);
		String summary = CurrentWorldQueryService.formatSites(List.of(first, second));
		assertTrue(summary.contains("All listed targets are air"));
		assertTrue(summary.contains("Each support is directly below"));
		assertTrue(summary.contains("All are within interaction range by distance only"));
		assertTrue(summary.contains("routes and support-face visibility are not verified"));
		assertTrue(summary.contains("grass_block support {snowy=false}"));
		assertTrue(summary.contains("stand at 1,64,1"));
		var exception = new CurrentWorldQueryService.PlacementSite(new BlockPos(0, 64, 2), "minecraft:oak_door", Map.of("open", "false", "facing", "west"), new BlockPos(0, 63, 2), "minecraft:dirt", Map.of(), 9, null, new BlockPos(0, 64, 3), false);
		summary = CurrentWorldQueryService.formatSites(List.of(first, exception));
		assertFalse(summary.contains("All listed targets are air"));
		assertFalse(summary.contains("All are within interaction range"));
		for (String fact : List.of("oak_door", "open=false", "facing=west", "out of reach", "no adjacent standing position found", "nearby required-block match 0,64,3")) assertTrue(summary.contains(fact), summary);
	}

}
