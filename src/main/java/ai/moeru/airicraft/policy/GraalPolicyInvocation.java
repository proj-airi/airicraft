package ai.moeru.airicraft.policy;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.ResourceLimits;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded guest execution for effect generators and snapshot queries; no Java objects cross the boundary. */
public final class GraalPolicyInvocation implements PolicyRuntime.Program {
	public static final int MAX_SOURCE_CHARS = 32_768;
	public static final int MAX_VALUE_CHARS = 16_384;
	private static final int MAX_QUERY_SNAPSHOT_CHARS = 2_097_152;
	private static final String QUERY_KERNEL = """
		(() => {
		  let query, snapshot;
		  return {
		    initialize(factory, input) {
		      query = factory();
		      if (typeof query !== 'function') throw Error('Define function query(world, input)');
		      snapshot = JSON.parse(input);
		    },
		    resume() {
		      const value = query(snapshot.world, snapshot.input);
		      if (value && (typeof value.then === 'function' || typeof value.next === 'function'))
		        throw Error('query must return synchronous JSON, not a promise or generator');
		      return JSON.stringify({done: true, value: value === undefined ? null : value});
		    }
		  };
		})()
		""";
	private static final String KERNEL = """
		(() => {
		  let iterator;
		  const policy = {
		    describe: name => ({operation: 'describe_tool', arguments: {name}}),
		    observeContainer: () => ({operation: 'observe_container', arguments: {}}),
		    withdraw: (syncId, items) => ({operation: 'withdraw', arguments: {syncId, items}}),
		    closeContainer: syncId => typeof syncId === 'number'
		      ? ({operation: 'close_container', arguments: {syncId}})
		      : ({operation: 'call_tool', arguments: {name: 'close_container', args: syncId || {}}})
		  };
		  return {
		    initialize(factory, input, methods) {
		      for (const [method, name] of Object.entries(JSON.parse(methods))) {
		        if (!(method in policy)) policy[method] = (args = {}) => ({operation: 'call_tool', arguments: {name, args}});
		      }
		      Object.freeze(policy);
		      const main = factory();
		      if (typeof main !== 'function') throw Error('Define function* main(policy, input)');
		      iterator = main(policy, JSON.parse(input));
		      if (!iterator || typeof iterator.next !== 'function') throw Error('main must be a generator');
		    },
		    resume(input) {
		      const step = iterator.next(JSON.parse(input));
		      return JSON.stringify({done: step.done, value: step.value === undefined ? null : step.value});
		    }
		  };
		})()
		""";
	private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("airicraft-policy").factory());
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicBoolean resuming = new AtomicBoolean();
	private volatile Context context;
	private Value guest;
	private final CompletableFuture<Void> ready;
	private final int executionTimeoutSeconds;

	public GraalPolicyInvocation(String source, JsonElement input) {
		this(source, input, false);
	}

	/** Same bounded guest engine, but with only a detached snapshot and no effect API. */
	public static CompletableFuture<JsonElement> query(String source, JsonElement world, JsonElement input) {
		encode(input);
		var snapshot = new com.google.gson.JsonObject();
		snapshot.add("world", world);
		snapshot.add("input", input);
		var invocation = new GraalPolicyInvocation(source, snapshot, true);
		return invocation.resume(JsonNull.INSTANCE).thenApply(step -> step.getAsJsonObject().get("value"))
			.whenComplete((result, error) -> invocation.close());
	}

	private GraalPolicyInvocation(String source, JsonElement input, boolean query) {
		executionTimeoutSeconds = query ? 3 : 1;
		if (source == null || source.isBlank() || source.length() > MAX_SOURCE_CHARS) throw new IllegalArgumentException("policy_source_limit");
		String encoded = query ? input.toString() : encode(input);
		if (encoded.length() > (query ? MAX_QUERY_SNAPSHOT_CHARS : MAX_VALUE_CHARS)) throw new IllegalArgumentException("query_snapshot_limit");
		ready = submit(() -> {
			context = Context.newBuilder("js").option("engine.WarnInterpreterOnly", "false")
				.allowHostAccess(HostAccess.NONE).allowHostClassLookup(name -> false).allowHostClassLoading(false)
				.allowIO(IOAccess.NONE).allowCreateThread(false).allowCreateProcess(false).allowNativeAccess(false)
				.allowEnvironmentAccess(EnvironmentAccess.NONE).allowPolyglotAccess(PolyglotAccess.NONE)
				.in(java.io.InputStream.nullInputStream()).out(java.io.OutputStream.nullOutputStream()).err(java.io.OutputStream.nullOutputStream())
				.resourceLimits(ResourceLimits.newBuilder().statementLimit(200_000, ignored -> true).build()).build();
			if (closed.get()) { context.close(true); throw new IllegalStateException("policy_cancelled"); }
			guest = context.eval("js", query ? QUERY_KERNEL : KERNEL);
			Value factory = context.eval("js", "(function() { 'use strict';\n" + source + "\n; return " + (query ? "query" : "main") + "; })");
			guest.invokeMember("initialize", factory, encoded, new com.google.gson.Gson().toJson(PolicyTools.methods()));
			return null;
		}, 10);
	}

	public CompletableFuture<JsonElement> resume(JsonElement result) {
		String encoded = encode(result);
		if (!resuming.compareAndSet(false, true)) return CompletableFuture.failedFuture(new IllegalStateException("policy_busy"));
		return ready.thenCompose(ignored -> submit(() -> {
			context.resetLimits();
			String json = guest.invokeMember("resume", encoded).asString();
			if (json.length() > MAX_VALUE_CHARS) throw new IllegalArgumentException("policy_value_limit");
			return JsonParser.parseString(json);
		}, executionTimeoutSeconds)).whenComplete((value, error) -> resuming.set(false));
	}

	private <T> CompletableFuture<T> submit(Supplier<T> action, int timeoutSeconds) {
		if (closed.get()) return CompletableFuture.failedFuture(new IllegalStateException("policy_cancelled"));
		return CompletableFuture.supplyAsync(() -> {
			if (closed.get()) throw new IllegalStateException("policy_cancelled");
			return action.get();
		}, worker).orTimeout(timeoutSeconds, TimeUnit.SECONDS).whenComplete((value, error) -> {
			if (error != null) close();
		});
	}

	private static String encode(JsonElement value) {
		String json = (value == null ? JsonNull.INSTANCE : value).toString();
		if (json.length() > MAX_VALUE_CHARS) throw new IllegalArgumentException("policy_value_limit");
		return json;
	}

	@Override public void close() {
		if (!closed.compareAndSet(false, true)) return;
		worker.shutdownNow();
		Context active = context;
		// Cancelling a guest must never wait on the Minecraft tick thread.
		if (active != null) CompletableFuture.runAsync(() -> active.close(true));
	}
}
