package ai.moeru.airicraft.portal;

import ai.moeru.airicraft.blueprint.ConstructionEscape;
import ai.moeru.airicraft.policy.PolicyRuntime;
import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Builds a Nether portal frame through the ordinary construction program, then lights it and
 * verifies that portal blocks fill the opening. A frame that does not light reports exactly
 * which cells are wrong instead of succeeding.
 */
public final class NetherPortalProgram implements PolicyRuntime.Program {
	/** Ticks to wait for portal blocks after the igniter was used. */
	static final int VERIFY_TICKS = 40;
	static final int MAX_IGNITIONS = 2;
	/** Walking from the stance into the opening takes well under a second; longer means something is in the way. */
	static final int ENTRY_WALK_TICKS = 120;
	/** Survival players teleport after 80 ticks inside the portal; allow for lag. */
	static final int ENTRY_WAIT_TICKS = 300;
	static final int ARRIVAL_LEAVE_TICKS = 100;

	public enum Entry { MOVING, INSIDE, DIMENSION_CHANGED }

	public interface World extends AutoCloseable {
		/** Block state text at a loaded position, for example {@code minecraft:obsidian}. */
		String state(ConstructionEscape.Position position);
		/** Standing position from which the ignition cell is reachable and outside the opening, or null. */
		ConstructionEscape.Position lightingStance();
		boolean playerInOpening();
		/** Standing position to walk into the opening from, nearest first; defaults to the lighting stance. */
		default ConstructionEscape.Position entryStance() { return lightingStance(); }
		default boolean playerNear(ConstructionEscape.Position position) { return false; }
		/** Where the player is, for failure messages. */
		default String playerPosition() { return "unknown"; }
		/** Walks one tick toward the opening, or holds still once inside. Must not read the old level after a dimension change. */
		default Entry enterStep() { throw new UnsupportedOperationException("portal_entry_unsupported"); }
		/** After a dimension change, walks one tick out of the arrival portal; true only once no portal block touches the player. */
		default boolean leaveArrivalStep() { return true; }
		/** Releases movement taken by {@link #enterStep}. */
		default void stopMoving() { }
		default String dimension() { return ""; }
		@Override void close();
	}

	private enum Phase { BUILD, STANCE, INSPECT, IGNITE, VERIFY, EXIT, APPROACH, ENTER, DONE }

	private final NetherPortalFrame frame;
	private final PolicyRuntime.Program construction;
	private final World world;
	private final String igniterItem;
	private final boolean light, enter;
	private String litOutcome = "lit";
	private String startDimension = "";
	private CompletableFuture<JsonElement> entering;
	private int walkTicks, insideTicks, leaveTicks;
	private boolean arrived;
	private ConstructionEscape.Position entryStance;
	private Phase phase = Phase.BUILD;
	private JsonElement constructionResult = JsonNull.INSTANCE;
	private CompletableFuture<JsonElement> verifying;
	private int verifyTicks, ignitions;
	private String lastIgnitionError = "";
	private boolean closed;

	public NetherPortalProgram(NetherPortalFrame frame, PolicyRuntime.Program construction, World world, String igniterItem, boolean light) {
		this(frame, construction, world, igniterItem, light, false);
	}

	public NetherPortalProgram(NetherPortalFrame frame, PolicyRuntime.Program construction, World world, String igniterItem, boolean light, boolean enter) {
		this.frame = Objects.requireNonNull(frame);
		this.construction = Objects.requireNonNull(construction);
		this.world = Objects.requireNonNull(world);
		this.igniterItem = igniterItem;
		this.light = light;
		this.enter = enter;
		if (enter && !light) throw new IllegalArgumentException("portal_entry_requires_light");
		if (light && (igniterItem == null || igniterItem.isBlank())) throw new IllegalArgumentException("portal_igniter_missing");
	}

	@Override public CompletableFuture<JsonElement> resume(JsonElement result) {
		if (closed) return CompletableFuture.failedFuture(new IllegalStateException("portal_cancelled"));
		try {
			return switch (phase) {
				case BUILD -> {
					if (result.isJsonNull() && lit()) yield afterLit("already_lit");
					yield construction.resume(result).thenCompose(step -> {
						var object = step.getAsJsonObject();
						if (!object.get("done").getAsBoolean()) return CompletableFuture.completedFuture(step);
						constructionResult = object.get("value").deepCopy();
						return afterBuild();
					});
				}
				case STANCE -> CompletableFuture.completedFuture(inspect()); // An unreachable stance still lets use_block pick its own.
				case INSPECT -> CompletableFuture.completedFuture(ignite());
				case IGNITE -> {
					if (!ok(result)) {
						lastIgnitionError = error(result);
						yield ignitions < MAX_IGNITIONS ? CompletableFuture.completedFuture(inspect())
							: CompletableFuture.failedFuture(new IllegalStateException("portal_ignite_failed error=" + lastIgnitionError + diagnosis()));
					}
					phase = Phase.VERIFY;
					verifyTicks = 0;
					verifying = new CompletableFuture<>();
					yield verifying;
				}
				case EXIT -> {
					if (world.playerInOpening()) throw new IllegalStateException("portal_exit_failed: still inside the lit portal player="
						+ world.playerPosition() + " error=" + (ok(result) ? "none" : error(result)));
					yield CompletableFuture.completedFuture(finish(litOutcome));
				}
				case APPROACH -> {
					if (!ok(result) && !world.playerNear(entryStance)) throw new IllegalStateException("portal_entry_stance_unreachable stance=" + key(entryStance)
						+ " player=" + world.playerPosition() + " error=" + error(result));
					yield startEntering();
				}
				case VERIFY, ENTER, DONE -> throw new IllegalStateException("portal_unexpected_result phase=" + phase);
			};
		} catch (RuntimeException error) {
			return CompletableFuture.failedFuture(error);
		}
	}

