package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SaliencePolicyTest {
	private static final Duration WARM = Duration.ofSeconds(60);

	private static RuleModule module(String name, String source) throws Exception {
		var module = new RuleModule("config:" + name + ".js", source, RuleModule.Hook.SALIENCE);
		RuleEngine.shared(module).awaitReady(WARM);
		return module;
	}

	private static PerceptCandidate item(String id, String itemId) {
		return new PerceptCandidate(id, "item", Map.of("itemId", itemId, "count", 1));
	}

	@Test void capsEachStepAndCarriesTheRestOver() throws Exception {
		var module = module("echo", """
			(lib => ({ step(input, state) {
			  return {percepts: input.candidates.map(c => ({type: 'perception.item_noticed', payload: {itemId: c.itemId, count: c.count}, candidateIds: [c.id]})), state};
			} }))""");
		var policy = new SaliencePolicy(new SemanticEventBuffer(8), module);
		for (int index = 0; index < 5; index++) policy.offer(item("c" + index, "minecraft:bread"), 1);
		assertEquals(2, policy.step(1, Map.of(), 2).size());
		assertEquals(3, policy.pendingCount());
		var percepts = policy.step(2, Map.of(), 2);
		assertEquals(List.of("c2"), percepts.get(0).candidateIds(), "oldest first");
		assertEquals(1L, percepts.get(0).payload().get("count"), "integral numbers stay integral");
		policy.step(3, Map.of(), 2);
		assertEquals(0, policy.pendingCount());
		assertEquals(List.of(), policy.step(4, Map.of(), 2), "no candidates, no step");
		assertEquals(5L, policy.debugState().get("percepts"));
	}

	@Test void unknownPerceptTypesAreRejectedAndUndecidedCandidatesDropped() throws Exception {
		var module = module("bad-type", """
			(lib => ({ step(input, state) {
			  return {percepts: [{type: 'task.blocked', payload: {}, candidateIds: [input.candidates[0].id]}], drops: [], state};
			} }))""");
		var policy = new SaliencePolicy(new SemanticEventBuffer(8), module);
		policy.offer(item("a", "minecraft:bread"), 1);
		assertEquals(List.of(), policy.step(1, Map.of(), 10));
		var debug = policy.debugState();
		assertEquals(1L, debug.get("rejectedPercepts"));
		assertEquals(1L, debug.get("drops"));
		assertEquals("dropped:unselected", ((List<Map<String, Object>>) debug.get("recent")).getFirst().get("outcome"));
	}

	@Test void failedStepsKeepCandidatesThenExpireAndAnOverrideReverts() throws Exception {
		var module = module("throws", "(lib => ({ step(input, state) { throw Error('broken'); } }))");
		var events = new SemanticEventBuffer(32);
		var policy = new SaliencePolicy(events, module);
		policy.offer(item("a", "minecraft:diamond"), 1);
		policy.step(1, Map.of(), 10);
		assertEquals(1, policy.pendingCount(), "a failed step keeps its candidates for the next tick");
		policy.step(2, Map.of(), 10);
		policy.step(3, Map.of(), 10);
		assertEquals(RuleModule.BUNDLED_SALIENCE, policy.module().origin(), "three consecutive failures revert the override");
		var types = new ArrayList<String>();
		events.query(null).events().forEach(event -> types.add(event.type()));
		assertEquals(List.of("rules.step_failed", "rules.step_failed", "rules.step_failed", "rules.reverted"), types);
		assertEquals(1, policy.pendingCount());
		policy.step(30, Map.of(), 10);
		assertEquals(0, policy.pendingCount(), "a candidate waits at most 20 ticks");
		assertEquals(1L, policy.debugState().get("expired"));
	}

	@Test void recordsEachStepForReplayAndRefusesAnAttentionModule() throws Exception {
		var policy = new SaliencePolicy(new SemanticEventBuffer(8), RuleModule.bundledSalience());
		RuleEngine.shared(RuleModule.bundledSalience()).awaitReady(WARM);
		var records = new ArrayList<SaliencePolicy.StepRecord>();
		policy.recordSteps(records::add);
		policy.offer(item("a", "minecraft:dirt"), 5);
		policy.step(5, Map.of("wanted", List.of()), 10);
		assertEquals(1, records.size());
		assertEquals("a", records.getFirst().input().getAsJsonArray("candidates").get(0).getAsJsonObject().get("id").getAsString());
		assertEquals("garbage", records.getFirst().drops().get(0).getAsJsonObject().get("reason").getAsString());
		assertTrue(policy.blockInterests().contains("minecraft:diamond_ore"));
		assertThrows(IllegalArgumentException.class, () -> policy.useModule(RuleModule.bundledAttention()));
	}

	@Test void plainKeepsJsonShapes() {
		assertEquals(Map.of("a", 3L, "b", 2.5, "c", List.of(true, "x")), SaliencePolicy.plain(JsonParser.parseString("{\"a\":3,\"b\":2.5,\"c\":[true,\"x\"],\"d\":null}")));
	}
}
