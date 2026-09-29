package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.rules.RuleModule;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RulesToolProviderTest {
	private final RulesToolProvider provider = new RulesToolProvider();

	private static JsonObject args(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	private String run(String tool, String json) throws Exception {
		return provider.execute(new PlannerToolCall("call", tool, args(json), null)).get();
	}

	@Test void offersThreeToolsAndOnlyUpdatingIsASideEffect() {
		var names = provider.openAiTools().stream().map(tool -> (String) ((Map<?, ?>) tool.get("function")).get("name")).toList();
		assertEquals(List.of("inspect_rules", "update_rules", "read_rules_docs"), names);
		assertTrue(provider.isReadTool("inspect_rules"));
		assertTrue(provider.isReadTool("read_rules_docs"));
		assertFalse(provider.isReadTool("update_rules"));
		assertFalse(provider.dynamicTools(), "the list is fixed, so the frozen prefix holds");
	}

	@Test void validatesArguments() {
		String ok = "{\"hook\":\"attention\",\"reason\":\"quiet\"";
		provider.validateArguments("update_rules", args(ok + ",\"source\":\"(lib => ({step(){}}))\"}"));
		provider.validateArguments("update_rules", args(ok + ",\"revert_to\":\"base\"}"));
		provider.validateArguments("update_rules", args(ok + ",\"revert_to\":3}"));
		provider.validateArguments("inspect_rules", args("{\"hook\":\"salience\",\"version\":2}"));
		provider.validateArguments("read_rules_docs", args("{}"));
		for (String bad : List.of(
			ok + "}",
			ok + ",\"source\":\"x\",\"revert_to\":\"base\"}",
			ok + ",\"revert_to\":\"latest\"}",
			ok + ",\"revert_to\":\"0\"}",
			"{\"hook\":\"attention\",\"source\":\"x\"}",
			"{\"hook\":\"attention\",\"reason\":\" \",\"source\":\"x\"}",
			"{\"hook\":\"attention\",\"reason\":\"" + "r".repeat(201) + "\",\"source\":\"x\"}",
			"{\"hook\":\"scheduler\",\"reason\":\"r\",\"source\":\"x\"}",
			"{\"hook\":\"attention\",\"reason\":\"r\",\"source\":\"" + "x".repeat(RuleModule.MAX_SOURCE_CHARS + 1) + "\"}",
			"{\"hook\":\"attention\",\"reason\":\"r\",\"source\":\"x\",\"extra\":1}")) {
			assertThrows(IllegalArgumentException.class, () -> provider.validateArguments("update_rules", args(bad)), bad.length() > 120 ? bad.substring(0, 120) : bad);
		}
		assertThrows(IllegalArgumentException.class, () -> provider.validateArguments("inspect_rules", args("{}")));
		assertThrows(IllegalArgumentException.class, () -> provider.validateArguments("inspect_rules", args("{\"hook\":\"attention\",\"version\":0}")));
		assertThrows(IllegalArgumentException.class, () -> provider.validateArguments("read_rules_docs", args("{\"hook\":\"attention\"}")));
	}

	@Test void invalidArgumentsBecomeToolErrorsAndAnUnboundProviderIsUnavailable() throws Exception {
		assertTrue(run("update_rules", "{\"hook\":\"attention\"}").startsWith("TOOL_ERROR: update_rules "));
		assertEquals("TOOL_UNAVAILABLE: rules_unavailable", run("inspect_rules", "{\"hook\":\"attention\"}"));
		assertEquals("TOOL_UNAVAILABLE: rules_unavailable", run("update_rules", "{\"hook\":\"attention\",\"reason\":\"r\",\"revert_to\":\"base\"}"));
	}

	@Test void theDocsCoverTheContractTheHelpersAndTheProtectedTypes() throws Exception {
		String docs = run("read_rules_docs", "{}");
		for (String helper : List.of("leakyBucket", "slidingWindow", "tumblingWindow", "cooldown", "hourlyCap", "cluster", "seededRandom")) {
			assertTrue(docs.contains(helper), helper);
		}
		for (String field : List.of("delivery", "urgency", "emitSemantic", "ruleMatch", "plannerRules", "candidateIds", "interests",
			"IMMEDIATE", "DEBOUNCE", "CRITICAL", "budget.autonomous_wakes", "AUTONOMOUS_BUDGET", "revert_to", "rules.reverted", "rules.updated")) {
			assertTrue(docs.contains(field), field);
		}
		for (String type : List.of("perception.block_noticed", "perception.item_noticed", "perception.entity_noticed", "perception.entity_lost",
			"perception.environment_changed")) {
			assertTrue(docs.contains(type), type);
		}
		EventCatalog.defaults().routingProfiles().forEach((type, profile) -> {
			if (profile.policyBypass()) assertTrue(docs.contains(type), "protected type " + type);
		});
		assertTrue(docs.contains(RuleModule.bundledAttention().source().strip()), "the bundled module is the worked example");
	}

	@Test void theDocsAndTheOperatorGuideAgreeOnTheContract() throws Exception {
		String guide = Files.readString(Path.of("docs/attention-rules.md"));
		String docs = run("read_rules_docs", "{}");
		// Every wake urgency, delivery and salience percept type the operator guide names is in the planner's docs.
		for (String token : List.of("NONE", "IMMEDIATE", "DEBOUNCE", "CRITICAL", "DIRECT", "HIGH", "NORMAL", "LOW", "SELF",
			"ALLOW", "IGNORE", "SEMANTIC_ONLY", "TRIGGER_ONLY", "50,000 statements", "16 KiB", "64 KiB")) {
			if (guide.contains(token)) assertTrue(docs.contains(token), token);
		}
	}
}
