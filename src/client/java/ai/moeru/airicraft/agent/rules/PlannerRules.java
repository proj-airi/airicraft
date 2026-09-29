package ai.moeru.airicraft.agent.rules;

import ai.moeru.airicraft.agent.attention.AttentionDecision;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import ai.moeru.airicraft.agent.perception.SaliencePolicy;
import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleException;
import ai.moeru.airicraft.rules.RuleModule;
import ai.moeru.airicraft.rules.RulesStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The planner's authorship of its own rule modules (spec 4.12). An edit is checked off the tick (the candidate loads,
 * runs an empty step and replays recent history next to the running module) and is then activated on the tick
 * thread, which is the only thread that publishes events. The constitution and the clamp are Java stages around the
 * modules and cannot be reached from here.
 */
public final class PlannerRules {
	public static final String SOURCE = "PlannerRules";
	public static final int MAX_REASON_CHARS = 200;
	static final Duration WARM_TIMEOUT = Duration.ofSeconds(30);
	static final Duration APPLY_TIMEOUT = Duration.ofSeconds(10);

	/** What the service reads from and does to the runtime. */
	public interface Host {
		long tick();

		boolean safetyHoldOpen();

		/** The module the hook's policy runs now. */
		RuleModule running(RuleModule.Hook hook);

		/** Switches the hook's policy to {@code module} with fresh rule state. Called on the tick thread. */
		void activate(RuleModule module);

		/** Recorded attention decisions, oldest first. */
		List<AttentionDecision> decisions();

		/** Events still in the event log, by sequence number. */
		Map<Long, SemanticEvent> events();

		List<SaliencePolicy.StepRecord> salienceSteps();

		/** Engine counters and recent rule ids of the hook's policy, for {@code inspect_rules}. */
		Map<String, Object> summary(RuleModule.Hook hook);

		/** Publishes on the tick thread. */
		void publish(String type, Map<String, Object> payload);
	}

	/** A refused or failed request: {@code code} is stable, {@code message} is for the planner. */
	public static final class Refused extends RuntimeException {
		private final String code;
		private final transient Map<String, Object> details;

		Refused(String code, String message, Map<String, Object> details) {
			super(message);
			this.code = code;
			this.details = details;
		}

		public String code() {
			return code;
		}

		public Map<String, Object> details() {
			return details;
		}
	}

	private final RulesStore store;
	private final Host host;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(
		Thread.ofPlatform().daemon().name("airicraft-rules-authoring").factory());
	private final ConcurrentLinkedQueue<Activation> applyQueue = new ConcurrentLinkedQueue<>();
	private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();

	public PlannerRules(RulesStore store, Host host) {
		this.store = Objects.requireNonNull(store, "store");
		this.host = Objects.requireNonNull(host, "host");
	}

	/**
	 * An accepted edit waiting for the tick. Exactly one of the tick (which applies it), the timeout and {@link #close}
	 * wins the state, so a warmed engine is either adopted or disposed, never both and never left behind.
	 */
	private static final class Activation {
		private static final int PENDING = 0;
		private static final int CLAIMED = 1;
		private static final int CANCELLED = 2;
		private final java.util.concurrent.atomic.AtomicInteger state = new java.util.concurrent.atomic.AtomicInteger(PENDING);
		private final RuleEngine detached;
		private final Runnable action;
		private final Runnable onCancel;

		Activation(RuleEngine detached, Runnable action, Runnable onCancel) {
			this.detached = detached;
			this.action = action;
			this.onCancel = onCancel;
		}

		/** The tick's turn: applies the edit unless it was already cancelled. */
		void run() {
			if (state.compareAndSet(PENDING, CLAIMED)) action.run();
		}

		/** Disposes the warmed engine of an edit that will not be applied; false if the tick already claimed it. */
		boolean cancel() {
			if (!state.compareAndSet(PENDING, CANCELLED)) return false;
			if (detached != null) detached.close();
			onCancel.run();
			return true;
		}
	}

