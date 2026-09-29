package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyMatch;
import ai.moeru.airicraft.agent.events.EventPolicyRule;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The bundled attention rules must decide exactly like the Java reference, which is also their fallback. */
class RuleDifferentialTest {
	private static final String[] JOBS = {null, "COLLECT_RESOURCE", "MINE_BLOCKS", "ENSURE_BLOCKS_IN_INVENTORY", "FOLLOW_PLAYER"};
	private static final String[] PLAYERS = {null, "Alex", "Steve"};
	private static final String[] ITEMS = {null, "minecraft:oak_log", "minecraft:diamond"};
	private static final String[] STATES = {null, "FAILED", "SUCCEEDED", ""};
	private static final String[] BLOCKS = {null, "minecraft:diamond_ore", "minecraft:chest"};
	private static final String[] CHANGES = {null, "dusk", "dawn", "rain_started"};
	private static final List<List<String>> TARGETS = List.of(List.of(), List.of("minecraft:diamond_ore"),
		List.of("minecraft:oak_log", "minecraft:diamond"));

	@Test void bundledRulesDecideExactlyLikeTheReference() throws Exception {
		RuleEngine.shared(RuleModule.bundledAttention()).awaitReady(Duration.ofSeconds(60));
		var profiles = new ArrayList<>(EventCatalog.defaults().routingProfiles().values());
		profiles.sort(java.util.Comparator.comparing(EventRoutingProfile::eventType));
		var random = new Random(20260928L);
		var mismatches = new ArrayList<String>();
		int compared = 0;
		for (int round = 0; round < 5_000; round++) {
			var profile = profiles.get(random.nextInt(profiles.size()));
			var state = new AttentionState(random.nextBoolean(), random.nextBoolean(), random.nextBoolean(),
				JOBS[random.nextInt(JOBS.length)], random.nextBoolean(), random.nextInt(4) == 0, random.nextBoolean(),
				TARGETS.get(random.nextInt(TARGETS.size())));
			var evidence = new AttentionEvidence(random.nextBoolean(), random.nextInt(4) == 0, random.nextBoolean());
			var payload = new HashMap<String, Object>();
			put(payload, "player", PLAYERS[random.nextInt(PLAYERS.length)]);
			put(payload, "actor", random.nextInt(3) == 0 ? "self" : null);
			put(payload, "itemId", ITEMS[random.nextInt(ITEMS.length)]);
			put(payload, "state", STATES[random.nextInt(STATES.length)]);
			put(payload, "blockId", BLOCKS[random.nextInt(BLOCKS.length)]);
			put(payload, "change", CHANGES[random.nextInt(CHANGES.length)]);
			if (random.nextInt(5) == 0) payload.put("amount", 3.5F);
			var event = new SemanticEvent(round + 1, round, round * 50L, profile.eventType(), Map.copyOf(payload));
			var rules = rules(random, profile.eventType());
			boolean plannerEnabled = random.nextInt(5) != 0;

			var reference = ReferenceAttentionPolicy.decide(state, evidence, event, profile, rules, plannerEnabled);
			var ruled = new RuleAttentionPolicy(() -> state, ignored -> evidence, new SemanticEventBuffer(8), RuleModule.bundledAttention())
				.decide(event, profile, rules, plannerEnabled);
			compared++;
			if (!reference.equals(ruled)) mismatches.add(profile.eventType() + " state=" + state + " evidence=" + evidence
				+ " payload=" + payload + " rules=" + rules.activeRules() + "\n  reference=" + reference + "\n  rules    =" + ruled);
			if (mismatches.size() > 5) break;
		}
		assertEquals(List.of(), mismatches, "rule decisions differ from the reference after " + compared + " cases");
	}

	private static EventPolicyState rules(Random random, String type) {
		var rules = new EventPolicyState();
		int count = random.nextInt(4);
		for (int index = 0; index < count; index++) {
			var effect = EventPolicyEffect.values()[random.nextInt(EventPolicyEffect.values().length)];
			// Mostly this event's type, sometimes another, with or without a narrowing field; ids may be absent.
			String eventType = random.nextInt(4) == 0 ? "pickup.item_picked_up" : type;
			var match = new EventPolicyMatch(eventType, random.nextInt(3) == 0 ? PLAYERS[1 + random.nextInt(2)] : null,
				random.nextInt(4) == 0 ? "server" : null, null, random.nextInt(3) == 0 ? ITEMS[1 + random.nextInt(2)] : null, null, null);
			String ruleId = random.nextInt(5) == 0 ? null : "rule-" + index;
			rules.upsert(new EventPolicyRule(ruleId, effect, match, random.nextBoolean() ? "because" : null, 1L, null, 0L, "planner"));
		}
		return rules;
	}

	private static void put(Map<String, Object> payload, String key, String value) {
		if (value != null) payload.put(key, value);
	}
}
