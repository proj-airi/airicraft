package ai.moeru.airicraft.compat.journeymap;

import ai.moeru.airicraft.agent.memory.LocationMemoryProvider;
import ai.moeru.airicraft.agent.memory.PlaceMemory;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Native JourneyMap is the only store, including user waypoints and automatic death markers. */
final class JourneyMapLocationMemoryProvider implements LocationMemoryProvider {
	private static final String MOD_ID = "airicraft";
	private final IClientAPI api;
	private final BooleanSupplier ready;
	private final Function<PlaceMemory.Place, Waypoint> create;

	JourneyMapLocationMemoryProvider(IClientAPI api, BooleanSupplier ready) {
		this(api, ready, place -> WaypointFactory.createClientWaypoint(MOD_ID,
			new BlockPos(place.x(), place.y(), place.z()), place.name(), place.dimension(), true));
	}

	JourneyMapLocationMemoryProvider(IClientAPI api, BooleanSupplier ready, Function<PlaceMemory.Place, Waypoint> create) {
		this.api = api;
		this.ready = ready;
		this.create = create;
	}

	@Override public String id() { return "journeymap"; }
	@Override public boolean available() { return ready.getAsBoolean(); }

	@Override
	public List<Location> listLocations() {
		requireReady();
		return api.getAllWaypoints().stream().map(JourneyMapLocationMemoryProvider::location).toList();
	}

	@Override
	public Location save(String existingId, PlaceMemory.Place place) {
		requireReady();
		Waypoint waypoint = existingId == null ? create.apply(place) : find(existingId);
		if (waypoint == null) throw new IllegalArgumentException("place_not_found: " + existingId);
		String metadata = JourneyMapLocationMetadata.write(waypoint.getCustomData(), place);
		waypoint.setName(place.name());
		waypoint.setPos(place.x(), place.y(), place.z());
		waypoint.setDimensions(List.of(place.dimension()));
		waypoint.setPrimaryDimension(place.dimension());
		waypoint.setPersistent(true);
		waypoint.setCustomData(metadata);
		if (existingId == null) {
			waypoint.setColor(0x33aaff);
			waypoint.setEnabled(true);
		}
		api.addWaypoint(MOD_ID, waypoint);
		return location(waypoint);
	}

	@Override
	public boolean delete(String id) {
		requireReady();
		Waypoint waypoint = find(id);
		if (waypoint == null) return false;
		api.removeWaypoint(MOD_ID, waypoint);
		return true;
	}

	private Waypoint find(String id) {
		// getWaypoint(modId, guid) filters by owner. Native user/death waypoints have a different owner.
		return api.getAllWaypoints().stream().filter(waypoint -> waypoint.getGuid().equals(id)).findFirst().orElse(null);
	}

	private void requireReady() {
		if (!available()) throw new IllegalStateException("location_backend_unavailable: JourneyMap has not loaded this world");
	}

	private static Location location(Waypoint waypoint) {
		var metadata = JourneyMapLocationMetadata.read(waypoint.getCustomData());
		// ClientWaypoint.getX/getZ project into the current dimension; getBlockPos is native storage.
		BlockPos pos = waypoint.getBlockPos();
		return new Location(waypoint.getGuid(), java.util.Objects.requireNonNullElse(waypoint.getName(), ""), waypoint.getPrimaryDimension(),
			pos.getX(), pos.getY(), pos.getZ(), metadata.note(), metadata.preserveArea());
	}
}
