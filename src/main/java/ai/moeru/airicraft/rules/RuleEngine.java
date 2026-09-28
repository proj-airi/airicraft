package ai.moeru.airicraft.rules;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

/**
 * A sandboxed GraalJS context running one rule module. The context is built off-thread ({@link #ready()} is
 * false until then); after that, {@link #step} runs synchronously on the caller's thread under a statement limit.
 * The engine keeps no rule state: each step receives the previous JSON state and returns the next one, so one
 * warm engine can serve every runtime in the JVM ({@link #shared}).
 */
public final class RuleEngine implements AutoCloseable {
	public static final int STATEMENT_LIMIT = 50_000;
	public static final int MAX_STATE_BYTES = 16 * 1024;
	public static final int MAX_OUTPUT_CHARS = 64 * 1024;
	/**
	 * Steps run off-thread before the engine reports ready. The interpreter specializes a module's code as it runs:
	 * a first step takes ~170 ms and early steps several ms, against ~0.3 ms once specialized. Steps run
	 * synchronously on the routing thread, so that cost is paid here instead.
	 */
	static final int WARMUP_STEPS = 600;
	private static final String[] WARMUP_TYPES = {
		"pickup.item_picked_up", "crafting.item_crafted", "combat.damage_taken", "player.physical", "social.item_offered",
		"social.player_spoke", "social.system_message", "smelting.output_ready", "task.blocked", "action_graph.goal_suspended",
		"action_graph.goal_terminal", "session.world_loaded", "task.notice"};
	private static final String[] WARMUP_JOBS = {"null", "\"MINE_BLOCKS\"", "\"COLLECT_RESOURCE\"", "\"IDLE\""};
	private static final ExecutorService WARMUP = Executors.newSingleThreadExecutor(
		Thread.ofPlatform().daemon().name("airicraft-rules-warmup").factory());
	/** The bundled module plus at most one override stay warm; replaced overrides are closed. */
	private static final Map<String, RuleEngine> SHARED = new LinkedHashMap<>();

	private final RuleModule module;
	private volatile CompletableFuture<Void> ready;
	private long rebuilds;
	private Context context;
	private Value kernel;
	private boolean closed;

	private RuleEngine(RuleModule module) {
		this.module = Objects.requireNonNull(module, "module");
		if (module.source().isBlank() || module.source().length() > RuleModule.MAX_SOURCE_CHARS) {
			ready = CompletableFuture.failedFuture(new RuleException("source_limit",
				"rule source must be 1-" + RuleModule.MAX_SOURCE_CHARS + " characters"));
		}
		else {
			ready = CompletableFuture.runAsync(this::build, WARMUP);
		}
	}

	/** The shared, warming engine for {@code module}; a different override replaces the previous one. */
	public static synchronized RuleEngine shared(RuleModule module) {
		String key = module.origin() + "#" + module.sha();
		RuleEngine existing = SHARED.get(key);
		if (existing != null) return existing;
		if (!module.bundled()) {
			SHARED.entrySet().removeIf(entry -> {
				if (entry.getValue().module.bundled()) return false;
				entry.getValue().close();
				return true;
			});
		}
		RuleEngine engine = new RuleEngine(module);
		SHARED.put(key, engine);
		return engine;
	}

	/**
	 * Builds a private engine, waits for it and runs one step with an empty input. Used to reject an invalid
	 * override before it replaces anything.
	 */
	public static void validate(RuleModule module, Duration timeout) throws RuleException {
		try (RuleEngine engine = new RuleEngine(module)) {
			engine.awaitReady(timeout);
			engine.step("{\"tick\":0,\"seed\":1,\"attention\":{},\"plannerRules\":[],\"events\":[]}", "{}");
		}
	}

	public RuleModule module() {
		return module;
	}

	public boolean ready() {
		return ready.isDone() && !ready.isCompletedExceptionally();
	}

	/** The load failure, or {@code null} while warming or when loaded. */
	public synchronized RuleException loadFailure() {
		if (!ready.isCompletedExceptionally()) return null;
		try {
			ready.join();
			return null;
		}
		catch (RuntimeException failure) {
			Throwable cause = failure.getCause() == null ? failure : failure.getCause();
			return cause instanceof RuleException rule ? rule : new RuleException("load_failed", String.valueOf(cause.getMessage()), cause);
		}
	}

