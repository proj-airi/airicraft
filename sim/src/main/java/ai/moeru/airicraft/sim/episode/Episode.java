package ai.moeru.airicraft.sim.episode;

import ai.moeru.airicraft.sim.arena.Arena;
import ai.moeru.airicraft.sim.fake.FakePlayerEntity;
import ai.moeru.airicraft.sim.input.ActionProfile;
import ai.moeru.airicraft.sim.input.Intent;
import ai.moeru.airicraft.sim.input.SimInputExecutor;
import ai.moeru.airicraft.sim.observe.ObservationSnapshot;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import ai.moeru.airicraft.sim.policy.CombatPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;

/**
 * One combat episode: obs → (delayed) policy decision → intent → vanilla tick →
 * event collection, recorded to JSONL. Terminates on player death, all tracked
 * mobs dead, or timeout.
 */
public final class Episode {
	public enum State { RUNNING, DONE, FAILED }
	public enum Outcome { PLAYER_DIED, ALL_MOBS_CLEARED, TIMEOUT, STOPPED, ERROR }

	private final String id;
	private final Arena arena;
	private final CombatPolicy policy;
	private final int maxTicks;
	private final double obsRadius;
	private final ActionProfile profile;
	private final Path logPath;

	private State state = State.RUNNING;
	private Outcome outcome;
	private long tick;
	private JsonObject lastObs;
	private final Deque<JsonObject> obsHistory = new ArrayDeque<>();
	private int kills;
	private double damageTaken;
	private double damageDealt;
	private float lastPlayerHealth = -1;
	private final Map<UUID, Float> lastMobHealth = new HashMap<>();
	private final JsonObject score = new JsonObject();

	private BufferedWriter writer;

	public Episode(String id, Arena arena, CombatPolicy policy, int maxTicks, double obsRadius,
			ActionProfile profile, Path logDir) throws IOException {
		this.id = id;
		this.arena = arena;
		this.policy = policy;
		this.maxTicks = maxTicks;
		this.obsRadius = obsRadius;
		this.profile = profile;
		this.logPath = logDir.resolve("episode-" + id + ".jsonl");
		Files.createDirectories(logDir);
		this.writer = Files.newBufferedWriter(logPath, StandardCharsets.UTF_8);
	}

	public String id() {
		return id;
	}

	public State state() {
		return state;
	}

	public Outcome outcome() {
		return outcome;
	}

	public long tick() {
		return tick;
	}

	public JsonObject lastObservation() {
		return lastObs;
	}

	public Path logPath() {
		return logPath;
	}

	/** Final score JSON (populated when the episode finishes; partial while running). */
	public JsonObject scoreJson() {
		return score;
	}

	/** HEAD-of-server-tick: snapshot obs, run policy on delayed obs, queue intent. */
	public void preTick() {
		if (state != State.RUNNING || arena.players().isEmpty()) {
			return;
		}
		FakePlayerEntity player = arena.players().get(0);
		JsonObject obs = ObservationSnapshot.build(arena.world(), player, mobUuids(), obsRadius, tick);
		lastObs = obs;
		obsHistory.addLast(obs);
		while (obsHistory.size() > profile.obsDelayTicks() + 1) {
			obsHistory.removeFirst();
		}
		JsonObject decisionObs = obsHistory.peekFirst();
		Intent intent;
		try {
			intent = policy.decide(decisionObs);
		} catch (Throwable t) {
			event("policy_error:" + t.getClass().getSimpleName());
			intent = Intent.IDLE;
		}
		player.getSimExecutor().tick(tick);
		player.getSimExecutor().setIntent(intent);
		record(obs, intent);
	}

