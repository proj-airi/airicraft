package ai.moeru.airicraft.sim;

import ai.moeru.airicraft.sim.arena.Arena;
import ai.moeru.airicraft.sim.episode.Episode;
import ai.moeru.airicraft.sim.input.ActionProfile;
import ai.moeru.airicraft.sim.input.SimInputExecutor;
import ai.moeru.airicraft.sim.policy.CombatPolicy;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.MinecraftServer;

/**
 * Per-server sim runtime: arenas, running episodes, tick hooks driven by the
 * tick-gate mixin. All methods are called on the server thread (or the sprint
 * worker, which runs ticks exclusively while active).
 */
public final class SimRuntime {
	private static volatile SimRuntime instance;

	private final MinecraftServer server;
	private final Map<String, Arena> arenas = new ConcurrentHashMap<>();
	private final Map<String, Episode> episodes = new ConcurrentHashMap<>();
	/** Last damage source seen per entity (all living entities, keyed by uuid). */
	private static final Map<UUID, DamageSource> LAST_DAMAGE = new ConcurrentHashMap<>();
	private final Path episodeLogDir;
	private long serverTick;
	private int episodeSeq;

	private SimRuntime(MinecraftServer server) {
		this.server = server;
		this.episodeLogDir = server.getRunDirectory().resolve("sim").resolve("episodes");
	}

	public static void attach(MinecraftServer server) {
		instance = new SimRuntime(server);
	}

	public static void detach() {
		SimRuntime rt = instance;
		if (rt != null) {
			for (Episode e : rt.episodes.values()) {
				e.stop();
			}
		}
		instance = null;
	}

	public static SimRuntime get() {
		return instance;
	}

	public static void beforeTick(MinecraftServer server) {
		SimRuntime rt = instance;
		if (rt == null || rt.server != server) {
			return;
		}
		for (Episode episode : rt.episodes.values()) {
			episode.preTick();
		}
	}

	public static void afterTick(MinecraftServer server) {
		SimRuntime rt = instance;
		if (rt == null || rt.server != server) {
			return;
		}
		rt.serverTick++;
		rt.episodes.values().removeIf(e -> e.state() != Episode.State.RUNNING);
		for (Episode episode : rt.episodes.values()) {
			episode.postTick();
		}
	}

	public static void onEntityDeath(LivingEntity entity, DamageSource source) {
		LAST_DAMAGE.remove(entity.getUuid());
		SimRuntime rt = instance;
		if (rt == null) {
			return;
		}
		for (Episode episode : rt.episodes.values()) {
			episode.onEntityDeath(entity, source);
		}
	}

	public static void recordDamageSource(LivingEntity entity, DamageSource source) {
		LAST_DAMAGE.put(entity.getUuid(), source);
	}

	public static DamageSource lastDamageSource(UUID entityId) {
		return LAST_DAMAGE.get(entityId);
	}

	/** Drops attribution state — called on arena resets between batches. */
	public static void clearDamageSources() {
		LAST_DAMAGE.clear();
	}

	/**
	 * Whether a mob's health loss counts toward the player's score. Credited:
	 * damage caused by the player, mob-vs-mob friendly fire, and falls (kiting
	 * mobs into each other / off ledges is part of positioning strategy).
	 * Explosions, entity cramming and suffocation are excluded.
	 */
	public static boolean isPlayerCredit(DamageSource source, LivingEntity player) {
		if (source == null) {
			return false;
		}
		String name = source.getName();
		if (name.startsWith("explosion") || name.equals("cramming") || name.equals("inWall")) {
			return false;
		}
		Entity attacker = source.getAttacker();
		if (attacker != null) {
			return true;
		}
		return name.equals("fall");
	}

	public MinecraftServer server() {
		return server;
	}

	public long serverTick() {
		return serverTick;
	}

	public Collection<Arena> arenas() {
		return arenas.values();
	}

	public Arena arena(String name) {
		Arena a = arenas.get(name);
		if (a == null) {
			throw new IllegalArgumentException("unknown arena: " + name);
		}
		return a;
	}

	public void registerArena(Arena arena) {
		arenas.put(arena.name(), arena);
	}

	public void removeArena(String name) {
		arenas.remove(name);
	}

	public Collection<Episode> episodes() {
		return episodes.values();
	}

	public Episode episode(String id) {
		return episodes.get(id);
	}

	public Episode startEpisode(Arena arena, CombatPolicy policy, int maxTicks, double obsRadius,
			ActionProfile profile) throws IOException {
		String id = "ep-" + (++episodeSeq) + "-" + UUID.randomUUID().toString().substring(0, 8);
		Episode episode = new Episode(id, arena, policy, maxTicks, obsRadius, profile, episodeLogDir);
		for (var player : arena.players()) {
			player.setSimExecutor(new SimInputExecutor(profile));
		}
		policy.reset();
		episodes.put(id, episode);
		return episode;
	}

	public JsonObject statusJson() {
		JsonObject o = new JsonObject();
		o.addProperty("serverTick", serverTick);
		o.addProperty("gate", ai.moeru.airicraft.sim.tick.SimTickGate.mode().name());
		JsonArray arr = new JsonArray();
		for (Arena a : arenas.values()) {
			JsonObject ja = new JsonObject();
			ja.addProperty("name", a.name());
			ja.addProperty("players", a.players().size());
			ja.addProperty("mobs", a.aliveTrackedMobs());
			ja.addProperty("resets", a.resetCount());
			arr.add(ja);
		}
		o.add("arenas", arr);
		JsonArray eps = new JsonArray();
		for (Episode e : episodes.values()) {
			JsonObject je = new JsonObject();
			je.addProperty("id", e.id());
			je.addProperty("state", e.state().name());
			je.addProperty("tick", e.tick());
			eps.add(je);
		}
		o.add("episodes", eps);
		return o;
	}
}