	public void awaitReady(Duration timeout) throws RuleException {
		try {
			ready.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException exception) {
			throw new RuleException("cold", "rule engine did not warm up within " + timeout);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new RuleException("cold", "interrupted while warming the rule engine");
		}
		catch (ExecutionException exception) {
			RuleException failure = loadFailure();
			throw failure != null ? failure : new RuleException("load_failed", String.valueOf(exception.getMessage()));
		}
	}

	/**
	 * Runs one step: {@code kernel.run(input, state)} returning {@code {"decisions": [...], "state": {...}}} as JSON.
	 * The statement limit is reset for every step; exhausting it, a guest error, or an oversized state fails the
	 * step and leaves the caller's state untouched.
	 */
	public synchronized RuleStepResult step(String inputJson, String stateJson) throws RuleException {
		if (closed) throw new RuleException("closed", "rule engine is closed");
		if (!ready()) {
			RuleException failure = loadFailure();
			throw failure != null ? failure : new RuleException("cold", "rule engine is still warming up");
		}
		if (stateJson.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
			throw new RuleException("state_limit", "rule state exceeds " + MAX_STATE_BYTES + " bytes");
		}
		String output;
		long start = System.nanoTime();
		try {
			context.resetLimits();
			output = kernel.invokeMember("run", inputJson, stateJson).asString();
		}
		catch (PolyglotException exception) {
			if (exception.isResourceExhausted() || exception.isCancelled()) {
				// Graal cancels a context that exhausts its limits; it cannot run again. Rebuild it off-thread.
				rebuild();
				throw new RuleException(exception.isResourceExhausted() ? "statement_limit" : "cancelled",
					exception.isResourceExhausted() ? "rule step exceeded " + STATEMENT_LIMIT + " statements" : "rule step was cancelled");
			}
			throw new RuleException("guest_error", String.valueOf(exception.getMessage()));
		}
		long nanos = System.nanoTime() - start;
		if (output.length() > MAX_OUTPUT_CHARS) throw new RuleException("output_limit", "rule output exceeds " + MAX_OUTPUT_CHARS + " characters");
		com.google.gson.JsonObject parsed;
		try {
			parsed = com.google.gson.JsonParser.parseString(output).getAsJsonObject();
		}
		catch (RuntimeException exception) {
			throw new RuleException("malformed_output", "rule output is not a JSON object");
		}
		if (!parsed.has("decisions") || !parsed.get("decisions").isJsonArray()) {
			throw new RuleException("malformed_output", "rule output has no decisions array");
		}
		String nextState = parsed.has("state") ? parsed.get("state").toString() : "{}";
		if (nextState.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
			throw new RuleException("state_limit", "returned rule state exceeds " + MAX_STATE_BYTES + " bytes");
		}
		return new RuleStepResult(parsed.getAsJsonArray("decisions"), nextState, nanos);
	}

	/** How many times a cancelled context was rebuilt. */
	public synchronized long rebuilds() {
		return rebuilds;
	}

	private void rebuild() {
		Context dead = context;
		context = null;
		kernel = null;
		rebuilds++;
		if (dead != null) WARMUP.execute(() -> dead.close(true));
		ready = CompletableFuture.runAsync(this::build, WARMUP);
	}

	private void build() {
		build(true);
	}

	private void build(boolean warm) {
		Context built = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false")
			.allowHostAccess(HostAccess.NONE).allowHostClassLookup(name -> false).allowHostClassLoading(false)
			.allowIO(IOAccess.NONE).allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
			.allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
			.in(java.io.InputStream.nullInputStream()).out(java.io.OutputStream.nullOutputStream()).err(java.io.OutputStream.nullOutputStream())
			.resourceLimits(ResourceLimits.newBuilder().statementLimit(STATEMENT_LIMIT, ignored -> true).build()).build();
		try {
			Value loadedKernel = built.eval("js", RuleModule.resource("kernel.js"));
			loadedKernel.invokeMember("load", built.eval("js", RuleModule.resource("lib.js")), built.eval("js", factoryExpression(module.source())));
			synchronized (this) {
				if (closed) {
					built.close(true);
					return;
				}
			}
			if (warm) warmUp(built, loadedKernel);
			synchronized (this) {
				if (closed) {
					built.close(true);
					return;
				}
				context = built;
				kernel = loadedKernel;
			}
		}
		catch (PolyglotException exception) {
			built.close(true);
			throw new java.util.concurrent.CompletionException(new RuleException("load_failed",
				module.origin() + ": " + exception.getMessage(), exception));
		}
		catch (WarmupCancelled cancelled) {
			built.close(true);
			build(false);
		}
		catch (RuntimeException exception) {
			built.close(true);
			throw exception;
		}
	}

