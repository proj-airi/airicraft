package ai.moeru.airicraft.agent.events;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Infers possible offers from client observations; never confirms ownership or pickup. */
public final class ItemOfferObserver {
	public record Player(UUID uuid, String name, Vec3d dropPosition, Vec3d look) {}
	public record Item(UUID uuid, String itemId, int count, Vec3d position, Vec3d velocity, int age) {}
	private record Candidate(Player player, Item spawn, long tick) {}
	private final Set<UUID> seen = new HashSet<>();
	private final Map<UUID, Candidate> pending = new HashMap<>();
	private String dimension;

	public List<Map<String, Object>> observe(long tick, String dimension, UUID self, Vec3d selfPosition,
		List<Player> players, List<Item> items) {
		Set<UUID> present = new HashSet<>();
		for (Item item : items) present.add(item.uuid());
		if (!dimension.equals(this.dimension)) {
			reset();
			this.dimension = dimension;
			seen.addAll(present); // Loading a world is not evidence of a fresh drop.
			return List.of();
		}
		seen.retainAll(present);
		pending.entrySet().removeIf(entry -> !present.contains(entry.getKey()) || tick - entry.getValue().tick() > 5);
		List<Map<String, Object>> events = new ArrayList<>();
		for (Item item : items) {
			if (seen.add(item.uuid()) && item.age() <= 5 && headsTowardSelf(item, selfPosition)) {
				// Include self and players facing elsewhere when checking ambiguity: proximity alone cannot identify a thrower.
				List<Player> sources = players.stream()
					.filter(player -> player.dropPosition().squaredDistanceTo(item.position()) <= 1.0)
					.toList();
				if (sources.size() == 1) {
					Player source = sources.getFirst();
					if (!source.uuid().equals(self) && horizontalAlignment(source.look(), item.velocity()) >= .7) {
						pending.put(item.uuid(), new Candidate(source, item, tick));
					}
				}
			}
			Candidate candidate = pending.get(item.uuid());
			// Entity spawn and tracked item-stack metadata arrive separately. Preserve the initial geometry.
			if (candidate == null || item.count() <= 0) continue;
			pending.remove(item.uuid());
			Map<String, Object> payload = new LinkedHashMap<>();
			payload.put("player", candidate.player().name());
			payload.put("playerUuid", candidate.player().uuid().toString());
			payload.put("itemEntityUuid", item.uuid().toString());
			payload.put("itemId", item.itemId());
			payload.put("count", item.count());
			payload.put("position", coordinates(item.position()));
			payload.put("spawnPosition", coordinates(candidate.spawn().position()));
			payload.put("velocity", coordinates(candidate.spawn().velocity()));
			payload.put("dimension", dimension);
			payload.put("inferred", true);
			payload.put("observationTick", tick);
			events.add(Map.copyOf(payload));
		}
		return List.copyOf(events);
	}

	private static boolean headsTowardSelf(Item item, Vec3d self) {
		Vec3d delta = self.subtract(item.position());
		double speed = Math.hypot(item.velocity().x, item.velocity().z);
		double distance = Math.hypot(delta.x, delta.z);
		if (speed < .1 || distance > 6 || Math.abs(delta.y) > 2.5) return false;
		double forward = (delta.x * item.velocity().x + delta.z * item.velocity().z) / speed;
		double missDistance = Math.abs(delta.x * item.velocity().z - delta.z * item.velocity().x) / speed;
		return forward > 0 && missDistance <= 1.5 && horizontalAlignment(delta, item.velocity()) >= .7;
	}

	private static double horizontalAlignment(Vec3d a, Vec3d b) {
		double length = Math.hypot(a.x, a.z) * Math.hypot(b.x, b.z);
		return length == 0 ? 0 : (a.x * b.x + a.z * b.z) / length;
	}

	private static Map<String, Object> coordinates(Vec3d position) {
		return Map.of("x", position.x, "y", position.y, "z", position.z);
	}

	public void reset() {
		seen.clear();
		pending.clear();
		dimension = null;
	}
}
