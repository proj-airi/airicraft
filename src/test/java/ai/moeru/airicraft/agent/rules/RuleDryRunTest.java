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

	/** Steps whose recorded outputs and states are deliberately from another module: only their inputs are used. */
	private static List<ai.moeru.airicraft.agent.perception.SaliencePolicy.StepRecord> recordedSteps(int count) {
		var steps = new ArrayList<ai.moeru.airicraft.agent.perception.SaliencePolicy.StepRecord>();
		for (int index = 0; index < count; index++) {
			var input = com.google.gson.JsonParser.parseString(
				"{\"tick\":" + index + ",\"seed\":" + index + ",\"context\":{},\"candidates\":[{\"id\":\"c" + index + "\",\"kind\":\"item\"}]}").getAsJsonObject();
			steps.add(new ai.moeru.airicraft.agent.perception.SaliencePolicy.StepRecord(index, "planner:salience/v0", input,
				"{\"foreign\":true}", com.google.gson.JsonParser.parseString("[{\"type\":\"perception.block_noticed\",\"payload\":{},\"candidateIds\":[\"old\"]}]").getAsJsonArray(),
				new com.google.gson.JsonArray()));
		}
		return steps;
	}

	private static RuleEngine salienceEngine(String name, String source) throws Exception {
		return engine(new RuleModule(name, source, RuleModule.Hook.SALIENCE));
	}

	@Test void aSalienceModuleThatDropsEverythingLosesItsPercepts() throws Exception {
		var diff = RuleDryRun.salience(recordedSteps(5), salienceEngine("config:emit.js", EMIT), salienceEngine("planner:salience/v1", DROP));
		assertEquals(5, diff.replayed());
		assertEquals(5, diff.changed());
		assertEquals(5, diff.lost());
		assertEquals(0, diff.gained());
		assertEquals(Map.of("changed", 5, "lost", 5), diff.byType().get("perception.item_noticed"));
		assertEquals(5, diff.toMap().get("perceptsLost"));
	}

	@Test void theRunningModuleIsReplayedToo_soRecordedOutputsFromOtherVersionsDoNotDistortTheDiff() throws Exception {
		// The recorded steps say block percepts; the running module never emits any, and neither does the candidate.
		var diff = RuleDryRun.salience(recordedSteps(4), salienceEngine("config:emit.js", EMIT), salienceEngine("planner:salience/v1", EMIT));
		assertEquals(4, diff.replayed());
		assertEquals(0, diff.changed());
		assertEquals(0, diff.lost());
	}

	@Test void aCandidateStartsFromAnEmptyStateNotTheRecordedOne() throws Exception {
		var strict = "(lib => ({ step(input, state) { if (state.foreign !== undefined) throw Error('foreign state'); return {state: {n: (state.n || 0) + 1}, percepts: [], drops: []}; } }))";
		var diff = RuleDryRun.salience(recordedSteps(3), salienceEngine("config:emit.js", EMIT), salienceEngine("planner:salience/v1", strict));
		assertFalse(diff.failed(), String.valueOf(diff.firstFailure()));
		assertEquals(3, diff.replayed());
	}

	@Test void aChangedCandidateOrPayloadIsAChangeEvenWithTheSameTypeCounts() throws Exception {
		var other = "(lib => ({ step(input, state) { return {state, percepts: input.candidates.map(c => ({type: 'perception.item_noticed', payload: {coal: true}, candidateIds: [c.id]}))}; } }))";
		var diff = RuleDryRun.salience(recordedSteps(3), salienceEngine("config:emit.js", EMIT), salienceEngine("planner:salience/v1", other));
		assertEquals(3, diff.changed());
		assertEquals(3, diff.gained());
		assertEquals(3, diff.lost());
		assertEquals(Map.of("changed", 3, "gained", 3, "lost", 3), diff.byType().get("perception.item_noticed"));
	}

	@Test void aStepTheRunningModuleCannotRunIsSkippedNotCompared() throws Exception {
		var broken = salienceEngine("config:broken.js", "(lib => ({ step() { throw Error('old'); } }))");
		var diff = RuleDryRun.salience(recordedSteps(2), broken, salienceEngine("planner:salience/v1", EMIT));
		assertEquals(0, diff.replayed());
		assertEquals(2, diff.skipped());
		assertFalse(diff.failed());
	}

	@Test void aSalienceModuleThatThrowsFailsTheDryRun() throws Exception {
		var diff = RuleDryRun.salience(recordedSteps(2), salienceEngine("config:emit.js", EMIT),
			salienceEngine("planner:salience/v1", "(lib => ({ step() { throw Error('bad'); } }))"));
		assertTrue(diff.failed());
		assertEquals(2, diff.newFailures());
	}

	@Test void noRecordedStepsReplaysNothing() throws Exception {
		assertEquals(0, RuleDryRun.salience(List.of(), salienceEngine("config:emit.js", EMIT), salienceEngine("planner:salience/v1", EMIT)).replayed());
	}

	@Test void aModuleThatOnlyRenamesTheRuleIdIsAChange() throws Exception {
		for (long seq = 1; seq <= 3; seq++) record(seq, "pickup.item_picked_up", true, true);
		String template = MUTE_PICKUPS.replace("'NONE'", "'IMMEDIATE'").replace("'LOW'", "'NORMAL'");
		var was = engine(new RuleModule("config:a.js", template.replace("mine.quiet", "a.rule"), RuleModule.Hook.ATTENTION));
		var now = engine(new RuleModule("planner:attention/v1", template.replace("mine.quiet", "b.rule"), RuleModule.Hook.ATTENTION));
		var diff = RuleDryRun.attention(decisions, events, was, now);
		assertEquals(3, diff.changed(), "the wake is the same, the deciding rule is not");
		assertEquals(0, diff.lost());
		assertEquals(0, diff.gained());
		assertEquals(Map.of("changed", 3), diff.byType().get("pickup.item_picked_up"));
	}
}
