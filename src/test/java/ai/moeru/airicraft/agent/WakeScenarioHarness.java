package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.*;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import ai.moeru.airicraft.agent.session.*;
import ai.moeru.airicraft.agent.wakes.*;
import java.time.Duration;
import java.util.Map;
import net.minecraft.util.math.Vec3d;

final class WakeScenarioHarness implements AutoCloseable {
	final MutableClock clock = new MutableClock();
	volatile long tick;
	final RecordingPlannerBackend backend = new RecordingPlannerBackend(() -> tick);
	final FakeWorldTaskExecutor executor = new FakeWorldTaskExecutor();
	final EmbodiedAgentRuntime runtime;

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
	}
	void tick(int count) {
		for (int i = 0; i < count; i++) {
			tick++;
			clock.advance(Duration.ofMillis(50));
			runtime.onClientTick(null);
			settle();
		}
	}
	void settle() {
		long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
		var dialogue = runtime.dialogueRuntimeForTests();
		while (dialogue.plannerDebugSnapshot().inFlight() && !backend.held()) {
			var response = dialogue.poll(tick, runtimeEvents());
			if (response != null) runtime.injectDialogueResponseForTests(response);
			if (System.nanoTime() > deadline) throw new AssertionError("Planner did not settle at tick " + tick);
			Thread.yield();
		}
	}
	private ai.moeru.airicraft.agent.events.SemanticEventBuffer runtimeEvents() {
		try {
			var field = EmbodiedAgentRuntime.class.getDeclaredField("eventBuffer");
			field.setAccessible(true);
			return (ai.moeru.airicraft.agent.events.SemanticEventBuffer) field.get(runtime);
		} catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
	void advanceWallClock(Duration duration) { clock.advance(duration); }
	void chat(String sender, String text) {
		runtime.injectNearbyPlayerForTests(sender, Vec3d.ZERO);
		runtime.onChatReceived(sender, text);
		settle();
	}
	void event(String type, Map<String, Object> payload) { runtime.appendEventForTests(type, payload); }
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
	@Override public void close() { runtime.shutdown(); }
}
