package ai.moeru.airicraft.agent.llm.delegation;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import com.google.gson.Gson;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Client-thread owned handoff. Model sessions and gameplay executors remain outside it. */
public final class PlannerDelegation {
	private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
	private static final int TRANSCRIPT_LIMIT = 96_000;
	private sealed interface State permits Controller, Starting, Thinking, Returning { }
	private record Controller() implements State { }
	private record Starting(Work work) implements State { }
	private record Thinking(Work work) implements State { }
	private record Returning(Work work, String status, String outcome) implements State { }
	private State state = new Controller();

	private static final class Work {
		final String id = UUID.randomUUID().toString();
		final String task;
		final String successCriteria;
		final String controllerContext;
		final Map<String, ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore.Decision> decisions = new LinkedHashMap<>();
		final CompletableFuture<String> result = new CompletableFuture<>();
		final ArrayDeque<String> evidence = new ArrayDeque<>();
		int evidenceChars;
		int omittedEntries;
		long lastEventSequence;
		Work(String task, String successCriteria, String controllerContext) {
			this.task = task;
			this.successCriteria = successCriteria;
			this.controllerContext = controllerContext;
		}
	}

	public boolean active() { return !(state instanceof Controller); }
	public boolean starting() { return state instanceof Starting; }
	public boolean returning() { return state instanceof Returning; }
	public String id() { return active() ? work().id : ""; }

	public CompletableFuture<String> delegate(String task, String successCriteria, String controllerContext) {
		if (active()) throw new IllegalStateException("A delegated task already owns gameplay decisions");
		// The goal store bounds intent fields and decision count. Cutting its serialized
		// context here can erase a constraint or split a decision in the middle.
		Work work = new Work(checked(task), checked(successCriteria), controllerContext == null ? "" : controllerContext);
		state = new Starting(work);
		return work.result;
	}

	/** Called with fresh world facts immediately before starting the thinking session. */
	public String start(Map<String, Object> currentFacts, long eventSequence) {
		if (!(state instanceof Starting starting)) throw new IllegalStateException("No handoff is waiting to start");
		Work work = starting.work();
		work.lastEventSequence = eventSequence;
		state = new Thinking(work);
		return "DELEGATED TASK: " + GSON.toJson(Map.of("delegationId", work.id, "task", work.task,
			"successCriteria", work.successCriteria, "controllerContext", work.controllerContext,
			"currentFacts", currentFacts, "guidanceDuringHandoff", work.evidence.stream().map(value -> GSON.fromJson(value, Object.class)).toList()));
	}

	public String continuation() {
		Work work = work();
		return "DELEGATED TASK CONTINUATION: " + work.id + "; task=" + work.task
			+ "; successCriteria=" + work.successCriteria
			+ ". Continue from fresh evidence or call return_control with success/give_up. A plaintext reply only yields.";
	}

	public String decide(String id, String name, String decision, String reason) {
		if (!(state instanceof Thinking) || !id().equals(id)) throw new IllegalStateException("stale_delegation");
		name = checked(name);
		if (name.length() > 64 || !work().decisions.containsKey(name) && work().decisions.size() >= 16) throw new IllegalArgumentException("decision_limit");
		var previous = work().decisions.get(name);
		work().decisions.put(name, new ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore.Decision(checked(decision), checked(reason), previous == null ? "" : previous.reason()));
		return GSON.toJson(work().decisions);
	}

	public void requestReturn(String id, String status, String outcome, boolean workIdle) {
		if (!(state instanceof Thinking thinking) || !thinking.work().id.equals(id))
			throw new IllegalStateException("Delegation identity is not the current thinking task");
		if (!workIdle) throw new IllegalStateException("Gameplay work is still running; await completion or cancel it before returning control");
		if (!status.equals("success") && !status.equals("give_up")) throw new IllegalArgumentException("status must be success or give_up");
		state = new Returning(thinking.work(), status, checked(outcome));
	}