	/** TAIL-of-server-tick: collect events, check termination. */
	public void postTick() {
		if (state != State.RUNNING || arena.players().isEmpty()) {
			return;
		}
		FakePlayerEntity player = arena.players().get(0);
		float health = player.getHealth();
		if (lastPlayerHealth >= 0 && health < lastPlayerHealth) {
			damageTaken += lastPlayerHealth - health;
		}
		lastPlayerHealth = health;
		for (Entity mob : arena.trackedMobs()) {
			if (mob instanceof LivingEntity living) {
				float hp = living.getHealth();
				Float prev = lastMobHealth.put(living.getUuid(), hp);
				if (prev != null && hp < prev) {
					damageDealt += prev - hp;
				}
			}
		}
		for (String ev : player.getSimExecutor().drainEvents()) {
			event(ev);
		}
		if (player.isDead() || player.isRemoved()) {
			finish(Outcome.PLAYER_DIED);
			return;
		}
		if (arena.aliveTrackedMobs() == 0 && !arena.trackedMobs().isEmpty()) {
			finish(Outcome.ALL_MOBS_CLEARED);
			return;
		}
		if (++tick >= maxTicks) {
			finish(Outcome.TIMEOUT);
		}
	}

	public void onEntityDeath(LivingEntity entity, DamageSource source) {
		if (arena.trackedMobs().contains(entity)) {
			kills++;
			event("kill:" + entity.getType().toString() + " by:" + source.getName());
		}
		if (entity instanceof FakePlayerEntity) {
			event("player_death:" + source.getName());
		}
	}

	public void stop() {
		if (state == State.RUNNING) {
			finish(Outcome.STOPPED);
		}
	}

	private void finish(Outcome o) {
		if (state != State.RUNNING) {
			return;
		}
		outcome = o;
		state = State.DONE;
		if (!arena.players().isEmpty()) {
			FakePlayerEntity player = arena.players().get(0);
			if (player.getSimExecutor() != null) {
				player.getSimExecutor().setIntent(Intent.IDLE);
			}
		}
		score.addProperty("outcome", o.name());
		score.addProperty("ticks", tick);
		score.addProperty("kills", kills);
		score.addProperty("damageTaken", damageTaken);
		score.addProperty("damageDealt", damageDealt);
		score.addProperty("remainingMobs", arena.aliveTrackedMobs());
		try {
			JsonObject end = new JsonObject();
			end.addProperty("type", "end");
			end.add("score", score);
			writer.write(end + "\n");
			writer.close();
		} catch (IOException e) {
			state = State.FAILED;
		}
	}

	private void record(JsonObject obs, Intent intent) {
		try {
			JsonObject line = new JsonObject();
			line.addProperty("type", "tick");
			line.addProperty("t", tick);
			line.add("obs", obs);
			line.add("intent", intentJson(intent));
			writer.write(line + "\n");
		} catch (IOException e) {
			state = State.FAILED;
		}
	}

	private void event(String name) {
		try {
			JsonObject line = new JsonObject();
			line.addProperty("type", "event");
			line.addProperty("t", tick);
			line.addProperty("name", name);
			writer.write(line + "\n");
		} catch (IOException e) {
			state = State.FAILED;
		}
	}

	private java.util.Set<java.util.UUID> mobUuids() {
		java.util.Set<java.util.UUID> uuids = new java.util.HashSet<>();
		for (Entity mob : arena.trackedMobs()) {
			if (mob.isAlive()) {
				uuids.add(mob.getUuid());
			}
		}
		return uuids;
	}

	private static JsonObject intentJson(Intent in) {
		JsonObject o = new JsonObject();
		if (in.lookEntityId() != null) {
			o.addProperty("lookEntity", in.lookEntityId());
		}
		if (in.lookPos() != null) {
			JsonArray a = new JsonArray();
			for (double v : in.lookPos()) {
				a.add(v);
			}
			o.add("lookPos", a);
		}
		if (in.moveDir() != null) {
			JsonArray a = new JsonArray();
			for (double v : in.moveDir()) {
				a.add(v);
			}
			o.add("moveDir", a);
		}
		o.addProperty("attack", in.attack());
		o.addProperty("jump", in.jump());
		o.addProperty("sprint", in.sprint());
		if (in.useHand() != null) {
			o.addProperty("useHand", in.useHand());
		}
		if (in.stopUsing()) {
			o.addProperty("stopUsing", true);
		}
		return o;
	}
}