	public RulesStore store() {
		return store;
	}

	/** Runs activations that passed their checks; the runtime calls it every tick, on the tick thread. */
	public void drain() {
		Activation activation;
		while ((activation = applyQueue.poll()) != null) activation.run();
	}

	/**
	 * What the planner asked for: exactly one of {@code source} or {@code revertTo} ({@code "base"} or a version
	 * number).
	 */
	public record Request(RuleModule.Hook hook, String source, String revertTo, String reason) {}

	/** Active versions and the latest edits per hook, for the debug state and the dashboard. */
	public Map<String, Object> debugState() {
		var result = new LinkedHashMap<String, Object>();
		for (RuleModule.Hook hook : RuleModule.Hook.values()) {
			var state = new LinkedHashMap<String, Object>();
			state.put("activeVersion", store.activeNumber(hook));
			state.put("base", store.base(hook).origin());
			state.put("running", host.running(hook).origin());
			var edits = new ArrayList<Map<String, Object>>();
			for (RulesStore.Version version : store.history(hook)) {
				edits.add(Map.of("version", version.number(), "kind", version.kind().name().toLowerCase(),
					"reason", version.reason(), "tick", version.tick()));
			}
			state.put("edits", edits);
			result.put(hook.name().toLowerCase(), state);
		}
		return result;
	}

	/** The active module of a hook, its history and health; {@code version} selects another version's source. */
	public Map<String, Object> inspect(RuleModule.Hook hook, Integer version) {
		var result = new LinkedHashMap<String, Object>();
		result.put("hook", hook.name().toLowerCase());
		RuleModule base = store.base(hook);
		int active = store.activeNumber(hook);
		result.put("activeVersion", active);
		result.put("base", base.origin());
		result.put("running", host.running(hook).origin());
		var history = new ArrayList<Map<String, Object>>();
		for (RulesStore.Version entry : store.history(hook)) {
			var row = new LinkedHashMap<String, Object>();
			row.put("version", entry.number());
			row.put("kind", entry.kind().name().toLowerCase());
			row.put("reason", entry.reason());
			row.put("tick", entry.tick());
			row.put("sha", entry.sha());
			row.put("fallback", entry.fallback());
			row.put("activatesBase", entry.base());
			history.add(row);
		}
		result.put("history", history);
		result.put("state", host.summary(hook));
		if (version == null) {
			result.put("source", host.running(hook).source());
			return result;
		}
		Optional<RulesStore.Version> found = store.find(hook, version);
		if (found.isEmpty()) throw new Refused("unknown_version", "version " + version + " is not in the history (kept: the latest "
			+ RulesStore.HISTORY + ")", Map.of());
		result.put("sourceOfVersion", version);
		result.put("source", found.get().base() ? base.source() : found.get().source());
		return result;
	}

	/**
	 * Checks and activates an edit. The future completes with the accepted edit (version, replay diff, warnings), or
	 * fails with {@link Refused}.
	 */
	public CompletableFuture<Map<String, Object>> update(Request request) {
		try {
			guard(request.hook());
		}
		catch (Refused refused) {
			return CompletableFuture.failedFuture(refused);
		}
		inFlight.incrementAndGet();
		return CompletableFuture.supplyAsync(() -> prepare(request), worker).thenCompose(prepared -> apply(request, prepared))
			.whenComplete((result, failure) -> inFlight.decrementAndGet());
	}

	/** Accepted edits waiting for the next tick. */
	int pendingActivations() {
		return applyQueue.size();
	}

	/** Edits still being checked or waiting for the tick; a test harness keeps ticking while this is above zero. */
	public int inFlight() {
		return inFlight.get();
	}

