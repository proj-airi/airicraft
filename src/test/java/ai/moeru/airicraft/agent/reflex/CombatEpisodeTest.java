package ai.moeru.airicraft.agent.reflex;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CombatEpisodeTest {
	private static CombatEpisode.Target target(String id, CombatEpisode.Outcome outcome, float health) {
		return new CombatEpisode.Target(id, id, "minecraft:" + id, outcome, health, 4);
	}

	@Test void retainsConfirmedDeathsAcrossRemovalAndReportsSurvivors() {
		var episode = new CombatEpisode(100, 20);
		episode.observe(18, List.of(target("zombie", CombatEpisode.Outcome.ALIVE, 12),
			target("skeleton", CombatEpisode.Outcome.ALIVE, 20)));
		episode.observe(16, List.of(target("zombie", CombatEpisode.Outcome.CONFIRMED_DEAD, 0),
			target("skeleton", CombatEpisode.Outcome.ALIVE, 7)));
		episode.observe(16, List.of(target("skeleton", CombatEpisode.Outcome.ALIVE, 7)));
		var summary = episode.summary(180, "sealed_shelter");
		assertEquals(80L, summary.get("durationTicks"));
		assertEquals(20F, summary.get("healthBefore"));
		assertEquals(16F, summary.get("healthAfter"));
		assertEquals(4F, summary.get("observedHealthLoss"));
		assertEquals("zombie", entries(summary, "confirmedDead").getFirst().get("uuid"));
		assertEquals(7F, entries(summary, "surviving").getFirst().get("lastObservedHealth"));
		assertTrue(entries(summary, "unconfirmed").isEmpty());
		assertTrue(summary.get("text").toString().contains("Confirmed dead (1)"));
		assertTrue(summary.get("text").toString().contains("Still alive (1)"));
	}

	@Test void disappearingTargetsAreUnknownUntilObservedAgain() {
		var episode = new CombatEpisode(1, 20);
		episode.observe(20, List.of(target("zombie", CombatEpisode.Outcome.ALIVE, 6)));
		episode.observe(20, List.of());
		var lost = episode.summary(10, "no_eligible_threats");
		assertTrue(entries(lost, "confirmedDead").isEmpty());
		assertTrue(entries(lost, "surviving").isEmpty());
		assertEquals(6F, entries(lost, "unconfirmed").getFirst().get("lastObservedHealth"));
		assertTrue(lost.get("text").toString().contains("Outcome unknown (1)"));
		episode.observe(20, List.of(target("zombie", CombatEpisode.Outcome.ALIVE, 6)));
		assertEquals(1, entries(episode.summary(11, "combat_stalemate"), "surviving").size());
	}

	@Test void healthRecoveryDoesNotEraseObservedLossOrLeakBetweenEpisodes() {
		var episode = new CombatEpisode(1, 20);
		episode.observe(14, List.of());
		episode.observe(18, List.of());
		episode.observe(16, List.of());
		assertEquals(8F, episode.summary(20, "no_eligible_threats").get("observedHealthLoss"));
		assertEquals(0F, new CombatEpisode(21, 16).summary(22, "no_eligible_threats").get("observedHealthLoss"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> entries(Map<String, Object> summary, String key) {
		return (List<Map<String, Object>>) assertInstanceOf(List.class, summary.get(key));
	}
}
