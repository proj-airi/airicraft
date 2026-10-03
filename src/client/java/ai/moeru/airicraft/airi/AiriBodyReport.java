package ai.moeru.airicraft.airi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Turns body state and agent events into the events that AIRI reads: {@code context:update}, {@code spark:notify} and
 * {@code spark:emit}. It does not touch Minecraft or the link, so tests can drive it directly. One thread uses it.
 */
public final class AiriBodyReport {
	public record Outgoing(String type, JsonObject data) {}

	/** What the status lane says about the body. Position is in blocks. */
	public record BodyFacts(boolean inWorld, boolean dead, String dimension, int x, int y, int z, float health, float maxHealth,
		int food, boolean plannerOn) {}

	static final String STATUS_CONTEXT_ID = "airicraft:status";
	static final String CHAT_CONTEXT_ID = "airicraft:chat";
	static final long STATUS_MIN_INTERVAL_MS = 5_000L;
	static final long STATUS_REFRESH_MS = 60_000L;
	static final long DAMAGE_MIN_INTERVAL_MS = 20_000L;
	static final int MAX_CHAT_LENGTH = 300;
	/** AIRI stage windows. The Mineflayer bot uses the same destination. */
	static final List<String> STAGE_DESTINATIONS = List.of("proj-airi:stage-*");

	private final Supplier<String> ids;
	private String lastStatus;
	private long lastStatusAtMs = Long.MIN_VALUE;
	private long lastDamageAtMs = Long.MIN_VALUE;
	private String commandId;
	private String commandWorkId;
	private String currentWorkId;
	private String currentWorkLabel;

	public AiriBodyReport(Supplier<String> ids) {
		this.ids = Objects.requireNonNull(ids, "ids");
	}

	/** Makes the next status go out. Call it when the link becomes ready again. */
	public void resend() {
		lastStatus = null;
	}

	/** The status lane. It goes out when the text changes, at most once per interval, and again after a long quiet time. */
	public List<Outgoing> status(BodyFacts facts, long nowMs) {
		String text = statusText(facts);
		boolean changed = !text.equals(lastStatus);
		long elapsed = lastStatusAtMs == Long.MIN_VALUE ? Long.MAX_VALUE : nowMs - lastStatusAtMs;
		if (lastStatus != null && !(changed && elapsed >= STATUS_MIN_INTERVAL_MS) && elapsed < STATUS_REFRESH_MS) {
			return List.of();
		}
		lastStatus = text;
		lastStatusAtMs = nowMs;
		return List.of(contextUpdate(STATUS_CONTEXT_ID, "status", "replace-self", text));
	}

	String statusText(BodyFacts facts) {
		if (!facts.inWorld()) {
			return "The Minecraft body is not in a world.";
		}
		StringBuilder text = new StringBuilder("The Minecraft body is in ").append(facts.dimension());
		if (facts.dead()) {
			return text.append(" and is dead.").toString();
		}
		text.append(" at ").append(facts.x()).append(' ').append(facts.y()).append(' ').append(facts.z()).append('.');
		text.append(" Health ").append(number(facts.health())).append(" of ").append(number(facts.maxHealth()));
		text.append(", food ").append(facts.food()).append(" of 20.");
		if (!facts.plannerOn()) {
			text.append(" The planner is off.");
		}
		else if (currentWorkLabel != null) {
			text.append(" Working on ").append(currentWorkLabel).append('.');
		}
		else {
			text.append(" No work is running.");
		}
		return text.toString();
	}

	/** One chat line in the game, said by a player or by the body. */
	public List<Outgoing> chat(String speaker, String message) {
		if (message == null || message.isBlank()) {
			return List.of();
		}
		String line = speaker + ": " + message.strip();
		if (line.length() > MAX_CHAT_LENGTH) line = line.substring(0, MAX_CHAT_LENGTH - 3) + "...";
		return List.of(contextUpdate(CHAT_CONTEXT_ID, "chat", "append-self", line));
	}

	/** The planner took a command. A command that was still open is dropped, because the new one replaces it. */
	public List<Outgoing> commandAccepted(String nextCommandId) {
		List<Outgoing> result = new ArrayList<>();
		if (commandId != null) {
			result.add(emit(commandId, "dropped", "A newer command replaced it."));
		}
		commandId = nextCommandId;
		commandWorkId = null;
		result.add(emit(nextCommandId, "queued", "The planner has the command."));
		return result;
	}

	public Outgoing commandRefused(String refusedCommandId, String reason) {
		return emit(refusedCommandId, "dropped", "The body cannot take commands now: " + reason + ".");
	}

	/** The open command is dropped, for example when the runtime reloads. */
	public List<Outgoing> dropOpenCommand(String reason) {
		if (commandId == null) {
			return List.of();
		}
		Outgoing dropped = emit(commandId, "dropped", reason);
		commandId = null;
		commandWorkId = null;
		return List.of(dropped);
	}

