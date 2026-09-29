package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The bundled attention module's autonomous-wake budget, with its state threaded through consecutive steps. */
class AttentionBudgetRulesTest {
	private static final AttentionEvidence EVIDENCE = new AttentionEvidence(false, false, true);

	@BeforeAll static void warm() throws Exception {
		RuleEngine.shared(RuleModule.bundledAttention()).awaitReady(Duration.ofSeconds(60));
	}

	private final RuleAttentionPolicy rules = new RuleAttentionPolicy(AttentionState::idle, ignored -> EVIDENCE,
		new SemanticEventBuffer(8), RuleModule.bundledAttention());
	private long seqNo;

	private WakeDecision decide(String type, long tick) {
		var profile = EventCatalog.defaults().routingProfiles().get(type);
		var event = new SemanticEvent(++seqNo, tick, tick * 50L, type, Map.of("itemId", "minecraft:oak_log", "count", 1));
		return rules.decide(event, profile, new EventPolicyState(), true).wake();
	}

	@Test void aPickupStormIsMutedAfterTheBurstAndTheBucketRefills() {
		var ruleIds = new ArrayList<String>();
		for (long tick = 1; tick <= 40; tick++) ruleIds.add(decide("pickup.item_picked_up", tick).ruleId());
		assertEquals(List.of("catalog.trigger"), ruleIds.subList(0, 10).stream().distinct().toList(), "a burst of 10 wakes");
		assertEquals(List.of("budget.autonomous_wakes"), ruleIds.subList(10, 40).stream().distinct().toList());
		var muted = decide("pickup.item_picked_up", 41);
		assertFalse(muted.wakes());
		assertTrue(muted.reason().startsWith("autonomous wake budget spent (level "), muted.reason());

		assertTrue(decide("pickup.item_picked_up", 41 + 1_000).wakes(), "1,000 quiet ticks leak the bucket");
	}

	@Test void protectedAndUrgentWakesAreNeverBudgeted() {
		for (long tick = 1; tick <= 20; tick++) decide("pickup.item_picked_up", tick);
		assertFalse(decide("pickup.item_picked_up", 21).wakes(), "the bucket is spent");
		assertTrue(decide("player.physical", 21).wakes(), "a protected type keeps its wake");
		assertTrue(decide("smelting.output_ready", 21).wakes(), "HIGH urgency is not autonomous chatter");
		assertEquals("catalog.trigger", decide("task.blocked", 22).ruleId());
	}
}
