package ai.moeru.airicraft.agent.social;

import ai.moeru.airicraft.agent.events.EventPublisher;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ChatIngestService {
	private static final String SOURCE = "ChatIngestService";
	public void ingest(
		String senderName,
		String plainTextMessage,
		long tick,
		NearbyPlayerTracker nearbyPlayerTracker,
		PrimaryInteractionResolver primaryInteractionResolver,
		EventPublisher eventBuffer
	) {
		Objects.requireNonNull(senderName, "senderName");
		Objects.requireNonNull(plainTextMessage, "plainTextMessage");
		Objects.requireNonNull(nearbyPlayerTracker, "nearbyPlayerTracker");
		Objects.requireNonNull(primaryInteractionResolver, "primaryInteractionResolver");
		Objects.requireNonNull(eventBuffer, "eventBuffer");

		String normalizedMessage = normalize(plainTextMessage);
		eventBuffer.from(SOURCE).publish(tick, "social.player_spoke", Map.of(
			"player", senderName,
			"message", plainTextMessage,
			"normalizedMessage", normalizedMessage
		));
		Optional<NearbyPlayerSnapshot> nearbyPlayer = nearbyPlayerTracker.findByName(senderName);
		nearbyPlayer.ifPresent(player -> primaryInteractionResolver.onPlayerSpoke(player, tick));

		if (isAddressedToAgent(plainTextMessage)) {
			eventBuffer.from(SOURCE).publish(tick, "social.player_addressed_agent", Map.of(
				"player", senderName,
				"message", plainTextMessage,
				"normalizedMessage", normalizedMessage
			));
		}
	}

	public void injectMessage(
		String senderName,
		String plainTextMessage,
		long tick,
		NearbyPlayerTracker nearbyPlayerTracker,
		PrimaryInteractionResolver primaryInteractionResolver,
		EventPublisher eventBuffer
	) {
		ingest(senderName, plainTextMessage, tick, nearbyPlayerTracker, primaryInteractionResolver, eventBuffer);
	}

	public void ingestSystemMessage(String plainTextMessage, long tick, EventPublisher eventBuffer) {
		Objects.requireNonNull(plainTextMessage, "plainTextMessage");
		Objects.requireNonNull(eventBuffer, "eventBuffer");

		String normalizedMessage = normalize(plainTextMessage);
		eventBuffer.from(SOURCE).publish(tick, "social.system_message", Map.of(
			"message", plainTextMessage,
			"normalizedMessage", normalizedMessage
		));
	}

	public static boolean isAddressedToAgent(String plainTextMessage) {
		String normalizedMessage = normalize(plainTextMessage);
		return normalizedMessage.regionMatches(true, 0, "@agent", 0, "@agent".length());
	}

	public static String normalize(String plainTextMessage) {
		return plainTextMessage.stripLeading();
	}
}
