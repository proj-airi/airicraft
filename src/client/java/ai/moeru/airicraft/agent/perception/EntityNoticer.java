package ai.moeru.airicraft.agent.perception;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The honest half of entity noticing: a living entity entering perception range with line of sight ({@code seen}),
 * with enter and exit hysteresis. Each entry becomes a candidate once per {@link #TTL_TICKS}; an entity that leaves
 * after having been a candidate becomes an {@code entity_lost} candidate, and the salience rules keep only those
 * they had turned into percepts. Hostiles the reflex tracks are flagged so the rules can leave them to it.
 */
public final class EntityNoticer {
	public static final long TTL_TICKS = 6_000L;

	public record Entity(UUID uuid, String entityType, String name, boolean named, boolean tamed, boolean baby, boolean hostile,
		double x, double y, double z, String mainHand) {}

	public interface Sight {
		boolean sees(Entity entity);
	}

	private final NoticedMemory memory = new NoticedMemory();
	private Hysteresis<UUID> hysteresis;
	private final Map<UUID, Entity> offered = new HashMap<>();

	public List<PerceptCandidate> sample(long tick, String scope, double eyeX, double eyeY, double eyeZ, int enterRange, int exitRange,
		int raycasts, List<Entity> entities, Sight sight, Set<String> reflexTracked) {
		memory.scope(scope);
		if (hysteresis == null) hysteresis = new Hysteresis<>(enterRange, exitRange);
		var sorted = new ArrayList<>(entities);
		sorted.sort(Comparator.comparingDouble(entity -> distance(entity, eyeX, eyeY, eyeZ)));
		var samples = new LinkedHashMap<UUID, Hysteresis.Sample>();
		var byUuid = new HashMap<UUID, Entity>();
		int budget = raycasts;
		for (Entity entity : sorted) {
			double distance = distance(entity, eyeX, eyeY, eyeZ);
			if (distance > exitRange) continue;
			byUuid.put(entity.uuid(), entity);
			boolean lineOfSight = false;
			// Tracked entities stay without line of sight; only a possible entry spends a raycast.
			if (!hysteresis.tracking(entity.uuid()) && distance <= enterRange && budget > 0) {
				budget--;
				lineOfSight = sight.sees(entity);
			}
			samples.put(entity.uuid(), new Hysteresis.Sample(distance, lineOfSight));
		}
		var update = hysteresis.update(samples);
		var candidates = new ArrayList<PerceptCandidate>();
		for (UUID uuid : update.entered()) {
			Entity entity = byUuid.get(uuid);
			if (!memory.notice("entity:" + uuid, tick, TTL_TICKS)) continue;
			offered.put(uuid, entity);
			candidates.add(new PerceptCandidate("entity:" + uuid + "@" + tick, "entity", fields(entity, eyeX, eyeY, eyeZ, reflexTracked)));
		}
		for (UUID uuid : update.exited()) {
			Entity entity = offered.remove(uuid);
			if (entity == null) continue;
			var fields = new LinkedHashMap<String, Object>();
			fields.put("entityType", entity.entityType());
			fields.put("uuid", uuid.toString());
			fields.put("name", entity.name());
			candidates.add(new PerceptCandidate("entity_lost:" + uuid + "@" + tick, "entity_lost", fields));
		}
		return candidates;
	}

	public void clear() {
		memory.clear();
		offered.clear();
		if (hysteresis != null) hysteresis.clear();
	}

	private static Map<String, Object> fields(Entity entity, double eyeX, double eyeY, double eyeZ, Set<String> reflexTracked) {
		var fields = new LinkedHashMap<String, Object>();
		fields.put("entityType", entity.entityType());
		fields.put("uuid", entity.uuid().toString());
		fields.put("name", entity.name());
		fields.put("named", entity.named());
		fields.put("tamed", entity.tamed());
		fields.put("baby", entity.baby());
		fields.put("hostile", entity.hostile());
		fields.put("reflexTracked", reflexTracked.contains(entity.uuid().toString()));
		fields.put("distance", Compass.round(distance(entity, eyeX, eyeY, eyeZ)));
		fields.put("direction", Compass.direction(entity.x() - eyeX, entity.z() - eyeZ));
		fields.put("x", Compass.round(entity.x()));
		fields.put("y", Compass.round(entity.y()));
		fields.put("z", Compass.round(entity.z()));
		if (entity.mainHand() != null) fields.put("equipment", Map.of("mainHand", entity.mainHand()));
		return fields;
	}

	private static double distance(Entity entity, double x, double y, double z) {
		double dx = entity.x() - x, dy = entity.y() - y, dz = entity.z() - z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}
}
