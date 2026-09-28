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
	/** Ticks advance with wall time in these tests (50 ms per tick) unless a test pauses them. */
	private static final long BASE_TICK = 1_000L;
	private static final List<String> IDEAS = List.of(
		"Idea A",
		"Idea B"
	);

	@Test void d7_FIXED_wallClockPauseDoesNotCountAsIdle() {
		var scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));
		assertTrue(scheduler.tick(true, 0, BASE_MS).isEmpty());
		// Five minutes of wall time with a single tick, as during a tick-debug pause, is not idle time.
		assertTrue(scheduler.tick(true, 1, BASE_MS + 300_000).isEmpty());
		assertTrue(scheduler.tick(true, 599, BASE_MS + 330_000).isEmpty());
		assertTrue(scheduler.tick(true, 600, BASE_MS + 330_050).isPresent());
	}

	@Test
	void doesNotFireBeforeInitialDelay() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 599L, BASE_MS + 29999L).isEmpty());
	}

	@Test
	void firesOnceAfterInitialDelay() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L).isEmpty());
		Optional<PlannerTrigger> trigger = scheduler.tick(true, BASE_TICK + 600L, BASE_MS + 30000L);

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

		scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L);
		assertTrue(scheduler.tick(true, BASE_TICK + 600L, BASE_MS + 30000L).isPresent());
		assertTrue(scheduler.tick(true, BASE_TICK + 1200L, BASE_MS + 60000L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 2399L, BASE_MS + 119999L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 2400L, BASE_MS + 120000L).isPresent());
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

		scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L);
		assertTrue(scheduler.tick(false, BASE_TICK + 400L, BASE_MS + 20000L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 800L, BASE_MS + 40000L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 1400L, BASE_MS + 70000L).isPresent());
	}

	@Test
	void recordActivityRestartsIdleDelayWithoutClearingCooldown() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L);
		assertTrue(scheduler.tick(true, BASE_TICK + 600L, BASE_MS + 30000L).isPresent());

		scheduler.recordActivity();

		assertTrue(scheduler.tick(true, BASE_TICK + 2400L, BASE_MS + 120000L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 2999L, BASE_MS + 149999L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 3000L, BASE_MS + 150000L).isPresent());
	}

	@Test
	void doesNotFireWhenDisabled() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(false, 30, 90, IDEAS));

		assertTrue(scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 20000L, BASE_MS + 1000000L).isEmpty());
	}

	@Test
	void doesNotFireWhenEitherTimerIsZero() {
		IdleIdeaScheduler noInitialDelay = new IdleIdeaScheduler(new IdleIdeasConfig(true, 0, 90, IDEAS));
		IdleIdeaScheduler noCooldown = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 0, IDEAS));

		assertTrue(noInitialDelay.tick(true, BASE_TICK + 20000L, BASE_MS + 1000000L).isEmpty());
		assertTrue(noCooldown.tick(true, BASE_TICK + 20000L, BASE_MS + 1000000L).isEmpty());
	}

	@Test
	void doesNotFireWhenIdeasEmpty() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, List.of()));

		assertTrue(scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 20000L, BASE_MS + 1000000L).isEmpty());
	}

	@Test
	void resetClearsState() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L);
		assertTrue(scheduler.tick(true, BASE_TICK + 600L, BASE_MS + 30000L).isPresent());
		scheduler.reset();
		assertTrue(scheduler.tick(true, BASE_TICK + 620L, BASE_MS + 31000L).isEmpty());
		assertTrue(scheduler.tick(true, BASE_TICK + 1220L, BASE_MS + 61000L).isPresent());
	}

	@Test
	void updateConfigSwitchesIdeas() {
		IdleIdeaScheduler scheduler = new IdleIdeaScheduler(new IdleIdeasConfig(true, 30, 90, IDEAS));

		scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L);
		assertTrue(scheduler.tick(true, BASE_TICK + 600L, BASE_MS + 30000L).isPresent());

		scheduler.updateConfig(new IdleIdeasConfig(false, 30, 90, IDEAS));
		assertTrue(scheduler.tick(true, BASE_TICK + 4000L, BASE_MS + 200000L).isEmpty());
		assertFalse(scheduler.tick(true, BASE_TICK + 20000L, BASE_MS + 1000000L).isPresent());
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

		scheduler.tick(true, BASE_TICK + 0L, BASE_MS + 0L);
		String text = scheduler.tick(true, BASE_TICK + 600L, BASE_MS + 30000L).orElseThrow().text();

		assertTrue(text.contains("- collecting flowers"));
		assertFalse(text.contains("survival progress"));
		assertFalse(text.contains("minecraft:charcoal"), "The torch hint belongs to the progress ideas");
	}
}
