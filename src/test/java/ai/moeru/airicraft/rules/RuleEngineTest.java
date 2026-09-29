package ai.moeru.airicraft.rules;

import com.google.gson.JsonParser;
import java.time.Duration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {
	private static final Duration WARM = Duration.ofSeconds(60);
	private static final String EMPTY = "{\"tick\":7,\"seed\":42,\"attention\":{},\"plannerRules\":[],\"events\":[]}";

	private static RuleEngine warm(String source) throws RuleException {
		RuleEngine engine = RuleEngine.shared(new RuleModule("test:" + Integer.toHexString(source.hashCode()), source));
		engine.awaitReady(WARM);
		return engine;
	}

	@Test void bundledAttentionModuleLoadsAndStepsAnEmptyInput() throws Exception {
		RuleEngine engine = RuleEngine.shared(RuleModule.bundledAttention());
		engine.awaitReady(WARM);
		RuleStepResult result = engine.step(EMPTY, "{\"kept\":1}");
		assertEquals(0, result.decisions().size());
		assertEquals("{\"kept\":1}", result.stateJson(), "the default module passes its state through");
		assertSame(engine, RuleEngine.shared(RuleModule.bundledAttention()), "one warm engine per module");
	}

	@Test void bundledSalienceModuleDeclaresInterestsAndReturnsPerceptsAndDrops() throws Exception {
		RuleEngine engine = RuleEngine.shared(RuleModule.bundledSalience());
		engine.awaitReady(WARM);
		assertTrue(engine.interests().getAsJsonArray("blocks").toString().contains("minecraft:diamond_ore"));
		RuleStepResult result = engine.step("{\"tick\":1,\"seed\":1,\"context\":{},\"candidates\":["
			+ "{\"id\":\"item:a\",\"kind\":\"item\",\"itemId\":\"minecraft:dirt\",\"count\":1}]}", "{}");
		assertEquals(0, result.decisions().size());
		assertEquals(0, result.percepts().size());
		assertEquals("garbage", result.drops().get(0).getAsJsonObject().get("reason").getAsString());
	}

	@Test void overridesOfDifferentHooksStayWarmSideBySide() throws Exception {
		RuleEngine attention = RuleEngine.shared(new RuleModule("config:attention-side.js", "(lib => ({ step(i, s) { return {state: s}; } }))"));
		RuleEngine salience = RuleEngine.shared(new RuleModule("config:salience-side.js",
			"(lib => ({ interests: {blocks: ['minecraft:chest']}, step(i, s) { return {state: s}; } }))", RuleModule.Hook.SALIENCE));
		attention.awaitReady(WARM);
		salience.awaitReady(WARM);
		assertNotNull(attention.step(EMPTY, "{}"), "a salience override must not close the attention override");
		assertEquals("[\"minecraft:chest\"]", salience.interests().getAsJsonArray("blocks").toString());
		RuleEngine.validate(new RuleModule("config:salience-valid.js", "(lib => ({ step(i, s) { return {percepts: [], state: s}; } }))",
			RuleModule.Hook.SALIENCE), WARM);
		assertEquals("load_failed", assertThrows(RuleException.class, () -> RuleEngine.validate(new RuleModule("config:bad-interests.js",
			"(lib => ({ interests: 'blocks', step(i, s) { return {state: s}; } }))", RuleModule.Hook.SALIENCE), WARM)).code());
	}

	@Test void stepsAreDeterministicWithAFrozenClockAndSeededRandom() throws Exception {
		RuleEngine engine = warm("""
			// A leading comment must not break loading.
			(lib => ({
			  step(input, state) {
			    const next = {count: (state.count || 0) + 1};
			    return {decisions: [{now: Date.now(), date: new Date().getTime(), random: Math.random()}], state: next};
			  }
			}));
			""");
		RuleStepResult first = engine.step(EMPTY, "{}");
		RuleStepResult again = engine.step(EMPTY, "{}");
		assertEquals(first.decisions(), again.decisions());
		assertEquals("{\"count\":1}", first.stateJson());
		var probe = first.decisions().get(0).getAsJsonObject();
		assertEquals(350, probe.get("now").getAsLong(), "tick 7 * 50 ms");
		assertEquals(350, probe.get("date").getAsLong());
		assertEquals("{\"count\":2}", engine.step(EMPTY, first.stateJson()).stateJson());
	}

	@Test void failedStepsReportStableCodesAndLeaveTheEngineUsable() throws Exception {
		RuleEngine engine = warm("""
			(lib => ({
			  step(input, state) {
			    if (input.tick === 1) throw Error('intentional');
			    if (input.tick === 2) while (true) {}
			    if (input.tick === 3) return {decisions: [], state: {big: 'x'.repeat(20000)}};
			    if (input.tick === 4) return {decisions: 5, state: {}};
			    return {decisions: [], state};
			  }
			}))
			""");
		assertEquals("guest_error", assertThrows(RuleException.class, () -> engine.step(tick(1), "{}")).code());
		assertEquals("statement_limit", assertThrows(RuleException.class, () -> engine.step(tick(2), "{}")).code());
		// Graal cancels an exhausted context; the engine rebuilds it and is cold meanwhile.
		assertEquals(1, engine.rebuilds());
		engine.awaitReady(WARM);
		assertEquals("state_limit", assertThrows(RuleException.class, () -> engine.step(tick(3), "{}")).code());
		assertEquals("malformed_output", assertThrows(RuleException.class, () -> engine.step(tick(4), "{}")).code());
		assertEquals("state_limit", assertThrows(RuleException.class, () -> engine.step(tick(5), "{\"big\":\"" + "x".repeat(20000) + "\"}")).code());
		assertEquals("{}", engine.step(tick(5), "{}").stateJson(), "a failed step never poisons the next one");
	}

	@Test void invalidModulesFailToLoadAndValidateRejectsThem() {
		RuleEngine broken = RuleEngine.shared(new RuleModule("test:broken", "(lib => ({ step(input, state) { return {"));
		assertEquals("load_failed", assertThrows(RuleException.class, () -> broken.awaitReady(WARM)).code());
		assertNotNull(broken.loadFailure());
		assertEquals("load_failed", assertThrows(RuleException.class,
			() -> RuleEngine.shared(new RuleModule("test:no-step", "(lib => ({}))")).awaitReady(WARM)).code(),
			"the kernel rejects a module without step()");
		assertEquals("load_failed", assertThrows(RuleException.class,
			() -> RuleEngine.validate(new RuleModule("config:bad.js", "(lib => ({ step() { return 1; } })"), WARM)).code());
		assertEquals("source_limit", assertThrows(RuleException.class,
			() -> RuleEngine.validate(new RuleModule("config:empty.js", " "), WARM)).code());
		assertDoesNotThrow(() -> RuleEngine.validate(RuleModule.bundledAttention(), WARM));
	}

	@Test void loadErrorsNameTheModuleAndItsOwnLineNumbers() {
		String source = "// line 1\n\n(lib => ({\n  step(input, state) { return {decisions: [], state} }\n  broken here\n}))\n";
		var failure = assertThrows(RuleException.class,
			() -> RuleEngine.validate(new RuleModule("config:rules/attention.js", source), WARM));
		assertEquals("load_failed", failure.code());
		assertTrue(failure.getMessage().contains("config:rules/attention.js:5:"), failure.getMessage());
		assertFalse(failure.getMessage().contains("Unnamed"), failure.getMessage());
	}

	@Test void aReplacedOverrideIsClosed() throws Exception {
		RuleEngine first = warm("(lib => ({ step(input, state) { return {decisions: [], state}; } }))");
		assertTrue(first.ready());
		RuleEngine second = RuleEngine.shared(new RuleModule("config:attention.js", "(lib => ({ step(i, s) { return {decisions: [], state: s}; } }))"));
		second.awaitReady(WARM);
		assertEquals("closed", assertThrows(RuleException.class, () -> first.step(EMPTY, "{}")).code());
		assertTrue(RuleEngine.shared(RuleModule.bundledAttention()) != null);
	}

	@Test void warmUpRunsOffThreadAndSurvivesModulesThatExhaustTheLimitOnSyntheticInput() throws Exception {
		assertTrue(RuleEngine.warmupInput(0).contains("\"events\""));
		com.google.gson.JsonParser.parseString(RuleEngine.warmupInput(RuleEngine.WARMUP_STEPS - 1));
		// Loops forever on anything but tick 7: warm-up is cancelled, the context is rebuilt without it, and real steps work.
		RuleEngine engine = warm("(lib => ({ step(input, state) { while (input.tick !== 7) {} return {decisions: [], state}; } }))");
		assertEquals("{}", engine.step(EMPTY, "{}").stateJson());
		assertEquals(0, engine.rebuilds(), "a warm-up cancel is not a runtime rebuild");
	}

	private static String tick(int tick) {
		return JsonParser.parseString(EMPTY).getAsJsonObject().deepCopy().toString().replace("\"tick\":7", "\"tick\":" + tick);
	}
}