	public void recordObservationFinding(PlannerToolCall call, String finding) {
		if (!active()) return;
		var iterator = work().evidence.iterator();
		boolean found = false;
		while (iterator.hasNext()) {
			String encoded = iterator.next();
			var entry = com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
			if (entry.has("toolCallId") && call.id().equals(entry.get("toolCallId").getAsString())) {
				iterator.remove(); work().evidenceChars -= encoded.length(); found = true;
			}
		}
		if (found) recordToolExchange(call, finding, false);
	}

	public void recordToolExchange(PlannerToolCall call, String result, boolean imageAttached) {
		if (!active()) return;
		append(Map.of("kind", "tool_exchange", "toolCallId", call.id(), "tool", call.name(), "arguments", call.arguments(),
			"result", clip(result, 12_000), "imageAttached", imageAttached));
	}

	public void recordEvent(long sequence, String type, Object payload) {
		if (!active() || sequence <= work().lastEventSequence) return;
		work().lastEventSequence = sequence;
		append(Map.of("kind", "observed_event", "sequence", sequence, "type", type));
	}

	public void recordGuidance(String sender, String message) {
		append(Map.of("kind", "user_guidance", "sender", String.valueOf(sender), "message", clip(message, 12_000)));
	}

	public void recordEventGap() { work().omittedEntries++; }

	public void failed(String message) {
		if (active()) state = new Returning(work(), "failed", clip(message, 2048));
	}

	/** Complete only after the thinking tool turn has stopped. Its report is appended to the controller as a tool result. */
	public void finish(Map<String, Object> finalFacts) {
		if (!(state instanceof Returning returned)) throw new IllegalStateException("Thinking planner has not returned control");
		Work work = returned.work();
		Map<String, Object> report = new LinkedHashMap<>();
		report.put("delegationId", work.id);
		report.put("task", work.task);
		report.put("status", returned.status());
		report.put("plannerReportedOutcome", returned.outcome());
		report.put("planningDecisions", work.decisions);
		report.put("evidenceContract", "Observed event entries reference shared observation event sequence identities; tool results report executor responses. Neither the assignment outcome nor planning decisions are world facts.");
		report.put("observedEvidence", work.evidence.stream().map(value -> GSON.fromJson(value, Object.class)).toList());
		report.put("omittedEvidenceEntries", work.omittedEntries);
		report.put("finalFacts", finalFacts);
		state = new Controller();
		work.result.complete("Tool result for delegate_task: " + GSON.toJson(report));
	}

	public void reset(String reason) {
		if (!active()) return;
		Work abandoned = work();
		state = new Controller();
		abandoned.result.complete("TOOL_ERROR: delegation interrupted: " + reason);
	}

	public Map<String, Object> snapshot() {
		if (!active()) return Map.of("role", "controller", "phase", "CONTROLLER");
		Work work = work();
		return Map.of("role", "thinking", "phase", state.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT),
			"delegationId", work.id, "task", work.task, "successCriteria", work.successCriteria,
			"recordedEvidenceEntries", work.evidence.size(), "omittedEvidenceEntries", work.omittedEntries);
	}

	private void append(Map<String, Object> entry) {
		Work work = work();
		String encoded = GSON.toJson(entry);
		if (encoded.length() > TRANSCRIPT_LIMIT) {
			work.omittedEntries++;
			return;
		}
		while (work.evidenceChars + encoded.length() > TRANSCRIPT_LIMIT) {
			work.evidenceChars -= work.evidence.removeFirst().length();
			work.omittedEntries++;
		}
		work.evidence.addLast(encoded);
		work.evidenceChars += encoded.length();
	}

	private Work work() {
		return switch (state) {
			case Starting s -> s.work();
			case Thinking s -> s.work();
			case Returning s -> s.work();
			case Controller ignored -> throw new IllegalStateException("No delegated task");
		};
	}

	private static String checked(String value) {
		if (value == null || value.isBlank() || value.length() > 2048)
			throw new IllegalArgumentException("Expected nonblank text up to 2048 characters");
		return value.trim();
	}
	private static String clip(String value, int limit) {
		if (value == null) return "";
		return value.length() <= limit ? value : value.substring(0, limit) + "\n[truncated]";
	}
}
