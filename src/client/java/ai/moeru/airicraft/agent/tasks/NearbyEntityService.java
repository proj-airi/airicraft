package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class NearbyEntityService {
	public static final int DEFAULT_MAX_RESULTS = 32;
	public static final int PLANNER_UUID_MIN_LENGTH = 8;

	private NearbyEntityService() {
	}

	public static List<NearbyEntitySnapshot> listNearbyEntities(Minecraft minecraft) {
		return listNearbyEntities(minecraft, EntitySelectorResolver.DEFAULT_NEARBY_RADIUS_BLOCKS, DEFAULT_MAX_RESULTS, Set.of());
	}

	public static List<NearbyEntitySnapshot> listNearbyEntities(Minecraft minecraft, double radius, int maxResults, Set<String> entityTypeIds) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return List.of();
		}
		return listNearbyEntities(
			minecraft.player,
			minecraft.level.entitiesForRendering(),
			radius,
			maxResults,
			entityTypeIds
		);
	}

	static EntitySelectorResolver.EntityCandidate toCandidate(Entity entity) {
		Objects.requireNonNull(entity, "entity");
		return new EntitySelectorResolver.EntityCandidate(
			entity.getId(),
			entity.getStringUUID(),
			entity.getName() == null ? null : entity.getName().getString(),
			BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(),
			entity.getX(),
			entity.getY(),
			entity.getZ(),
			entity.isAlive()
		);
	}

	static List<NearbyEntitySnapshot> listNearbyEntities(
		Entity self,
		Iterable<? extends Entity> entities,
		double nearbyRadius,
		int maxResults
	) {
		return listNearbyEntities(self, entities, nearbyRadius, maxResults, Set.of());
	}

	private static List<NearbyEntitySnapshot> listNearbyEntities(
		Entity self, Iterable<? extends Entity> entities, double nearbyRadius, int maxResults, Set<String> entityTypeIds
	) {
		if (self == null || entities == null) {
			return List.of();
		}
		ArrayList<NearbyEntitySnapshot> snapshots = new ArrayList<>();
		for (Entity entity : entities) {
			if (entity == null || entity == self || entity.isRemoved()) {
				continue;
			}
			EntitySelectorResolver.EntityCandidate candidate = toCandidate(entity);
			if (!EntitySelectorResolver.isWithinRadius(
				self.getX(),
				self.getY(),
				self.getZ(),
				candidate.x(),
				candidate.y(),
				candidate.z(),
				nearbyRadius
			)) {
				continue;
			}
			double distance = Math.sqrt(candidate.squaredDistanceTo(self.getX(), self.getY(), self.getZ()));
			Float health = null;
			Float maxHealth = null;
			Boolean baby = null;
			if (entity instanceof LivingEntity livingEntity) {
				health = livingEntity.getHealth();
				maxHealth = livingEntity.getMaxHealth();
				baby = livingEntity.isBaby();
			}
			snapshots.add(new NearbyEntitySnapshot(
				candidate.entityId(),
				candidate.uuid(),
				candidate.name(),
				candidate.entityTypeId(),
				candidate.x(),
				candidate.y(),
				candidate.z(),
				distance,
				candidate.alive(),
				health,
				maxHealth,
				baby,
				entity instanceof Player
			));
		}
		return selectSnapshots(snapshots, entityTypeIds, maxResults);
	}

	static List<NearbyEntitySnapshot> selectSnapshots(List<NearbyEntitySnapshot> snapshots, Set<String> entityTypeIds, int maxResults) {
		return snapshots.stream()
			.filter(snapshot -> entityTypeIds.isEmpty() || entityTypeIds.contains(snapshot.entityTypeId()))
			.sorted(Comparator.comparingDouble(NearbyEntitySnapshot::distance)
				.thenComparing(snapshot -> safeText(snapshot.entityTypeId()))
				.thenComparing(snapshot -> safeText(snapshot.name()))
				.thenComparing(snapshot -> safeText(snapshot.uuid())))
			.limit(maxResults > 0 ? maxResults : Long.MAX_VALUE)
			.toList();
	}

	private static String safeText(String value) {
		return value == null ? "" : value;
	}

	public static String plannerUuidToken(String uuid) {
		String trimmed = uuid == null ? null : uuid.trim();
		if (trimmed == null || trimmed.isEmpty()) {
			return "";
		}
		if (trimmed.length() <= PLANNER_UUID_MIN_LENGTH) {
			return trimmed;
		}
		return trimmed.substring(0, PLANNER_UUID_MIN_LENGTH);
	}

	public static Map<String, String> plannerUuidTokens(List<NearbyEntitySnapshot> nearbyEntities) {
		if (nearbyEntities == null || nearbyEntities.isEmpty()) {
			return Map.of();
		}
		List<String> normalizedUuids = nearbyEntities.stream()
			.map(NearbyEntitySnapshot::uuid)
			.filter(Objects::nonNull)
			.map(NearbyEntityService::normalizedUuid)
			.filter(Objects::nonNull)
			.distinct()
			.toList();
		LinkedHashMap<String, String> tokens = new LinkedHashMap<>();
		for (NearbyEntitySnapshot snapshot : nearbyEntities) {
			if (snapshot == null || snapshot.uuid() == null || tokens.containsKey(snapshot.uuid())) {
				continue;
			}
			tokens.put(snapshot.uuid(), uniquePlannerUuidToken(snapshot.uuid(), normalizedUuids));
		}
		return Map.copyOf(tokens);
	}

	private static String uniquePlannerUuidToken(String uuid, List<String> normalizedUuids) {
		String trimmed = uuid == null ? null : uuid.trim();
		String normalized = normalizedUuid(trimmed);
		if (trimmed == null || trimmed.isEmpty() || normalized == null) {
			return "";
		}
		int length = Math.min(Math.max(PLANNER_UUID_MIN_LENGTH, 1), normalized.length());
		while (length < normalized.length()) {
			String prefix = normalized.substring(0, length);
			long matches = normalizedUuids.stream()
				.filter(candidate -> candidate.startsWith(prefix))
				.count();
			if (matches == 1L) {
				return trimmed.substring(0, Math.min(length, trimmed.length()));
			}
			length++;
		}
		return trimmed;
	}

	private static String normalizedUuid(String uuid) {
		String trimmed = uuid == null ? null : uuid.trim();
		return trimmed == null || trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
	}

	public record NearbyEntitySnapshot(
		int entityId,
		String uuid,
		String name,
		String entityTypeId,
		double x,
		double y,
		double z,
		double distance,
		boolean alive,
		Float health,
		Float maxHealth,
		Boolean baby,
		boolean isPlayer
	) {
		public String compactDescription() {
			return compactDescription(plannerUuidToken(uuid));
		}

		public String compactDescription(String uuidToken) {
			StringBuilder builder = new StringBuilder("{")
				.append("uuid=").append(uuidToken == null || uuidToken.isBlank() ? uuid : uuidToken)
				.append(", name=").append(name)
				.append(", entityTypeId=").append(entityTypeId)
				.append(", distance=").append(format(distance))
				.append(", alive=").append(alive);
			if (isPlayer) {
				builder.append(", player=true");
			}
			if (health != null) {
				builder.append(", health=").append(format(health.doubleValue()));
			}
			if (maxHealth != null) {
				builder.append(", maxHealth=").append(format(maxHealth.doubleValue()));
			}
			if (baby != null) {
				builder.append(", baby=").append(baby);
			}
			builder.append(", pos=")
				.append(format(x)).append(',')
				.append(format(y)).append(',')
				.append(format(z))
				.append('}');
			return builder.toString();
		}

		private static String format(double value) {
			return String.format(Locale.ROOT, "%.1f", value);
		}
	}
}
