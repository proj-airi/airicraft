package ai.moeru.airicraft.agent.semantic;

import ai.moeru.airicraft.agent.llm.RelativeTimeFormatter;

import java.util.Locale;
import java.util.Map;

public final class SemanticContextUpdateFormatter {
	private SemanticContextUpdateFormatter() {
	}

	static String format(AggregatedSemanticEvent event, long anchorTimeMs) {
		String relativeTime = RelativeTimeFormatter.format(event.timestampMs(), anchorTimeMs);
		return switch (event.type()) {
			case "session.world_loaded" -> {
				Object dimension = event.payload().get("dimensionId");
				yield dimension == null
					? "A world loaded " + relativeTime + "."
					: "A world loaded " + relativeTime + " in " + dimension + ".";
			}
			case "session.world_unloaded" -> "The world unloaded " + relativeTime + ".";
			case "session.connection_lost" -> "The world connection was lost " + relativeTime + ".";
			case "session.lan_opened" -> {
				Object port = event.payload().get("port");
				yield port == null
					? "LAN sharing opened " + relativeTime + "."
					: "LAN sharing opened " + relativeTime + " on port " + port + ".";
			}
			case "crafting.item_crafted" -> actor(event.payload()) + " crafted " + itemCount(event.payload()) + "x " + itemId(event.payload()) + " " + relativeTime + ".";
			case "combat.damage_taken" -> {
				String actor = actor(event.payload());
				String amount = decimalValue(event.payload(), "amount", 0.0F);
				String healthAfter = decimalValue(event.payload(), "healthAfter", 0.0F);
				Object attackerName = event.payload().get("attackerName");
				Object damageTypeId = event.payload().get("damageTypeId");
				if (attackerName != null && !String.valueOf(attackerName).isBlank()) {
					yield actor + " took " + amount + " damage from " + attackerName + " and dropped to " + healthAfter + " health " + relativeTime + ".";
				}
				if (damageTypeId != null && !String.valueOf(damageTypeId).isBlank()) {
					yield actor + " took " + amount + " damage from " + damageTypeId + " and dropped to " + healthAfter + " health " + relativeTime + ".";
				}
				yield actor + " took " + amount + " damage and dropped to " + healthAfter + " health " + relativeTime + ".";
			}
			case "reflex.resolved" -> event.payload().get("combatSummary") instanceof Map<?, ?> summary
				? "Survival reflex resolved " + relativeTime + ". " + summary.get("text")
				: "Survival reflex resolved " + relativeTime + ": " + event.payload().get("reason") + ".";
			case "social.item_offered" -> playerName(event.payload()) + " possibly offered " + itemCount(event.payload())
				+ "x " + itemId(event.payload()) + " toward you " + relativeTime + "; inferred, pickup unconfirmed.";
			case "pickup.item_picked_up" -> actor(event.payload()) + " picked up " + itemCount(event.payload()) + "x " + itemId(event.payload()) + " " + relativeTime + ".";
			case "lighting.torch_placed" -> {
				int count = itemCount(event.payload());
				Object mode = event.payload().get("mode");
				Object x = event.payload().get("x");
				Object y = event.payload().get("y");
				Object z = event.payload().get("z");
				String location = x == null || y == null || z == null ? "" : " (latest at " + x + ", " + y + ", " + z + ")";
				yield "The lighting reflex placed " + count + " torch" + (count == 1 ? "" : "es")
					+ " under the " + (mode == null ? "active" : mode) + " policy" + location + " " + relativeTime + ".";
			}
			case "social.player_joined_game" -> playerName(event.payload()) + " joined the game " + relativeTime + ".";
			case "social.player_left_game" -> playerName(event.payload()) + " left the game " + relativeTime + ".";
			case "social.player_joined_nearby" -> playerName(event.payload()) + " came nearby " + relativeTime + ".";
			case "social.player_left_nearby" -> playerName(event.payload()) + " left nearby " + relativeTime + ".";
			case "follow.target_acquired" -> "Started following " + playerName(event.payload()) + " " + relativeTime + ".";
			case "follow.target_lost" -> "Lost the follow target " + playerName(event.payload()) + " " + relativeTime + ".";
			case "follow.stuck" -> "Movement got stuck while following " + playerName(event.payload()) + " " + relativeTime + ".";
			case "planner.goal_set" -> "The planner set goal " + goalName(event.payload()) + " " + relativeTime + ".";
			case "planner.goal_cleared" -> "The planner cleared goal " + goalName(event.payload()) + " " + relativeTime + ".";
			case "planner.degraded_entered" -> "The planner entered degraded mode " + relativeTime + ".";
			case "planner.degraded_cleared" -> "The planner recovered from degraded mode " + relativeTime + ".";
			case "planner.reset_requested" -> "A planner reset was requested " + relativeTime + ".";
			default -> null;
		};
	}

	private static String playerName(Map<String, Object> payload) {
		Object player = payload.get("player");
		return player == null || String.valueOf(player).isBlank() ? "the active player" : String.valueOf(player);
	}

	private static String goalName(Map<String, Object> payload) {
		Object goalType = payload.get("goalType");
		Object targetPlayer = payload.get("targetPlayer");
		if (goalType == null) {
			return "the current goal";
		}
		if (targetPlayer == null || String.valueOf(targetPlayer).isBlank()) {
			return String.valueOf(goalType);
		}
		return goalType + " for " + targetPlayer;
	}

	private static String itemId(Map<String, Object> payload) {
		Object itemId = payload.get("itemId");
		if (itemId == null || String.valueOf(itemId).isBlank()) {
			return "an item";
		}
		return String.valueOf(itemId);
	}

	private static int itemCount(Map<String, Object> payload) {
		Object count = payload.get("count");
		if (count instanceof Number number) {
			return Math.max(1, number.intValue());
		}
		if (count == null) {
			return 1;
		}
		try {
			return Math.max(1, Integer.parseInt(String.valueOf(count)));
		}
		catch (NumberFormatException ignored) {
			return 1;
		}
	}

	private static String actor(Map<String, Object> payload) {
		Object actor = payload.get("actor");
		if (actor == null || String.valueOf(actor).isBlank()) {
			return "Someone";
		}
		String actorValue = String.valueOf(actor);
		if ("self".equals(actorValue)) {
			return "You";
		}
		return actorValue;
	}

	private static String decimalValue(Map<String, Object> payload, String key, float fallback) {
		Object value = payload.get(key);
		float numeric = fallback;
		if (value instanceof Number number) {
			numeric = number.floatValue();
		}
		else if (value != null) {
			try {
				numeric = Float.parseFloat(String.valueOf(value));
			}
			catch (NumberFormatException ignored) {
				numeric = fallback;
			}
		}
		if (Math.abs(numeric - Math.round(numeric)) < 0.001F) {
			return Integer.toString(Math.round(numeric));
		}
		String text = String.format(Locale.ROOT, "%.2f", numeric);
		int trimIndex = text.length();
		while (trimIndex > 0 && text.charAt(trimIndex - 1) == '0') {
			trimIndex--;
		}
		if (trimIndex > 0 && text.charAt(trimIndex - 1) == '.') {
			trimIndex--;
		}
		return text.substring(0, trimIndex);
	}
}

record AggregatedSemanticEvent(
	String type,
	Map<String, Object> payload,
	long tick,
	long timestampMs,
	int sourceEventCount,
	long firstSourceSeqNo,
	long lastSourceSeqNo
) {
}
