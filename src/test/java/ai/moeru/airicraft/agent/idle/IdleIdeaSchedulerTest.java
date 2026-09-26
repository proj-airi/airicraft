package ai.moeru.airicraft.agent.idle;

import ai.moeru.airicraft.agent.llm.PlannerTrigger;
import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdleIdeaSchedulerTest {
	private static final long BASE_MS = 1_000_000_000L;
	private static final List<String> IDEAS = List.of(
		"Idea A",
		"Idea B"
	);

	@Test
	void doesNotFireBeforeInitialDelay() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 29_999L).isEmpty());
	}

	@Test
	void firesOnceAfterInitialDelay() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		Optional<PlannerTrigger> trigger = scheduler.tick(true, 2L, BASE_MS + 30_000L);

		assertTrue(trigger.isPresent());
		assertEquals(PlannerTriggerType.IDLE_THINK, trigger.get().type());
		assertEquals("self", trigger.get().speaker());
		assertTrue(trigger.get().text().startsWith("IDLE THINK:"));
		assertTrue(trigger.get().text().contains("Idea A"));
		assertTrue(trigger.get().text().contains("Idea B"));
		assertTrue(trigger.get().text().contains("smelting a log into minecraft:charcoal"));
		assertTrue(trigger.get().text().contains("crafting minecraft:torch from charcoal and sticks"));
	}

	@Test
	void enforcesCooldownBetweenFires() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 30_000L).isPresent());
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 60_000L).isEmpty());
		assertTrue(scheduler.tick(true, 4L, BASE_MS + 119_999L).isEmpty());
		assertTrue(scheduler.tick(true, 5L, BASE_MS + 120_000L).isPresent());
	}

	@Test
	void fireNowBypassesDelayAndCooldown() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		Optional<PlannerTrigger> first = scheduler.fireNow(1L, BASE_MS);
		Optional<PlannerTrigger> second = scheduler.fireNow(2L, BASE_MS + 1_000L);

		assertTrue(first.isPresent());
		assertTrue(second.isPresent());
		assertEquals(PlannerTriggerType.IDLE_THINK, first.get().type());
		assertEquals(2L, second.get().tick());
	}

	@Test
	void fireNowBypassesEnabledSwitch() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(false, 30, 90, IDEAS));

		Optional<PlannerTrigger> trigger = scheduler.fireNow(1L, BASE_MS);

		assertTrue(trigger.isPresent());
		assertEquals(PlannerTriggerType.IDLE_THINK, trigger.get().type());
	}

	@Test
	void resetsIdleTimerWhenJobBecomesActive() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(false, 2L, BASE_MS + 20_000L).isEmpty());
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 40_000L).isEmpty());
		assertTrue(scheduler.tick(true, 4L, BASE_MS + 70_000L).isPresent());
	}

	@Test
	void recordActivityRestartsIdleDelayWithoutClearingCooldown() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 30_000L).isPresent());

		scheduler.recordActivity();

		assertTrue(scheduler.tick(true, 3L, BASE_MS + 120_000L).isEmpty());
		assertTrue(scheduler.tick(true, 4L, BASE_MS + 149_999L).isEmpty());
		assertTrue(scheduler.tick(true, 5L, BASE_MS + 150_000L).isPresent());
	}

	@Test
	void doesNotFireWhenDisabled() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(false, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 1_000_000L).isEmpty());
	}

	@Test
	void doesNotFireWhenEitherTimerIsZero() {
		IdleIdeaScheduler noInitialDelay = new IdleIdeaScheduler(new IdleIdeasConfig(true, 0, 90, IDEAS));
		IdleIdeaScheduler noCooldown = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 0, IDEAS));

		assertTrue(noInitialDelay.tick(true, 1L, BASE_MS + 1_000_000L).isEmpty());
		assertTrue(noCooldown.tick(true, 1L, BASE_MS + 1_000_000L).isEmpty());
	}

	@Test
	void doesNotFireWhenIdeasEmpty() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, List.of()));

		assertTrue(scheduler.tick(true, 1L, BASE_MS).isEmpty());
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 1_000_000L).isEmpty());
	}

	@Test
	void resetClearsState() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 30_000L).isPresent());
		scheduler.reset();
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 31_000L).isEmpty());
		assertTrue(scheduler.tick(true, 4L, BASE_MS + 61_000L).isPresent());
	}

	@Test
	void updateConfigSwitchesIdeas() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, 1L, BASE_MS);
		assertTrue(scheduler.tick(true, 2L, BASE_MS + 30_000L).isPresent());

		scheduler.updateConfig(new IdleIdeasConfig(false, 30, 90, IDEAS));
		assertTrue(scheduler.tick(true, 3L, BASE_MS + 200_000L).isEmpty());
		assertFalse(scheduler.tick(true, 4L, BASE_MS + 1_000_000L).isPresent());
	}

	@Test
	void characterInterestsMakeIdleTurnsFreeTime() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS), List.of("exploring caves"));

		String text = scheduler.fireNow(1L, BASE_MS).orElseThrow().text();

		assertTrue(text.startsWith("IDLE THINK:"));
		assertTrue(text.contains("This is your free time."));
		assertTrue(text.contains("- exploring caves"));
		assertTrue(text.indexOf("exploring caves") < text.indexOf("Idea A"), "The character's interests come before chores");
		assertTrue(text.contains("Do not read these lists back to the player."));
	}

	@Test
	void interestsAloneStillFireWhenNoProgressIdeasAreConfigured() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, List.of()), List.of("collecting flowers"));

		scheduler.tick(true, 1L, BASE_MS);
		String text = scheduler.tick(true, 2L, BASE_MS + 30_000L).orElseThrow().text();

		assertTrue(text.contains("- collecting flowers"));
		assertFalse(text.contains("survival progress"));
		assertFalse(text.contains("minecraft:charcoal"), "The torch hint belongs to the progress ideas");
	}
}