	@Override public void tick() {
		if (phase == Phase.BUILD) construction.tick();
		if (phase == Phase.ENTER) { tickEntering(); return; }
		if (phase != Phase.VERIFY || verifying == null || verifying.isDone()) return;
		var future = verifying;
		try {
			if (lit()) afterLit("lit").whenComplete((step, error) -> {
				if (error != null) future.completeExceptionally(error);
				else future.complete(step);
			});
			else if (++verifyTicks >= VERIFY_TICKS) {
				if (ignitions < MAX_IGNITIONS) future.complete(inspect());
				else future.completeExceptionally(new IllegalStateException("portal_not_lit" + diagnosis()));
			}
		} catch (RuntimeException error) {
			future.completeExceptionally(error);
		}
	}

	@Override public JsonElement progress() {
		var value = new JsonObject();
		value.addProperty("phase", phase.name().toLowerCase(Locale.ROOT));
		value.addProperty("ignitions", ignitions);
		if (phase == Phase.BUILD) value.add("construction", construction.progress());
		return value;
	}

	@Override public void close() {
		if (closed) return;
		closed = true;
		if (verifying != null && !verifying.isDone()) verifying.completeExceptionally(new IllegalStateException("portal_cancelled"));
		if (entering != null && !entering.isDone()) entering.completeExceptionally(new IllegalStateException("portal_cancelled"));
		try { world.stopMoving(); construction.close(); } finally { world.close(); }
	}

	private CompletableFuture<JsonElement> afterBuild() {
		if (!light) return CompletableFuture.completedFuture(finish("built_unlit"));
		if (lit()) return afterLit("already_lit");
		var stance = world.lightingStance();
		if (stance == null) return CompletableFuture.completedFuture(inspect());
		phase = Phase.STANCE;
		return CompletableFuture.completedFuture(navigate(stance));
	}

	/** A lit portal: step out of it, or walk in when asked to travel. */
	private CompletableFuture<JsonElement> afterLit(String outcome) {
		litOutcome = outcome;
		if (enter) {
			// Already inside (for example just arrived through it): step out and back in on foot, or wait if there is nowhere to step.
			if (world.playerInOpening()) return startEntering();
			entryStance = world.entryStance();
			if (entryStance == null) throw new IllegalStateException("portal_entry_stance_not_found: no standing spot in front of or behind the opening" + diagnosis());
			phase = Phase.APPROACH;
			return CompletableFuture.completedFuture(navigate(entryStance));
		}
		var stance = world.lightingStance();
		if (stance != null && world.playerInOpening()) {
			phase = Phase.EXIT;
			return CompletableFuture.completedFuture(navigate(stance));
		}
		return CompletableFuture.completedFuture(finish(outcome));
	}

	private CompletableFuture<JsonElement> startEntering() {
		phase = Phase.ENTER;
		startDimension = world.dimension();
		walkTicks = 0;
		insideTicks = 0;
		entering = new CompletableFuture<>();
		return entering;
	}

	private void tickEntering() {
		if (entering == null || entering.isDone()) return;
		try {
			if (arrived) {
				// Navigation cannot start inside portal blocks, so the arrival portal is left before reporting.
				boolean out = world.leaveArrivalStep();
				if (out || ++leaveTicks > ARRIVAL_LEAVE_TICKS) {
					world.stopMoving();
					var step = finish("entered");
					step.getAsJsonObject("value").addProperty("leftArrivalPortal", out);
					entering.complete(step);
				}
				return;
			}
			switch (world.enterStep()) {
				case DIMENSION_CHANGED -> { arrived = true; leaveTicks = 0; }
				case INSIDE -> {
					if (++insideTicks > ENTRY_WAIT_TICKS) {
						world.stopMoving();
						entering.completeExceptionally(new IllegalStateException("portal_did_not_teleport insideTicks=" + insideTicks));
					}
				}
				case MOVING -> {
					if (++walkTicks > ENTRY_WALK_TICKS) {
						world.stopMoving();
						entering.completeExceptionally(new IllegalStateException("portal_entry_blocked: could not walk into the opening stance="
							+ key(entryStance) + " player=" + world.playerPosition()));
					}
				}
			}
		} catch (RuntimeException error) {
			world.stopMoving();
			entering.completeExceptionally(error);
		}
	}

