package ai.moeru.airicraft.sim;

import ai.moeru.airicraft.sim.arena.Arena;
import ai.moeru.airicraft.sim.episode.Episode;
import ai.moeru.airicraft.sim.fake.FakePlayerEntity;
import ai.moeru.airicraft.sim.input.ActionProfile;
import ai.moeru.airicraft.sim.observe.ObservationSnapshot;
import ai.moeru.airicraft.sim.policy.CombatPolicy;
import ai.moeru.airicraft.sim.policy.Policies;
import ai.moeru.airicraft.sim.spawn.SpawnService;
import ai.moeru.airicraft.sim.tick.SimTickGate;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Localhost HTTP control surface for external orchestration. */
public final class SimHttpControl {
	private static HttpServer server;

	private SimHttpControl() {}

	public static int port() {
		return Integer.getInteger("airicraft.sim.port", 8777);
	}

	public static void start(MinecraftServer mc) {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", port()), 0);
		} catch (IOException e) {
			AiricraftSimServer.LOGGER.error("sim control bind failed", e);
			return;
		}
		server.createContext("/v1/status", ex -> respond(ex, () -> SimRuntime.get().statusJson()));
		server.createContext("/v1/observe", ex -> respond(ex, () -> {
			String arenaName = query(ex, "arena");
			Arena arena = SimRuntime.get().arena(arenaName);
			return run(mc, () -> ObservationSnapshot.build(arena.world(), arena.players().get(0),
					java.util.Set.of(), num(ex, "radius", 24.0), SimRuntime.get().serverTick()));
		}));
		server.createContext("/v1/arena", ex -> respond(ex, () -> {
			JsonObject body = body(ex);
			return run(mc, () -> createArena(mc, body));
		}));
		server.createContext("/v1/player", ex -> respond(ex, () -> {
			JsonObject body = body(ex);
			return run(mc, () -> spawnPlayer(mc, body));
		}));
		server.createContext("/v1/spawn", ex -> respond(ex, () -> {
			JsonObject body = body(ex);
			return run(mc, () -> spawnMob(body));
		}));
		server.createContext("/v1/episode", ex -> {
			if ("GET".equals(ex.getRequestMethod())) {
				respond(ex, () -> episodeJson(SimRuntime.get().episode(query(ex, "id"))));
			} else {
				respond(ex, () -> {
					JsonObject body = body(ex);
					return run(mc, () -> startEpisode(body));
				});
			}
		});
		server.createContext("/v1/episode/stop", ex -> respond(ex, () -> {
			String id = query(ex, "id");
			return run(mc, () -> {
				Episode e = SimRuntime.get().episode(id);
				if (e != null) {
					e.stop();
				}
				return episodeJson(e);
			});
		}));
		server.createContext("/v1/equip", ex -> respond(ex, () -> {
			JsonObject body = body(ex);
			return run(mc, () -> equipPlayer(body));
		}));
		server.createContext("/v1/reset", ex -> respond(ex, () -> {
			JsonObject body = body(ex);
			return run(mc, () -> {
				Arena arena = SimRuntime.get().arena(body.get("arena").getAsString());
				SimRuntime.clearDamageSources();
				arena.reset();
				JsonObject o = new JsonObject();
				o.addProperty("arena", arena.name());
				o.addProperty("resets", arena.resetCount());
				return o;
			});
		}));
		server.createContext("/v1/tick", ex -> respond(ex, () -> {
			JsonObject body = body(ex);
			return tick(body, mc);
		}));
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	public static void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	// ---- handlers ----

	private static JsonObject createArena(MinecraftServer mc, JsonObject body) {
		String name = body.get("name").getAsString();
		ServerWorld world = world(mc, body.has("world") ? body.get("world").getAsString() : "minecraft:overworld");
		JsonArray center = body.getAsJsonArray("center");
		Vec3d c = new Vec3d(center.get(0).getAsDouble(), center.get(1).getAsDouble(), center.get(2).getAsDouble());
		int size = body.has("size") ? body.get("size").getAsInt() : 33;
		float yaw = body.has("yaw") ? body.get("yaw").getAsFloat() : 0;
		Arena arena = Arena.createFlat(name, world, c, size, yaw);
		arena.reset(); // clear whatever was inside
		SimRuntime.get().registerArena(arena);
		JsonObject o = new JsonObject();
		o.addProperty("arena", name);
		return o;
	}

