package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import ai.moeru.airicraft.rules.RuleModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class AttentionReplayTest {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	private static final EventRoutingProfile PICKUP = new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false);
	private static final EventRoutingProfile DAMAGE = new EventRoutingProfile("combat.damage_taken", true, PlannerTriggerType.DAMAGE, false);

	@TempDir Path run;

	@Test void replaysARecordedRunThroughTheReferenceAndTheRules() throws Exception {
		var busyReflex = new AttentionState(false, false, true, null, true, false, false);
		var ignoreDirt = new EventPolicyRule("quiet-dirt", EventPolicyEffect.IGNORE,
			new EventPolicyMatch("pickup.item_picked_up", null, null, null, "minecraft:dirt", null, null), "noise", 1L, null, 0L, "planner");
		var events = List.of(
			new SemanticEvent(1, 10, 500, "pickup.item_picked_up", Map.of("itemId", "minecraft:oak_log", "count", 2)),
			new SemanticEvent(2, 11, 550, "combat.damage_taken", Map.of("amount", 3.0, "damageTypeId", "minecraft:mob_attack")),
			new SemanticEvent(3, 12, 600, "pickup.item_picked_up", Map.of("itemId", "minecraft:dirt", "count", 1)),
			new SemanticEvent(4, 13, 650, "task.notice", Map.of()));
		var decisions = List.of(
			decision(1, "pickup.item_picked_up", true, Delivery.IMMEDIATE, "catalog.trigger",
				new AttentionDecision.Inputs(AttentionState.idle(), AttentionEvidence.NONE, true, PICKUP, List.of())),
			// Recorded as a wake although the reflex owned actuation: the replay must flag it.
			decision(2, "combat.damage_taken", true, Delivery.IMMEDIATE, "catalog.trigger",
				new AttentionDecision.Inputs(busyReflex, AttentionEvidence.NONE, true, DAMAGE, List.of())),
			decision(3, "pickup.item_picked_up", false, Delivery.NONE, "quiet-dirt",
				new AttentionDecision.Inputs(AttentionState.idle(), AttentionEvidence.NONE, true, PICKUP, List.of(ignoreDirt))),
			decision(4, "task.notice", false, Delivery.NONE, "catalog.raw_only", null),
			decision(9, "pickup.item_picked_up", true, Delivery.IMMEDIATE, "catalog.trigger",
				new AttentionDecision.Inputs(AttentionState.idle(), AttentionEvidence.NONE, true, PICKUP, List.of())));
		writeJsonl("events.jsonl", events.stream().map(event -> Map.of("collectedAt", "t", "event", event)).toList());
		writeJsonl("attention-decisions.jsonl", decisions.stream().map(decision -> Map.of("collectedAt", "t", "decision", decision)).toList());

		Map<String, Object> report = AttentionReplay.replay(run, RuleModule.bundledAttention(), Duration.ofSeconds(60));

		assertEquals(AttentionReplay.SCHEMA, report.get("schema"));
		assertEquals(5, report.get("decisions"));
		assertEquals(3, report.get("replayed"));
		assertEquals(1, report.get("skippedWithoutInputs"));
		assertEquals(1, report.get("missingEvents"), "decision 9 has no recorded event");
		assertEquals(1, report.get("recordedVsReference"));
		assertEquals(0, report.get("referenceVsRules"), "the bundled rules decide like the reference");
		@SuppressWarnings("unchecked")
		var entries = (List<Map<String, Object>>) report.get("entries");
		var damage = entries.get(1);
		assertEquals(2L, damage.get("seqNo"));
		assertEquals(true, damage.get("recordedDiffersFromReference"));
		assertEquals("ownership.reflex_actuation", ((Map<?, ?>) damage.get("reference")).get("ruleId"));
		assertEquals("RULES", ((Map<?, ?>) damage.get("rules")).get("stage"));
		assertEquals("quiet-dirt", ((Map<?, ?>) entries.get(2).get("reference")).get("ruleId"), "recorded planner rules are replayed");
	}

	@Test void aRecordedDecisionRoundTripsWithItsInputs() {
		var log = new AttentionDecisionLog();
		var inputs = new AttentionDecision.Inputs(AttentionState.idle(), new AttentionEvidence(true, false, true), false, PICKUP, List.of());
		log.record(decision(5, "pickup.item_picked_up", true, Delivery.IMMEDIATE, "catalog.trigger", inputs));
		var restored = GSON.fromJson(GSON.toJson(log.query(null).decisions().getFirst()), AttentionDecision.class);
		assertEquals(inputs, restored.inputs());
		assertNull(((AttentionDecision) ((List<?>) log.debugState(1).get("latest")).getFirst()).inputs(),
			"debug views stay compact");
	}

	private static AttentionDecision decision(long seq, String type, boolean semantic, Delivery delivery, String ruleId, AttentionDecision.Inputs inputs) {
		return new AttentionDecision(seq, seq + 9, type, semantic, delivery, Urgency.LOW, AttentionStage.RULES, ruleId, "", delivery == Delivery.IMMEDIATE, inputs);
	}

	private void writeJsonl(String name, List<?> rows) throws Exception {
		var lines = new ArrayList<String>();
		for (Object row : rows) lines.add(GSON.toJson(row));
		Files.write(run.resolve(name), lines);
	}
}
