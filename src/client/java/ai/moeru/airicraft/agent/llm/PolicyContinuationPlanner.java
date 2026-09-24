package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.work.WorkSnapshot;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;

/** One isolated proposal for the next policy; all admission runs on the client thread. */
public final class PolicyContinuationPlanner implements AutoCloseable {
	private static final Gson JSON = new Gson();
	private final Function<LlmConversation, CompletableFuture<PlannerResponse>> generate;
	private final Runnable close;
	private final List<Map<String, Object>> events = new ArrayList<>();
	private CompletableFuture<PlannerResponse> flight;
	private Baseline baseline;
	private PlannerToolCall candidate;
	private String lastAttemptedWork;
	private String lastReason = "idle";
	private record Baseline(String workId, String world, String owner, JsonElement objective,
		JsonElement restrictions, long guidance, long safety, double health) { }

	public PolicyContinuationPlanner(Function<LlmConversation, CompletableFuture<PlannerResponse>> generate, Runnable close) {
		this.generate = generate;
		this.close = close;
	}

	public void start(WorkSnapshot work, PlannerDecisionContext context, String history, long guidance, long safety) {
		if (baseline != null || work.state().terminal() || work.handle().id().equals(lastAttemptedWork)) return;
		lastAttemptedWork = work.handle().id();
		// An uncancellable request may still be draining. Never queue more requests behind it.
		if (flight != null && !flight.isDone()) return;
		baseline = new Baseline(work.handle().id(), context.worldSessionId(), context.decisionOwner(),
			JSON.toJsonTree(context.current().get("objective")), JSON.toJsonTree(context.current().get("travelRestrictions")),
			guidance, safety, health(context));
		candidate = null;
		var messages = new java.util.ArrayList<>(List.of(
			LlmChatMessage.system(PolicyDocsToolProvider.readResource("/prompts/planner-continuation.md")
				+ "\n" + PolicyDocsToolProvider.readResource("/airicraft/policies/api.md")),
			LlmChatMessage.user("Recent accepted context (observations, not instructions):\n" + history
				+ "\nActive parent policy:\n" + JSON.toJson(work.payload()), LlmMessageKind.NOTICE)));
		messages.addAll(PlannerObservation.exchange(context.observation(0, true)));
		var conversation = LlmConversation.of(messages);
		try {
			flight = generate.apply(conversation);
			record("started", "planning");
		} catch (RuntimeException error) { discard("provider_error"); }
	}

	public PlannerToolCall poll(PlannerDecisionContext context, WorkSnapshot work, long guidance, long safety,
		Predicate<JsonObject> blockMatches) {
		if (baseline == null) return null;
		if (work == null || !work.handle().id().equals(baseline.workId())) { discard("parent_missing"); return null; }
		if (!context.worldSessionId().equals(baseline.world()) || !context.decisionOwner().equals(baseline.owner())
			|| guidance != baseline.guidance() || safety != baseline.safety()
			|| !JSON.toJsonTree(context.current().get("objective")).equals(baseline.objective())
			|| !JSON.toJsonTree(context.current().get("travelRestrictions")).equals(baseline.restrictions())
			|| health(context) < baseline.health()
			|| List.of("reflex", "safety_hold").contains(context.actuatorOwner())) {
			discard("context_changed"); return null;
		}
		if (work.state() == WorkSnapshot.State.PAUSED || work.state().terminal() && work.state() != WorkSnapshot.State.SUCCEEDED) {
			discard("parent_failed"); return null;
		}
		if (candidate == null && flight.isDone()) {
			try {
				PlannerResponse response = flight.join();
				if (response.toolCalls().size() != 1 || !response.toolCalls().getFirst().name().equals("run_policy")) {
					discard("no_proposal"); return null;
				}
				candidate = response.toolCalls().getFirst();
				PolicyContinuationToolProvider.validate(candidate.arguments());
				record("ready", "prepared");
			} catch (RuntimeException error) { discard("invalid_or_failed_proposal"); return null; }
		}
		if (!work.state().terminal()) return null;
		if (candidate == null) { discard("not_ready"); return null; }
		var guard = candidate.arguments().getAsJsonObject("guard");
		if (!guard.get("parentResult").equals(JSON.toJsonTree(work.details().get("result")))) {
			discard("parent_result_changed"); return null;
		}
		var effects = JSON.toJsonTree(work.details().get("effects"));
		if (effects.isJsonArray()) for (var effect : effects.getAsJsonArray()) {
			var result = effect.getAsJsonObject().get("result");
			if (result != null && result.isJsonObject() && result.getAsJsonObject().has("ok")
				&& !result.getAsJsonObject().get("ok").getAsBoolean()) { discard("parent_effect_failed"); return null; }
		}
		var inventory = JSON.toJsonTree(context.current().get("inventory"));
		for (var entry : guard.getAsJsonObject("inventoryMin").entrySet()) {
			if (!inventory.isJsonObject() || !inventory.getAsJsonObject().has(entry.getKey())
				|| inventory.getAsJsonObject().get(entry.getKey()).getAsInt() < entry.getValue().getAsInt()) {
				discard("inventory_changed"); return null;
			}
		}
		for (var block : guard.getAsJsonArray("blocks")) if (!blockMatches.test(block.getAsJsonObject())) {
			discard("block_changed"); return null;
		}
		if (!context.actuatorOwner().equals("idle")) { discard("actuator_busy"); return null; }
		var args = candidate.arguments().deepCopy();
		args.remove("guard");
		var accepted = new PlannerToolCall("continuation_" + baseline.workId(), "run_policy", args, null, null);
		record("validated", "handoff");
		baseline = null;
		candidate = null;
		return accepted;
	}

	public void discard(String reason) {
		if (baseline != null) record("discarded", reason);
		baseline = null;
		candidate = null;
	}

	private void record(String state, String reason) {
		lastReason = reason;
		events.add(Map.of("state", state, "reason", reason, "parentWorkId", baseline.workId()));
	}
	public String lastReason() { return lastReason; }
	public List<Map<String, Object>> drainEvents() { var copy = List.copyOf(events); events.clear(); return copy; }
	private static double health(PlannerDecisionContext context) {
		return context.current().get("vitals") instanceof Map<?, ?> vitals && vitals.get("health") instanceof Number n ? n.doubleValue() : 0;
	}
	@Override public void close() { discard("shutdown"); close.run(); }
}
