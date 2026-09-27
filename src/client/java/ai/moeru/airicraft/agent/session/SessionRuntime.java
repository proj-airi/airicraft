package ai.moeru.airicraft.agent.session;

import ai.moeru.airicraft.agent.events.EventPublisher;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.integrated.IntegratedServer;

import java.util.Map;

public final class SessionRuntime {
	private static final String SOURCE = "SessionRuntime";
	private SessionSnapshot snapshot = SessionSnapshot.initial();

	public void onClientStarted(MinecraftClient client, long tick, EventPublisher eventBuffer) {
		snapshot = deriveSnapshot(client, tick);
		if (!snapshot.clientBooted()) {
			snapshot = snapshot.withClientBooted(true);
		}
	}

	public SessionSnapshot poll(MinecraftClient client, long tick, EventPublisher eventBuffer) {
		SessionSnapshot nextSnapshot = deriveSnapshot(client, tick).withClientBooted(true);
		emitTransitions(snapshot, nextSnapshot, tick, eventBuffer);
		snapshot = nextSnapshot;
		return snapshot;
	}

	public void onWorldLeave(long tick, EventPublisher eventBuffer) {
		if (snapshot.worldLoaded()) {
			if (snapshot.mode() == SessionMode.REMOTE_MULTIPLAYER) {
				eventBuffer.from(SOURCE).publish(tick, "session.connection_lost", Map.of(
					"mode", snapshot.mode().name()
				));
			}
			eventBuffer.from(SOURCE).publish(tick, "session.world_unloaded", Map.of(
				"mode", snapshot.mode().name()
			));
		}

		snapshot = snapshot
			.withWorldLoaded(false)
			.withPlayerLifecycleState(PlayerLifecycleState.UNAVAILABLE)
			.withMode(SessionMode.OUT_OF_WORLD)
			.withDimensionId(null)
			.withLanPublished(false)
			.withLanPort(0)
			.withTickCount(tick);
	}

	public SessionSnapshot snapshot() {
		return snapshot;
	}

	public SessionSnapshot onPlayerDied(long tick, EventPublisher eventBuffer) {
		if (!snapshot.worldLoaded() || snapshot.requiresRespawn()) {
			return snapshot;
		}
		snapshot = snapshot
			.withPlayerLifecycleState(PlayerLifecycleState.DEAD)
			.withTickCount(tick);
		eventBuffer.from(SOURCE).publish(tick, "player.died", Map.of(
			"mode", snapshot.mode().name(),
			"dimensionId", snapshot.dimensionId()
		));
		return snapshot;
	}

	public SessionSnapshot onPlayerRespawned(long tick, EventPublisher eventBuffer) {
		if (!snapshot.requiresRespawn()) {
			return snapshot;
		}
		snapshot = snapshot
			.withPlayerLifecycleState(PlayerLifecycleState.ALIVE)
			.withTickCount(tick);
		eventBuffer.from(SOURCE).publish(tick, "player.respawned", Map.of(
			"mode", snapshot.mode().name(),
			"dimensionId", snapshot.dimensionId()
		));
		return snapshot;
	}

	private static SessionSnapshot deriveSnapshot(MinecraftClient client, long tick) {
		if (client == null) {
			return SessionSnapshot.initial().withTickCount(tick);
		}

		boolean worldLoaded = client.world != null && client.player != null;
		String dimensionId = worldLoaded ? String.valueOf(client.world.getRegistryKey().getValue()) : null;
		IntegratedServer server = client.getServer();
		int lanPort = worldLoaded && server != null ? server.getServerPort() : 0;
		boolean lanPublished = worldLoaded && client.isInSingleplayer() && lanPort > 0;
		SessionMode mode;
		if (!worldLoaded) {
			mode = SessionMode.OUT_OF_WORLD;
		}
		else if (!client.isInSingleplayer() || client.getCurrentServerEntry() != null) {
			mode = SessionMode.REMOTE_MULTIPLAYER;
		}
		else if (lanPublished) {
			mode = SessionMode.SINGLEPLAYER_LAN_HOST;
		}
		else {
			mode = SessionMode.SINGLEPLAYER_LOCAL;
		}

		PlayerLifecycleState playerLifecycleState = worldLoaded && (client.player.isDead() || client.player.getHealth() <= 0.0F)
			? PlayerLifecycleState.DEAD
			: worldLoaded ? PlayerLifecycleState.ALIVE : PlayerLifecycleState.UNAVAILABLE;
		return new SessionSnapshot(mode, true, worldLoaded, dimensionId, lanPublished, lanPort, tick, playerLifecycleState);
	}

	private static void emitTransitions(
		SessionSnapshot previousSnapshot,
		SessionSnapshot nextSnapshot,
		long tick,
		EventPublisher eventBuffer
	) {
		if (!previousSnapshot.worldLoaded() && nextSnapshot.worldLoaded()) {
			eventBuffer.from(SOURCE).publish(tick, "session.world_loaded", Map.of(
				"mode", nextSnapshot.mode().name(),
				"dimensionId", nextSnapshot.dimensionId()
			));
		}

		if (previousSnapshot.worldLoaded() && !nextSnapshot.worldLoaded()) {
			eventBuffer.from(SOURCE).publish(tick, "session.world_unloaded", Map.of(
				"mode", previousSnapshot.mode().name()
			));
		}

		if (!previousSnapshot.lanPublished() && nextSnapshot.lanPublished()) {
			eventBuffer.from(SOURCE).publish(tick, "session.lan_opened", Map.of(
				"port", nextSnapshot.lanPort()
			));
		}

		if (!previousSnapshot.requiresRespawn() && nextSnapshot.requiresRespawn()) {
			eventBuffer.from(SOURCE).publish(tick, "player.died", Map.of(
				"mode", nextSnapshot.mode().name(),
				"dimensionId", nextSnapshot.dimensionId()
			));
		}

		if (previousSnapshot.requiresRespawn() && nextSnapshot.playerLifecycleState() == PlayerLifecycleState.ALIVE) {
			eventBuffer.from(SOURCE).publish(tick, "player.respawned", Map.of(
				"mode", nextSnapshot.mode().name(),
				"dimensionId", nextSnapshot.dimensionId()
			));
		}
	}
}
