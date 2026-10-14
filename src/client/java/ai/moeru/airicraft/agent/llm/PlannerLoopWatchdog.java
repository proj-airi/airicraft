package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Watches the planner's own turns for loops that the work watchdog cannot see: autonomous turns that keep coming while
 * nothing in the world changes, a tool that keeps failing the same way, and running work the planner keeps cancelling
 * to start over. The first notice is advisory. A
 * no-progress loop that outlives it holds autonomous wakes for a cooldown that doubles per hold; the player's
 * guidance or real progress clears it.
 */
public final class PlannerLoopWatchdog {
	/** Turns with the same tool names while nothing changed. */
	public static final int REPEAT_TURNS = 3;
	/** Turns of any kind while nothing changed. */
	public static final int STREAK_TURNS = 8;
	/** Further no-progress turns after the notice before autonomous wakes are held. */
	public static final int HOLD_AFTER_TURNS = 3;
	/** Identical failures of one tool before a notice, and between later notices. */
	public static final int REPEAT_FAILURES = 3;
	/** Times the planner cancelled its own running work of one tool before a notice, and between later notices. */
	public static final int REPEAT_ABANDONS = 3;
	public static final long HOLD_TICKS = 1_200L;
	public static final long MAX_HOLD_TICKS = 12_000L;
	public static final String NO_PROGRESS = "no_progress";
	public static final String REPEATED_FAILURE = "repeated_failure";
	public static final String ABANDONED_WORK = "abandoned_work";
	private static final String CANCELLED = "Cancelled by clear_queue";
	private static final int MAX_FAILURE_KEYS = 64;
	private static final int MAX_RESULT_CHARS = 240;

	/**
	 * One finding. {@code holdTicks > 0} starts a hold of that length; {@code released} ends one. {@code tools} are the
	 * repeated tool names, {@code result} the repeated failure.
	 */
	public record Notice(String kind, int count, List<String> tools, String result, long holdTicks, boolean released) {
		public Notice {
			tools = List.copyOf(tools);
		}

		public boolean hold() {
			return holdTicks > 0L;
		}
	}

	private Object baseline;
	private boolean hasBaseline;
	private int streak;
	private final Map<String, Integer> signatures = new HashMap<>();
	private int noticedAt = -1;
	private int holds;
	private boolean holdActive;
	private long holdUntil = Long.MIN_VALUE;
	private final LinkedHashMap<String, Integer> failures = new LinkedHashMap<>();
	private final Map<String, Integer> abandons = new HashMap<>();

	/**
	 * Records one accepted planner turn. {@code state} is what counts as progress when it changes (inventory, place,
	 * goal), not work that was started or cancelled; {@code null} means it is unknown, and the turn is ignored, as is
	 * a turn without tool calls.
	 */
	public Notice onTurn(long tick, boolean directGuidance, List<String> toolNames, Object state) {
		if (directGuidance) {
			clearStreak(state);
			holds = 0;
			return null;
		}
		// A reply without tool calls is the planner waiting for the next event, which is what a loop should become.
		if (state == null || toolNames.isEmpty()) return null;
		if (!hasBaseline || !Objects.equals(baseline, state)) {
			if (hasBaseline) holds = 0;
			clearStreak(state);
		}
		streak++;
		var names = new TreeSet<>(toolNames);
		int repeats = signatures.merge(String.join("+", names), 1, Integer::sum);
		if (noticedAt < 0) {
			if (repeats < REPEAT_TURNS && streak < STREAK_TURNS) return null;
			noticedAt = streak;
			return new Notice(NO_PROGRESS, streak, List.copyOf(names), null, 0L, false);
		}
		if (streak - noticedAt < HOLD_AFTER_TURNS) return null;
		noticedAt = streak;
		long ticks = Math.min(MAX_HOLD_TICKS, HOLD_TICKS << Math.min(holds, 16));
		holds++;
		holdActive = true;
		holdUntil = tick + ticks;
		return new Notice(NO_PROGRESS, streak, List.copyOf(names), null, ticks, false);
	}

	/**
	 * Records one finished tool call. Any result that is neither a failure nor a cancellation clears that tool's
	 * failure and cancellation history.
	 */
	public Notice onResult(String toolName, String resultText) {
		if (toolName == null || resultText == null || resultText.startsWith(CANCELLED)) return null;
		String prefix = toolName + "\n";
		if (!isFailure(resultText)) {
			failures.keySet().removeIf(key -> key.startsWith(prefix));
			abandons.remove(toolName);
			return null;
		}
		String key = prefix + normalize(resultText);
		int count = failures.merge(key, 1, Integer::sum);
		while (failures.size() > MAX_FAILURE_KEYS) failures.remove(failures.keySet().iterator().next());
		if (count % REPEAT_FAILURES != 0) return null;
		return new Notice(REPEATED_FAILURE, count, List.of(toolName), abbreviate(resultText), 0L, false);
	}

