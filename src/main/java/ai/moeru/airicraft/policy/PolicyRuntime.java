package ai.moeru.airicraft.policy;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Client-thread coordinator. A yielded effect resumes only after the host's terminal result. */
public final class PolicyRuntime implements AutoCloseable {
	public static final int MAX_TICKS = 12_000;
	public static final int MAX_EFFECTS = 128;
	public interface Program extends AutoCloseable {
		CompletableFuture<JsonElement> resume(JsonElement result);
		default void tick() { }
		default JsonElement progress() { return JsonNull.INSTANCE; }
		@Override void close();
	}
	public interface Host extends AutoCloseable {
		CompletableFuture<JsonElement> execute(JsonObject effect);
		default void tick() { }
		@Override void close();
	}
	public record Outcome(String state, String reason, JsonElement result, List<JsonObject> effects, int elapsedTicks) { }
	private enum Phase { SCRIPT, EFFECT, FINISHED }
	private final Program invocation;
	private final int maxTicks, maxEffects;
	private final Host host;
	private final Consumer<Outcome> terminal;
	private final List<JsonObject> effects = new ArrayList<>();
	private CompletableFuture<JsonElement> pending;
	private Phase phase = Phase.SCRIPT;
	private int ticks;

	public PolicyRuntime(String source, JsonElement input, Host host, Consumer<Outcome> terminal) {
		this(new GraalPolicyInvocation(source, input), host, terminal, MAX_TICKS, MAX_EFFECTS);
	}

	public PolicyRuntime(Program program, Host host, Consumer<Outcome> terminal, int maxTicks, int maxEffects) {
		if (maxTicks < 1 || maxEffects < 1) throw new IllegalArgumentException("positive_work_limits_required");
		this.host = host;
		this.terminal = terminal;
		this.maxTicks = maxTicks;
		this.maxEffects = maxEffects;
		invocation = program;
		pending = invocation.resume(JsonNull.INSTANCE);
	}

	public JsonObject progress() {
		var value=new JsonObject();value.addProperty("elapsedTicks",ticks);value.addProperty("effects",effects.size());value.add("program",invocation.progress());return value;
	}

	public boolean active() { return phase != Phase.FINISHED; }

	public void tick() {
		if (!active()) return;
		if (++ticks > maxTicks) { finish("FAILED", "policy_tick_limit", JsonNull.INSTANCE); return; }
		try {
			invocation.tick();
			host.tick();
			if (!pending.isDone()) return;
			JsonElement value = pending.join(); // Already complete; never blocks the client thread.
			if (phase == Phase.EFFECT) {
				effects.getLast().add("result", value.deepCopy());
				pending = invocation.resume(value);
				phase = Phase.SCRIPT;
			} else {
				JsonObject step = value.getAsJsonObject();
				if (step.get("done").getAsBoolean()) { finish("SUCCEEDED", "returned", step.get("value")); return; }
				if (effects.size() >= maxEffects) { finish("FAILED", "policy_effect_limit", JsonNull.INSTANCE); return; }
				JsonObject effect = step.getAsJsonObject("value");
				effects.add(effect.deepCopy());
				pending = host.execute(effect);
				phase = Phase.EFFECT;
			}
		} catch (RuntimeException error) {
			Throwable cause = error;
			while (cause.getCause() != null) cause = cause.getCause();
			finish("FAILED", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), JsonNull.INSTANCE);
		}
	}

	public void cancel(String reason) { if (active()) finish("CANCELLED", reason, JsonNull.INSTANCE); }

	private void finish(String state, String reason, JsonElement result) {
		phase = Phase.FINISHED;
		host.close();
		invocation.close();
		terminal.accept(new Outcome(state, reason, result.deepCopy(), List.copyOf(effects), ticks));
	}

	@Override public void close() { cancel("runtime_closed"); }
}