	private static JsonObject spawnPlayer(MinecraftServer mc, JsonObject body) {
		Arena arena = SimRuntime.get().arena(body.get("arena").getAsString());
		String name = body.has("name") ? body.get("name").getAsString() : "Bot";
		Vec3d pos = arena.playerSpawn();
		if (body.has("pos")) {
			JsonArray a = body.getAsJsonArray("pos");
			pos = new Vec3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
		}
		float yaw = body.has("yaw") ? body.get("yaw").getAsFloat() : arena.playerYaw();
		FakePlayerEntity player = FakePlayerEntity.spawn(mc, arena.world(), name, pos, yaw);
		player.setSimExecutor(new ai.moeru.airicraft.sim.input.SimInputExecutor(ActionProfile.defaults()));
		arena.addPlayer(player);
		JsonObject o = new JsonObject();
		o.addProperty("player", name);
		o.addProperty("uuid", player.getUuidAsString());
		return o;
	}

	private static JsonObject spawnMob(JsonObject body) {
		Arena arena = SimRuntime.get().arena(body.get("arena").getAsString());
		String type = body.get("type").getAsString();
		boolean target = !body.has("targetPlayer") || body.get("targetPlayer").getAsBoolean();
		Double minDist = body.has("minDist") ? body.get("minDist").getAsDouble() : null;
		int count = body.has("count") ? body.get("count").getAsInt() : 1;
		JsonArray spawned = new JsonArray();
		net.minecraft.util.math.random.Random random = net.minecraft.util.math.random.Random.create();
		for (int i = 0; i < count; i++) {
			Vec3d pos;
			if (body.has("pos")) {
				JsonArray a = body.getAsJsonArray("pos");
				pos = new Vec3d(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
			} else {
				pos = SpawnService.pickSpawnPos(arena,
						ai.moeru.airicraft.sim.spawn.SpawnRules.effectiveMinDistance(minDist), random);
				if (pos == null) {
					throw new IllegalStateException("no valid spawn position found in arena");
				}
			}
			net.minecraft.entity.Entity e = SpawnService.spawn(arena, type, pos, target, minDist);
			spawned.add(e.getUuidAsString());
		}
		JsonObject o = new JsonObject();
		o.add("spawned", spawned);
		o.addProperty("aliveMobs", arena.aliveTrackedMobs());
		return o;
	}

	private static JsonObject equipPlayer(JsonObject body) {
		Arena arena = SimRuntime.get().arena(body.get("arena").getAsString());
		if (arena.players().isEmpty()) {
			throw new IllegalStateException("arena has no fake player");
		}
		FakePlayerEntity player = arena.players().get(0);
		int equipped = 0;
		for (com.google.gson.JsonElement el : body.getAsJsonArray("items")) {
			JsonObject it = el.getAsJsonObject();
			net.minecraft.item.Item item = net.minecraft.registry.Registries.ITEM
					.get(Identifier.of(it.get("id").getAsString()));
			net.minecraft.item.ItemStack stack = new net.minecraft.item.ItemStack(item,
					it.has("count") ? it.get("count").getAsInt() : 1);
			String slot = it.get("slot").getAsString();
			switch (slot) {
				case "main" -> player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, stack);
				case "off" -> player.setStackInHand(net.minecraft.util.Hand.OFF_HAND, stack);
				case "head" -> player.equipStack(net.minecraft.entity.EquipmentSlot.HEAD, stack);
				case "chest" -> player.equipStack(net.minecraft.entity.EquipmentSlot.CHEST, stack);
				case "legs" -> player.equipStack(net.minecraft.entity.EquipmentSlot.LEGS, stack);
				case "feet" -> player.equipStack(net.minecraft.entity.EquipmentSlot.FEET, stack);
				default -> player.getInventory().offerOrDrop(stack);
			}
			equipped++;
		}
		JsonObject o = new JsonObject();
		o.addProperty("equipped", equipped);
		return o;
	}

