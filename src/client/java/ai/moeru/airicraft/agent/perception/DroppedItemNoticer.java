package ai.moeru.airicraft.agent.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The honest half of item noticing: an item entity the player can see, within radius, the first time for that
 * entity. Items younger than {@link #SETTLE_TICKS} wait, so the offer inference (which decides within its first
 * ticks) has spoken first and an offered item is marked {@code offered}.
 */
public final class DroppedItemNoticer {
	public static final int SETTLE_TICKS = 10;
	/** Items despawn after 6,000 ticks; remember a little longer. */
	public static final long TTL_TICKS = 7_200L;

	public record Item(UUID uuid, String itemId, int count, double x, double y, double z, int age) {}

	public interface Sight {
		boolean sees(Item item);
	}

	private final NoticedMemory memory = new NoticedMemory();

	public List<PerceptCandidate> sample(long tick, String scope, double eyeX, double eyeY, double eyeZ, int radius, int raycasts,
		List<Item> items, Sight sight, Set<UUID> offered, Predicate<Item> ownMiningDrop) {
		memory.scope(scope);
		var candidates = new ArrayList<PerceptCandidate>();
		var eligible = new ArrayList<Item>();
		for (Item item : items) {
			if (item.age() < SETTLE_TICKS || item.count() <= 0) continue;
			if (distance(item, eyeX, eyeY, eyeZ) > radius) continue;
			if (!memory.noticed("item:" + item.uuid(), tick)) eligible.add(item);
		}
		eligible.sort(Comparator.comparingDouble(item -> distance(item, eyeX, eyeY, eyeZ)));
		int budget = raycasts;
		for (Item item : eligible) {
			if (budget-- <= 0) break;
			if (!sight.sees(item)) continue;
			String key = "item:" + item.uuid();
			memory.notice(key, tick, TTL_TICKS);
			boolean wasOffered = offered.contains(item.uuid());
			var fields = new LinkedHashMap<String, Object>();
			fields.put("itemId", item.itemId());
			fields.put("count", item.count());
			fields.put("x", Compass.round(item.x()));
			fields.put("y", Compass.round(item.y()));
			fields.put("z", Compass.round(item.z()));
			fields.put("distance", Compass.round(distance(item, eyeX, eyeY, eyeZ)));
			fields.put("direction", Compass.direction(item.x() - eyeX, item.z() - eyeZ));
			fields.put("age", item.age());
			fields.put("attribution", wasOffered ? "thrown_by_player" : ownMiningDrop.test(item) ? "own_mining_drop" : "unknown");
			fields.put("offered", wasOffered);
			fields.put("itemEntityUuid", item.uuid().toString());
			candidates.add(new PerceptCandidate(key, "item", fields));
		}
		return candidates;
	}

	public void clear() {
		memory.clear();
	}

	private static double distance(Item item, double x, double y, double z) {
		double dx = item.x() - x, dy = item.y() - y, dz = item.z() - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
