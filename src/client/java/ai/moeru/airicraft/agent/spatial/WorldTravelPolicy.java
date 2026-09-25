package ai.moeru.airicraft.agent.spatial;

import com.google.gson.Gson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import java.nio.file.*;
import java.io.IOException;
import java.util.Map;

/** Immutable publication to A*. Strategy bounds can change; recorded user bounds can only tighten. */
public final class WorldTravelPolicy {
	private static final Gson GSON = new Gson();
	private record State(Object world, TravelBounds user, TravelBounds strategy, String error) { }
	private record Document(int version, String dimension, TravelBounds user) { }
	private static volatile State state = new State(null, null, null, "");
	private static volatile boolean workActive;
	private static java.util.function.Consumer<Map<String,Object>> changeObserver = ignored -> { };
	private WorldTravelPolicy() { }
	public static void observeChanges(java.util.function.Consumer<Map<String,Object>> observer) {
		changeObserver = java.util.Objects.requireNonNull(observer);
	}
	public static void tick(MinecraftClient client, boolean active) {
		workActive = active;
		if (client == null || client.world == state.world()) return;
		state = new State(client.world, null, null, "");
		if (client.world == null || client.getServer() == null) return;
		try {
			Path path = file(client);
			if (Files.exists(path)) {
				Document document = GSON.fromJson(Files.readString(path), Document.class);
				if (document.version() != 1) throw new IOException("unsupported travel policy version");
				if (document.dimension().equals(client.world.getRegistryKey().getValue().toString())) state = new State(client.world, document.user(), null, "");
			}
		} catch (IOException | RuntimeException e) { state = new State(client.world, null, null, "travel_policy_unavailable"); }
	}
	public static Map<String,Object> snapshot() {
		State s = state;
		var result = new java.util.LinkedHashMap<String,Object>();
		result.put("userBounds", s.user()); result.put("strategyBounds", s.strategy()); result.put("error", s.error());
		result.put("coordinateMeaning", "inclusive occupied cells, including head and edits; search bounds are separate");
		return result;
	}
	public static void configure(MinecraftClient client, String scope, TravelBounds bounds) throws IOException {
		tick(client, workActive);
		if (client.world == null) throw new IllegalStateException("world_not_loaded");
		State s = state;
		if (!s.error().isEmpty()) throw new IllegalStateException(s.error());
		if (scope.equals("strategy")) {
			state = new State(client.world, s.user(), bounds, "");
			changeObserver.accept(Map.of("scope", scope, "policy", snapshot()));
			return;
		}
		if (!scope.equals("user") || bounds == null) throw new IllegalArgumentException("user_bounds_cannot_be_removed_by_planner");
		if (client.getServer() == null) throw new IllegalStateException("durable_user_bounds_require_local_world");
		if (s.user() != null && !s.user().includes(bounds)) throw new IllegalArgumentException("cannot_relax_user_restrictions");
		Path file = file(client); Files.createDirectories(file.getParent());
		Path temp = Files.createTempFile(file.getParent(), "travel-", ".tmp");
		try {
			Files.writeString(temp, GSON.toJson(new Document(1, client.world.getRegistryKey().getValue().toString(), bounds)));
			Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			state = new State(client.world, bounds, s.strategy(), "");
			changeObserver.accept(Map.of("scope", scope, "policy", snapshot()));
		} finally { Files.deleteIfExists(temp); }
	}
	private static Path file(MinecraftClient c) {
		String dimension = c.world.getRegistryKey().getValue().toString().replace(':','_').replace('/','_');
		return c.getServer().getSavePath(WorldSavePath.ROOT).resolve("airicraft/travel-" + dimension + ".json");
	}
	public static boolean allows(Object world, int x, int y, int z) {
		State s = state;
		return s.world() != world || s.error().isEmpty() && (s.user() == null || s.user().contains(x,y,z)) && (s.strategy() == null || s.strategy().contains(x,y,z));
	}
	public static boolean permitsMovement(Object world, int x,int y,int z,int tx,int ty,int tz,boolean jumping) {
		State s = state;
		return s.world() != world || s.error().isEmpty()
			&& (s.user() == null || s.user().permitsMovement(x,y,z,tx,ty,tz,jumping))
			&& (s.strategy() == null || s.strategy().permitsMovement(x,y,z,tx,ty,tz,jumping));
	}
	/**
	 * The bounds a navigation policy must respect in this world: the intersection of user and strategy
	 * bounds, or null for none. {@code closed} means travel is not permitted at all.
	 */
	public record Limit(TravelBounds bounds, boolean closed) { }

	public static Limit limit(Object world) {
		State s = state;
		if (s.world() != world) return new Limit(null, false);
		if (!s.error().isEmpty()) return new Limit(null, true);
		if (s.user() == null || s.strategy() == null) return new Limit(s.user() == null ? s.strategy() : s.user(), false);
		try {
			return new Limit(s.user().intersect(s.strategy()), false);
		}
		catch (IllegalArgumentException disjoint) {
			return new Limit(null, true);
		}
	}
	public static boolean blocksEdit(BlockPos pos) {
		var c = MinecraftClient.getInstance();
		return workActive && c != null && !allows(c.world, pos.getX(),pos.getY(),pos.getZ());
	}
}
