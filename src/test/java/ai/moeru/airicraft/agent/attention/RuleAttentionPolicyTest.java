package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleAttentionPolicyTest {
	private static final EventRoutingProfile SMELTING = new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true);
	private static final EventRoutingProfile PICKUP = new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false);

	private static RuleModule module(String name, String source) throws Exception {
		var module = new RuleModule("config:" + name + ".js", source);
		RuleEngine.shared(module).awaitReady(Duration.ofSeconds(60));
		return module;
	}

	private static SemanticEvent event(long seq, String type) {
		return new SemanticEvent(seq, seq, seq * 50, type, Map.of());
	}

	@Test void protectedTypesAreClampedWhenRulesTryToSilenceThem() throws Exception {
		var silence = module("silence", """
			(lib => ({ step(input, state) {
			  return {state, decisions: input.events.map(e => ({seqNo: e.seqNo, emitSemantic: false,
			    ruleMatch: {effect: 'IGNORE', ruleIndex: -1, ruleId: 'mute', reason: 'quiet', bypassed: false},
			    policy: {effect: 'IGNORE', ruleIndex: -1, ruleId: 'mute', reason: 'quiet', bypassed: false},
			    wake: {delivery: 'NONE', urgency: 'LOW', ruleId: 'mute', reason: 'quiet'}}))};
			} }))
			""");
		var feed = new SemanticEventBuffer(8);
		var policy = new RuleAttentionPolicy(AttentionState::idle, event -> AttentionEvidence.NONE, feed, silence);
		var smelting = policy.decide(event(1, "smelting.output_ready"), SMELTING, new EventPolicyState(), true);
		assertTrue(smelting.wake().wakes());
		assertEquals(AttentionStage.CLAMP, smelting.wake().stage());
		assertTrue(smelting.policy().bypassed());
		assertTrue(smelting.emitSemantic());
		var pickup = policy.decide(event(2, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true);
		assertFalse(pickup.wake().wakes(), "an unprotected type follows the rules");
		assertEquals("mute", pickup.wake().ruleId());
		assertEquals(1L, policy.debugState().get("clamps"));
	}

	@Test void repeatedFailuresFallBackThenRevertToTheBundledModule() throws Exception {
		var broken = module("throws", "(lib => ({ step() { throw Error('bad rule'); } }))");
		RuleEngine.shared(RuleModule.bundledAttention()).awaitReady(Duration.ofSeconds(60));
		var diagnostics = new SemanticEventBuffer(16);
		var policy = new RuleAttentionPolicy(AttentionState::idle, event -> AttentionEvidence.NONE, diagnostics, broken);
		for (long seq = 1; seq <= 3; seq++) {
			var outcome = policy.decide(event(seq, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true);
			assertEquals(AttentionStage.FALLBACK, outcome.wake().stage(), "the reference decides while the rules fail");
			assertTrue(outcome.wake().wakes());
		}
		assertEquals(RuleModule.BUNDLED_ATTENTION, policy.module().origin());
		var published = diagnostics.query(null).events().stream().map(SemanticEvent::type).toList();
		assertEquals(java.util.List.of("rules.step_failed", "rules.step_failed", "rules.step_failed", "rules.reverted"), published);
		assertEquals(AttentionStage.RULES, policy.decide(event(4, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true).wake().stage());
	}

	@Test void aFailingPlannerVersionRevertsToThePreviousVersionNotTheBundledModule() throws Exception {
		var store = new ai.moeru.airicraft.rules.RulesStore();
		String mute = """
			(lib => ({ step(input, state) {
			  return {state, decisions: input.events.map(e => ({seqNo: e.seqNo, emitSemantic: false,
			    ruleMatch: {effect: 'IGNORE', ruleIndex: -1, ruleId: null, reason: null, bypassed: false},
			    policy: {effect: 'IGNORE', ruleIndex: -1, ruleId: null, reason: null, bypassed: false},
			    wake: {delivery: 'NONE', urgency: 'LOW', ruleId: 'mine.mute', reason: 'quiet'}}))};
			} }))
			""";
		var first = store.append(RuleModule.Hook.ATTENTION, ai.moeru.airicraft.rules.RulesStore.Kind.UPDATE, mute, "mute", 1, 0, null);
		store.append(RuleModule.Hook.ATTENTION, ai.moeru.airicraft.rules.RulesStore.Kind.UPDATE,
			"(lib => ({ step() { throw Error('bad rule'); } }))", "break", 2, first.number(), null);
		var diagnostics = new SemanticEventBuffer(16);
		RuleEngine.shared(store.activeModule(RuleModule.Hook.ATTENTION)).awaitReady(Duration.ofSeconds(60));
		var policy = new RuleAttentionPolicy(AttentionState::idle, event -> AttentionEvidence.NONE, diagnostics, store.activeModule(RuleModule.Hook.ATTENTION));
		policy.useRevert(store);
		for (long seq = 1; seq <= 3; seq++) policy.decide(event(seq, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true);
		assertEquals("planner:attention/v3", policy.module().origin(), "a copy of v1, the version v2 replaced");
		var reverted = diagnostics.query(null).events().getLast();
		assertEquals("rules.reverted", reverted.type());
		assertEquals("attention", reverted.payload().get("hook"));
		assertEquals(2, reverted.payload().get("fromVersion"));
		assertEquals(3, reverted.payload().get("toVersion"));
		RuleEngine.shared(policy.module()).awaitReady(Duration.ofSeconds(60));
		assertEquals("mine.mute", policy.decide(event(4, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true).wake().ruleId());
	}

	@Test void malformedSequenceNumbersFailTheStepAndFallBack() throws Exception {
		for (String seqNo : java.util.List.of("'oops'", "null", "{}")) {
			var module = module("bad-seq-" + seqNo.hashCode(), "(lib => ({ step(input, state) { return {state, decisions: [{seqNo: " + seqNo + "}]}; } }))");
			var diagnostics = new SemanticEventBuffer(8);
			var policy = new RuleAttentionPolicy(AttentionState::idle, event -> AttentionEvidence.NONE, diagnostics, module);
			var outcome = assertDoesNotThrow(() -> policy.decide(event(1, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true));
			assertEquals(AttentionStage.FALLBACK, outcome.wake().stage(), seqNo);
			assertTrue(outcome.wake().wakes(), "the reference decision is used");
			assertTrue(String.valueOf(policy.debugState().get("lastFailure")).startsWith("malformed_decision"), seqNo);
			assertEquals("rules.step_failed", diagnostics.query(null).events().getFirst().type());
		}
	}

	@Test void evaluationSuppressionHoldsEvenWhenAnOverrideWakesAnIgnoredEvent() throws Exception {
		var ignoreButWake = module("ignore-but-wake", """
			(lib => ({ step(input, state) {
			  return {state, decisions: input.events.map(e => ({seqNo: e.seqNo, emitSemantic: false,
			    ruleMatch: {effect: 'IGNORE', ruleIndex: -1, ruleId: 'loud', reason: 'x', bypassed: false},
			    policy: {effect: 'IGNORE', ruleIndex: -1, ruleId: 'loud', reason: 'x', bypassed: false},
			    wake: {delivery: 'IMMEDIATE', urgency: 'HIGH', ruleId: 'loud', reason: 'x'}}))};
			} }))
			""");
		var suppressed = new AttentionState(true, false, false, null, true, false, false);
		var policy = new RuleAttentionPolicy(() -> suppressed, event -> AttentionEvidence.NONE, new SemanticEventBuffer(8), ignoreButWake);
		var outcome = policy.decide(event(1, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true);
		assertFalse(outcome.wake().wakes(), "no autonomous wake after an evaluation");
		assertEquals(AttentionStage.CONSTITUTION, outcome.wake().stage());
		assertEquals("constitution.evaluation_suppressed", outcome.wake().ruleId());
		var unsuppressed = new RuleAttentionPolicy(AttentionState::idle, event -> AttentionEvidence.NONE, new SemanticEventBuffer(8), ignoreButWake);
		assertTrue(unsuppressed.decide(event(2, "pickup.item_picked_up"), PICKUP, new EventPolicyState(), true).wake().wakes(),
			"outside suppression the override's wake stands");
	}

	@Test void constitutionTypesNeverReachTheRules() throws Exception {
		var broken = module("never-called", "(lib => ({ step() { throw Error('must not run'); } }))");
		var diagnostics = new SemanticEventBuffer(8);
		var policy = new RuleAttentionPolicy(AttentionState::idle, event -> new AttentionEvidence(true, false, true), diagnostics, broken);
		var chat = policy.decide(event(1, "social.player_addressed_agent"),
			new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true), new EventPolicyState(), true);
		assertEquals(AttentionStage.CONSTITUTION, chat.wake().stage());
		assertEquals(0, diagnostics.size());
	}
}
