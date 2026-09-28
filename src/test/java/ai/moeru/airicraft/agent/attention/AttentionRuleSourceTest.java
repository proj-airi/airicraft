package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.ConfigLoadException;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AttentionRuleSourceTest {
	private static final Duration WARM = Duration.ofSeconds(60);
	private static final EventRoutingProfile PICKUP = new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false);
	private static final String LOUD_PICKUPS = """
		// Wakes on every event with its own rule id.
		(lib => ({ step(input, state) {
		  return {state, decisions: input.events.map(e => ({seqNo: e.seqNo, emitSemantic: true,
		    ruleMatch: {effect: 'ALLOW', ruleIndex: -1, ruleId: null, reason: null, bypassed: false},
		    policy: {effect: 'ALLOW', ruleIndex: -1, ruleId: null, reason: null, bypassed: false},
		    wake: {delivery: 'IMMEDIATE', urgency: 'HIGH', ruleId: 'override.loud', reason: 'custom'}}))};
		} }))
		""";

	@TempDir Path configDir;

	private void writeOverride(String source) throws Exception {
		Path path = configDir.resolve(AttentionRuleSource.RELATIVE_PATH);
		Files.createDirectories(path.getParent());
		Files.writeString(path, source);
	}

	private static SemanticEvent pickup(long seq) {
		return new SemanticEvent(seq, seq, seq * 50, "pickup.item_picked_up", Map.of());
	}

	@Test void withoutAnOverrideTheBundledModuleIsUsed() throws Exception {
		assertEquals(RuleModule.BUNDLED_ATTENTION, AttentionRuleSource.load(configDir).origin());
		assertEquals(RuleModule.BUNDLED_ATTENTION, AttentionRuleSource.loadStrict(configDir, WARM).origin());
	}

	@Test void aValidOverrideReplacesTheBundledRules() throws Exception {
		writeOverride(LOUD_PICKUPS);
		RuleModule module = AttentionRuleSource.loadStrict(configDir, WARM);
		assertEquals("config:rules/attention.js", module.origin());
		RuleEngine.shared(module).awaitReady(WARM);

		var policy = new RuleAttentionPolicy(AttentionState::idle, event -> AttentionEvidence.NONE, new SemanticEventBuffer(8),
			RuleModule.bundledAttention());
		policy.useModule(module);
		var outcome = policy.decide(pickup(1), PICKUP, new EventPolicyState(), true);
		assertEquals(AttentionStage.RULES, outcome.wake().stage());
		assertEquals("override.loud", outcome.wake().ruleId());
		assertEquals(Urgency.HIGH, outcome.wake().urgency());
		assertEquals("config:rules/attention.js", policy.debugState().get("module"));
	}

	@Test void reloadRejectsAnInvalidOverrideButStartupFallsBack() throws Exception {
		writeOverride("(lib => ({ step(input, state) { return {");
		var failure = assertThrows(ConfigLoadException.class, () -> AttentionRuleSource.loadStrict(configDir, WARM));
		assertTrue(failure.getMessage().contains("rules/attention.js"), failure.getMessage());
		assertTrue(failure.getMessage().contains("load_failed"), failure.getMessage());

		writeOverride("(lib => ({ step() { return 1; } }))");
		assertTrue(assertThrows(ConfigLoadException.class, () -> AttentionRuleSource.loadStrict(configDir, WARM))
			.getMessage().contains("guest_error"), "a module that cannot step an empty input is rejected");

		// Startup does not validate: the policy reports the load failure and reverts to the bundled module.
		writeOverride("(lib => ({ step(input, state) { return {");
		RuleModule startup = AttentionRuleSource.load(configDir);
		assertEquals("config:rules/attention.js", startup.origin());
		RuleEngine engine = RuleEngine.shared(startup);
		assertThrows(Exception.class, () -> engine.awaitReady(WARM));
		RuleEngine.shared(RuleModule.bundledAttention()).awaitReady(WARM);
		var diagnostics = new SemanticEventBuffer(8);
		var policy = new RuleAttentionPolicy(AttentionState::idle, event -> AttentionEvidence.NONE, diagnostics, startup);
		assertEquals(AttentionStage.FALLBACK, policy.decide(pickup(1), PICKUP, new EventPolicyState(), true).wake().stage());
		assertEquals(RuleModule.BUNDLED_ATTENTION, policy.module().origin());
		assertEquals(List.of("rules.step_failed", "rules.reverted"),
			diagnostics.query(null).events().stream().map(SemanticEvent::type).toList());
		assertEquals(AttentionStage.RULES, policy.decide(pickup(2), PICKUP, new EventPolicyState(), true).wake().stage());
	}
}
