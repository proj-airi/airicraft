package ai.moeru.airicraft.agent.social;

import ai.moeru.airicraft.agent.events.EventPublisher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class NearbyPlayerTracker {
	private static final String SOURCE = "NearbyPlayerTracker";
	private static final double DEFAULT_NEARBY_RADIUS = 32.0D;

	private final double nearbyRadius;
	private final Map<UUID, NearbyPlayerSnapshot> injectedPlayers = new LinkedHashMap<>();
	private final Map<String, UUID> injectedPlayerIds = new LinkedHashMap<>();
	private final Map<UUID, NearbyPlayerSnapshot> nearbyPlayers = new LinkedHashMap<>();

	public NearbyPlayerTracker() {
		this(DEFAULT_NEARBY_RADIUS);
	}

	public NearbyPlayerTracker(double nearbyRadius) {
		this.nearbyRadius = nearbyRadius;
	}

	public void poll(Minecraft minecraft, long tick, EventPublisher eventBuffer) {
		Map<UUID, NearbyPlayerSnapshot> nextNearby = new LinkedHashMap<>();

		if (minecraft != null && minecraft.level != null && minecraft.player != null) {
			LocalPlayer self = minecraft.player;
			Vec3 selfPos = new Vec3(self.getX(), self.getY(), self.getZ());
			double nearbyRadiusSquared = nearbyRadius * nearbyRadius;
			for (AbstractClientPlayer player : minecraft.level.players()) {
				if (player == self) {
					continue;
				}

				if (player.distanceToSqr(selfPos) > nearbyRadiusSquared) {
					continue;
				}

				nextNearby.put(player.getUUID(), new NearbyPlayerSnapshot(
					player.getUUID(),
					player.getName().getString(),
					player.getX(),
					player.getY(),
					player.getZ(),
					false
				));
			}
		}

		nextNearby.putAll(injectedPlayers);
		replaceNearbyPlayers(nextNearby, tick, eventBuffer);
	}

	public void injectPlayerNearby(String playerName, Vec3 pos, long tick, EventPublisher eventBuffer) {
		Objects.requireNonNull(playerName, "playerName");
		Objects.requireNonNull(pos, "pos");
		UUID playerUuid = injectedPlayerIds.computeIfAbsent(playerName, ignored -> UUID.randomUUID());
		injectedPlayers.put(playerUuid, new NearbyPlayerSnapshot(
			playerUuid,
			playerName,
			pos.x,
			pos.y,
			pos.z,
			true
		));
		Map<UUID, NearbyPlayerSnapshot> nextNearby = new LinkedHashMap<>(nearbyPlayers);
		nextNearby.put(playerUuid, injectedPlayers.get(playerUuid));
		replaceNearbyPlayers(nextNearby, tick, eventBuffer);
	}

	public void injectPlayerMove(String playerName, Vec3 pos, long tick, EventPublisher eventBuffer) {
		injectPlayerNearby(playerName, pos, tick, eventBuffer);
	}

	public void injectPlayerDisconnect(String playerName, long tick, EventPublisher eventBuffer) {
		UUID playerUuid = injectedPlayerIds.get(playerName);
		if (playerUuid == null) {
			return;
		}

		injectedPlayers.remove(playerUuid);
		Map<UUID, NearbyPlayerSnapshot> nextNearby = new LinkedHashMap<>(nearbyPlayers);
		nextNearby.remove(playerUuid);
		replaceNearbyPlayers(nextNearby, tick, eventBuffer);
	}

	public void clear(long tick, EventPublisher eventBuffer) {
		Map<UUID, NearbyPlayerSnapshot> nextNearby = Map.of();
		replaceNearbyPlayers(nextNearby, tick, eventBuffer);
		injectedPlayers.clear();
		injectedPlayerIds.clear();
	}

	public List<NearbyPlayerSnapshot> snapshot() {
		return List.copyOf(nearbyPlayers.values());
	}

	public Optional<NearbyPlayerSnapshot> findByName(String playerName) {
		for (NearbyPlayerSnapshot player : nearbyPlayers.values()) {
			if (player.name().equals(playerName)) {
				return Optional.of(player);
			}
		}
		return Optional.empty();
	}

	public boolean isNearby(UUID playerUuid) {
		return nearbyPlayers.containsKey(playerUuid);
	}

	private void replaceNearbyPlayers(Map<UUID, NearbyPlayerSnapshot> nextNearby, long tick, EventPublisher eventBuffer) {
		for (NearbyPlayerSnapshot previous : nearbyPlayers.values()) {
			if (!nextNearby.containsKey(previous.uuid())) {
				eventBuffer.from(SOURCE).publish(tick, "social.player_left_nearby", Map.of(
					"player", previous.name()
				));
			}
		}

		for (NearbyPlayerSnapshot current : nextNearby.values()) {
			if (!nearbyPlayers.containsKey(current.uuid())) {
				eventBuffer.from(SOURCE).publish(tick, "social.player_joined_nearby", Map.of(
					"player", current.name(),
					"x", current.x(),
					"y", current.y(),
					"z", current.z()
				));
			}
		}

		nearbyPlayers.clear();
		nearbyPlayers.putAll(nextNearby);
	}
}
