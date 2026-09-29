package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Delta-first presentation of {@code observe} state, shared by both planner backends. The first observation, a
 * world change, an event gap, a refresh after compaction, an observation the model asked for itself, and at least
 * every {@link #BASELINE_EVERY_OBSERVATIONS} observations or {@link #BASELINE_EVERY_TICKS} ticks carry the full
 * {@code current} state. Every other one carries {@code currentChanges}: inventory deltas with resulting totals,
 * vitals as before and after, a block-position move, and a JSON Patch for the rest. Velocity and sub-block movement
 * are not reported as changes. A baseline is also sent whenever the changes are larger than the state.
 * <p>
 * The presenter holds only presentation state; canonical observations stay intact in history and the recorder.
 */
public final class ObservationPresenter {
	public static final int BASELINE_EVERY_OBSERVATIONS = 20;
	public static final long BASELINE_EVERY_TICKS = 6_000L;

	private JsonObject previous;
	private JsonElement world;
	private int sinceBaseline;
	private long baselineTick;

	public ObservationPresenter copy() {
		var copy = new ObservationPresenter();
		copy.previous = previous == null ? null : previous.deepCopy();
		copy.world = world == null ? null : world.deepCopy();
		copy.sinceBaseline = sinceBaseline;
		copy.baselineTick = baselineTick;
		return copy;
	}

	/**
	 * The payload to render for this observation: {@code current} for a baseline, otherwise {@code currentChanges}.
	 * {@code explicit} marks an observation the model requested (always a baseline).
	 */
	public JsonObject present(JsonObject observation, boolean explicit) {
		JsonObject payload = observation.deepCopy();
		if (!payload.has("current") || !payload.get("current").isJsonObject()) return payload;
		JsonObject current = payload.getAsJsonObject("current");
		dropRepeatedObjective(payload, current);
		long tick = payload.has("tick") && payload.get("tick").isJsonPrimitive() ? payload.get("tick").getAsLong() : 0L;
		boolean baseline = previous == null || !Objects.equals(world, payload.get("worldSessionId"))
			|| payload.has("missingEventRange") || payload.has("stateBaseline") || explicit
			|| sinceBaseline + 1 >= BASELINE_EVERY_OBSERVATIONS || tick - baselineTick >= BASELINE_EVERY_TICKS;
		payload.remove("stateBaseline");
		JsonObject result = payload;
		if (!baseline) {
			JsonObject changes = changes(previous, current);
			// A long change list can cost more than the state itself; "unchanged" never does.
			if (changes.isEmpty() || changes.toString().length() <= current.toString().length()) result = withChanges(payload, changes);
			else baseline = true;
		}
		previous = current;
		world = payload.get("worldSessionId");
		if (baseline) {
			sinceBaseline = 0;
			baselineTick = tick;
		}
		else sinceBaseline++;
		return result;
	}

	/** The authoritative objective is already current state or a change; an objective event need not repeat it. */
	private static void dropRepeatedObjective(JsonObject payload, JsonObject current) {
		if (!payload.has("events") || !payload.get("events").isJsonArray()) return;
		for (JsonElement item : payload.getAsJsonArray("events")) {
			if (!item.isJsonObject()) continue;
			JsonObject event = item.getAsJsonObject();
			if (!new JsonPrimitive("objective.changed").equals(event.get("type")) || !event.has("payload") || !event.get("payload").isJsonObject()) continue;
			JsonObject evidence = event.getAsJsonObject("payload");
			if (evidence.has("objective") && evidence.get("objective").equals(current.get("objective"))) evidence.remove("objective");
		}
	}

	private static JsonObject withChanges(JsonObject payload, JsonObject changes) {
		var rebuilt = new JsonObject();
		for (var entry : payload.entrySet()) {
			if (entry.getKey().equals("current")) rebuilt.add("currentChanges", changes);
			else rebuilt.add(entry.getKey(), entry.getValue());
		}
		return rebuilt;
	}

	/** Semantic changes from {@code before} to {@code after}; an empty object means unchanged. */
	static JsonObject changes(JsonObject before, JsonObject after) {
		var changes = new JsonObject();
		JsonArray inventory = inventoryChanges(object(before, "inventory"), object(after, "inventory"));
		if (!inventory.isEmpty()) changes.add("inventory", inventory);
		JsonArray vitals = vitalChanges(object(before, "vitals"), object(after, "vitals"));
		if (!vitals.isEmpty()) changes.add("vitals", vitals);
		JsonObject moved = move(object(object(before, "physical"), "position"), object(object(after, "physical"), "position"));
		if (moved != null) changes.add("moved", moved);
		var patch = new JsonArray();
		diff(strip(before), strip(after), "", patch);
		if (!patch.isEmpty()) changes.add("patch", patch);
		return changes;
	}

	private static JsonArray inventoryChanges(JsonObject before, JsonObject after) {
		var changes = new JsonArray();
		var items = new TreeSet<String>();
		if (before != null) items.addAll(before.keySet());
		if (after != null) items.addAll(after.keySet());
		for (String item : items) {
			long was = count(before, item), now = count(after, item);
			if (was == now) continue;
			var change = new JsonObject();
			change.addProperty("itemId", item);
			change.addProperty("change", now - was);
			change.addProperty("total", now);
			changes.add(change);
		}
		return changes;
	}

	private static JsonArray vitalChanges(JsonObject before, JsonObject after) {
		var changes = new JsonArray();
		if (after == null) return changes;
		for (var entry : after.entrySet()) {
			JsonElement was = before == null ? null : before.get(entry.getKey());
			if (entry.getValue().equals(was)) continue;
			var change = new JsonObject();
			change.addProperty("field", entry.getKey());
			if (was != null) change.add("from", was);
			change.add("to", entry.getValue());
			changes.add(change);
		}
		return changes;
	}

	/** A move to another block; movement within a block is noise. */
	private static JsonObject move(JsonObject before, JsonObject after) {
		if (before == null || after == null) return null;
		int[] from = block(before), to = block(after);
		if (from == null || to == null || java.util.Arrays.equals(from, to)) return null;
		var moved = new JsonObject();
		moved.add("from", point(from));
		moved.add("to", point(to));
		return moved;
	}

	private static int[] block(JsonObject position) {
		if (!position.has("x") || !position.has("y") || !position.has("z")) return null;
		return new int[] {(int) Math.floor(position.get("x").getAsDouble()), (int) Math.floor(position.get("y").getAsDouble()),
			(int) Math.floor(position.get("z").getAsDouble())};
	}

	private static JsonObject point(int[] block) {
		var point = new JsonObject();
		point.addProperty("x", block[0]);
		point.addProperty("y", block[1]);
		point.addProperty("z", block[2]);
		return point;
	}

	/** State without the parts reported semantically or treated as noise. */
	private static JsonObject strip(JsonObject state) {
		JsonObject copy = state.deepCopy();
		copy.remove("inventory");
		copy.remove("vitals");
		if (copy.has("physical") && copy.get("physical").isJsonObject()) {
			copy.getAsJsonObject("physical").remove("position");
			copy.getAsJsonObject("physical").remove("velocity");
		}
		return copy;
	}

	private static JsonObject object(JsonObject parent, String key) {
		return parent != null && parent.has(key) && parent.get(key).isJsonObject() ? parent.getAsJsonObject(key) : null;
	}

	private static long count(JsonObject inventory, String item) {
		if (inventory == null || !inventory.has(item) || !inventory.get(item).isJsonPrimitive()) return 0;
		return inventory.get(item).getAsLong();
	}

	static void diff(JsonObject before, JsonObject after, String path, JsonArray patch) {
		for (String key : before.keySet()) if (!after.has(key)) patch.add(operation("remove", path + "/" + escape(key), null));
		for (var entry : after.entrySet()) {
			JsonElement old = before.get(entry.getKey()), value = entry.getValue();
			if (value.equals(old)) continue;
			String next = path + "/" + escape(entry.getKey());
			if (old != null && old.isJsonObject() && value.isJsonObject()) diff(old.getAsJsonObject(), value.getAsJsonObject(), next, patch);
			else patch.add(operation(old == null ? "add" : "replace", next, value));
		}
	}

	private static JsonObject operation(String op, String path, JsonElement value) {
		JsonObject operation = new JsonObject();
		operation.addProperty("op", op);
		operation.addProperty("path", path);
		if (value != null) operation.add("value", value);
		return operation;
	}

	private static String escape(String key) {
		return key.replace("~", "~0").replace("/", "~1");
	}
}