	/** One agent event. Only the event types below make output. */
	public List<Outgoing> event(String type, Map<String, ?> payload, long nowMs) {
		return switch (type) {
			case "player.died" -> List.of(notify("alarm", "immediate", "The Minecraft body died.",
				"Dimension: " + text(payload, "dimensionId", "unknown") + "."));
			case "combat.damage_taken" -> damage(payload, nowMs);
			case "work.changed" -> work(payload);
			case "social.player_spoke", "social.player_addressed_agent" -> chat(text(payload, "player", "A player"), text(payload, "message", ""));
			default -> List.of();
		};
	}

	private List<Outgoing> damage(Map<String, ?> payload, long nowMs) {
		if (lastDamageAtMs != Long.MIN_VALUE && nowMs - lastDamageAtMs < DAMAGE_MIN_INTERVAL_MS) {
			return List.of();
		}
		lastDamageAtMs = nowMs;
		String attacker = text(payload, "attackerName", text(payload, "damageTypeId", "an unknown source"));
		String note = "Source: " + attacker + ". Health now: " + number(payload.get("healthAfter")) + ".";
		return List.of(notify("alarm", "soon", "The Minecraft body took damage.", note));
	}

	/**
	 * Command progress follows the first top-level work that starts after the command. Later work does not change it.
	 * A top-level failure that no command owns becomes an alarm.
	 */
	private List<Outgoing> work(Map<String, ?> payload) {
		if (!text(payload, "parentWorkId", "").isEmpty()) {
			return List.of();
		}
		String workId = text(payload, "workId", "");
		String state = text(payload, "state", "");
		String label = text(payload, "label", "work");
		boolean terminal = state.equals("SUCCEEDED") || state.equals("FAILED") || state.equals("CANCELLED");
		if (!terminal && Boolean.TRUE.equals(payload.get("foreground"))) {
			currentWorkId = workId;
			currentWorkLabel = label;
		}
		else if (terminal && workId.equals(currentWorkId)) {
			currentWorkId = null;
			currentWorkLabel = null;
		}
		if (commandId != null && commandWorkId == null && !terminal && Boolean.TRUE.equals(payload.get("foreground"))) {
			commandWorkId = workId;
			return List.of(emit(commandId, "working", "Started " + label + "."));
		}
		if (commandId != null && workId.equals(commandWorkId) && terminal) {
			String id = commandId;
			commandId = null;
			commandWorkId = null;
			return switch (state) {
				case "SUCCEEDED" -> List.of(emit(id, "done", "Finished " + label + "."));
				case "FAILED" -> List.of(emit(id, "blocked", "Failed " + label + failure(payload)));
				default -> List.of(emit(id, "dropped", "Cancelled " + label + "."));
			};
		}
		if (state.equals("FAILED") && Boolean.TRUE.equals(payload.get("foreground"))) {
			return List.of(notify("alarm", "soon", "Work in Minecraft failed: " + label + ".", failure(payload).substring(1).strip()));
		}
		return List.of();
	}

	private static String failure(Map<String, ?> payload) {
		if (payload.get("details") instanceof Map<?, ?> details) {
			for (String key : List.of("failure", "blockedReason", "reason", "message")) {
				Object value = details.get(key);
				if (value != null && !value.toString().isBlank()) return ": " + value + ".";
			}
		}
		return ".";
	}

	private Outgoing contextUpdate(String contextId, String lane, String strategy, String text) {
		JsonObject data = new JsonObject();
		data.addProperty("id", ids.get());
		data.addProperty("contextId", contextId);
		data.addProperty("lane", lane);
		data.addProperty("strategy", strategy);
		data.addProperty("text", text);
		return new Outgoing("context:update", data);
	}

	private Outgoing notify(String kind, String urgency, String headline, String note) {
		JsonObject data = new JsonObject();
		data.addProperty("id", ids.get());
		data.addProperty("eventId", ids.get());
		data.addProperty("lane", "game");
		data.addProperty("kind", kind);
		data.addProperty("urgency", urgency);
		data.addProperty("headline", headline);
		if (!note.isBlank()) data.addProperty("note", note);
		data.add("destinations", destinations());
		return new Outgoing("spark:notify", data);
	}

	private Outgoing emit(String forCommandId, String state, String note) {
		JsonObject data = new JsonObject();
		data.addProperty("id", ids.get());
		data.addProperty("eventId", forCommandId);
		data.addProperty("state", state);
		data.addProperty("note", note);
		data.add("destinations", destinations());
		return new Outgoing("spark:emit", data);
	}

	private static JsonArray destinations() {
		JsonArray result = new JsonArray();
		STAGE_DESTINATIONS.forEach(result::add);
		return result;
	}

	private static String text(Map<String, ?> payload, String key, String fallback) {
		Object value = payload.get(key);
		return value == null || value.toString().isBlank() ? fallback : value.toString();
	}

	private static String number(Object value) {
		return value instanceof Number number ? String.format(Locale.ROOT, "%.0f", number.doubleValue()) : "?";
	}
}
