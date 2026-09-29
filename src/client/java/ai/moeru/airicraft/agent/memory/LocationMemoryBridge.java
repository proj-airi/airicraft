package ai.moeru.airicraft.agent.memory;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Backend selection is based on installation, never on temporary availability. No store migration. */
public final class LocationMemoryBridge {
	private static volatile LocationMemoryProvider journeyMap;
	private static final AtomicLong REVISION = new AtomicLong();

	private LocationMemoryBridge() {}

	public static void registerJourneyMap(LocationMemoryProvider provider) {
		journeyMap = java.util.Objects.requireNonNull(provider);
		changed();
	}

	public static void changed() { REVISION.incrementAndGet(); }
	public static long revision() { return REVISION.get(); }

	public static LocationMemoryService forClient(Minecraft minecraft) {
		if (minecraft.level == null) throw new IllegalStateException("world_not_loaded");
		return new LocationMemoryService(select(FabricLoader.getInstance().isModLoaded("journeymap"), journeyMap, () -> {
			if (minecraft.getSingleplayerServer() == null) {
				throw new IllegalStateException("world_persistence_unavailable: requires a locally hosted world save without JourneyMap");
			}
			return new PlaceMemory(minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT));
		}));
	}

	static LocationMemoryProvider select(boolean journeyMapInstalled, LocationMemoryProvider external,
		Supplier<LocationMemoryProvider> local) {
		if (!journeyMapInstalled) return local.get();
		if (external == null || !external.available()) {
			throw new IllegalStateException("location_backend_unavailable: JourneyMap is installed but its location backend is not ready");
		}
		return external;
	}
}
