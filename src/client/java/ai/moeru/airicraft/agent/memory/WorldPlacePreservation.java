package ai.moeru.airicraft.agent.memory;

import ai.moeru.airicraft.Airicraft;
import baritone.api.BaritoneAPI;
import baritone.api.utils.input.Input;
import net.minecraft.client.MinecraftClient;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.util.List;

/** Client-thread publication, immutable reads on Baritone's path calculation thread. */
public final class WorldPlacePreservation {
	private static volatile Snapshot current = new Snapshot(null, List.of(), false);
	private static long revision = -1;
	private static int ticksUntilRefresh;
	private static long lastFailureLog;

	private WorldPlacePreservation() {}

	public static void tick(MinecraftClient client) {
		if (current.world() != client.world || revision != LocationMemoryBridge.revision() || --ticksUntilRefresh <= 0) reload(client);
	}

	public static void clear() {
		current = new Snapshot(null, List.of(), false);
	}

	public static void reload(MinecraftClient client) {
		ticksUntilRefresh = 20;
		revision = LocationMemoryBridge.revision();
		if (client.world == null || (client.getServer() == null && !FabricLoader.getInstance().isModLoaded("journeymap"))) {
			current = new Snapshot(client.world, List.of(), false);
			return;
		}
		try {
			var places = LocationMemoryBridge.forClient(client).list();
			String dimension = client.world.getRegistryKey().getValue().toString();
			current = new Snapshot(client.world, places.stream().filter(place -> place.dimension().equals(dimension))
				.map(LocationMemoryProvider.Location::preserveArea).filter(java.util.Objects::nonNull).toList(), false);
		}
		catch (IOException | RuntimeException exception) {
			// A broken memory file must not silently turn a built home into available resources.
			current = failedSnapshot(current, client.world);
			long now = System.currentTimeMillis();
			if (now - lastFailureLog >= 10_000L) {
				lastFailureLog = now;
				Airicraft.LOGGER.error("Cannot load location protection; automatic terrain edits remain restricted", exception);
			}
		}
	}

	static Snapshot failedSnapshot(Snapshot previous, Object world) {
		return new Snapshot(world, previous.world() == world ? previous.areas() : List.of(), true);
	}

	public static java.util.Map<String, Object> debugSnapshot() {
		Snapshot snapshot = current;
		return java.util.Map.of("areas", snapshot.areas(), "unavailable", snapshot.unavailable());
	}

	/** Preserved areas in this world, or null when protection data is unavailable and no edit is safe. */
	public static List<PlaceMemory.PreservedArea> areas(Object world) {
		Snapshot snapshot = current;
		if (snapshot.world() != world) return List.of();
		return snapshot.unavailable() ? null : snapshot.areas();
	}

	public static boolean contains(Object world, int x, int y, int z) {
		return current.contains(world, x, y, z);
	}

	public static boolean contains(Object world, BlockPos pos) {
		return contains(world, pos.getX(), pos.getY(), pos.getZ());
	}

	/** Also guards an already-calculated path when a preserved area is added while it runs. */
	public static boolean blocksPathBreaking(BlockPos pos) {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.world != null && contains(client.world, pos)
			&& BaritoneAPI.getProvider().getPrimaryBaritone().getInputOverrideHandler().isInputForcedDown(Input.CLICK_LEFT);
	}

	record Snapshot(Object world, List<PlaceMemory.PreservedArea> areas, boolean unavailable) {
		Snapshot { areas = List.copyOf(areas); }

		static Snapshot from(Object world, String dimension, List<PlaceMemory.Place> places) {
			return new Snapshot(world, places.stream().filter(place -> place.dimension().equals(dimension))
				.map(PlaceMemory.Place::preserveArea).filter(java.util.Objects::nonNull).toList(), false);
		}

		boolean contains(Object world, int x, int y, int z) {
			if (this.world == null || this.world != world) return false;
			if (unavailable) return true;
			for (PlaceMemory.PreservedArea area : areas) if (area.contains(x, y, z)) return true;
			return false;
		}
	}
}
