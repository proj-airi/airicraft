package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.*;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.session.*;
import ai.moeru.airicraft.agent.wakes.*;
import java.time.Duration;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

final class WakeScenarioHarness implements AutoCloseable {
	final MutableClock clock = new MutableClock();
	volatile long tick;
	final RecordingPlannerBackend backend = new RecordingPlannerBackend(() -> tick);
	final FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
	final EmbodiedAgentRuntime runtime;
	private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingResolutions = new java.util.concurrent.ConcurrentLinkedQueue<>();

	WakeScenarioHarness() { this(Map.of()); }
	WakeScenarioHarness(Map<String, Object> options) { this(options, false); }
	WakeScenarioHarness(Map<String, Object> options, boolean proactive) {
		var config = new java.util.HashMap<String, Object>(Map.of("providerBaseUrl", "http://127.0.0.1:9", "apiKey", "test", "model", "test",
			"plannerCompactionTriggerTokens", 1000000, "plannerSummarizeToolResults", false));
		config.putAll(options);
		runtime = new EmbodiedAgentRuntime(new AiricraftConfig(-1, true, proactive, true, 2, 0), AgentConfigLoader.fromMapStrict(config, AgentConfig.defaults()),
			new FirstPersonScreenshotService(), executor, NoopObservability.INSTANCE,
			new ai.moeru.airicraft.agent.tasks.SmeltingProcessManager(), new CameraController(), null,
			new ai.moeru.airicraft.agent.tasks.MiningOpportunityPolicyState(), new ai.moeru.airicraft.agent.tasks.MiningOpportunityJournal(),
			ignored -> backend, clock);
		runtime.overrideSessionSnapshotForTests(new SessionSnapshot(SessionMode.REMOTE_MULTIPLAYER, true, true, "minecraft:overworld", false, 0, 0));
		runtime.overrideActionGraphResolutionExecutorForTests(pendingResolutions::add);
		// Decide through the bundled GraalJS rules, not the cold-engine fallback; both decide identically.
		try {
			ai.moeru.airicraft.rules.RuleEngine.shared(ai.moeru.airicraft.rules.RuleModule.bundledAttention()).awaitReady(Duration.ofSeconds(60));
		}
		catch (ai.moeru.airicraft.rules.RuleException exception) { throw new AssertionError(exception); }
		backend.closeGate();
	}
	void tick(int count) {
		for (int i = 0; i < count; i++) {
			// Event callbacks can submit between ticks. Keep worker dispatch on that tick.
			settle();
			tick++;
			clock.advance(Duration.ofMillis(50));
			runtime.onClientTick(null);
			settle();
		}
	}
	/**
	 * Waits for running provider calls and tool futures to finish without consuming their results. The next
	 * {@code onClientTick} applies them through the runtime's own poll, with its real session, goal and task
	 * context, exactly as in production. Polling here would apply results off-tick with fabricated context.
	 * Provider calls are released only here, so a response never races the poll of the tick that submitted it.
	 * Action-graph route resolutions are queued by the harness and run here, so a resolution scheduled during a
	 * tick is applied on the next tick rather than whenever the advisor thread happens to finish.
	 */
	void settle() {
		long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
		var dialogue = runtime.dialogueRuntimeForTests();
		Runnable resolution;
		while ((resolution = pendingResolutions.poll()) != null) resolution.run();
		backend.openGate();
		try {
			while (dialogue.plannerWorkRunning() && !backend.held()) {
				if (System.nanoTime() > deadline) throw new AssertionError("Planner did not settle at tick " + tick);
				Thread.yield();
			}
		} finally {
			backend.closeGate();
		}
	}
	ai.moeru.airicraft.agent.events.EventStream runtimeEvents() {
		try {
			var field = EmbodiedAgentRuntime.class.getDeclaredField("eventBus");
			field.setAccessible(true);
			return (ai.moeru.airicraft.agent.events.EventStream) field.get(runtime);
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	void advanceWallClock(Duration duration) { clock.advance(duration); }
	void chat(String sender, String text) {
		runtime.injectNearbyPlayerForTests(sender, Vec3.ZERO);
		runtime.onChatReceived(sender, text);
		settle();
	}
	void event(String type, Map<String, Object> payload) { runtime.appendEventForTests(type, payload); }
	/** A noticing sensor's candidate, as if sampled this tick; the next tick's salience step decides on it. */
	void candidate(ai.moeru.airicraft.agent.perception.PerceptCandidate candidate) {
		// Decide through the warm bundled salience rules; only scenarios that notice something pay for the warm-up.
		try { ai.moeru.airicraft.rules.RuleEngine.shared(ai.moeru.airicraft.rules.RuleModule.bundledSalience()).awaitReady(Duration.ofSeconds(60)); }
		catch (ai.moeru.airicraft.rules.RuleException exception) { throw new AssertionError(exception); }
		runtime.saliencePolicyForTests().offer(candidate, tick);
	}
	static ai.moeru.airicraft.agent.perception.PerceptCandidate block(String blockId, int x, int y, int z, double distance) {
		return new ai.moeru.airicraft.agent.perception.PerceptCandidate("block:" + blockId + "@" + x + "," + y + "," + z, "block",
			Map.of("blockId", blockId, "x", x, "y", y, "z", z, "distance", distance, "direction", "north",
				"exposedFaces", java.util.List.of("up"), "dimension", "minecraft:overworld"));
	}
	/** A reflex begins, opening safety epoch {@code epoch} as {@code SurvivalReflexRuntime.begin} would. */
	void reflexStarted(long epoch) {
		try {
			var field = EmbodiedAgentRuntime.class.getDeclaredField("survivalReflexRuntime");
			field.setAccessible(true);
			var reflex = (ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime) field.get(runtime);
			var snapshot = reflex.getClass().getDeclaredField("snapshot");
			snapshot.setAccessible(true);
			snapshot.set(reflex, new ai.moeru.airicraft.agent.reflex.SurvivalReflexSnapshot(
				ai.moeru.airicraft.agent.reflex.SurvivalReflexState.ACTIVE, ai.moeru.airicraft.agent.reflex.SurvivalReflexCause.DROWNING,
				ai.moeru.airicraft.agent.reflex.SurvivalReflexAction.SWIM_TO_AIR, epoch, null, null, null, java.util.List.of(),
				10f, 20f, 100, 300, tick, tick, 0, null));
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
		reflex(new ai.moeru.airicraft.agent.reflex.SurvivalReflexEvent("reflex.started",
			Map.of("safetyEpoch", epoch, "cause", "DROWNING", "action", "SWIM_TO_AIR")));
	}
	void reflex(ai.moeru.airicraft.agent.reflex.SurvivalReflexEvent event) {
		try {
			var field = EmbodiedAgentRuntime.class.getDeclaredField("survivalReflexRuntime");
			field.setAccessible(true);
			ai.moeru.airicraft.agent.reflex.ReflexEventFixture.enqueue((ai.moeru.airicraft.agent.reflex.SurvivalReflexRuntime) field.get(runtime), event);
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	WakeTranscript transcript(String name) {
		return WakeTranscript.capture(name, backend.requests(), runtime.debugTimeline(null).entries(),
			runtime.recentEvents(null).events(), runtime.dialogueRuntimeForTests().allAvailableTools());
	}
	@Override public void close() { backend.openGate(); runtime.shutdown(); }
}