	/**
	 * Runs {@link #WARMUP_STEPS} representative attention steps and discards their results. A module that fails on
	 * this synthetic input is not rejected here; its real steps fail and fall back as usual. A warm-up step that
	 * exhausts the statement limit cancels the context, so it is rebuilt once without warm-up.
	 */
	private void warmUp(Context built, Value loadedKernel) {
		for (int step = 0; step < WARMUP_STEPS; step++) {
			try {
				built.resetLimits();
				loadedKernel.invokeMember("run", warmupInput(step), "{}");
			}
			catch (PolyglotException exception) {
				if (exception.isResourceExhausted() || exception.isCancelled()) throw new WarmupCancelled();
				return;
			}
		}
	}

	private static final class WarmupCancelled extends RuntimeException {
		WarmupCancelled() {
			super(null, null, false, false);
		}
	}

	static String warmupInput(int step) {
		String type = WARMUP_TYPES[step % WARMUP_TYPES.length];
		boolean flag = (step / WARMUP_TYPES.length) % 2 == 0;
		String job = WARMUP_JOBS[(step / 2) % WARMUP_JOBS.length];
		String rules = step % 3 == 0 ? "[]" : "[{\"index\":0,\"ruleId\":\"warmup\",\"effect\":\"" + (step % 2 == 0 ? "IGNORE" : "SEMANTIC_ONLY")
			+ "\",\"reason\":\"warmup\",\"match\":{\"eventType\":\"" + type + "\",\"itemId\":\"minecraft:dirt\"}}]";
		return "{\"tick\":" + step + ",\"seed\":" + (step + 1) + ",\"attention\":{\"proactiveSocialMode\":" + flag
			+ ",\"reflexOwnsActuation\":" + !flag + ",\"activeJobType\":" + job + ",\"activeJobIdle\":" + (step % 5 == 0)
			+ ",\"activeJobTerminal\":" + (step % 7 == 0) + ",\"pendingCraftToolResult\":" + (step % 4 == 0) + "},\"plannerRules\":" + rules
			+ ",\"events\":[{\"seqNo\":" + (step + 1) + ",\"type\":\"" + type + "\",\"fields\":{\"itemId\":\"minecraft:" + (flag ? "dirt" : "oak_log")
			+ "\",\"player\":\"Alex\",\"state\":\"" + (flag ? "FAILED" : "SUCCEEDED") + "\"},\"profile\":{\"semantic\":true,\"trigger\":" + (step % 6 != 0)
			+ ",\"bypass\":" + (step % 11 == 0) + "},\"plannerEnabled\":" + (step % 9 != 0) + ",\"evidence\":{\"addressedToAgent\":" + (step % 8 == 0)
			+ ",\"resetCommand\":false,\"senderWithinChatDistance\":" + flag + "}}]}";
	}

	/**
	 * A module is one JS expression that evaluates to a factory {@code lib => ({step(input, state, lib)})}, optionally
	 * preceded by comments. It is evaluated in strict mode inside a function, so it cannot declare globals.
	 */
	static String factoryExpression(String source) {
		String expression = source.strip();
		while (expression.endsWith(";")) expression = expression.substring(0, expression.length() - 1).strip();
		return "(function() { 'use strict';\nreturn (\n" + expression + "\n);\n})()";
	}

	@Override
	public synchronized void close() {
		if (closed) return;
		closed = true;
		Context active = context;
		context = null;
		kernel = null;
		// Never wait on the caller's thread (the client tick) for guest teardown.
		if (active != null) WARMUP.execute(() -> active.close(true));
	}
}
