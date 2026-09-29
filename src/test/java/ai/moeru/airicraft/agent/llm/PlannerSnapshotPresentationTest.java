package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PlannerSnapshotPresentationTest {
	private static String snapshot(String world, int tick, String current, boolean refresh) {
		return "{\"worldSessionId\":\"" + world + "\",\"tick\":" + tick
			+ ",\"serverTick\":" + tick + ",\"current\":" + current + (refresh ? ",\"stateBaseline\":true" : "")
			+ ",\"afterEventSequence\":0,\"throughEventSequence\":0,\"events\":[]}";
	}

	/** Real states are far larger than one change; pad small fixtures so deltas are the cheaper form. */
	private static String padded(String current) {
		return current.substring(0, current.length() - 1) + (current.length() > 2 ? "," : "")
			+ "\"travelRestrictions\":{\"coordinateMeaning\":\"inclusive occupied cells, including head and edits; search bounds are separate\"},"
			+ "\"foodPolicy\":{\"goal\":\"MOVEMENT\",\"foodChoice\":\"ANY\",\"note\":\"eat when hunger blocks sprinting or health regeneration\"}}";
	}

	private static List<Map<String, Object>> observations(String... results) {
		var messages = new ArrayList<Map<String, Object>>();
		for (int index = 0; index < results.length; index++) {
			String id = "call_observe_" + index;
			messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", id, "type", "function",
				"function", Map.of("name", PlannerObservation.TOOL_NAME, "arguments", "{}")))));
			messages.add(Map.of("role", "tool", "tool_call_id", id, "content", results[index]));
		}
		return messages;
	}

	private static List<String> present(String... results) {
		var rendered = new ArrayList<String>();
		JsonArray wire = new PlannerReferences().presentMessages(observations(results));
		for (int index = 1; index < wire.size(); index += 2) rendered.add(wire.get(index).getAsJsonObject().get("content").getAsString());
		return rendered;
	}

	@Test void nestedChangesAndRemovalsBecomeJsonPatchWithoutRepeatingUnchangedNotes() {
		String before = padded("{\"objective\":{\"decisions\":{\"old\":\"historical note\"}},\"inventory\":{\"oak_log\":2,\"dirt\":1},\"work\":[1]}");
		String after = padded("{\"objective\":{\"decisions\":{\"old\":\"historical note\"}},\"inventory\":{\"oak_log\":1},\"work\":[]}");
		var rendered = present(snapshot("a", 1, before, false), snapshot("a", 2, after, false), snapshot("a", 3, after, false));
		assertTrue(rendered.get(0).contains("historical note"));
		assertTrue(rendered.get(1).contains("Inventory: dirt -1 (none left), oak_log -1 (1)"), rendered.get(1));
		assertTrue(rendered.get(1).contains("RFC 6902"));
		assertTrue(rendered.get(1).contains("[{\"op\":\"replace\",\"path\":\"/work\",\"value\":[]}]"), rendered.get(1));
		assertFalse(rendered.get(1).contains("historical note"));
		assertTrue(rendered.get(2).contains("State unchanged since the previous observation."));
		assertTrue(rendered.get(2).contains("client tick 3"));
	}

	@Test void pointerSegmentsAreEscaped() {
		var rendered = present(snapshot("a", 1, padded("{\"a/b\":{\"c~d\":1}}"), false), snapshot("a", 2, padded("{\"a/b\":{\"c~d\":2}}"), false));
		assertTrue(rendered.get(1).contains("\"path\":\"/a~1b/c~0d\""), rendered.get(1));
	}

	@Test void newWorldRefreshAndLostHistoryAlwaysReceiveFullState() {
		String health = "{\"health\":20}";
		String gap = snapshot("b", 4, health, false).replace("\"events\":[]", "\"events\":[],\"missingEventRange\":{\"from\":1,\"to\":5}");
		var rendered = present(snapshot("a", 1, health, false), snapshot("b", 2, health, false),
			snapshot("b", 3, health, true), gap, snapshot("b", 5, health, false));
		for (int index = 0; index < 4; index++) assertFalse(rendered.get(index).contains("RFC 6902"), rendered.get(index));
		assertFalse(rendered.get(2).contains("stateBaseline"));
		assertTrue(rendered.get(3).contains("MISSING evidence"));
		assertTrue(rendered.get(4).contains("State unchanged"));
	}

	@Test void onlyResultsOfObserveCallsArePresentedAsObservations() {
		String raw = snapshot("a", 1, "{\"health\":20}", false);
		var messages = List.<Map<String, Object>>of(
			Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_other", "type", "function",
				"function", Map.of("name", "inspect_inventory", "arguments", "{}")))),
			Map.of("role", "tool", "tool_call_id", "call_other", "content", raw),
			Map.of("role", "user", "content", raw));
		JsonArray wire = new PlannerReferences().presentMessages(messages);
		assertEquals(raw, wire.get(1).getAsJsonObject().get("content").getAsString());
		assertEquals(raw, wire.get(2).getAsJsonObject().get("content").getAsString());
	}

	@Test void wireRetriesAreIdenticalAndCanonicalSnapshotsStayIntact() {
		var refs = new PlannerReferences();
		String baseline = snapshot("a", 1, padded("{\"inventory\":{\"dirt\":2}}"), false);
		String next = snapshot("a", 2, padded("{\"inventory\":{\"dirt\":1}}"), false);
		var messages = observations(baseline, next);
		var wire = refs.presentMessages(messages);
		assertEquals(wire, refs.presentMessages(messages));
		assertEquals(next, messages.get(3).get("content"));
		assertEquals("tool", wire.get(3).getAsJsonObject().get("role").getAsString());
		assertEquals("call_observe_1", wire.get(3).getAsJsonObject().get("tool_call_id").getAsString());
		assertTrue(wire.get(3).toString().contains("Inventory: dirt -1 (1)"), wire.get(3).toString());
		assertFalse(refs.presentMessages(messages.subList(2, 4)).toString().contains("State changes"));
	}

	@Test void vitalsAndBlockMovesAreSemanticAndVelocityIsNoise() {
		String before = "{\"vitals\":{\"health\":20.0,\"food\":20},\"physical\":{\"grounded\":true,\"position\":{\"x\":1.2,\"y\":64.0,\"z\":2.7},\"velocity\":{\"x\":0.0,\"y\":0.0,\"z\":0.0}}}";
		String jitter = "{\"vitals\":{\"health\":20.0,\"food\":20},\"physical\":{\"grounded\":true,\"position\":{\"x\":1.8,\"y\":64.0,\"z\":2.1},\"velocity\":{\"x\":0.1,\"y\":-0.08,\"z\":0.0}}}";
		String hurt = "{\"vitals\":{\"health\":14.0,\"food\":20},\"physical\":{\"grounded\":false,\"position\":{\"x\":5.5,\"y\":64.0,\"z\":2.1},\"velocity\":{\"x\":0.1,\"y\":-0.08,\"z\":0.0}}}";
		var rendered = present(snapshot("a", 1, padded(before), false), snapshot("a", 2, padded(jitter), false),
			snapshot("a", 3, padded(hurt), false));
		assertTrue(rendered.get(1).contains("State unchanged since the previous observation."), rendered.get(1));
		assertTrue(rendered.get(2).contains("Vitals: health 20.0 -> 14.0"), rendered.get(2));
		assertTrue(rendered.get(2).contains("Moved from block (1,64,2) to (5,64,2)."), rendered.get(2));
		assertTrue(rendered.get(2).contains("\"path\":\"/physical/grounded\""), rendered.get(2));
		assertFalse(rendered.get(2).contains("velocity"), rendered.get(2));
	}

	@Test void anExplicitObserveAndThePeriodicBaselineCarryFullState() {
		String state = "{\"inventory\":{\"dirt\":1}}";
		var messages = new ArrayList<Map<String, Object>>(observations(snapshot("a", 1, state, false), snapshot("a", 2, state, false)));
		messages.add(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of("id", "call_model_7", "type", "function",
			"function", Map.of("name", PlannerObservation.TOOL_NAME, "arguments", "{}")))));
		messages.add(Map.of("role", "tool", "tool_call_id", "call_model_7", "content", snapshot("a", 3, state, false)));
		JsonArray wire = new PlannerReferences().presentMessages(messages);
		assertTrue(wire.get(3).getAsJsonObject().get("content").getAsString().contains("State unchanged"));
		String explicit = wire.get(5).getAsJsonObject().get("content").getAsString();
		assertTrue(explicit.contains("Carrying dirt"), "the model asked for it: full state\n" + explicit);

		var many = new String[ObservationPresenter.BASELINE_EVERY_OBSERVATIONS + 1];
		for (int index = 0; index < many.length; index++) many[index] = snapshot("a", index + 1, state, false);
		var periodic = present(many);
		assertTrue(periodic.get(1).contains("State unchanged"));
		assertTrue(periodic.get(ObservationPresenter.BASELINE_EVERY_OBSERVATIONS).contains("Carrying dirt"), "every 20 observations");
		var late = present(snapshot("a", 1, state, false), snapshot("a", 1 + (int) ObservationPresenter.BASELINE_EVERY_TICKS, state, false));
		assertTrue(late.get(1).contains("Carrying dirt"), "or every 6,000 ticks");
	}

	@Test void aLongChangeListFallsBackToTheFullState() {
		var before = new StringBuilder("{\"inventory\":{");
		var after = new StringBuilder("{\"inventory\":{");
		for (int index = 0; index < 30; index++) {
			before.append(index == 0 ? "" : ",").append("\"item_").append(index).append("\":").append(index + 1);
			after.append(index == 0 ? "" : ",").append("\"item_").append(index).append("\":").append(index + 2);
		}
		var rendered = present(snapshot("a", 1, before.append("}}").toString(), false), snapshot("a", 2, after.append("}}").toString(), false));
		assertFalse(rendered.get(1).contains("State changes"), rendered.get(1));
	}

	@Test void objectiveEventKeepsIdentityWithoutRepeatingCurrentNotebook() {
		String raw = snapshot("a", 1, "{\"objective\":{\"decisions\":{\"old\":\"long historical note\"}}}", false)
			.replace("\"events\":[]", "\"events\":[{\"seqNo\":8,\"tick\":1,\"type\":\"objective.changed\",\"payload\":{\"objective\":{\"decisions\":{\"old\":\"long historical note\"}},\"reason\":\"updated\"}}]");
		String rendered = present(raw).getFirst();
		assertEquals(1, rendered.split("long historical note", -1).length - 1, rendered);
		assertTrue(rendered.contains("updated"), rendered);
	}
}
