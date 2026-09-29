package ai.moeru.airicraft.agent;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.LinkedHashMap;
import java.util.Map;

final class LocalDamageTracker {
	private static final long INITIAL_SYNC_GRACE_TICKS = 10L;

	private PendingDamageObservation pendingObservation;
	private long lastLifecycleResetTick = Long.MIN_VALUE;

	static boolean shouldCaptureHealthLoss(boolean healthInitialized, float healthBefore, float healthAfter) {
		return Float.isFinite(healthBefore)
			&& Float.isFinite(healthAfter)
			&& healthAfter < healthBefore;
	}

	boolean shouldCaptureHealthLossAfterLifecycle(
		boolean healthInitialized,
		long tick,
		float healthBefore,
		float healthAfter
	) {
		if (!shouldCaptureHealthLoss(healthInitialized, healthBefore, healthAfter)) {
			return false;
		}
		if (healthInitialized) {
			return true;
		}
		if (lastLifecycleResetTick == Long.MIN_VALUE) {
			return true;
		}
		return tick - lastLifecycleResetTick > INITIAL_SYNC_GRACE_TICKS;
	}

	void observeDamageSource(long tick, DamageSource damageSource) {
		if (damageSource == null) {
			return;
		}
		Entity attacker = damageSource.getEntity();
		observeDamage(
			tick,
			damageTypeId(damageSource),
			entityUuid(attacker),
			entityName(attacker),
			entityTypeId(attacker),
			entityTypeId(damageSource.getDirectEntity()),
			attacker instanceof LivingEntity,
			attacker instanceof Player
		);
	}

	void observeDamage(
		long tick,
		String damageTypeId,
		String attackerName,
		String attackerEntityTypeId,
		String directSourceEntityTypeId
	) {
		observeDamage(tick, damageTypeId, null, attackerName, attackerEntityTypeId, directSourceEntityTypeId, attackerEntityTypeId != null, false);
	}

	void observeDamage(
		long tick,
		String damageTypeId,
		String attackerUuid,
		String attackerName,
		String attackerEntityTypeId,
		String directSourceEntityTypeId,
		boolean attackerLiving,
		boolean attackerPlayer
	) {
		pendingObservation = new PendingDamageObservation(
			tick,
			blankToNull(damageTypeId),
			blankToNull(attackerUuid),
			blankToNull(attackerName),
			blankToNull(attackerEntityTypeId),
			blankToNull(directSourceEntityTypeId),
			attackerLiving,
			attackerPlayer
		);
	}

	Map<String, Object> consumeDamage(boolean healthInitialized, long tick, float healthBefore, float healthAfter) {
		if (!shouldCaptureHealthLossAfterLifecycle(healthInitialized, tick, healthBefore, healthAfter)) {
			return null;
		}

		LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
		payload.put("actor", "self");
		payload.put("amount", healthBefore - healthAfter);
		payload.put("healthBefore", healthBefore);
		payload.put("healthAfter", healthAfter);
		payload.put("fatal", healthAfter <= 0.0F);

		if (pendingObservation != null) {
			long age = tick - pendingObservation.captureTick();
			if (age >= 0L) {
				if (age <= 1L) {
					putIfPresent(payload, "damageTypeId", pendingObservation.damageTypeId());
					putIfPresent(payload, "attackerUuid", pendingObservation.attackerUuid());
					putIfPresent(payload, "attackerName", pendingObservation.attackerName());
					putIfPresent(payload, "attackerEntityTypeId", pendingObservation.attackerEntityTypeId());
					putIfPresent(payload, "directSourceEntityTypeId", pendingObservation.directSourceEntityTypeId());
					payload.put("attackerLiving", pendingObservation.attackerLiving());
					payload.put("attackerPlayer", pendingObservation.attackerPlayer());
				}
				pendingObservation = null;
			}
		}

		return Map.copyOf(payload);
	}

	void pruneStale(long tick) {
		if (pendingObservation != null && tick - pendingObservation.captureTick() > 2L) {
			pendingObservation = null;
		}
	}

	void clear() {
		pendingObservation = null;
		lastLifecycleResetTick = Long.MIN_VALUE;
	}

	PendingDamageObservation pendingObservation() {
		return pendingObservation;
	}

	void onLifecycleReset(long tick) {
		pendingObservation = null;
		lastLifecycleResetTick = tick;
	}

	private static String damageTypeId(DamageSource damageSource) {
		if (damageSource == null) {
			return null;
		}
		return damageSource.typeHolder()
			.unwrapKey()
			.map(key -> key.location().toString())
			.orElseGet(damageSource::getMsgId);
	}

	private static String entityName(Entity entity) {
		if (entity == null || entity.getName() == null) {
			return null;
		}
		return blankToNull(entity.getName().getString());
	}

	private static String entityUuid(Entity entity) {
		return entity == null ? null : blankToNull(entity.getStringUUID());
	}

	private static String entityTypeId(Entity entity) {
		if (entity == null || entity.getType() == null) {
			return null;
		}
		return blankToNull(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
	}

	private static void putIfPresent(Map<String, Object> payload, String key, Object value) {
		if (value == null) {
			return;
		}
		String text = String.valueOf(value);
		if (!text.isBlank()) {
			payload.put(key, value);
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	record PendingDamageObservation(
		long captureTick,
		String damageTypeId,
		String attackerUuid,
		String attackerName,
		String attackerEntityTypeId,
		String directSourceEntityTypeId,
		boolean attackerLiving,
		boolean attackerPlayer
	) {
	}
}