	private static JsonObject startEpisode(JsonObject body) throws IOException {
		Arena arena = SimRuntime.get().arena(body.get("arena").getAsString());
		if (arena.players().isEmpty()) {
			throw new IllegalStateException("arena has no fake player");
		}
		CombatPolicy policy = Policies.create(body.get("policy").getAsString());
		if (body.has("params") && body.get("params").isJsonObject()) {
			policy.configure(body.getAsJsonObject("params"));
		}
		int maxTicks = body.has("maxTicks") ? body.get("maxTicks").getAsInt() : 1200;
		double obsRadius = body.has("obsRadius") ? body.get("obsRadius").getAsDouble() : 24.0;
		ActionProfile profile = ActionProfile.defaults();
		Episode episode = SimRuntime.get().startEpisode(arena, policy, maxTicks, obsRadius, profile);
		return episodeJson(episode);
	}

	private static JsonObject episodeJson(Episode e) {
		JsonObject o = new JsonObject();
		if (e == null) {
			o.addProperty("error", "unknown episode");
			return o;
		}
		o.addProperty("id", e.id());
		o.addProperty("state", e.state().name());
		if (e.outcome() != null) {
			o.addProperty("outcome", e.outcome().name());
		}
		o.addProperty("tick", e.tick());
		o.addProperty("log", e.logPath().toString());
		if (e.state() != Episode.State.RUNNING) {
			o.add("score", e.scoreJson());
		}
		return o;
	}

	private static JsonObject tick(JsonObject body, MinecraftServer mc) {
		String mode = body.get("mode").getAsString();
		JsonObject o = new JsonObject();
		switch (mode) {
			case "freeze" -> SimTickGate.freeze();
			case "run" -> SimTickGate.run();
			case "sprint" -> {
				int ticks = body.has("ticks") ? body.get("ticks").getAsInt() : 20;
				try {
					o.addProperty("sprinted", SimTickGate.sprint(mc, ticks).get(60, TimeUnit.SECONDS));
				} catch (Exception e) {
					throw new IllegalStateException("sprint failed: " + e.getMessage(), e);
				}
			}
			default -> throw new IllegalArgumentException("unknown tick mode: " + mode);
		}
		o.addProperty("mode", SimTickGate.mode().name());
		return o;
	}

	// ---- helpers ----

	private static ServerWorld world(MinecraftServer mc, String key) {
		return mc.getWorld(net.minecraft.registry.RegistryKey.of(
				net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(key)));
	}

	private static <T> T run(MinecraftServer mc, ThrowingSupplier<T> fn) {
		CompletableFuture<T> f = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				f.complete(fn.get());
			} catch (Throwable t) {
				f.completeExceptionally(t);
			}
		});
		try {
			return f.get(30, TimeUnit.SECONDS);
		} catch (Exception e) {
			Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
			java.io.StringWriter sw = new java.io.StringWriter();
			cause.printStackTrace(new java.io.PrintWriter(sw));
			String trace = sw.toString();
			throw new IllegalStateException("server action failed: "
					+ (trace.length() > 1200 ? trace.substring(0, 1200) : trace), e);
		}
	}

	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}

	private static JsonObject body(com.sun.net.httpserver.HttpExchange ex) throws IOException {
		try (InputStream in = ex.getRequestBody()) {
			return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static String query(com.sun.net.httpserver.HttpExchange ex, String key) {
		String q = ex.getRequestURI().getRawQuery();
		if (q == null) {
			return null;
		}
		for (String part : q.split("&")) {
			int eq = part.indexOf('=');
			if (eq > 0 && part.substring(0, eq).equals(key)) {
				return part.substring(eq + 1);
			}
		}
		return null;
	}

	private static double num(com.sun.net.httpserver.HttpExchange ex, String key, double dflt) {
		String v = query(ex, key);
		return v == null ? dflt : Double.parseDouble(v);
	}

	private interface Responder {
		JsonObject get() throws Exception;
	}

	private static void respond(com.sun.net.httpserver.HttpExchange ex, Responder r) {
		try {
			JsonObject out = r.get();
			byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().set("Content-Type", "application/json");
			ex.sendResponseHeaders(200, bytes.length);
			try (OutputStream os = ex.getResponseBody()) {
				os.write(bytes);
			}
		} catch (Throwable t) {
			try {
				JsonObject err = new JsonObject();
				err.addProperty("error", t.getMessage() == null ? t.toString() : t.getMessage());
				byte[] bytes = err.toString().getBytes(StandardCharsets.UTF_8);
				ex.sendResponseHeaders(500, bytes.length);
				try (OutputStream os = ex.getResponseBody()) {
					os.write(bytes);
				}
			} catch (IOException ignored) {
			}
		} finally {
			ex.close();
		}
	}
}
