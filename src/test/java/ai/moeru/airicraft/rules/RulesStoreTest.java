package ai.moeru.airicraft.rules;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static ai.moeru.airicraft.rules.RuleModule.Hook.ATTENTION;
import static ai.moeru.airicraft.rules.RuleModule.Hook.SALIENCE;
import static org.junit.jupiter.api.Assertions.*;

class RulesStoreTest {
	private final RulesStore store = new RulesStore();

	private RulesStore.Version update(String source, long tick, int fallback) {
		return store.append(ATTENTION, RulesStore.Kind.UPDATE, source, "reason", tick, fallback, Map.of("replayed", 1));
	}

	@Test void startsOnTheBundledBaseWithNoHistory() {
		assertEquals(0, store.activeNumber(ATTENTION));
		assertTrue(store.active(ATTENTION).isEmpty());
		assertEquals(RuleModule.BUNDLED_ATTENTION, store.activeModule(ATTENTION).origin());
		assertEquals(RuleModule.BUNDLED_SALIENCE, store.activeModule(SALIENCE).origin());
	}

	@Test void aVersionRunsAsAModuleThatNamesItsHookAndNumber() {
		var first = update("(lib => ({step(){}}))", 10, 0);
		var module = store.activeModule(ATTENTION);
		assertEquals(1, first.number());
		assertEquals("planner:attention/v1", module.origin());
		assertFalse(module.bundled());
		assertEquals(1, RulesStore.versionOf(module));
		assertEquals(0, RulesStore.versionOf(RuleModule.bundledAttention()));
		assertEquals(0, RulesStore.versionOf(new RuleModule("config:rules/attention.js", "x")));
		assertEquals(0, store.activeNumber(SALIENCE), "hooks are independent");
	}

	@Test void historyIsBoundedAndNumbersOnlyGrow() {
		for (int i = 1; i <= RulesStore.HISTORY + 3; i++) update("(lib => ({step(){}}))//" + i, i, 0);
		var history = store.history(ATTENTION);
		assertEquals(RulesStore.HISTORY, history.size());
		assertEquals(4, history.getFirst().number());
		assertEquals(RulesStore.HISTORY + 3, history.getLast().number());
		assertTrue(store.find(ATTENTION, 1).isEmpty());
	}

	@Test void resetSetsTheBaseAndDropsTheEdits() {
		update("(lib => ({step(){}}))", 1, 0);
		var override = new RuleModule("config:rules/attention.js", "(lib => ({step(){}}))");
		store.reset(override);
		assertTrue(store.history(ATTENTION).isEmpty());
		assertSame(override, store.activeModule(ATTENTION));
		assertEquals(-1, store.retryAtTick(ATTENTION, 2), "a reset also forgets the edit rate");
	}

	@Test void editsAreRateLimitedPerHookAndAutomaticRevertsAreNot() {
		for (int i = 0; i < RulesStore.MAX_UPDATES; i++) update("(lib => ({step(){}}))//" + i, 100 + i, 0);
		assertEquals(100 + RulesStore.UPDATE_WINDOW_TICKS, store.retryAtTick(ATTENTION, 500));
		assertEquals(-1, store.retryAtTick(SALIENCE, 500));
		assertEquals(-1, store.retryAtTick(ATTENTION, 100 + RulesStore.UPDATE_WINDOW_TICKS));
		store.append(ATTENTION, RulesStore.Kind.AUTO_REVERT, null, "auto", 600, 0, null);
		assertEquals(-1, store.retryAtTick(ATTENTION, 100 + RulesStore.UPDATE_WINDOW_TICKS), "the revert did not use a slot");
	}

	@Test void aFailingVersionRevertsToTheVersionItReplaced() {
		var v1 = update("(lib => ({step(){}}))//one", 1, 0);
		var v2 = update("(lib => ({step(){}}))//two", 2, v1.number());
		var result = store.revert(RulesStore.module(ATTENTION, v2), 50);
		assertEquals(2, result.fromVersion());
		assertEquals(3, result.toVersion(), "the revert is a new version that copies v1");
		assertTrue(result.to().source().endsWith("//one"));
		assertEquals("planner:attention/v3", result.to().origin());
		assertEquals(3, store.activeNumber(ATTENTION));
		assertEquals(RulesStore.Kind.AUTO_REVERT, store.active(ATTENTION).orElseThrow().kind());

		// v3 copies v1, so it falls back where v1 did (the base), never to the version that just failed.
		var again = store.revert(result.to(), 60);
		assertEquals(0, again.toVersion());
		assertEquals(RuleModule.BUNDLED_ATTENTION, again.to().origin());
		assertEquals(0, store.activeNumber(ATTENTION), "the base is active again");
		assertTrue(store.active(ATTENTION).orElseThrow().base());
	}

	@Test void aFirstEditRevertsToTheOperatorsOverride() {
		var override = new RuleModule("config:rules/attention.js", "(lib => ({step(){}}))//operator");
		store.reset(override);
		var v1 = update("(lib => ({step(){}}))//planner", 1, 0);
		var result = store.revert(RulesStore.module(ATTENTION, v1), 9);
		assertSame(override, result.to());
		assertEquals(0, result.toVersion());
	}

	@Test void aFailingOperatorOverrideRevertsToTheBundledModuleAndBecomesTheBase() {
		var override = new RuleModule("config:rules/attention.js", "(lib => ({step(){}}))//operator");
		store.reset(override);
		var result = store.revert(override, 5);
		assertEquals(RuleModule.BUNDLED_ATTENTION, result.to().origin());
		assertEquals(RuleModule.BUNDLED_ATTENTION, store.base(ATTENTION).origin());
	}

	@Test void aVersionThatFellOutOfTheHistoryFallsBackToTheBase() {
		var v1 = update("(lib => ({step(){}}))//one", 1, 0);
		var v2 = update("(lib => ({step(){}}))//two", 2, v1.number());
		for (int i = 0; i < RulesStore.HISTORY; i++) update("(lib => ({step(){}}))//more" + i, 3 + i, 0);
		assertTrue(store.find(ATTENTION, v1.number()).isEmpty());
		var result = store.revert(RulesStore.module(ATTENTION, store.history(ATTENTION).getFirst()), 99);
		assertEquals(0, result.toVersion());
	}
}