	private void guard(RuleModule.Hook hook) {
		if (host.safetyHoldOpen()) {
			throw new Refused("work_in_safety_hold", "a safety hold is open; edit the rules after it releases", Map.of());
		}
		long retryAt = store.retryAtTick(hook, host.tick());
		if (retryAt >= 0) {
			throw new Refused("rules_update_rate", "at most " + RulesStore.MAX_UPDATES + " edits of the " + hook.name().toLowerCase()
				+ " rules per " + RulesStore.UPDATE_WINDOW_TICKS + " ticks; try again at tick " + retryAt, Map.of("retryAtTick", retryAt));
		}
	}

	private record Prepared(RuleModule module, RuleEngine detached, RulesStore.Kind kind, String source, int fallback, int previous,
		int expectedNext, RuleDryRun.Diff diff, List<String> warnings) {}

	private Prepared prepare(Request request) {
		RuleModule.Hook hook = request.hook();
		int previous = store.activeNumber(hook);
		int expectedNext = store.nextNumber(hook);
		RulesStore.Kind kind;
		String source;
		int fallback;
		RuleModule candidate;
		if (request.source() != null) {
			kind = RulesStore.Kind.UPDATE;
			source = request.source();
			fallback = previous;
			candidate = new RuleModule(RulesStore.ORIGIN_PREFIX + hook.name().toLowerCase() + "/v" + expectedNext, source, hook);
		}
		else if (request.revertTo().equals("base")) {
			kind = RulesStore.Kind.ROLLBACK;
			source = null;
			fallback = 0;
			candidate = store.base(hook);
		}
		else {
			int number = Integer.parseInt(request.revertTo());
			RulesStore.Version target = store.find(hook, number).orElseThrow(() -> new Refused("unknown_version",
				"version " + number + " is not in the history (kept: the latest " + RulesStore.HISTORY + ")", Map.of()));
			kind = RulesStore.Kind.ROLLBACK;
			if (target.base()) {
				source = null;
				fallback = 0;
				candidate = store.base(hook);
			}
			else {
				source = target.source();
				fallback = target.fallback();
				candidate = new RuleModule(RulesStore.ORIGIN_PREFIX + hook.name().toLowerCase() + "/v" + expectedNext, source, hook);
			}
		}
		// A bundled candidate has a shared engine that is never evicted. Anything else is built privately, so checking
		// it cannot close the override that is running, and it is adopted only once it is accepted.
		boolean owned = !candidate.bundled();
		RuleEngine detached = owned ? RuleEngine.detached(candidate) : RuleEngine.shared(candidate);
		try {
			detached.awaitReady(WARM_TIMEOUT);
			if (owned) detached.checkEmptyStep();
		}
		catch (RuleException exception) {
			if (owned) detached.close();
			throw new Refused("cold".equals(exception.code()) ? "rules_warmup_timeout" : "rules_invalid",
				exception.code() + ": " + exception.getMessage(), Map.of("code", exception.code()));
		}
		RuleDryRun.Diff diff;
		try {
			diff = hook == RuleModule.Hook.ATTENTION
				? RuleDryRun.attention(host.decisions(), host.events(), RuleEngine.shared(host.running(hook)), detached)
				: RuleDryRun.salience(host.salienceSteps(), RuleEngine.shared(host.running(hook)), detached);
		}
		catch (RuntimeException exception) {
			if (owned) detached.close();
			throw exception;
		}
		if (diff.failed()) {
			if (owned) detached.close();
			throw new Refused("rules_step_failed", diff.firstFailure() + " (" + diff.newFailures() + " of " + diff.replayed()
				+ " replayed steps failed)", Map.of("replay", diff.toMap()));
		}
		var warnings = new ArrayList<String>();
		if (hook == RuleModule.Hook.ATTENTION && source != null && !source.contains("plannerRules")) {
			warnings.add("this module never reads input.plannerRules, so update_event_policy has no effect while it runs");
		}
		if (diff.replayed() == 0) {
			warnings.add("there was no recorded history to replay; the edit was checked only for loading and one empty step");
		}
		return new Prepared(candidate, owned ? detached : null, kind, source, fallback, previous, expectedNext, diff, warnings);
	}

