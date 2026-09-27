package ai.moeru.airicraft.agent.semantic;

import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventQueryResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SemanticContextProjector {
	private static final String DROPPED_CONTEXT_NOTICE = "Some earlier context events were dropped before they could be summarized.";

	public SemanticContextProjectionResult project(SemanticEventQueryResult queryResult, long anchorTimeMs) {
		if (queryResult == null) {
			return SemanticContextProjectionResult.empty(0L);
		}

		ArrayList<SemanticContextUpdate> updates = new ArrayList<>();
		List<SemanticEvent> events = queryResult.events();
		if (queryResult.truncated()) {
			SemanticEvent firstEvent = events.isEmpty() ? null : events.getFirst();
			updates.add(new SemanticContextUpdate(
				SemanticContextUpdateKind.NOTICE,
				DROPPED_CONTEXT_NOTICE,
				firstEvent == null ? -1L : firstEvent.tick(),
				firstEvent == null ? anchorTimeMs : firstEvent.timestampMs(),
				0,
				firstEvent == null ? 0L : firstEvent.seqNo(),
				firstEvent == null ? 0L : firstEvent.seqNo(),
				null,
				Map.of(),
				null
			));
		}

		LinkedHashMap<String, AggregationAccumulator> groups = new LinkedHashMap<>();
		for (SemanticEvent event : events) {
			Objects.requireNonNull(event, "event");
			AggregationStrategy strategy = AggregationStrategy.forType(event.type());
			if (strategy == AggregationStrategy.SKIP) {
				continue;
			}

			String key = strategy.groupKey(event);
			AggregationAccumulator existing = groups.get(key);
			if (existing == null) {
				groups.put(key, AggregationAccumulator.start(strategy, key, event));
				continue;
			}
			existing.absorb(event);
		}

		for (AggregationAccumulator accumulator : groups.values()) {
			AggregatedSemanticEvent aggregatedEvent = accumulator.finish();
			String text = SemanticContextUpdateFormatter.format(aggregatedEvent, anchorTimeMs);
			if (text == null || text.isBlank()) {
				continue;
			}
			updates.add(new SemanticContextUpdate(
				SemanticContextUpdateKind.NOTICE,
				text,
				aggregatedEvent.tick(),
				aggregatedEvent.timestampMs(),
				aggregatedEvent.sourceEventCount(),
				aggregatedEvent.firstSourceSeqNo(),
				aggregatedEvent.lastSourceSeqNo(),
				aggregatedEvent.type(),
				aggregatedEvent.payload(),
				accumulator.key()
			));
		}

		return new SemanticContextProjectionResult(updates, Math.max(0L, queryResult.latestSeqNo()));
	}

	private enum AggregationStrategy {
		SINGLE,
		SUM_COUNT_BY_TYPE_ACTOR_ITEM,
		SUM_LIGHTING_BY_POLICY,
		SUM_DAMAGE_BY_CONTEXT,
		DEDUPE_BY_TYPE_PLAYER,
		DEDUP_BY_TYPE,
		SKIP;

		private String groupKey(SemanticEvent event) {
			return switch (this) {
				case SINGLE -> event.type() + "#seq:" + event.seqNo();
				case SUM_COUNT_BY_TYPE_ACTOR_ITEM -> event.type() + "|actor=" + value(event, "actor") + "|itemId=" + value(event, "itemId");
				case SUM_LIGHTING_BY_POLICY -> event.type() + "|policyRevision=" + value(event, "policyRevision") + "|mode=" + value(event, "mode");
				case SUM_DAMAGE_BY_CONTEXT -> event.type()
					+ "|actor=" + value(event, "actor")
					+ "|damageTypeId=" + value(event, "damageTypeId")
					+ "|attackerName=" + value(event, "attackerName")
					+ "|attackerEntityTypeId=" + value(event, "attackerEntityTypeId")
					+ "|directSourceEntityTypeId=" + value(event, "directSourceEntityTypeId");
				case DEDUPE_BY_TYPE_PLAYER -> event.type() + "|player=" + value(event, "player");
				case DEDUP_BY_TYPE -> event.type();
				case SKIP -> "skip#" + event.seqNo();
			};
		}

		private static AggregationStrategy forType(String eventType) {
			if (eventType == null || eventType.isBlank()) {
				return SKIP;
			}
			return switch (eventType) {
				case "pickup.item_picked_up", "crafting.item_crafted" -> SUM_COUNT_BY_TYPE_ACTOR_ITEM;
				case "lighting.torch_placed" -> SUM_LIGHTING_BY_POLICY;
				case "combat.damage_taken" -> SUM_DAMAGE_BY_CONTEXT;
				case "social.player_joined_game",
					"social.player_left_game",
					"social.player_joined_nearby",
					"social.player_left_nearby",
					"follow.stuck" -> DEDUPE_BY_TYPE_PLAYER;
				case "planner.reset_requested", "planner.degraded_entered", "planner.degraded_cleared" -> DEDUP_BY_TYPE;
				case "reflex.resolved",
					"social.item_offered",
					"session.world_loaded",
					"session.world_unloaded",
					"session.connection_lost",
					"session.lan_opened",
					"follow.target_acquired",
					"follow.target_lost",
					"planner.goal_set",
					"planner.goal_cleared" -> SINGLE;
				default -> SKIP;
			};
		}

		private static String value(SemanticEvent event, String key) {
			Object value = event.payload().get(key);
			return value == null ? "-" : String.valueOf(value);
		}
	}

	private static final class AggregationAccumulator {
		private final AggregationStrategy strategy;
		private final String key;
		private final String type;
		private final Map<String, Object> payload;
		private int sourceEventCount;
		private long firstSourceSeqNo;
		private long lastSourceSeqNo;
		private long tick;
		private long timestampMs;

		private AggregationAccumulator(
			AggregationStrategy strategy,
			String key,
			String type,
			Map<String, Object> payload,
			int sourceEventCount,
			long firstSourceSeqNo,
			long lastSourceSeqNo,
			long tick,
			long timestampMs
		) {
			this.strategy = strategy;
			this.key = key;
			this.type = type;
			this.payload = payload;
			this.sourceEventCount = sourceEventCount;
			this.firstSourceSeqNo = firstSourceSeqNo;
			this.lastSourceSeqNo = lastSourceSeqNo;
			this.tick = tick;
			this.timestampMs = timestampMs;
		}

		private static AggregationAccumulator start(AggregationStrategy strategy, String key, SemanticEvent event) {
			return new AggregationAccumulator(
				strategy,
				key,
				event.type(),
				new java.util.LinkedHashMap<>(event.payload()),
				1,
				event.seqNo(),
				event.seqNo(),
				event.tick(),
				event.timestampMs()
			);
		}

		private void absorb(SemanticEvent event) {
			sourceEventCount++;
			lastSourceSeqNo = Math.max(lastSourceSeqNo, event.seqNo());
			tick = Math.max(tick, event.tick());
			timestampMs = Math.max(timestampMs, event.timestampMs());

			if (strategy == AggregationStrategy.SUM_COUNT_BY_TYPE_ACTOR_ITEM) {
				payload.put("count", countValue(payload.get("count")) + countValue(event.payload().get("count")));
				return;
			}
			if (strategy == AggregationStrategy.SUM_LIGHTING_BY_POLICY) {
				payload.put("count", countValue(payload.getOrDefault("count", 1)) + 1);
				for (String key : List.of("x", "y", "z", "offhandCount", "torchCount", "activity", "lightLevelBefore", "side", "facing")) {
					if (event.payload().containsKey(key)) {
						payload.put(key, event.payload().get(key));
					}
				}
				return;
			}
			if (strategy == AggregationStrategy.SUM_DAMAGE_BY_CONTEXT) {
				payload.put("amount", floatValue(payload.get("amount")) + floatValue(event.payload().get("amount")));
				if (!payload.containsKey("healthBefore")) {
					payload.put("healthBefore", event.payload().get("healthBefore"));
				}
				payload.put("healthAfter", event.payload().get("healthAfter"));
				payload.put("fatal", booleanValue(payload.get("fatal")) || booleanValue(event.payload().get("fatal")));
				return;
			}
			if (strategy == AggregationStrategy.DEDUPE_BY_TYPE_PLAYER) {
				mergeDistinctValue("player", event.payload().get("player"));
			}
		}

		private void mergeDistinctValue(String key, Object candidate) {
			if (candidate == null || String.valueOf(candidate).isBlank()) {
				return;
			}
			Object existing = payload.get(key);
			if (existing == null || String.valueOf(existing).isBlank()) {
				payload.put(key, candidate);
				return;
			}
			if (String.valueOf(existing).equals(String.valueOf(candidate))) {
				return;
			}
			LinkedHashSet<String> values = new LinkedHashSet<>();
			values.add(String.valueOf(existing));
			values.add(String.valueOf(candidate));
			payload.put(key, String.join(", ", values));
		}

		private AggregatedSemanticEvent finish() {
			return new AggregatedSemanticEvent(
				type,
				Map.copyOf(payload),
				tick,
				timestampMs,
				sourceEventCount,
				firstSourceSeqNo,
				lastSourceSeqNo
			);
		}

		private String key() {
			return key;
		}

		private static int countValue(Object value) {
			if (value instanceof Number number) {
				return Math.max(1, number.intValue());
			}
			if (value == null) {
				return 1;
			}
			try {
				return Math.max(1, Integer.parseInt(String.valueOf(value)));
			}
			catch (NumberFormatException ignored) {
				return 1;
			}
		}

		private static float floatValue(Object value) {
			if (value instanceof Number number) {
				return number.floatValue();
			}
			if (value == null) {
				return 0.0F;
			}
			try {
				return Float.parseFloat(String.valueOf(value));
			}
			catch (NumberFormatException ignored) {
				return 0.0F;
			}
		}

		private static boolean booleanValue(Object value) {
			if (value instanceof Boolean bool) {
				return bool;
			}
			return value != null && Boolean.parseBoolean(String.valueOf(value));
		}
	}
}
