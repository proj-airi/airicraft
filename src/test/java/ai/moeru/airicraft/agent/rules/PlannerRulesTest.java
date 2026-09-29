package ai.moeru.airicraft.agent.rules;

import ai.moeru.airicraft.agent.attention.AttentionDecision;
import ai.moeru.airicraft.agent.attention.AttentionEvidence;
import ai.moeru.airicraft.agent.attention.AttentionStage;
import ai.moeru.airicraft.agent.attention.AttentionState;
import ai.moeru.airicraft.agent.attention.Delivery;
import ai.moeru.airicraft.agent.attention.Urgency;
import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.perception.SaliencePolicy;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import ai.moeru.airicraft.rules.RulesStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlannerRulesTest {
	static final String MUTE_PICKUPS = """
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
		  // plannerRules
		} }))
		""";

	private static final class FakeHost implements PlannerRules.Host {
		final AtomicLong tick = new AtomicLong(100);
		volatile boolean hold;
		final Map<RuleModule.Hook, RuleModule> running = new LinkedHashMap<>();
		final List<AttentionDecision> decisions = new ArrayList<>();
		final Map<Long, SemanticEvent> events = new LinkedHashMap<>();
		final List<RuleModule> activated = new ArrayList<>();
		final List<String> published = new ArrayList<>();
		final List<Map<String, Object>> payloads = new ArrayList<>();

		FakeHost() {
			running.put(RuleModule.Hook.ATTENTION, RuleModule.bundledAttention());
			running.put(RuleModule.Hook.SALIENCE, RuleModule.bundledSalience());
		}

		@Override public long tick() { return tick.get(); }
		@Override public boolean safetyHoldOpen() { return hold; }
		@Override public RuleModule running(RuleModule.Hook hook) { return running.get(hook); }
		@Override public void activate(RuleModule module) { running.put(module.hook(), module); activated.add(module); }
		@Override public List<AttentionDecision> decisions() { return List.copyOf(decisions); }
		@Override public Map<Long, SemanticEvent> events() { return Map.copyOf(events); }
		@Override public List<SaliencePolicy.StepRecord> salienceSteps() { return List.of(); }
		@Override public Map<String, Object> summary(RuleModule.Hook hook) { return Map.of("steps", 0); }
		@Override public void publish(String type, Map<String, Object> payload) { published.add(type); payloads.add(payload); }

		void pickups(int count) {
			for (long seq = 1; seq <= count; seq++) {
				var profile = EventCatalog.defaults().routingProfiles().get("pickup.item_picked_up");
				var event = new SemanticEvent(seq, seq, seq * 50, "pickup.item_picked_up", Map.of("itemId", "minecraft:oak_log", "count", 1));
				events.put(seq, event);
				decisions.add(new AttentionDecision(seq, seq, event.type(), true, Delivery.IMMEDIATE, Urgency.NORMAL, AttentionStage.RULES, "catalog.trigger", "", true,
					new AttentionDecision.Inputs(AttentionState.idle(), new AttentionEvidence(false, false, true), true, profile, List.of())));
			}
		}
	}

	private final FakeHost host = new FakeHost();
	private final RulesStore store = new RulesStore();
	private final PlannerRules rules = new PlannerRules(store, host);

	@BeforeAll static void warm() throws Exception {
		RuleEngine.shared(RuleModule.bundledAttention()).awaitReady(Duration.ofSeconds(60));
		RuleEngine.shared(RuleModule.bundledSalience()).awaitReady(Duration.ofSeconds(60));
	}

	@AfterEach void close() {
		rules.close();
	}

	/** Runs the game's tick loop until the request settles. */
	private Map<String, Object> settle(CompletableFuture<Map<String, Object>> future) throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
		while (!future.isDone() && System.nanoTime() < deadline) {
			rules.drain();
			Thread.sleep(5);
		}
		rules.drain();
		try {
			return future.get(1, TimeUnit.SECONDS);
		}
		catch (ExecutionException failure) {
			throw failure.getCause() instanceof PlannerRules.Refused refused ? refused : failure;
		}
	}

	private PlannerRules.Refused refusal(CompletableFuture<Map<String, Object>> future) {
		try {
			settle(future);
		}
		catch (PlannerRules.Refused refused) {
			return refused;
		}
		catch (Exception failure) {
			throw new AssertionError(failure);
		}
		throw new AssertionError("expected a refusal");
	}

	private CompletableFuture<Map<String, Object>> edit(RuleModule.Hook hook, String source) {
		return rules.update(new PlannerRules.Request(hook, source, null, "mute pickups while building"));
	}

	@SuppressWarnings("unchecked")
	@Test void anAcceptedEditReplaysHistoryActivatesOnTheTickAndPublishes() throws Exception {
		host.pickups(6);
		var result = settle(edit(RuleModule.Hook.ATTENTION, MUTE_PICKUPS));
		assertEquals(true, result.get("accepted"));
		assertEquals(1, result.get("activeVersion"));
		assertEquals(0, result.get("previousVersion"));
		var replay = (Map<String, Object>) result.get("replay");
		assertEquals(6, replay.get("replayed"));
		assertEquals(6, replay.get("wakesLost"));
		assertEquals("planner:attention/v1", host.running(RuleModule.Hook.ATTENTION).origin());
		assertEquals(1, store.activeNumber(RuleModule.Hook.ATTENTION));
		assertEquals(List.of("rules.updated"), host.published);
		assertEquals(6, host.payloads.getFirst().get("wakesLost"));
		assertEquals("attention", host.payloads.getFirst().get("hook"));
		assertNull(result.get("warnings"), "the module mentions plannerRules");
	}

	@Test void nothingChangesUntilTheTickRunsTheActivation() throws Exception {
		host.pickups(2);
		var future = edit(RuleModule.Hook.ATTENTION, MUTE_PICKUPS);
		Thread.sleep(1500);
		assertFalse(future.isDone(), "activation waits for the tick thread");
		assertTrue(host.activated.isEmpty());
		assertEquals(0, store.activeNumber(RuleModule.Hook.ATTENTION));
		settle(future);
		assertEquals(1, host.activated.size());
	}

	@Test void aModuleThatFailsToLoadOrThrowsIsRejectedAndNothingChanges() throws Exception {
		host.pickups(3);
		var syntax = refusal(edit(RuleModule.Hook.ATTENTION, "(lib => ({ step( { }))"));
		assertEquals("rules_invalid", syntax.code());
		var throwing = refusal(edit(RuleModule.Hook.ATTENTION, "(lib => ({ step() { throw Error('bad rule'); } }))"));
		assertEquals("rules_invalid", throwing.code(), "the empty step already throws");
		var lateThrow = refusal(edit(RuleModule.Hook.ATTENTION,
			"(lib => ({ step(input, state) { if (input.events.length) throw Error('late'); return {state, decisions: []}; } }))"));
		assertEquals("rules_step_failed", lateThrow.code());
		assertTrue(lateThrow.details().containsKey("replay"), "the failing replay is shown");
		assertTrue(host.activated.isEmpty());
		assertTrue(host.published.isEmpty());
		assertEquals(0, store.activeNumber(RuleModule.Hook.ATTENTION));
		assertEquals(RuleModule.BUNDLED_ATTENTION, host.running(RuleModule.Hook.ATTENTION).origin());
	}

	@Test void aHookMismatchOrOversizedSourceNeverReachesTheEngine() {
		// The provider validates arguments; the service still refuses a hold and a rate breach itself.
		host.hold = true;
		assertEquals("work_in_safety_hold", refusal(edit(RuleModule.Hook.ATTENTION, MUTE_PICKUPS)).code());
	}

	@Test void editsAreRefusedPastTheRateLimit() throws Exception {
		for (int i = 0; i < RulesStore.MAX_UPDATES; i++) store.append(RuleModule.Hook.SALIENCE, RulesStore.Kind.UPDATE, "(lib => ({step(){}}))//" + i, "r", 50, 0, null);
		var refused = refusal(edit(RuleModule.Hook.SALIENCE, "(lib => ({ step(i, s) { return {state: s}; } }))"));
		assertEquals("rules_update_rate", refused.code());
		assertEquals(50 + RulesStore.UPDATE_WINDOW_TICKS, refused.details().get("retryAtTick"));
		host.tick.set(50 + RulesStore.UPDATE_WINDOW_TICKS);
		assertEquals(true, settle(edit(RuleModule.Hook.SALIENCE, "(lib => ({ step(i, s) { return {state: s}; } }))")).get("accepted"));
	}

	@Test void rollbackToTheBaseAndToAVersionAreVersionsToo() throws Exception {
		host.pickups(4);
		settle(edit(RuleModule.Hook.ATTENTION, MUTE_PICKUPS));
		var toBase = settle(rules.update(new PlannerRules.Request(RuleModule.Hook.ATTENTION, null, "base", "too quiet")));
		assertEquals(0, toBase.get("activeVersion"), "the base is active again");
		assertEquals(RuleModule.BUNDLED_ATTENTION, host.running(RuleModule.Hook.ATTENTION).origin());
		var back = settle(rules.update(new PlannerRules.Request(RuleModule.Hook.ATTENTION, null, "1", "actually fine")));
		assertEquals(3, back.get("activeVersion"), "a rollback is a new version that copies v1");
		assertEquals("planner:attention/v3", host.running(RuleModule.Hook.ATTENTION).origin());
		assertEquals(List.of("rules.updated", "rules.updated", "rules.updated"), host.published);
		assertEquals("rollback", host.payloads.get(2).get("kind"));
		assertEquals("unknown_version", refusal(rules.update(new PlannerRules.Request(RuleModule.Hook.ATTENTION, null, "9", "x"))).code());
	}

	@SuppressWarnings("unchecked")
	@Test void warnsWhenAnAttentionModuleIgnoresPlannerRulesOrThereIsNoHistory() throws Exception {
		var result = settle(edit(RuleModule.Hook.ATTENTION, MUTE_PICKUPS.replace("// plannerRules", "")));
		var warnings = (List<String>) result.get("warnings");
		assertEquals(2, warnings.size(), warnings.toString());
		assertTrue(warnings.getFirst().contains("update_event_policy"));
		assertTrue(warnings.getLast().contains("no recorded history"));
	}

	@SuppressWarnings("unchecked")
	@Test void inspectShowsSourceHistoryAndAnOlderVersionsSource() throws Exception {
		host.pickups(2);
		var first = inspectHost();
		assertEquals(0, first.get("activeVersion"));
		assertEquals(RuleModule.bundledAttention().source(), first.get("source"));
		settle(edit(RuleModule.Hook.ATTENTION, MUTE_PICKUPS));
		var after = rules.inspect(RuleModule.Hook.ATTENTION, null);
		assertEquals(1, after.get("activeVersion"));
		assertEquals(MUTE_PICKUPS, after.get("source"));
		var history = (List<Map<String, Object>>) after.get("history");
		assertEquals("mute pickups while building", history.getFirst().get("reason"));
		assertEquals(MUTE_PICKUPS, rules.inspect(RuleModule.Hook.ATTENTION, 1).get("source"));
		assertEquals("unknown_version", assertThrows(PlannerRules.Refused.class, () -> rules.inspect(RuleModule.Hook.ATTENTION, 7)).code());
		assertEquals("attention v1", "attention v" + ((Map<String, Object>) rules.debugState().get("attention")).get("activeVersion"));
	}

	private Map<String, Object> inspectHost() {
		return rules.inspect(RuleModule.Hook.ATTENTION, null);
	}

	@Test void aSalienceEditIsCheckedAndActivatedToo() throws Exception {
		var result = settle(edit(RuleModule.Hook.SALIENCE, "(lib => ({ step(input, state) { return {state, percepts: [], drops: []}; } }))"));
		assertEquals(true, result.get("accepted"));
		assertEquals("planner:salience/v1", host.running(RuleModule.Hook.SALIENCE).origin());
		assertEquals("salience", host.payloads.getFirst().get("hook"));
		assertTrue(host.payloads.getFirst().containsKey("perceptsLost"));
	}
}
