package ai.moeru.airicraft.agent.perception;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoticersTest {
	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");

	@Test void droppedItemsAreNoticedOnceWhenSettledVisibleAndInRange() {
		var noticer = new DroppedItemNoticer();
		var items = List.of(
			new DroppedItemNoticer.Item(A, "minecraft:bread", 3, 3, 64, 0, 40),
			new DroppedItemNoticer.Item(B, "minecraft:diamond", 1, 2, 64, 0, 4),      // still settling
			new DroppedItemNoticer.Item(C, "minecraft:apple", 1, 30, 64, 0, 40));     // out of range
		var found = noticer.sample(1, "w|o", 0, 65.6, 0, 12, 16, items, item -> true, Set.of(A), item -> false);
		assertEquals(1, found.size());
		var candidate = found.getFirst();
		assertEquals("item", candidate.kind());
		assertEquals(true, candidate.fields().get("offered"));
		assertEquals("thrown_by_player", candidate.fields().get("attribution"));
		assertTrue(noticer.sample(2, "w|o", 0, 65.6, 0, 12, 16, items, item -> true, Set.of(), item -> false).isEmpty(), "once per entity");
		var hidden = List.of(new DroppedItemNoticer.Item(B, "minecraft:diamond", 1, 2, 64, 0, 40));
		assertTrue(noticer.sample(3, "w|o", 0, 65.6, 0, 12, 16, hidden, item -> false, Set.of(), item -> false).isEmpty());
		var mined = noticer.sample(4, "w|o", 0, 65.6, 0, 12, 16, hidden, item -> true, Set.of(), item -> true);
		assertEquals("own_mining_drop", mined.getFirst().fields().get("attribution"));
	}

	@Test void entitiesEnterWithSightLeaveWithHysteresisAndAreRememberedForATime() {
		var noticer = new EntityNoticer();
		var alex = new EntityNoticer.Entity(A, "minecraft:player", "Alex", false, false, false, false, 10, 64, 0, "minecraft:iron_sword");
		var zombie = new EntityNoticer.Entity(B, "minecraft:zombie", "Zombie", false, false, false, true, 5, 64, 0, null);
		var found = noticer.sample(1, "w|o", 0, 65.6, 0, 16, 20, 8, List.of(alex, zombie), entity -> true, Set.of(B.toString()));
		assertEquals(2, found.size());
		assertEquals("minecraft:zombie", found.getFirst().fields().get("entityType"), "nearest first");
		assertEquals(true, found.getFirst().fields().get("reflexTracked"));
		assertEquals(java.util.Map.of("mainHand", "minecraft:iron_sword"), found.get(1).fields().get("equipment"));

		var farther = new EntityNoticer.Entity(A, "minecraft:player", "Alex", false, false, false, false, 18, 64, 0, null);
		assertTrue(noticer.sample(2, "w|o", 0, 65.6, 0, 16, 20, 8, List.of(farther, zombie), entity -> false, Set.of()).isEmpty(),
			"between enter and exit range stays tracked");
		var lost = noticer.sample(3, "w|o", 0, 65.6, 0, 16, 20, 8, List.of(zombie), entity -> true, Set.of());
		assertEquals(List.of("entity_lost"), lost.stream().map(PerceptCandidate::kind).toList());
		assertTrue(noticer.sample(4, "w|o", 0, 65.6, 0, 16, 20, 8, List.of(alex, zombie), entity -> true, Set.of()).isEmpty(),
			"re-entering within the TTL is not noticed again");

		var hidden = new EntityNoticer.Entity(C, "minecraft:villager", "Villager", false, false, false, false, 6, 64, 0, null);
		assertEquals(List.of("entity_lost"), noticer.sample(5, "w|o", 0, 65.6, 0, 16, 20, 8, List.of(hidden), entity -> false, Set.of())
			.stream().map(PerceptCandidate::kind).toList(), "the zombie left; the villager without line of sight did not enter");
		assertTrue(noticer.sample(6, "w|o", 0, 65.6, 0, 16, 20, 0, List.of(hidden), entity -> true, Set.of()).isEmpty(),
			"no raycast budget, no entry this tick");
		assertEquals(1, noticer.sample(7, "w|o", 0, 65.6, 0, 16, 20, 8, List.of(hidden), entity -> true, Set.of()).size());
	}

	@Test void changedEntityRangesApplyToTheNextSample() {
		var noticer = new EntityNoticer();
		var alex = new EntityNoticer.Entity(A, "minecraft:player", "Alex", false, false, false, false, 7, 64, 0, null);
		assertEquals(1, noticer.sample(1, "w|o", 0, 65.6, 0, 8, 10, 8, List.of(alex), entity -> true, Set.of()).size());
		var farther = new EntityNoticer.Entity(A, "minecraft:player", "Alex", false, false, false, false, 15, 64, 0, null);
		var villager = new EntityNoticer.Entity(C, "minecraft:villager", "Villager", false, false, false, false, 12, 64, 0, null);
		var wider = noticer.sample(2, "w|o", 0, 65.6, 0, 16, 20, 8, List.of(farther, villager), entity -> true, Set.of());
		assertEquals(List.of("entity"), wider.stream().map(PerceptCandidate::kind).toList(),
			"under the wider ranges the villager at 12 enters and Alex at 15 stays tracked");
		assertEquals("minecraft:villager", wider.getFirst().fields().get("entityType"));
	}

	@Test void environmentReportsTransitionsAfterABaseline() {
		var watcher = new EnvironmentWatcher();
		assertTrue(watcher.sample(1, new EnvironmentWatcher.Sample("o", 11_990, false, false, "minecraft:plains", 15)).isEmpty(), "baseline");
		var dusk = watcher.sample(2, new EnvironmentWatcher.Sample("o", 12_010, true, false, "minecraft:plains", 15));
		assertEquals(List.of("dusk", "rain_started"), dusk.stream().map(candidate -> candidate.fields().get("change")).toList());
		assertTrue(watcher.sample(3, new EnvironmentWatcher.Sample("o", 12_020, true, false, "minecraft:forest", 15)).isEmpty(),
			"a biome must hold before it counts");
		var biome = watcher.sample(3 + EnvironmentWatcher.BIOME_HOLD_TICKS, new EnvironmentWatcher.Sample("o", 12_100, true, false, "minecraft:forest", 15));
		assertEquals("biome_changed", biome.getFirst().fields().get("change"));
		assertEquals("minecraft:plains", biome.getFirst().fields().get("from"));
		long tick = 200;
		for (; tick < 200 + EnvironmentWatcher.LIGHT_HOLD_TICKS; tick++) {
			assertTrue(watcher.sample(tick, new EnvironmentWatcher.Sample("o", 12_500, true, false, "minecraft:forest", 2)).isEmpty());
		}
		assertEquals("dark", watcher.sample(tick, new EnvironmentWatcher.Sample("o", 12_500, true, false, "minecraft:forest", 2))
			.getFirst().fields().get("change"));
		assertTrue(watcher.sample(tick + 1, new EnvironmentWatcher.Sample("the_nether", 12_500, false, false, "minecraft:nether_wastes", 7)).isEmpty(),
			"a new dimension is a new baseline");
		assertEquals(EnvironmentWatcher.Phase.DAWN, EnvironmentWatcher.phase(23_000));
		assertEquals(EnvironmentWatcher.Phase.DAY, EnvironmentWatcher.phase(24_100));
	}

	@Test void compassUsesMinecraftDirections() {
		assertEquals("north", Compass.direction(0, -5));
		assertEquals("south", Compass.direction(0, 5));
		assertEquals("east", Compass.direction(5, 0));
		assertEquals("west", Compass.direction(-5, 0));
		assertEquals("northeast", Compass.direction(5, -5));
		assertEquals("here", Compass.direction(0, 0));
	}
}