	/**
	 * Records the planner cancelling its own running work ({@code clear_queue} with that call active). Starting
	 * over and over changes work state without getting anywhere, so it is counted per tool until the tool succeeds.
	 */
	public Notice onAbandoned(String toolName) {
		if (toolName == null) return null;
		int count = abandons.merge(toolName, 1, Integer::sum);
		if (count % REPEAT_ABANDONS != 0) return null;
		return new Notice(ABANDONED_WORK, count, List.of(toolName), null, 0L, false);
	}

	/**
	 * Checks the progress state while a hold is on: work that was already running may still get somewhere without a
	 * planner turn. A change ends the hold at once, as progress between turns would, and {@link #release} reports it.
	 */
	public void observeState(Object state) {
		if (!holdActive || holdUntil == Long.MIN_VALUE || state == null || Objects.equals(baseline, state)) return;
		clearStreak(state);
		holds = 0;
	}

	/** Autonomous wakes are held until {@code tick} reaches the end of the cooldown. */
	public boolean holding(long tick) {
		return holdActive && tick < holdUntil;
	}

	/**
	 * Ends a hold once, when its cooldown elapsed or progress or guidance cleared it. An elapsed hold keeps the
	 * loop's streak, so repeating the loop holds again for twice as long.
	 */
	public Notice release(long tick) {
		if (!holdActive || tick < holdUntil) return null;
		holdActive = false;
		return new Notice(NO_PROGRESS, streak, List.of(), null, 0L, true);
	}

	public void reset() {
		clearStreak(null);
		hasBaseline = false;
		holds = 0;
		holdActive = false;
		failures.clear();
		abandons.clear();
	}

	private void clearStreak(Object state) {
		baseline = state;
		hasBaseline = state != null;
		streak = 0;
		signatures.clear();
		noticedAt = -1;
		holdUntil = Long.MIN_VALUE;
	}

	static boolean isFailure(String text) {
		if (text.startsWith("TOOL_ERROR") || text.startsWith("TOOL_UNAVAILABLE")) return true;
		JsonObject json = json(text);
		if (json == null) return false;
		if (falseValue(json, "accepted") || falseValue(json, "ok") || falseValue(json, "success")) return true;
		if ("FAILED".equals(string(json, "state"))) return true;
		return !string(json, "failureCode").isBlank() || !string(json, "failure").isBlank() || !string(json, "error").isBlank();
	}

	/** Ids, ticks and ages differ between otherwise identical failures, so they are left out of the comparison. */
	static String normalize(String text) {
		JsonObject json = json(text);
		if (json == null) return text.replaceAll("\\d{4,}", "#").strip();
		return strip(json).toString();
	}

	private static JsonElement strip(JsonElement element) {
		if (element.isJsonObject()) {
			var stripped = new JsonObject();
			var keys = new TreeSet<>(element.getAsJsonObject().keySet());
			for (String key : keys) if (!volatileKey(key)) stripped.add(key, strip(element.getAsJsonObject().get(key)));
			return stripped;
		}
		if (element.isJsonArray()) {
			var stripped = new com.google.gson.JsonArray();
			element.getAsJsonArray().forEach(value -> stripped.add(strip(value)));
			return stripped;
		}
		return element;
	}

	private static boolean volatileKey(String key) {
		// Item and block ids (minecraft:stick) are evidence, not bookkeeping.
		if (key.indexOf(':') >= 0) return false;
		return key.equals("id") || key.endsWith("Id") || key.endsWith("Ids") || key.equals("tick") || key.equals("ticks")
			|| key.endsWith("Tick") || key.endsWith("Ticks") || key.startsWith("age") || key.endsWith("Seconds")
			|| key.endsWith("Millis") || key.endsWith("Ms") || key.equals("seqNo");
	}

	/** Tool results are {@code Tool result for name: {json}} receipts or {@code Work outcome: {json}}. */
	private static JsonObject json(String text) {
		int start = text.indexOf('{');
		if (start < 0) return null;
		String prefix = text.substring(0, start);
		if (!(prefix.isBlank() || prefix.startsWith("Tool result for ") || prefix.startsWith("Work outcome"))) return null;
		try {
			JsonElement parsed = JsonParser.parseString(text.substring(start));
			return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
		} catch (RuntimeException malformed) {
			return null;
		}
	}

	private static boolean falseValue(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean();
	}

	private static String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value == null || value.isJsonNull() ? "" : value.isJsonPrimitive() ? value.getAsString() : value.toString();
	}

	private static String abbreviate(String text) {
		String flat = text.replaceAll("\\s+", " ").strip();
		return flat.length() <= MAX_RESULT_CHARS ? flat : flat.substring(0, MAX_RESULT_CHARS - 3) + "...";
	}

	/** Names of the calls that make up one turn, for {@link #onTurn}. */
	public static List<String> toolNames(List<PlannerToolCall> calls) {
		var names = new ArrayList<String>();
		for (PlannerToolCall call : calls) names.add(call.name());
		return names;
	}
}
