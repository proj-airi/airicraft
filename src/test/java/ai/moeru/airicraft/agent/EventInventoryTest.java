package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.llm.PlannerDecisionContext;
import ai.moeru.airicraft.agent.work.WorkHandle;
import ai.moeru.airicraft.agent.work.WorkSnapshot;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventInventoryTest {
	private static final Path INVENTORY = Path.of("src/test/resources/planner/wakes/event-inventory.json");
	private JsonObject inventory() throws Exception {
		return JsonParser.parseString(Files.readString(INVENTORY)).getAsJsonObject();
	}

	@Test void routingProfilesMatchInventory() throws Exception {
		var actual = EmbodiedAgentRuntime.eventRoutingProfilesForTests();
		var expected = new TreeMap<String, Map<String, Object>>();
		for (var value : inventory().getAsJsonArray("types")) {
			var entry = value.getAsJsonObject();
			if (entry.get("profile").isJsonNull()) continue;
			var profile = entry.getAsJsonObject("profile");
			expected.put(entry.get("type").getAsString(), Map.of(
				"semantic", profile.get("semantic").getAsBoolean(),
				"trigger", profile.get("trigger").isJsonNull() ? "" : profile.get("trigger").getAsString(),
				"bypass", profile.get("bypass").getAsBoolean()));
		}
		var observed = new TreeMap<String, Map<String, Object>>();
		actual.forEach((type, profile) -> observed.put(type, Map.of("semantic", profile.semanticEligible(),
			"trigger", profile.triggerType() == null ? "" : profile.triggerType().name(), "bypass", profile.policyBypass())));
		assertEquals(expected, observed);
	}

	@Test void observeVisibilityMatchesInventory() throws Exception {
		for (var value : inventory().getAsJsonArray("types")) {
			var entry = value.getAsJsonObject();
			String type = entry.has("type") ? entry.get("type").getAsString() : entry.get("prefix").getAsString() + "example";
			var events = new SemanticEventBuffer(2);
			Map<String, Object> payload = type.equals("work.changed")
				? new WorkSnapshot(new WorkHandle("JOB:test"), "", WorkSnapshot.State.FAILED, "mine", "FAILED", false, 1, Map.of()).payload()
				: Map.of();
			events.append(1, type, payload);
			var context = new PlannerDecisionContext("test", 1, 1, "controller", "idle", Map.of(), events.query(null));
			assertEquals(entry.get("observeVisible").getAsBoolean(),
				!((List<?>) context.observation(0).get("events")).isEmpty(), type);
		}
	}

	@Test void everyEmittedEventTypeIsInventoried() throws Exception {
		var data = inventory();
		Set<String> exact = new HashSet<>();
		List<String> prefixes = new ArrayList<>();
		for (var value : data.getAsJsonArray("types")) {
			var entry = value.getAsJsonObject();
			if (entry.has("type")) assertTrue(exact.add(entry.get("type").getAsString()), "duplicate type: " + entry);
			else prefixes.add(entry.get("prefix").getAsString());
		}
		data.getAsJsonArray("notEventTypes").forEach(value -> exact.add(value.getAsString()));
		var pattern = Pattern.compile("\"((?:social|pickup|crafting|smelting|combat|player|reflex|lighting|planner|session|follow|task|work|food|action_graph|mission|objective|policy|interaction|inventory|container|survival)\\.[a-z_]+)\"");
		Set<String> missing = new TreeSet<>();
		try (var paths = Files.walk(Path.of(System.getProperty("user.dir"), "src/client/java/ai/moeru/airicraft/agent"))) {
			for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
				var matcher = pattern.matcher(Files.readString(path));
				while (matcher.find()) {
					String type = matcher.group(1);
					if (!exact.contains(type) && prefixes.stream().noneMatch(type::startsWith)) missing.add(type);
				}
			}
		}
		assertEquals(Set.of(), missing, "Uninventoried event ids");
	}
}