	private JsonElement inspect() {
		phase = Phase.INSPECT;
		var cell = frame.ignitionCell();
		var args = new JsonObject();
		args.addProperty("mode", "inspect_area");
		args.addProperty("scope", "box");
		args.addProperty("x1", cell.x()); args.addProperty("x2", cell.x());
		args.addProperty("y1", cell.y() - 1); args.addProperty("y2", cell.y());
		args.addProperty("z1", cell.z()); args.addProperty("z2", cell.z());
		args.addProperty("detail", "blocks");
		args.addProperty("maxResults", 2);
		return effect("inspect_world", args);
	}

	private JsonElement ignite() {
		phase = Phase.IGNITE;
		ignitions++;
		var cell = frame.ignitionCell();
		var args = new JsonObject();
		args.addProperty("itemId", igniterItem);
		args.addProperty("x", cell.x()); args.addProperty("y", cell.y()); args.addProperty("z", cell.z());
		args.addProperty("facePreference", "down");
		var support = new JsonArray();
		support.add(NetherPortalFrame.OBSIDIAN);
		args.add("expectedSupportBlockIds", support);
		return effect("use_block", args);
	}

	private JsonElement navigate(ConstructionEscape.Position stance) {
		var args = new JsonObject();
		args.addProperty("x", stance.x()); args.addProperty("y", stance.y()); args.addProperty("z", stance.z());
		args.addProperty("exactY", true);
		return effect("navigate_to", args);
	}

	private JsonObject finish(String outcome) {
		phase = Phase.DONE;
		var value = new JsonObject();
		value.addProperty("portal", outcome);
		value.add("frame", new Gson().toJsonTree(frame.describe()));
		value.addProperty("ignitions", ignitions);
		if (outcome.equals("entered")) {
			value.addProperty("fromDimension", startDimension);
			value.addProperty("dimension", world.dimension());
		}
		value.add("construction", constructionResult.deepCopy());
		var done = new JsonObject();
		done.addProperty("done", true);
		done.add("value", value);
		return done;
	}

	boolean lit() {
		return frame.positions(NetherPortalFrame.Role.INTERIOR).stream()
			.allMatch(p -> world.state(p).startsWith(NetherPortalFrame.PORTAL));
	}

	/** Cells that keep the frame from lighting, so the planner and logs never claim a broken frame is right. */
	String diagnosis() {
		var wrong = new ArrayList<String>();
		for (var p : frame.positions(NetherPortalFrame.Role.OBSIDIAN)) {
			String state = world.state(p);
			if (!state.startsWith(NetherPortalFrame.OBSIDIAN)) wrong.add(p.x() + "," + p.y() + "," + p.z() + "=" + state + "(needs obsidian)");
		}
		for (var p : frame.positions(NetherPortalFrame.Role.INTERIOR)) {
			String state = world.state(p);
			if (!state.startsWith("minecraft:air") && !state.startsWith("minecraft:cave_air") && !state.startsWith("minecraft:fire")
				&& !state.startsWith(NetherPortalFrame.PORTAL)) wrong.add(p.x() + "," + p.y() + "," + p.z() + "=" + state + "(opening must be empty)");
		}
		String prefix = lastIgnitionError.isEmpty() ? "" : " lastIgnitionError=" + lastIgnitionError;
		return prefix + (wrong.isEmpty() ? " frameCells=all_correct" : " wrongCells=" + String.join(";", wrong));
	}

	private static String key(ConstructionEscape.Position p) { return p == null ? "none" : p.x() + "," + p.y() + "," + p.z(); }

	private static boolean ok(JsonElement result) {
		return result != null && result.isJsonObject() && result.getAsJsonObject().has("ok") && result.getAsJsonObject().get("ok").getAsBoolean();
	}

	private static String error(JsonElement result) {
		if (result != null && result.isJsonObject()) {
			var object = result.getAsJsonObject();
			for (String key : List.of("error", "reason", "message")) if (object.has(key) && object.get(key).isJsonPrimitive()) return object.get(key).getAsString();
			if (object.has("work")) {
				String work = object.get("work").toString();
				return work.length() > 400 ? work.substring(0, 400) + "..." : work;
			}
		}
		return String.valueOf(result);
	}

	private static JsonObject effect(String name, JsonObject args) {
		var call = new JsonObject();
		call.addProperty("name", name);
		call.add("args", args);
		var effect = new JsonObject();
		effect.addProperty("operation", "call_tool");
		effect.add("arguments", call);
		var step = new JsonObject();
		step.addProperty("done", false);
		step.add("value", effect);
		return step;
	}
}
