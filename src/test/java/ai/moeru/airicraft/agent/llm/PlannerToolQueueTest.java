package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerToolQueueTest {
	private PlannerToolCall call(String id) { return new PlannerToolCall(id, "mine_blocks", new JsonObject(), null); }

	@Test void advancesWithoutPlannerAndAppendsBehindExistingCalls() {
		var queue = new PlannerToolQueue<String>();
		var executed = new ArrayList<String>();
		var futures = new HashMap<String, CompletableFuture<String>>();
		java.util.function.Function<PlannerToolCall, CompletableFuture<String>> execute = c -> {
			executed.add(c.id()); return futures.computeIfAbsent(c.id(), ignored -> new CompletableFuture<>());
		};
		queue.append(List.of(call("A"), call("B"), call("C")));
		queue.tick(execute, result -> null, id -> null);
		assertEquals(List.of("A"), executed);
		futures.get("A").complete("done");
		assertEquals("A", queue.tick(execute, result -> null, id -> null).call().id());
		assertEquals(List.of("A", "B"), executed, "B starts before any planner response");
		queue.append(List.of(call("D")));
		futures.get("B").complete("done");
		queue.tick(execute, result -> null, id -> null);
		assertEquals(List.of("A", "B", "C"), executed);
		assertEquals(List.of("D"), queue.pending().stream().map(PlannerToolCall::id).toList());
	}

	@Test void acceptanceWaitsForWorkCompletionAndClearAbortsActiveWork() {
		var queue = new PlannerToolQueue<String>();
		var executed = new ArrayList<String>();
		java.util.function.Function<PlannerToolCall, CompletableFuture<String>> execute = c -> {
			executed.add(c.id()); return CompletableFuture.completedFuture("JOB:" + c.id());
		};
		queue.append(List.of(call("A"), call("B")));
		queue.tick(execute, result -> result, id -> null);
		queue.tick(execute, result -> result, id -> null);
		assertEquals(List.of("A"), executed);
		var cancelled = new ArrayList<String>();
		queue.clear((call, workId) -> cancelled.add(call.id() + ":" + workId));
		assertEquals(List.of("A:JOB:A"), cancelled);
		queue.tick(execute, result -> result, id -> "SUCCEEDED");
		assertEquals(List.of("A"), executed);
		assertNull(queue.active());
		assertTrue(queue.pending().isEmpty());
	}

	@Test void terminalWorkAdvancesAndLateCancelledFutureCannotAdvance() {
		var queue = new PlannerToolQueue<String>();
		var future = new CompletableFuture<String>();
		queue.append(List.of(call("A"), call("B")));
		queue.tick(c -> future, result -> result, id -> null);
		queue.clear((c, id) -> {});
		future.complete("JOB:A");
		assertNull(queue.tick(c -> { fail("Cancelled tail executed"); return null; }, result -> result, id -> "done"));
		queue.append(List.of(call("C"), call("D")));
		queue.tick(c -> CompletableFuture.completedFuture("JOB:" + c.id()), result -> result, id -> null);
		queue.tick(c -> CompletableFuture.completedFuture("JOB:" + c.id()), result -> result, id -> null);
		var completion = queue.tick(c -> CompletableFuture.completedFuture("JOB:" + c.id()), result -> result, id -> "FAILED");
		assertEquals("FAILED", completion.workOutcome());
		assertEquals("D", queue.active().id());
	}
}
