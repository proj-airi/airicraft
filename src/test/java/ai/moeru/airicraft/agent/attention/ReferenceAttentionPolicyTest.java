package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One row per attention branch that moved out of the trigger factories and the default policy resolver. */
class ReferenceAttentionPolicyTest {
	private static final AttentionState IDLE = AttentionState.idle();
	private static final AttentionEvidence NEAR = new AttentionEvidence(false, false, true);

	private record Row(String name, String type, Map<String, Object> payload, AttentionState state, AttentionEvidence evidence,
		Delivery delivery, Urgency urgency, AttentionStage stage, String ruleId) { }

	private static AttentionState state(boolean suppressed, boolean proactive, boolean reflex, String job, boolean pendingCraft) {
		return new AttentionState(suppressed, proactive, reflex, job, job == null, false, pendingCraft);
	}

	@Test void gateReproducesEveryMovedFactoryBranch() {
		var rows = new Row[] {
			// createPlannerTrigger: evaluation suppression list
			new Row("suppressed pickup", "pickup.item_picked_up", Map.of(), state(true, true, false, null, false), NEAR,
				Delivery.NONE, Urgency.LOW, AttentionStage.CONSTITUTION, "constitution.evaluation_suppressed"),
			new Row("addressed chat is never evaluation-suppressed", "social.player_addressed_agent", Map.of(), state(true, true, false, null, false), NEAR,
				Delivery.IMMEDIATE, Urgency.DIRECT, AttentionStage.CONSTITUTION, "constitution.direct_chat"),
			// createAddressedChatTrigger
			new Row("addressed reset command", "social.player_addressed_agent", Map.of(), IDLE, new AttentionEvidence(true, true, true),
				Delivery.NONE, Urgency.LOW, AttentionStage.CONSTITUTION, "constitution.reset_command"),
			new Row("addressed out of range", "social.player_addressed_agent", Map.of(), IDLE, new AttentionEvidence(true, false, false),
				Delivery.NONE, Urgency.LOW, AttentionStage.CONSTITUTION, "constitution.chat_distance"),
			// createLocalControllerTrigger
			new Row("controller reset", "social.local_controller_spoke", Map.of(), IDLE, new AttentionEvidence(false, true, false),
				Delivery.NONE, Urgency.LOW, AttentionStage.CONSTITUTION, "constitution.reset_command"),
			new Row("controller chat ignores distance", "social.local_controller_spoke", Map.of(), IDLE, AttentionEvidence.NONE,
				Delivery.IMMEDIATE, Urgency.DIRECT, AttentionStage.CONSTITUTION, "constitution.direct_chat"),
			// createAiriCommandTrigger
			new Row("AIRI command wakes even when evaluation is suppressed", "social.airi_commanded", Map.of(), state(true, false, false, null, false),
				AttentionEvidence.NONE, Delivery.IMMEDIATE, Urgency.DIRECT, AttentionStage.CONSTITUTION, "constitution.airi_command"),
			// createReflexResolvedTrigger
			new Row("reflex resolved", "reflex.resolved", Map.of(), state(false, false, true, null, false), AttentionEvidence.NONE,
				Delivery.IMMEDIATE, Urgency.CRITICAL, AttentionStage.CONSTITUTION, "constitution.safety_handoff"),
			// createPlayerSpokeTrigger
			new Row("ambient chat that is addressed", "social.player_spoke", Map.of(), state(false, true, false, null, false), new AttentionEvidence(true, false, true),
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "chat.addressed_routed_separately"),
			new Row("ambient chat, proactive off", "social.player_spoke", Map.of(), IDLE, NEAR,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "chat.proactive_mode_off"),
			new Row("ambient chat out of range", "social.player_spoke", Map.of(), state(false, true, false, null, false), AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "chat.distance"),
			new Row("ambient chat wakes", "social.player_spoke", Map.of(), state(false, true, false, null, false), NEAR,
				Delivery.IMMEDIATE, Urgency.LOW, AttentionStage.RULES, "chat.ambient"),
			// createSystemTrigger
			new Row("system message, proactive off", "social.system_message", Map.of(), IDLE, AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "chat.proactive_mode_off"),
			new Row("system message wakes", "social.system_message", Map.of(), state(false, true, false, null, false), AttentionEvidence.NONE,
				Delivery.IMMEDIATE, Urgency.LOW, AttentionStage.RULES, "chat.system_message"),
			// createPickupTrigger / createCraftTrigger
			new Row("pickup during collect-resource", "pickup.item_picked_up", Map.of(), state(false, false, false, "COLLECT_RESOURCE", false), AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "ownership.collect_resource_progress"),
			new Row("pickup otherwise", "pickup.item_picked_up", Map.of(), state(false, false, false, "MINE_BLOCKS", false), AttentionEvidence.NONE,
				Delivery.IMMEDIATE, Urgency.LOW, AttentionStage.RULES, "catalog.trigger"),
			new Row("craft during collect-resource", "crafting.item_crafted", Map.of(), state(false, false, false, "COLLECT_RESOURCE", true), AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "ownership.collect_resource_progress"),
			new Row("craft with pending craft result", "crafting.item_crafted", Map.of(), state(false, false, false, null, true), AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "ownership.pending_craft_result"),
			// createDamageTrigger / createPhysicalTrigger
			new Row("damage while reflex owns actuation", "combat.damage_taken", Map.of(), state(false, false, true, null, false), AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "ownership.reflex_actuation"),
			new Row("physical while reflex owns actuation", "player.physical", Map.of(), state(false, false, true, null, false), AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "ownership.reflex_actuation"),
			new Row("physical otherwise", "player.physical", Map.of(), IDLE, AttentionEvidence.NONE,
				Delivery.IMMEDIATE, Urgency.NORMAL, AttentionStage.RULES, "catalog.trigger"),
			// createActionGraphTerminalTrigger
			new Row("graph succeeded", "action_graph.goal_terminal", Map.of("state", "SUCCEEDED"), IDLE, AttentionEvidence.NONE,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "graph.terminal_not_failed"),
			new Row("graph failed", "action_graph.goal_terminal", Map.of("state", "FAILED"), IDLE, AttentionEvidence.NONE,
				Delivery.IMMEDIATE, Urgency.HIGH, AttentionStage.RULES, "catalog.trigger"),
			new Row("smelting output", "smelting.output_ready", Map.of(), IDLE, AttentionEvidence.NONE,
				Delivery.IMMEDIATE, Urgency.HIGH, AttentionStage.RULES, "catalog.trigger"),
			// Phase 4 percepts: a running job owns percepts about its own targets; otherwise they debounce.
			new Row("noticed vein, idle", "perception.block_noticed", Map.of("blockId", "minecraft:diamond_ore"), IDLE, NEAR,
				Delivery.DEBOUNCE, Urgency.LOW, AttentionStage.RULES, "percept.notice"),
			new Row("noticed vein owned by the mining job", "perception.block_noticed", Map.of("blockId", "minecraft:diamond_ore"),
				new AttentionState(false, false, false, "MINE_BLOCKS", false, false, false, java.util.List.of("minecraft:diamond_ore")), NEAR,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "ownership.active_job_target"),
			new Row("noticed item owned by a collect job", "perception.item_noticed", Map.of("itemId", "minecraft:diamond"),
				new AttentionState(false, false, false, "COLLECT_RESOURCE", false, false, false, java.util.List.of("minecraft:diamond")), NEAR,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "ownership.active_job_target"),
			new Row("a terminal job owns nothing", "perception.item_noticed", Map.of("itemId", "minecraft:diamond"),
				new AttentionState(false, false, false, "COLLECT_RESOURCE", false, true, false, java.util.List.of("minecraft:diamond")), NEAR,
				Delivery.DEBOUNCE, Urgency.LOW, AttentionStage.RULES, "percept.notice"),
			new Row("dusk while idle", "perception.environment_changed", Map.of("change", "dusk"), IDLE, NEAR,
				Delivery.DEBOUNCE, Urgency.LOW, AttentionStage.RULES, "percept.dusk_idle"),
			new Row("dusk while working", "perception.environment_changed", Map.of("change", "dusk"), state(false, false, false, "MINE_BLOCKS", false), NEAR, Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "percept.environment_evidence"),
			new Row("rain is evidence only", "perception.environment_changed", Map.of("change", "rain_started"), IDLE, NEAR,
				Delivery.NONE, Urgency.LOW, AttentionStage.RULES, "percept.environment_evidence"),
		};
		for (var row : rows) {
			var decision = ReferenceAttentionPolicy.gate(event(row.type(), row.payload()), row.state(), row.evidence());
			assertEquals(row.delivery(), decision.delivery(), row.name());
			assertEquals(row.urgency(), decision.urgency(), row.name());
			assertEquals(row.stage(), decision.stage(), row.name());
			assertEquals(row.ruleId(), decision.ruleId(), row.name());
		}
	}

