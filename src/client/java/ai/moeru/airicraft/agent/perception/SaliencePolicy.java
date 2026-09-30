package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.Airicraft;
import ai.moeru.airicraft.agent.events.EventPublisher;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleException;
import ai.moeru.airicraft.rules.RuleModule;
import ai.moeru.airicraft.rules.RuleRevert;
import ai.moeru.airicraft.rules.RuleStepResult;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The salience hook (spec 4.12): candidates from the noticing sensors go through a sandboxed GraalJS module that
 * decides which become percepts. At most {@code candidatesPerStep} candidates go into one step, oldest first; the
 * rest wait for the next tick. Candidates wait out a cold engine without ageing. A failed step publishes
 * {@code rules.step_failed} and keeps its candidates, which are offered again for up to {@link #RETRY_TICKS} ticks
 * after their first failed attempt and then dropped; after three consecutive failures an override reverts to the
 * bundled module. There is no Java mirror: noticing is not safety-critical, and the attention
 * constitution keeps the agent wakeable without it.
 */
public final class SaliencePolicy {
	public static final String SOURCE = "SaliencePolicy";
	public static final Set<String> PERCEPT_TYPES = Set.of("perception.block_noticed", "perception.item_noticed",
		"perception.entity_noticed", "perception.entity_lost", "perception.environment_changed");
	static final int REVERT_AFTER_FAILURES = 3;
	static final int RETRY_TICKS = 20;
	private static final int RECENT_DECISIONS = 64;
	private static final int MAX_PENDING = 512;
	private static final Gson GSON = new Gson();

	public record Percept(String type, Map<String, Object> payload, List<String> candidateIds) {}

	/** One step as the host ran it, for recording and replay: input, state before, and the output. */
	public record StepRecord(long tick, String module, JsonObject input, String stateBefore, JsonArray percepts, JsonArray drops) {}

	/** A waiting candidate; {@code failedSince} is the tick of its first failed step, or -1 before any. */
	private static final class Pending {
		final PerceptCandidate candidate;
		long failedSince = -1L;

		Pending(PerceptCandidate candidate) { this.candidate = candidate; }

		PerceptCandidate candidate() { return candidate; }
	}

	private final EventPublisher diagnostics;
	private final ArrayDeque<Pending> pending = new ArrayDeque<>();
	private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();
	private Consumer<StepRecord> recorder = ignored -> { };
	private RuleModule module;
	private RuleRevert revert = RuleRevert.toBundled(RuleModule.Hook.SALIENCE);
	/** Built on first use, so a runtime that never notices anything never warms a salience engine. */
	private RuleEngine engine;
	private String ruleState = "{}";
	private int consecutiveFailures;
	private long steps;
	private long failures;
	private long reverts;
	private long percepts;
	private long drops;
	private long expired;
	private long rejected;
	private long maxStepNanos;
	private final long[] stepWindow = new long[256];
	private long timedSteps;
	private String lastFailure = "";

	public SaliencePolicy(EventPublisher diagnostics, RuleModule module) {
		this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
		useModule(module);
	}

	/** Switches to {@code module} (an override or the bundled module) with fresh rule state. */
	public synchronized void useModule(RuleModule module) {
		if (Objects.requireNonNull(module, "module").hook() != RuleModule.Hook.SALIENCE) {
			throw new IllegalArgumentException("not a salience module: " + module.origin());
		}
		this.module = module;
		engine = null;
		ruleState = "{}";
		consecutiveFailures = 0;
	}

	public synchronized RuleModule module() {
		return module;
	}

	/** Chooses where a failing override reverts; the runtime passes the planner's version store. */
	public synchronized void useRevert(RuleRevert revert) {
		this.revert = Objects.requireNonNull(revert, "revert");
	}

	private RuleEngine engine() {
		if (engine == null) engine = RuleEngine.shared(module);
		return engine;
	}

	public synchronized void recordSteps(Consumer<StepRecord> recorder) {
		this.recorder = recorder == null ? ignored -> { } : recorder;
	}

	/** Block ids and {@code #tags} the module asked the host to scan for; empty while the engine is cold. */
	public synchronized Set<String> blockInterests() {
		var interests = engine().interests();
		var blocks = new LinkedHashSet<String>();
		if (interests.has("blocks") && interests.get("blocks").isJsonArray()) {
			for (JsonElement element : interests.getAsJsonArray("blocks")) {
				if (element.isJsonPrimitive()) blocks.add(element.getAsString());
			}
		}
		return Set.copyOf(blocks);
	}

	public synchronized void offer(PerceptCandidate candidate, long tick) {
		if (pending.size() >= MAX_PENDING) {
			pending.removeFirst();
			expired++;
		}
		pending.addLast(new Pending(candidate));
	}

	public synchronized int pendingCount() {
		return pending.size();
	}

	/** Forgets queued candidates, for example at a world boundary. */
	public synchronized void clear() {
		pending.clear();
		ruleState = "{}";
	}

	/**
	 * Runs one step when candidates are waiting and returns the percepts to publish. {@code context} is the plain
	 * data the rules may read (goal, wanted items, inventory, the running job).
	 */
	public synchronized List<Percept> step(long tick, Map<String, Object> context, int candidatesPerStep) {
		if (pending.isEmpty()) return List.of();
		RuleException loadFailure = engine().loadFailure();
		// A warming engine has not attempted anything yet: candidates wait without ageing.
		if (loadFailure == null && !engine().ready()) return List.of();
		expire(tick);
		if (pending.isEmpty()) return List.of();
		var batch = new ArrayList<Pending>();
		for (Pending entry : pending) {
			if (batch.size() >= candidatesPerStep) break;
			batch.add(entry);
		}
		if (loadFailure != null) {
			failed(batch, tick);
			fail(tick, loadFailure);
			return List.of();
		}
		JsonObject input = input(tick, context, batch);
		String stateBefore = ruleState;
		RuleStepResult result;
		try {
			result = engine().step(input.toString(), ruleState);
		}
		catch (RuleException exception) {
			if (!"cold".equals(exception.code())) {
				failed(batch, tick);
				fail(tick, exception);
			}
			return List.of();
		}
		for (int index = 0; index < batch.size(); index++) pending.removeFirst();
		ruleState = result.stateJson();
		steps++;
		consecutiveFailures = 0;
		maxStepNanos = Math.max(maxStepNanos, result.nanos());
		stepWindow[(int) (timedSteps++ % stepWindow.length)] = result.nanos();
		recorder.accept(new StepRecord(tick, module.origin(), input, stateBefore, result.percepts(), result.drops()));
		var out = new ArrayList<Percept>();
		var decided = new LinkedHashSet<String>();
		for (JsonElement element : result.percepts()) {
			Percept percept = percept(element);
			if (percept == null) {
				rejected++;
				continue;
			}
			out.add(percept);
			percepts++;
			decided.addAll(percept.candidateIds());
			remember(tick, percept.candidateIds(), percept.type());
		}
		for (JsonElement element : result.drops()) {
			if (!element.isJsonObject()) continue;
			JsonObject drop = element.getAsJsonObject();
			String id = drop.has("candidateId") ? drop.get("candidateId").getAsString() : null;
			if (id == null || !decided.add(id)) continue;
			drops++;
			remember(tick, List.of(id), "dropped:" + (drop.has("reason") ? drop.get("reason").getAsString() : "unspecified"));
		}
		for (Pending entry : batch) {
			if (decided.add(entry.candidate().id())) {
				drops++;
				remember(tick, List.of(entry.candidate().id()), "dropped:unselected");
			}
		}
		return List.copyOf(out);
	}

	/** Engine status, counters and recent decisions for the bridge debug state and the dashboard. */
	public synchronized Map<String, Object> debugState() {
		var result = new LinkedHashMap<String, Object>();
		result.put("module", module.origin());
		result.put("ready", engine != null && engine.ready());
		result.put("blockInterests", engine == null ? 0 : blockInterests().size());
		result.put("pending", pending.size());
		result.put("steps", steps);
		result.put("percepts", percepts);
		result.put("drops", drops);
		result.put("expired", expired);
		result.put("rejectedPercepts", rejected);
		result.put("failures", failures);
		result.put("consecutiveFailures", consecutiveFailures);
		result.put("reverts", reverts);
		result.put("maxStepMicros", maxStepNanos / 1_000L);
		int count = (int) Math.min(timedSteps, stepWindow.length);
		long[] recentSteps = java.util.Arrays.copyOf(stepWindow, count);
		java.util.Arrays.sort(recentSteps);
		result.put("stepP50Micros", count == 0 ? 0 : recentSteps[count / 2] / 1_000L);
		result.put("stepP99Micros", count == 0 ? 0 : recentSteps[Math.min(count - 1, (int) Math.ceil(count * .99) - 1)] / 1_000L);
		result.put("stateBytes", ruleState.getBytes(StandardCharsets.UTF_8).length);
		result.put("lastFailure", lastFailure);
		result.put("recent", List.copyOf(recent));
		return result;
	}

	private static void failed(List<Pending> batch, long tick) {
		for (Pending entry : batch) if (entry.failedSince < 0) entry.failedSince = tick;
	}

	private void expire(long tick) {
		var stale = pending.iterator();
		while (stale.hasNext()) {
			Pending entry = stale.next();
			if (entry.failedSince < 0 || tick - entry.failedSince <= RETRY_TICKS) continue;
			stale.remove();
			expired++;
			remember(tick, List.of(entry.candidate().id()), "dropped:salience_unavailable");
		}
	}

	private void fail(long tick, RuleException exception) {
		failures++;
		consecutiveFailures++;
		lastFailure = exception.code() + ": " + exception.getMessage();
		String failedModule = module.origin();
		diagnostics.publish(tick, "rules.step_failed", Map.of(
			"module", failedModule,
			"code", exception.code(),
			"message", String.valueOf(exception.getMessage()),
			"candidates", pending.size()
		), SOURCE, null);
		boolean loadFailed = engine().loadFailure() != null;
		if (!module.bundled() && (loadFailed || consecutiveFailures >= REVERT_AFTER_FAILURES)) {
			RuleRevert.Result target = revert.revert(module, tick);
			module = target.to();
			engine = RuleEngine.shared(module);
			ruleState = "{}";
			consecutiveFailures = 0;
			reverts++;
			Airicraft.LOGGER.warn("Salience rules {} failed ({}); reverted to {}", failedModule, lastFailure, module.origin());
			diagnostics.publish(tick, "rules.reverted", Map.of(
				"hook", "salience",
				"from", failedModule,
				"to", module.origin(),
				"fromVersion", target.fromVersion(),
				"toVersion", target.toVersion(),
				"reason", loadFailed ? "load_failed" : "consecutive_step_failures"
			), SOURCE, null);
		}
	}

	private void remember(long tick, List<String> candidateIds, String outcome) {
		for (String id : candidateIds) {
			recent.addLast(Map.of("tick", tick, "candidateId", id, "outcome", outcome));
			while (recent.size() > RECENT_DECISIONS) recent.removeFirst();
		}
	}

	private static JsonObject input(long tick, Map<String, Object> context, List<Pending> batch) {
		var input = new JsonObject();
		input.addProperty("tick", tick);
		input.addProperty("seed", tick);
		input.add("context", GSON.toJsonTree(context == null ? Map.of() : context));
		var candidates = new JsonArray();
		for (Pending entry : batch) candidates.add(GSON.toJsonTree(entry.candidate().toInput()));
		input.add("candidates", candidates);
		return input;
	}

	/** A percept the host accepts: a known {@code perception.*} type with an object payload. */
	static Percept percept(JsonElement element) {
		if (!element.isJsonObject()) return null;
		JsonObject object = element.getAsJsonObject();
		if (!object.has("type") || !object.get("type").isJsonPrimitive()) return null;
		String type = object.get("type").getAsString();
		if (!PERCEPT_TYPES.contains(type)) return null;
		if (!object.has("payload") || !object.get("payload").isJsonObject()) return null;
		var ids = new ArrayList<String>();
		if (object.has("candidateIds") && object.get("candidateIds").isJsonArray()) {
			for (JsonElement id : object.getAsJsonArray("candidateIds")) if (id.isJsonPrimitive()) ids.add(id.getAsString());
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> payload = (Map<String, Object>) plain(object.get("payload"));
		return new Percept(type, payload, List.copyOf(ids));
	}

	/** JSON to plain Java values; integral numbers stay integral so payloads render as {@code 3}, not {@code 3.0}. */
	static Object plain(JsonElement element) {
		if (element == null || element.isJsonNull()) return null;
		if (element.isJsonObject()) {
			var map = new LinkedHashMap<String, Object>();
			for (var entry : element.getAsJsonObject().entrySet()) {
				Object value = plain(entry.getValue());
				if (value != null) map.put(entry.getKey(), value);
			}
			return map;
		}
		if (element.isJsonArray()) {
			var list = new ArrayList<Object>();
			for (JsonElement item : element.getAsJsonArray()) list.add(plain(item));
			return list;
		}
		var primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) return primitive.getAsBoolean();
		if (primitive.isString()) return primitive.getAsString();
		double value = primitive.getAsDouble();
		if (value == Math.rint(value) && Math.abs(value) < 1e15) return (long) value;
		return value;
	}
}
