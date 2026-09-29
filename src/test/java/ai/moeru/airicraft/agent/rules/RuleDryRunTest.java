package ai.moeru.airicraft.agent.rules;

import ai.moeru.airicraft.agent.attention.AttentionDecision;
import ai.moeru.airicraft.agent.attention.AttentionEvidence;
import ai.moeru.airicraft.agent.attention.AttentionStage;
import ai.moeru.airicraft.agent.attention.AttentionState;
import ai.moeru.airicraft.agent.attention.Delivery;
import ai.moeru.airicraft.agent.attention.Urgency;
import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleDryRunTest {
	private static final String MUTE_PICKUPS = """
		(lib => ({ step(input, state) {
		  return {state, decisions: input.events.map(e => {
		    const quiet = e.type === 'pickup.item_picked_up';
		    const effect = quiet ? 'IGNORE' : 'ALLOW';
		    return {seqNo: e.seqNo, emitSemantic: !quiet,
		      ruleMatch: {effect, ruleIndex: -1, ruleId: null, reason: null, bypassed: false},
		      policy: {effect, ruleIndex: -1, ruleId: null, reason: null, bypassed: false},
		      wake: quiet ? {delivery: 'NONE', urgency: 'LOW', ruleId: 'mine.quiet', reason: 'quiet while building'}
		        : {delivery: 'IMMEDIATE', urgency: 'NORMAL', ruleId: 'mine.default', reason: 'default'}};
		  })};
		} }))
		""";
	private static final List<RuleEngine> engines = new ArrayList<>();

	private static RuleEngine engine(RuleModule module) throws Exception {
		var engine = RuleEngine.detached(module);
		engines.add(engine);
		engine.awaitReady(Duration.ofSeconds(60));
		return engine;
	}

	private static RuleEngine bundled;

	@BeforeAll static void warm() throws Exception {
		bundled = RuleEngine.shared(RuleModule.bundledAttention());
		bundled.awaitReady(Duration.ofSeconds(60));
	}

	@AfterAll static void close() {
		engines.forEach(RuleEngine::close);
	}

	private final Map<Long, SemanticEvent> events = new LinkedHashMap<>();
	private final List<AttentionDecision> decisions = new ArrayList<>();

	private void record(long seq, String type, boolean withInputs, boolean keepEvent) {
		var profile = EventCatalog.defaults().routingProfiles().get(type);
		var event = new SemanticEvent(seq, seq, seq * 50, type, Map.of("itemId", "minecraft:oak_log", "count", 1));
		if (keepEvent) events.put(seq, event);
		var inputs = withInputs ? new AttentionDecision.Inputs(AttentionState.idle(), new AttentionEvidence(false, false, true), true, profile, List.of()) : null;
		decisions.add(new AttentionDecision(seq, seq, type, true, Delivery.IMMEDIATE, Urgency.NORMAL, AttentionStage.RULES, "catalog.trigger", "", true, inputs));
	}

	@Test void aModuleThatMutesPickupsLosesTheirWakesAndNothingElse() throws Exception {
		for (long seq = 1; seq <= 5; seq++) record(seq, "pickup.item_picked_up", true, true);
		record(6, "smelting.output_ready", true, true);
		var diff = RuleDryRun.attention(decisions, events, bundled, engine(new RuleModule("planner:attention/v1", MUTE_PICKUPS, RuleModule.Hook.ATTENTION)));
		assertEquals(6, diff.replayed());
		assertEquals(6, diff.changed(), "the module's default wake is NORMAL, so smelting's urgency differs");
		assertEquals(5, diff.lost());
		assertEquals(0, diff.gained());
		assertFalse(diff.failed());
		assertEquals(Map.of("changed", 5, "lost", 5), diff.byType().get("pickup.item_picked_up"));
		assertEquals(Map.of("changed", 1), diff.byType().get("smelting.output_ready"), "a protected type keeps its wake, and loses none");
		assertEquals(6, diff.examples().size());
		assertTrue(String.valueOf(diff.examples().getFirst().get("now")).contains("no wake"), diff.examples().toString());
		assertEquals(5, diff.toMap().get("wakesLost"));
	}

	@Test void theSameModuleOnBothSidesChangesNothing() throws Exception {
		for (long seq = 1; seq <= 4; seq++) record(seq, "pickup.item_picked_up", true, true);
		var diff = RuleDryRun.attention(decisions, events, bundled, bundled);
		assertEquals(4, diff.replayed());
		assertEquals(0, diff.changed());
		assertTrue(diff.byType().isEmpty());
	}

	@Test void entriesWithoutInputsOrEventsAreSkippedAndCounted() throws Exception {
		record(1, "pickup.item_picked_up", true, true);
		record(2, "pickup.item_picked_up", false, true);
		record(3, "pickup.item_picked_up", true, false);
		var diff = RuleDryRun.attention(decisions, events, bundled, bundled);
		assertEquals(1, diff.replayed());
		assertEquals(2, diff.skipped());
	}

	@Test void onlyTheNewestEntriesAreReplayed() throws Exception {
		for (long seq = 1; seq <= RuleDryRun.ATTENTION_EVENTS + 30; seq++) record(seq, "pickup.item_picked_up", true, true);
		var diff = RuleDryRun.attention(decisions, events, bundled, bundled);
		assertEquals(RuleDryRun.ATTENTION_EVENTS, diff.replayed());
	}

	@Test void aModuleThatThrowsIsReportedAsFailingWithItsMessage() throws Exception {
		for (long seq = 1; seq <= 3; seq++) record(seq, "pickup.item_picked_up", true, true);
		var broken = new RuleModule("planner:attention/v1", "(lib => ({ step() { throw Error('bad rule'); } }))", RuleModule.Hook.ATTENTION);
		var diff = RuleDryRun.attention(decisions, events, bundled, engine(broken));
		assertTrue(diff.failed());
		assertEquals(3, diff.newFailures(), "every step failed and none reverted the module mid-replay");
		assertTrue(diff.firstFailure().startsWith("guest_error"), diff.firstFailure());
	}

	@Test void anEmptyHistoryReplaysNothing() throws Exception {
		var diff = RuleDryRun.attention(List.of(), Map.of(), bundled, bundled);
		assertEquals(0, diff.replayed());
		assertEquals(0, diff.skipped());
	}

	private static final String EMIT = """
		(lib => ({ step(input, state) {
		  return {state: {seen: (state.seen || 0) + input.candidates.length},
		    percepts: input.candidates.map(c => ({type: 'perception.item_noticed', payload: {}, candidateIds: [c.id]}))};
		} }))
		""";
	private static final String DROP = """
		(lib => ({ step(input, state) {
		  return {state, drops: input.candidates.map(c => ({candidateId: c.id, reason: 'quiet'}))};
		} }))
		""";

	private static List<ai.moeru.airicraft.agent.perception.SaliencePolicy.StepRecord> recordedSteps(int count) throws Exception {
		var old = engine(new RuleModule("config:emit.js", EMIT, RuleModule.Hook.SALIENCE));
		var steps = new ArrayList<ai.moeru.airicraft.agent.perception.SaliencePolicy.StepRecord>();
		String state = "{}";
		for (int index = 0; index < count; index++) {
			var input = com.google.gson.JsonParser.parseString(
				"{\"tick\":" + index + ",\"seed\":" + index + ",\"context\":{},\"candidates\":[{\"id\":\"c" + index + "\",\"kind\":\"item\"}]}").getAsJsonObject();
			var result = old.step(input.toString(), state);
			steps.add(new ai.moeru.airicraft.agent.perception.SaliencePolicy.StepRecord(index, "config:emit.js", input, state, result.percepts(), result.drops()));
			state = result.stateJson();
		}
		return steps;
	}

	@Test void aSalienceModuleThatDropsEverythingLosesItsPercepts() throws Exception {
		var steps = recordedSteps(5);
		var diff = RuleDryRun.salience(steps, engine(new RuleModule("planner:salience/v1", DROP, RuleModule.Hook.SALIENCE)));
		assertEquals(5, diff.replayed());
		assertEquals(5, diff.changed());
		assertEquals(5, diff.lost());
		assertEquals(0, diff.gained());
		assertEquals(Map.of("changed", 5, "lost", 5), diff.byType().get("perception.item_noticed"));
		assertEquals(5, diff.toMap().get("perceptsLost"));
	}

	@Test void theSameSalienceModuleChangesNothingAndThreadsItsState() throws Exception {
		var steps = recordedSteps(4);
		var diff = RuleDryRun.salience(steps, engine(new RuleModule("planner:salience/v1", EMIT, RuleModule.Hook.SALIENCE)));
		assertEquals(4, diff.replayed());
		assertEquals(0, diff.changed());
	}

	@Test void aSalienceModuleThatThrowsFailsTheDryRun() throws Exception {
		var diff = RuleDryRun.salience(recordedSteps(2), engine(new RuleModule("planner:salience/v1",
			"(lib => ({ step() { throw Error('bad'); } }))", RuleModule.Hook.SALIENCE)));
		assertTrue(diff.failed());
		assertEquals(2, diff.newFailures());
	}

	@Test void noRecordedStepsReplaysNothing() throws Exception {
		assertEquals(0, RuleDryRun.salience(List.of(), engine(new RuleModule("planner:salience/v1", EMIT, RuleModule.Hook.SALIENCE))).replayed());
	}
}
