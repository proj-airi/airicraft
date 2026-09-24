package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import com.google.gson.JsonParser;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerDecisionContextTest {
	@Test void sendsTerminalOutcomeOnceAndRefreshesAfterCompactionWithoutLosingHoldOrFailure() {
		var events = new SemanticEventBuffer(8);
		var failed = new ai.moeru.airicraft.agent.work.WorkSnapshot(new ai.moeru.airicraft.agent.work.WorkHandle("JOB:old"), "",
			ai.moeru.airicraft.agent.work.WorkSnapshot.State.FAILED, "mine", "FAILED", false, 1,
			Map.of("request", Map.of("large", "request details"), "failure", "no_path"));
		var paused = new ai.moeru.airicraft.agent.work.WorkSnapshot(new ai.moeru.airicraft.agent.work.WorkHandle("JOB:active"), "",
			ai.moeru.airicraft.agent.work.WorkSnapshot.State.PAUSED, "mine", "PAUSED", true, 2, Map.of("holdId", "hold1"));
		events.append(1, "work.changed", failed.payload());
		var context = new PlannerDecisionContext("world", 2, 2, "controller", "reflex", Map.of("work", java.util.List.of(failed.payload(), paused.payload())), events.query(null));
		String first = PlannerObservation.render(context.observation(0));
		assertTrue(first.contains("no_path"));
		assertFalse(first.contains("request details"));
		String next = PlannerObservation.render(context.observation(1));
		assertFalse(next.contains("JOB:old"));
		assertTrue(next.contains("hold1"));
		assertTrue(PlannerObservation.render(context.observation(1, true)).contains("JOB:old"));
		assertEquals(2, ((java.util.List<?>) context.current().get("work")).size());
		assertTrue(events.query(null).events().getFirst().payload().containsKey("details"));
	}
	@Test void overflowReportsMissingRangeAlongsideCurrentFactsAndRetainedOutcomes() {
		var events = new SemanticEventBuffer(2);
		events.append(1, "task.started", Map.of("workId", "old"));
		events.append(2, "task.failed", Map.of("workId", "old"));
		events.append(3, "task.started", Map.of("workId", "new"));
		var context = new PlannerDecisionContext("world", 3, 3, "controller", "work",
			Map.of("workId", "new"), events.query(null));
		var payload = JsonParser.parseString(PlannerObservation.render(context.observation(0))).getAsJsonObject();
		assertEquals(1, payload.getAsJsonObject("missingEventRange").get("to").getAsLong());
		assertEquals("new", payload.getAsJsonObject("current").get("workId").getAsString());
		assertEquals(2, payload.getAsJsonArray("events").size());
		assertTrue(payload.toString().contains("task.failed"));
		assertEquals(3, payload.get("throughEventSequence").getAsLong());
	}

	@Test void separateHistoryCursorsDoNotConsumeEachOthersEvidence() {
		var events = new SemanticEventBuffer(4);
		events.append(1, "task.completed", Map.of("workId", "wood"));
		var context = new PlannerDecisionContext("world", 2, 2, "controller", "idle", Map.of(), events.query(null));
		assertFalse(PlannerObservation.render(context.observation(1)).contains("wood"));
		assertTrue(PlannerObservation.render(context.forOwner("thinking").observation(0)).contains("wood"));
		assertTrue(PlannerObservation.render(context.forOwner("thinking").observation(0)).contains("thinking"));
	}
}
