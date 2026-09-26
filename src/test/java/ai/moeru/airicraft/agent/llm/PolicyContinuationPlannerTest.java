package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.work.WorkHandle;
import ai.moeru.airicraft.agent.work.WorkSnapshot;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class PolicyContinuationPlannerTest {
	private final CompletableFuture<PlannerResponse> response = new CompletableFuture<>();
	private final PolicyContinuationPlanner planner = new PolicyContinuationPlanner(conversation -> response, () -> {});
	private final WorkHandle handle = WorkHandle.of(WorkHandle.Kind.OPERATION, "parent");

	@Test void preparesWhileParentRunsAndAcceptsOnlyAfterVerifiedHandoff() {
		start();
		response.complete(proposal());
		assertNull(planner.poll(context("world", 4), work(WorkSnapshot.State.RUNNING, Map.of()), 1, 0, b -> true));
		var accepted = planner.poll(context("world", 4), success(), 1, 0, b -> true);
		assertNotNull(accepted);
		assertEquals("run_policy", accepted.name());
		assertFalse(accepted.arguments().has("guard"));
		assertNull(planner.poll(context("world", 4), success(), 1, 0, b -> true));
	}

	@Test void discardsOnChangedGoalGuidanceWorldOrSafetyBeforeCompletion() {
		start();
		assertNull(planner.poll(context("other-world", 4), work(WorkSnapshot.State.RUNNING, Map.of()), 1, 0, b -> true));
		response.complete(proposal());
		assertNull(planner.poll(context("world", 4), success(), 1, 0, b -> true));
		assertEquals("context_changed", planner.lastReason());
	}

	@Test void rejectsMissingInventoryAndChangedBlocks() {
		start(); response.complete(proposal());
		assertNull(planner.poll(context("world", 3), success(), 1, 0, b -> true));
		assertEquals("inventory_changed", planner.lastReason());
	}

	@Test void rejectsChangedBlockAtHandoff() {
		start(); response.complete(proposal());
		assertNull(planner.poll(context("world", 4), success(), 1, 0, b -> false));
		assertEquals("block_changed", planner.lastReason());
	}

	@Test void normalReturnDoesNotProveParentSucceededSemantically() {
		start(); response.complete(proposal());
		assertNull(planner.poll(context("world", 4), work(WorkSnapshot.State.SUCCEEDED,
			Map.of("result", Map.of("gathered", false))), 1, 0, b -> true));
		assertEquals("parent_result_changed", planner.lastReason());
	}

	@Test void lateResponseNeverDelaysNormalPlanningOrBecomesExecutable() {
		start();
		assertNull(planner.poll(context("world", 4), success(), 1, 0, b -> true));
		assertEquals("not_ready", planner.lastReason());
		response.complete(proposal());
		assertNull(planner.poll(context("world", 4), success(), 1, 0, b -> true));
	}

	@Test void rejectsNewGuidanceAndSafetyEpoch() {
		start(); response.complete(proposal());
		assertNull(planner.poll(context("world", 4), success(), 2, 1, b -> true));
		assertEquals("context_changed", planner.lastReason());
	}

	@Test void changedObjectiveDiscardsBeforeHandoff() {
		start(); response.complete(proposal());
		var old = context("world", 4);
		var facts = new java.util.HashMap<>(old.current()); facts.put("objective", "explore");
		assertNull(planner.poll(new PlannerDecisionContext(old.worldSessionId(), 11, 11, "controller", "policy", facts, old.observations()),
			work(WorkSnapshot.State.RUNNING, Map.of()), 1, 0, b -> true));
		assertEquals("context_changed", planner.lastReason());
	}

	@Test void failedParentCannotPromoteEvenMatchingReturnValue() {
		start(); response.complete(proposal());
		assertNull(planner.poll(context("world", 4), work(WorkSnapshot.State.FAILED, Map.of("result", Map.of("gathered", true))), 1, 0, b -> true));
		assertEquals("parent_failed", planner.lastReason());
	}

	@Test void failedChildCannotBeHiddenBySuccessfulParentReturn() {
		start(); response.complete(proposal());
		assertNull(planner.poll(context("world", 4), work(WorkSnapshot.State.SUCCEEDED,
			Map.of("result", Map.of("gathered", true), "effects", List.of(Map.of("result", Map.of("ok", false))))), 1, 0, b -> true));
		assertEquals("parent_effect_failed", planner.lastReason());
	}

	@Test void missingGuardCannotReachExecution() {
		start();
		var args = proposal().toolCalls().getFirst().arguments().deepCopy(); args.remove("guard");
		response.complete(new PlannerResponse("", new PlannerToolCall("bad", "run_policy", args, null), null));
		assertNull(planner.poll(context("world", 4), success(), 1, 0, b -> true));
		assertEquals("invalid_or_failed_proposal", planner.lastReason());
	}

	@Test void uncancellableRequestDoesNotAccumulateMoreSpeculativeCalls() {
		var requests = new java.util.ArrayList<LlmConversation>();
		var bounded = new PolicyContinuationPlanner(c -> { requests.add(c); return response; }, () -> {});
		bounded.start(work(WorkSnapshot.State.RUNNING, Map.of()), context("world", 0), "", 1, 0);
		bounded.discard("changed");
		var other = new WorkSnapshot(WorkHandle.of(WorkHandle.Kind.OPERATION, "second"), "", WorkSnapshot.State.RUNNING,
			"run_policy", "POLICY", true, 11, Map.of());
		bounded.start(other, context("world", 0), "", 1, 0);
		assertEquals(1, requests.size());
	}

	@Test void speculativeRegistryAdvertisesOnlyProposalTool() {
		var registry = PlannerToolRegistry.isolated(new PolicyContinuationToolProvider());
		registry.freezeToolPrefix();
		assertEquals(1, registry.openAiTools().size());
		assertEquals("run_policy", ((Map<?, ?>) registry.openAiTools().getFirst().get("function")).get("name"));
	}

	private void start() { planner.start(work(WorkSnapshot.State.RUNNING, Map.of("source", "gather wood")), context("world", 0), "goal: craft", 1, 0); }
	private WorkSnapshot success() { return work(WorkSnapshot.State.SUCCEEDED, Map.of("result", Map.of("gathered", true))); }
	private WorkSnapshot work(WorkSnapshot.State state, Map<String, Object> details) {
		return new WorkSnapshot(handle, "", state, "run_policy", "POLICY", !state.terminal(), 10, details);
	}
	private PlannerDecisionContext context(String world, int logs) {
		return new PlannerDecisionContext(world, 10, 10, "controller", "idle", Map.of(
			"objective", "craft", "inventory", Map.of("minecraft:oak_log", logs), "vitals", Map.of("health", 20)), new SemanticEventBuffer(32).query(null));
	}
	private PlannerResponse proposal() {
		return new PlannerResponse("", new PlannerToolCall("next", "run_policy", JsonParser.parseString("""
			{"source":"function* main(p) { return {crafted:true}; }", "input":{},
			 "guard":{"parentResult":{"gathered":true},"inventoryMin":{"minecraft:oak_log":4},
			 "blocks":[{"x":1,"y":64,"z":2,"blockId":"minecraft:crafting_table"}]}}
			""").getAsJsonObject(), null), null);
	}
}
