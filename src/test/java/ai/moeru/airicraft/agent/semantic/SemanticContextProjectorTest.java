package ai.moeru.airicraft.agent.semantic;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticContextProjectorTest {
	private final SemanticContextProjector projector = new SemanticContextProjector();

	@Test
	void preservesCombatOutcomeInSemanticContext() {
		var event = new SemanticEvent(1, 20, 1000, "reflex.resolved", Map.of(
			"reason", "no_eligible_threats", "combatSummary", Map.of("text", "Confirmed dead (1): Zombie; still alive (0).")));
		var result = projector.project(new SemanticEventQueryResult(1, 1, false, List.of(event)), 1000);
		assertEquals(1, result.updates().size());
		assertTrue(result.updates().getFirst().text().contains("Confirmed dead (1): Zombie"));
	}

	@Test
	void preservesPossibleOfferAsUnconfirmedInContext() {
		var event = new SemanticEvent(1, 20, 1000, "social.item_offered",
			Map.of("player", "Alice", "itemId", "minecraft:bread", "count", 3, "inferred", true));
		var result = projector.project(new SemanticEventQueryResult(1, 1, false, List.of(event)), 1000);
		assertEquals(1, result.updates().size());
		assertTrue(result.updates().getFirst().text().contains("Alice"));
		assertTrue(result.updates().getFirst().text().contains("3x minecraft:bread"));
		assertTrue(result.updates().getFirst().text().contains("possibly offered"));
	}

	@Test
	void coalescesRepeatedPickupEventsIntoSingleUpdate() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			1L,
			3L,
			false,
			List.of(
				itemEvent(1L, 100L, 1_000L, "pickup.item_picked_up", "minecraft:dirt", 1),
				itemEvent(2L, 101L, 1_010L, "pickup.item_picked_up", "minecraft:dirt", 1),
				itemEvent(3L, 102L, 1_020L, "pickup.item_picked_up", "minecraft:dirt", 1)
			)
		), 1_020L);

		assertEquals(1, result.updates().size());
		SemanticContextUpdate update = result.updates().getFirst();
		assertEquals(3, update.sourceEventCount());
		assertEquals(1L, update.firstSourceSeqNo());
		assertEquals(3L, update.lastSourceSeqNo());
		assertTrue(update.text().contains("3x minecraft:dirt"));
		assertEquals(3L, result.latestObservedSeqNo());
	}

	@Test
	void preservesFirstSeenOrderAcrossMixedBatches() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			1L,
			4L,
			false,
			List.of(
				itemEvent(1L, 100L, 1_000L, "pickup.item_picked_up", "minecraft:dirt", 1),
				itemEvent(2L, 101L, 1_010L, "pickup.item_picked_up", "minecraft:cobblestone", 1),
				itemEvent(3L, 102L, 1_020L, "pickup.item_picked_up", "minecraft:dirt", 1),
				playerEvent(4L, 103L, 1_030L, "social.player_joined_game", "Alice")
			)
		), 1_030L);

		assertEquals(3, result.updates().size());
		assertTrue(result.updates().get(0).text().contains("2x minecraft:dirt"));
		assertTrue(result.updates().get(1).text().contains("1x minecraft:cobblestone"));
		assertTrue(result.updates().get(2).text().contains("Alice joined the game"));
	}

	@Test
	void doesNotMergeAcrossDifferentEventTypes() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			1L,
			2L,
			false,
			List.of(
				itemEvent(1L, 100L, 1_000L, "pickup.item_picked_up", "minecraft:dirt", 1),
				itemEvent(2L, 101L, 1_010L, "crafting.item_crafted", "minecraft:dirt", 1)
			)
		), 1_010L);

		assertEquals(2, result.updates().size());
		assertTrue(result.updates().get(0).text().contains("picked up 1x minecraft:dirt"));
		assertTrue(result.updates().get(1).text().contains("crafted 1x minecraft:dirt"));
	}

	@Test
	void dedupesRepeatedPlayerJoinEventsByPlayer() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			1L,
			2L,
			false,
			List.of(
				playerEvent(1L, 100L, 1_000L, "social.player_joined_game", "Alice"),
				playerEvent(2L, 101L, 1_010L, "social.player_joined_game", "Alice")
			)
		), 1_010L);

		assertEquals(1, result.updates().size());
		assertEquals(2, result.updates().getFirst().sourceEventCount());
		assertTrue(result.updates().getFirst().text().contains("Alice joined the game"));
	}

	@Test
	void keepsNonAggregatedStateTransitionsSeparate() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			1L,
			2L,
			false,
			List.of(
				playerEvent(1L, 100L, 1_000L, "follow.target_acquired", "Alice"),
				playerEvent(2L, 101L, 1_010L, "follow.target_lost", "Alice")
			)
		), 1_010L);

		assertEquals(2, result.updates().size());
		assertTrue(result.updates().get(0).text().contains("Started following Alice"));
		assertTrue(result.updates().get(1).text().contains("Lost the follow target Alice"));
	}

	@Test
	void emitsDroppedContextNoticeWhenBatchWasTruncated() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			4L,
			5L,
			true,
			List.of(
				itemEvent(4L, 100L, 1_000L, "pickup.item_picked_up", "minecraft:dirt", 1),
				itemEvent(5L, 101L, 1_010L, "pickup.item_picked_up", "minecraft:dirt", 2)
			)
		), 1_010L);

		assertEquals(2, result.updates().size());
		assertTrue(result.updates().get(0).text().contains("dropped before they could be summarized"));
		assertTrue(result.updates().get(1).text().contains("3x minecraft:dirt"));
		assertEquals(5L, result.latestObservedSeqNo());
	}

	@Test
	void coalescesRepeatedDamageEventsIntoSingleUpdateWithLatestHealth() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			1L,
			3L,
			false,
			List.of(
				damageEvent(1L, 100L, 1_000L, 2.0F, 18.0F, "minecraft:mob_attack", "Zombie"),
				damageEvent(2L, 101L, 1_010L, 1.5F, 16.5F, "minecraft:mob_attack", "Zombie"),
				damageEvent(3L, 102L, 1_020L, 1.0F, 15.5F, "minecraft:mob_attack", "Zombie")
			)
		), 1_020L);

		assertEquals(1, result.updates().size());
		SemanticContextUpdate update = result.updates().getFirst();
		assertEquals(3, update.sourceEventCount());
		assertEquals(4.5F, ((Number) update.payload().get("amount")).floatValue());
		assertEquals(20.0F, ((Number) update.payload().get("healthBefore")).floatValue());
		assertEquals(15.5F, ((Number) update.payload().get("healthAfter")).floatValue());
		assertTrue(update.text().contains("Zombie"));
	}

	@Test
	void batchesTorchPlacementsByPolicyRevisionWithoutTriggerSemantics() {
		SemanticContextProjectionResult result = projector.project(new SemanticEventQueryResult(
			1L,
			2L,
			false,
			List.of(
				lightingEvent(1L, 100L, 1_000L, 4, 50),
				lightingEvent(2L, 120L, 2_000L, 8, 49)
			)
		), 2_000L);

		assertEquals(1, result.updates().size());
		SemanticContextUpdate update = result.updates().getFirst();
		assertEquals(2, update.sourceEventCount());
		assertTrue(update.text().contains("placed 2 torches"));
		assertTrue(update.text().contains("latest at 8, 64, 0"));
	}

	private static SemanticEvent itemEvent(long seqNo, long tick, long timestampMs, String type, String itemId, int count) {
		return new SemanticEvent(seqNo, tick, timestampMs, type, Map.of(
			"actor", "self",
			"itemId", itemId,
			"count", count
		));
	}

	private static SemanticEvent playerEvent(long seqNo, long tick, long timestampMs, String type, String player) {
		return new SemanticEvent(seqNo, tick, timestampMs, type, Map.of("player", player));
	}

	private static SemanticEvent damageEvent(
		long seqNo,
		long tick,
		long timestampMs,
		float amount,
		float healthAfter,
		String damageTypeId,
		String attackerName
	) {
		return new SemanticEvent(seqNo, tick, timestampMs, "combat.damage_taken", Map.of(
			"actor", "self",
			"amount", amount,
			"healthBefore", healthAfter + amount,
			"healthAfter", healthAfter,
			"fatal", false,
			"damageTypeId", damageTypeId,
			"attackerName", attackerName,
			"attackerEntityTypeId", "minecraft:zombie",
			"directSourceEntityTypeId", "minecraft:zombie"
		));
	}

	private static SemanticEvent lightingEvent(long seqNo, long tick, long timestampMs, int x, int offhandCount) {
		return new SemanticEvent(seqNo, tick, timestampMs, "lighting.torch_placed", Map.of(
			"policyRevision", 1L,
			"mode", "darkness",
			"x", x,
			"y", 64,
			"z", 0,
			"offhandCount", offhandCount,
			"lightLevelBefore", 0
		));
	}
}