	@Test void everyTriggerEligibleCatalogTypeHasAnExplicitGateBranch() {
		for (var entry : EventCatalog.defaults().routingProfiles().entrySet()) {
			if (!entry.getValue().triggerEligible()) continue;
			var decision = ReferenceAttentionPolicy.gate(event(entry.getKey(), Map.of("state", "FAILED")),
				state(false, true, false, null, false), NEAR);
			assertNotEquals("catalog.no_trigger", decision.ruleId(), entry.getKey());
		}
	}

	@Test void defaultRuleKeepsPickupsQuietDuringMiningJobs() {
		var pickup = event("pickup.item_picked_up", Map.of());
		assertEquals(EventPolicyEffect.SEMANTIC_ONLY, ReferenceAttentionPolicy.defaultRule(pickup, state(false, false, false, "MINE_BLOCKS", false)).effect());
		assertEquals(EventPolicyEffect.SEMANTIC_ONLY, ReferenceAttentionPolicy.defaultRule(pickup, state(false, false, false, "ENSURE_BLOCKS_IN_INVENTORY", false)).effect());
		assertEquals(EventPolicyEffect.ALLOW, ReferenceAttentionPolicy.defaultRule(pickup, state(false, false, false, "COLLECT_RESOURCE", false)).effect());
		assertEquals(EventPolicyEffect.ALLOW, ReferenceAttentionPolicy.defaultRule(pickup, new AttentionState(false, false, false, "MINE_BLOCKS", false, true, false)).effect());
		assertEquals(EventPolicyEffect.ALLOW, ReferenceAttentionPolicy.defaultRule(event("crafting.item_crafted", Map.of()), state(false, false, false, "MINE_BLOCKS", false)).effect());
	}

