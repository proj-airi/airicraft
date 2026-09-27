package ai.moeru.airicraft.agent.events;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ItemOfferObserverTest {
	private static final UUID SELF = new UUID(0, 1);
	private static final UUID ALICE = new UUID(0, 2);
	private static final UUID ITEM = new UUID(0, 3);
	private static final Vec3d SELF_POSITION = new Vec3d(0, 64, 0);
	private static final ItemOfferObserver.Player PLAYER = new ItemOfferObserver.Player(ALICE, "Alice",
		new Vec3d(3, 65.3, 0), new Vec3d(-1, 0, 0));
	private final ItemOfferObserver observer = new ItemOfferObserver();

	private ItemOfferObserver.Item item(Vec3d position, Vec3d velocity, int age, int count) {
		return new ItemOfferObserver.Item(ITEM, "minecraft:bread", count, position, velocity, age);
	}
	private ItemOfferObserver.Item offer(int count) {
		return item(new Vec3d(2.8, 65.3, 0), new Vec3d(-.3, .1, 0), 1, count);
	}
	private List<java.util.Map<String, Object>> observe(long tick, List<ItemOfferObserver.Item> items,
		List<ItemOfferObserver.Player> players) {
		return observer.observe(tick, "minecraft:overworld", SELF, SELF_POSITION, players, items);
	}
	private void baseline() { observe(1, List.of(), List.of(PLAYER)); }

	@Test void detectsOfferOnceWithPlayerItemAndLocation() {
		baseline();
		var events = observe(2, List.of(offer(3)), List.of(PLAYER));
		assertEquals(1, events.size());
		var event = events.getFirst();
		assertEquals("Alice", event.get("player"));
		assertEquals(ALICE.toString(), event.get("playerUuid"));
		assertEquals("minecraft:bread", event.get("itemId"));
		assertEquals(3, event.get("count"));
		assertEquals(true, event.get("inferred"));
		assertEquals(java.util.Map.of("x", 2.8, "y", 65.3, "z", 0.0), event.get("position"));
		assertTrue(observe(3, List.of(offer(3)), List.of(PLAYER)).isEmpty());
	}
	@Test void waitsForStackMetadataButUsesOriginalThrowGeometry() {
		baseline();
		assertTrue(observe(2, List.of(offer(0)), List.of(PLAYER)).isEmpty());
		var moved = item(new Vec3d(1.5, 65, 0), new Vec3d(-.2, -.1, 0), 4, 3);
		assertEquals(1, observe(5, List.of(moved), List.of()).size());
	}
	@Test void ignoresExistingItemsOnJoinAndReset() {
		assertTrue(observe(1, List.of(offer(3)), List.of(PLAYER)).isEmpty());
		assertTrue(observe(2, List.of(offer(3)), List.of(PLAYER)).isEmpty());
		observer.reset();
		assertTrue(observe(3, List.of(offer(3)), List.of(PLAYER)).isEmpty());
	}
	@Test void ignoresSelfDropsAndAmbiguousNearbyPlayers() {
		baseline();
		var self = new ItemOfferObserver.Player(SELF, "self", PLAYER.dropPosition(), PLAYER.look());
		assertTrue(observe(2, List.of(offer(3)), List.of(self)).isEmpty());
		observe(3, List.of(), List.of());
		assertTrue(observe(4, List.of(offer(3)), List.of(PLAYER, self)).isEmpty());
	}
	@Test void rejectsGroundLootStationaryOldAwayAndDistantItems() {
		var invalid = List.of(
			item(new Vec3d(2.8, 64, 0), new Vec3d(-.3, .1, 0), 1, 3),
			item(offer(3).position(), Vec3d.ZERO, 1, 3),
			item(offer(3).position(), offer(3).velocity(), 30, 3),
			item(offer(3).position(), new Vec3d(.3, .1, 0), 1, 3),
			item(new Vec3d(20, 65.3, 0), offer(3).velocity(), 1, 3),
			item(offer(3).position(), new Vec3d(0, .1, .3), 1, 3));
		for (var item : invalid) {
			observer.reset();
			baseline();
			assertTrue(observe(2, List.of(item), List.of(PLAYER)).isEmpty(), item.toString());
		}
	}
	@Test void expiresMissingMetadataAndIgnoresDimensionBaseline() {
		baseline();
		observe(2, List.of(offer(0)), List.of(PLAYER));
		assertTrue(observe(10, List.of(offer(3)), List.of(PLAYER)).isEmpty());
		assertTrue(observer.observe(11, "minecraft:the_nether", SELF, SELF_POSITION,
			List.of(PLAYER), List.of(offer(3))).isEmpty());
	}
}
