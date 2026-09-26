package ai.moeru.airicraft.agent;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WakeRuntimeSeamsTest {
	@Test void injectedBackendUsesClockAndContinueEndsTurn() {
		try (var harness = new WakeScenarioHarness()) {
			harness.tick(1);
			harness.chat("Alex", "@agent hello");
			harness.tick(5);
			assertEquals(1, harness.backend.requests().size());
			var request = harness.backend.requests().getFirst().request();
			assertEquals(1, request.request().tick());
			harness.tick(20);
			assertEquals(1, harness.backend.requests().size(), "continue must end the turn");
		}
	}
	@Test void manualIdleWakeUsesInjectedClock() {
		try (var harness = new WakeScenarioHarness()) {
			harness.tick(1);
			harness.advanceWallClock(Duration.ofMinutes(5));
			var trigger = harness.runtime.fireIdleIdeaTriggerManually();
			assertTrue(trigger.isPresent());
			assertEquals(harness.clock.millis(), trigger.orElseThrow().timestampMs());
		}
	}
	@Test void changedPromptChangesOnlySystemHash() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1); h.chat("Alex", "@agent hello"); h.tick(5);
			var original = h.backend.requests().getFirst();
			var request = original.request();
			var messages = request.conversation().messages().stream().map(m -> m.role().equals("system")
				? ai.moeru.airicraft.agent.llm.LlmChatMessage.system(m.content() + "\nDifferent instruction.") : m).toList();
			var changed = new ai.moeru.airicraft.agent.wakes.RecordingPlannerBackend.Request(original.index(),
				new ai.moeru.airicraft.agent.llm.PlannerBackendRequest(request.generation(), request.attempt(), request.phase(), request.request(),
					new ai.moeru.airicraft.agent.llm.LlmConversation(messages)), original.observedAtTick());
			var a = ai.moeru.airicraft.agent.wakes.WakeTranscript.capture("prompt", java.util.List.of(original), java.util.List.of(), java.util.List.of(), java.util.List.of()).data();
			var b = ai.moeru.airicraft.agent.wakes.WakeTranscript.capture("prompt", java.util.List.of(changed), java.util.List.of(), java.util.List.of(), java.util.List.of()).data();
			var pa = a.getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonObject("prefix");
			var pb = b.getAsJsonArray("requests").get(0).getAsJsonObject().getAsJsonObject("prefix");
			assertNotEquals(pa.remove("systemSha"), pb.remove("systemSha")); assertEquals(a, b);
		}
	}
	@Test void transcriptReportsOnlyMessagesAddedSincePreviousRequest() {
		try (var h = new WakeScenarioHarness()) {
			h.tick(1); h.chat("Alex", "@agent hello"); h.tick(5);
			var first = h.backend.requests().getFirst();
			var request = first.request();
			var second = new ai.moeru.airicraft.agent.wakes.RecordingPlannerBackend.Request(2,
				new ai.moeru.airicraft.agent.llm.PlannerBackendRequest(request.generation() + 1, request.attempt(), request.phase(), request.request(),
					request.conversation().withAppended(ai.moeru.airicraft.agent.llm.LlmChatMessage.user("new guidance", ai.moeru.airicraft.agent.llm.LlmMessageKind.USER_TURN))),
				first.observedAtTick() + 1);
			var transcript = ai.moeru.airicraft.agent.wakes.WakeTranscript.capture("delta", java.util.List.of(first, second),
				java.util.List.of(), java.util.List.of(), java.util.List.of()).data();
			var delta = transcript.getAsJsonArray("requests").get(1).getAsJsonObject().getAsJsonArray("newMessages");
			assertEquals(1, delta.size());
			assertEquals("new guidance", delta.get(0).getAsJsonObject().get("text").getAsString());
		}
	}

}
