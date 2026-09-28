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
				context = built;
				kernel = loadedKernel;
			}
		}
		catch (PolyglotException exception) {
			built.close(true);
			throw new java.util.concurrent.CompletionException(new RuleException("load_failed",
				module.origin() + ": " + exception.getMessage(), exception));
		}
		catch (RuntimeException exception) {
			built.close(true);
			throw exception;
		}
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