	private CompletableFuture<Map<String, Object>> apply(Request request, Prepared prepared) {
		var done = new CompletableFuture<Map<String, Object>>();
		var activation = new Activation(prepared.detached(), () -> {
			try {
				done.complete(activate(request, prepared));
			}
			catch (RuntimeException failure) {
				if (prepared.detached() != null) prepared.detached().close();
				done.completeExceptionally(failure);
			}
		}, () -> done.completeExceptionally(new Refused("rules_unavailable", "the rules service was shut down before your edit was applied", Map.of())));
		// Without a tick the queue is never drained, so the timeout disposes of the engine itself.
		done.orTimeout(APPLY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).whenComplete((result, failure) -> {
			if (failure != null && activation.cancel()) applyQueue.remove(activation);
		});
		var settled = done.exceptionallyCompose(failure -> {
			Throwable cause = failure instanceof java.util.concurrent.TimeoutException || failure.getCause() instanceof java.util.concurrent.TimeoutException
				? new Refused("rules_update_timeout", "the game did not tick for " + APPLY_TIMEOUT.toSeconds() + " s; the edit was not applied", Map.of())
				: failure;
			return CompletableFuture.failedFuture(cause);
		});
		// Queue last: everything that hangs off the future is registered by now, so the tick thread completes the
		// whole chain (the tool result included) inside its drain, not this worker thread afterwards.
		applyQueue.add(activation);
		return settled;
	}

	/** Tick thread: rechecks what may have changed while the candidate was being checked, then switches. */
	private Map<String, Object> activate(Request request, Prepared prepared) {
		RuleModule.Hook hook = request.hook();
		guard(hook);
		if (store.nextNumber(hook) != prepared.expectedNext()) {
			throw new Refused("rules_changed_during_check", "the " + hook.name().toLowerCase()
				+ " rules changed while your edit was being checked (an automatic revert); inspect_rules and send it again", Map.of());
		}
		if (prepared.detached() != null) RuleEngine.adopt(prepared.detached());
		long tick = host.tick();
		RulesStore.Version version = store.append(hook, prepared.kind(), prepared.source(), request.reason(), tick, prepared.fallback(),
			prepared.diff().toMap());
		host.activate(prepared.module());
		var event = new LinkedHashMap<String, Object>();
		event.put("hook", hook.name().toLowerCase());
		event.put("version", store.activeNumber(hook));
		event.put("previousVersion", prepared.previous());
		event.put("kind", prepared.kind().name().toLowerCase());
		event.put("sha", version.sha());
		event.put("reason", request.reason());
		event.put("replayed", prepared.diff().replayed());
		event.put("changed", prepared.diff().changed());
		event.put(hook == RuleModule.Hook.ATTENTION ? "wakesGained" : "perceptsGained", prepared.diff().gained());
		event.put(hook == RuleModule.Hook.ATTENTION ? "wakesLost" : "perceptsLost", prepared.diff().lost());
		host.publish("rules.updated", event);
		var result = new LinkedHashMap<String, Object>();
		result.put("accepted", true);
		result.put("hook", hook.name().toLowerCase());
		result.put("activeVersion", store.activeNumber(hook));
		result.put("previousVersion", prepared.previous());
		result.put("kind", prepared.kind().name().toLowerCase());
		result.put("sha", version.sha());
		result.put("replay", prepared.diff().toMap());
		if (!prepared.warnings().isEmpty()) result.put("warnings", prepared.warnings());
		result.put("note", "Rules run with fresh state from the next event. roll back with update_rules revert_to.");
		return result;
	}

	/** Stops the worker and refuses every edit still waiting for a tick, disposing of its warmed engine. */
	public void close() {
		worker.shutdownNow();
		Activation activation;
		while ((activation = applyQueue.poll()) != null) activation.cancel();
	}
}
