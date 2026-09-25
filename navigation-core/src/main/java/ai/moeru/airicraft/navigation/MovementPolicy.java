package ai.moeru.airicraft.navigation;

import java.util.List;

/**
 * What one navigation request may do and what it costs. Immutable and passed with each request;
 * callers derive variants instead of changing shared settings.
 *
 * @param allowBreak      whether moves may break blocks (tunnel)
 * @param allowPlace      whether moves may place blocks (bridge, pillar); also needs placeable blocks
 * @param allowSprint     whether flat moves are costed and driven at sprint speed
 * @param allowDoors      whether moves may open or close doors, fence gates and trapdoors
 * @param maxSafeFall     the highest fall, in blocks, onto solid ground
 * @param maxWaterFall    the highest fall, in blocks, into water
 * @param placeableBlocks throwaway blocks available for placement
 * @param waterPenalty    extra cost per move that ends in water
 * @param breakPenalty    extra cost per broken block, on top of its break time
 * @param placePenalty    cost per placed block
 * @param jumpPenalty     extra cost per jump
 * @param travelBounds    cells every move envelope and edit must stay inside; null for none
 * @param protectedAreas  cells that must never be broken or placed into
 * @param avoidances      cost multipliers around points
 */
public record MovementPolicy(
	boolean allowBreak,
	boolean allowPlace,
	boolean allowSprint,
	boolean allowDoors,
	int maxSafeFall,
	int maxWaterFall,
	int placeableBlocks,
	double waterPenalty,
	double breakPenalty,
	double placePenalty,
	double jumpPenalty,
	Box travelBounds,
	List<Box> protectedAreas,
	List<Avoidance> avoidances
) {
	public MovementPolicy {
		if (maxSafeFall < 0 || maxWaterFall < 0 || placeableBlocks < 0) throw new IllegalArgumentException("negative policy limit");
		protectedAreas = List.copyOf(protectedAreas);
		avoidances = List.copyOf(avoidances);
	}

	/** Matches the Baritone profile Airicraft applied before the in-house backend, minus parkour. */
	public static MovementPolicy defaults() {
		return new MovementPolicy(true, true, true, true, 3, 64, 0, 3.0, 2.0, 20.0, 2.0, null, List.of(), List.of());
	}

	/** Walking only: no breaking, placing or doors. Used where the executor cannot edit terrain. */
	public MovementPolicy noEdits() {
		return new MovementPolicy(false, false, allowSprint, false, maxSafeFall, maxWaterFall, 0,
			waterPenalty, breakPenalty, placePenalty, jumpPenalty, travelBounds, protectedAreas, avoidances);
	}

	public MovementPolicy withBreaking(boolean allow) {
		return new MovementPolicy(allow, allowPlace, allowSprint, allowDoors, maxSafeFall, maxWaterFall, placeableBlocks,
			waterPenalty, breakPenalty, placePenalty, jumpPenalty, travelBounds, protectedAreas, avoidances);
	}

	public MovementPolicy withPlaceableBlocks(int count) {
		return new MovementPolicy(allowBreak, allowPlace, allowSprint, allowDoors, maxSafeFall, maxWaterFall, Math.max(0, count),
			waterPenalty, breakPenalty, placePenalty, jumpPenalty, travelBounds, protectedAreas, avoidances);
	}

	public MovementPolicy withSprint(boolean allow) {
		return new MovementPolicy(allowBreak, allowPlace, allow, allowDoors, maxSafeFall, maxWaterFall, placeableBlocks,
			waterPenalty, breakPenalty, placePenalty, jumpPenalty, travelBounds, protectedAreas, avoidances);
	}

	public MovementPolicy withWaterPenalty(double penalty) {
		return new MovementPolicy(allowBreak, allowPlace, allowSprint, allowDoors, maxSafeFall, maxWaterFall, placeableBlocks,
			penalty, breakPenalty, placePenalty, jumpPenalty, travelBounds, protectedAreas, avoidances);
	}

	public MovementPolicy withTravelBounds(Box bounds) {
		return new MovementPolicy(allowBreak, allowPlace, allowSprint, allowDoors, maxSafeFall, maxWaterFall, placeableBlocks,
			waterPenalty, breakPenalty, placePenalty, jumpPenalty, bounds, protectedAreas, avoidances);
	}

	public MovementPolicy withProtectedAreas(List<Box> areas) {
		return new MovementPolicy(allowBreak, allowPlace, allowSprint, allowDoors, maxSafeFall, maxWaterFall, placeableBlocks,
			waterPenalty, breakPenalty, placePenalty, jumpPenalty, travelBounds, areas, avoidances);
	}

	public MovementPolicy withAvoidances(List<Avoidance> sources) {
		return new MovementPolicy(allowBreak, allowPlace, allowSprint, allowDoors, maxSafeFall, maxWaterFall, placeableBlocks,
			waterPenalty, breakPenalty, placePenalty, jumpPenalty, travelBounds, protectedAreas, sources);
	}

	public boolean canPlace() {
		return allowPlace && placeableBlocks > 0;
	}

	public boolean permitsMovement(int x, int y, int z, int tx, int ty, int tz, boolean jumping) {
		return travelBounds == null || travelBounds.permitsMovement(x, y, z, tx, ty, tz, jumping);
	}

	/** Whether a cell may be broken or placed into: inside the travel bounds and outside every protected area. */
	public boolean permitsEdit(int x, int y, int z) {
		if (travelBounds != null && !travelBounds.contains(x, y, z)) return false;
		for (Box area : protectedAreas) if (area.contains(x, y, z)) return false;
		return true;
	}

	public double avoidanceFactor(int x, int y, int z) {
		double factor = 1.0;
		for (Avoidance avoidance : avoidances) factor *= avoidance.factor(x, y, z);
		return factor;
	}
}