	@Test void plannerRulesWinOverTheDefaultRuleAndBypassedTypesSkipBoth() {
		var rules = new EventPolicyState();
		var pickupProfile = new EventRoutingProfile("pickup.item_picked_up", true, ai.moeru.airicraft.agent.llm.PlannerTriggerType.PICKUP, false);
		var policy = new ReferenceAttentionPolicy(() -> state(false, false, false, "MINE_BLOCKS", false), event -> AttentionEvidence.NONE);
		var mined = policy.decide(event("pickup.item_picked_up", Map.of()), pickupProfile, rules, true);
		assertEquals("default-mining-pickup-semantic-only", mined.policy().matchedRuleId());
		assertTrue(mined.emitSemantic());
		assertFalse(mined.wake().wakes());

		rules.upsert(new EventPolicyRule("wake-pickups", EventPolicyEffect.TRIGGER_ONLY,
			new EventPolicyMatch("pickup.item_picked_up", null, null, null, null, null, null), "watch pickups", 1, null, 0, "planner"));
		var ruled = policy.decide(event("pickup.item_picked_up", Map.of()), pickupProfile, rules, true);
		assertEquals("wake-pickups", ruled.policy().matchedRuleId());
		assertEquals(0, ruled.ruleMatchIndex());
		assertFalse(ruled.emitSemantic());
		assertTrue(ruled.wake().wakes());
		assertEquals(0, rules.activeRules().getFirst().matchCount(), "deciding never records a match");

		var reflexProfile = new EventRoutingProfile("reflex.resolved", true, ai.moeru.airicraft.agent.llm.PlannerTriggerType.SYSTEM, true);
		rules.upsert(new EventPolicyRule("mute-reflex", EventPolicyEffect.IGNORE,
			new EventPolicyMatch("reflex.resolved", null, null, null, null, null, null), "", 1, null, 0, "planner"));
		var bypassed = policy.decide(event("reflex.resolved", Map.of()), reflexProfile, rules, true);
		assertTrue(bypassed.policy().bypassed());
		assertEquals("constitution.safety_handoff", bypassed.wake().ruleId());
	}

	private static SemanticEvent event(String type, Map<String, Object> payload) {
		return new SemanticEvent(1, 1, 1, type, payload);
	}
}
