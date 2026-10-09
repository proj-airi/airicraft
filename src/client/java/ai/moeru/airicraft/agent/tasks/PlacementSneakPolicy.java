package ai.moeru.airicraft.agent.tasks;

import java.util.Optional;
import java.util.Set;

/** Conservative value-only exception to the normal sneak-before-placement rule. */
final class PlacementSneakPolicy {
	private static final double EDGE_MARGIN_BLOCKS = 0.05D;
	private static final double FLOOR_HEIGHT_EPSILON = 1.0E-6D;
	private static final Set<String> KNOWN_INERT_SUPPORTS = Set.of(
		"minecraft:stone", "minecraft:cobblestone", "minecraft:deepslate", "minecraft:cobbled_deepslate",
		"minecraft:dirt", "minecraft:grass_block",
		"minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks",
		"minecraft:acacia_planks", "minecraft:dark_oak_planks", "minecraft:mangrove_planks", "minecraft:cherry_planks",
		"minecraft:pale_oak_planks", "minecraft:bamboo_planks", "minecraft:crimson_planks", "minecraft:warped_planks"
	);

	private PlacementSneakPolicy() { }

	static boolean continueNavigationBeforePreparing(boolean navigationStarted, boolean supportVisible) {
		return navigationStarted && !supportVisible;
	}

	static boolean requiresSneak(Conditions conditions) {
		return !knownInertSupport(conditions.supportBlockId())
			|| !conditions.stableStanding()
			|| !conditions.fullySupported()
			|| conditions.velocityX() != 0D
			|| conditions.velocityZ() != 0D;
	}

	private static boolean knownInertSupport(String blockId) {
		// Intentionally narrow: neither a missing menu nor a full collision cube proves
		// non-interactivity (doors/buttons and modded blocks must keep sneaking).
		return blockId != null && KNOWN_INERT_SUPPORTS.contains(blockId);
	}

	/** Every column in this region must have a loaded full collision cube below the feet. */
	static Optional<SupportRegion> supportRegion(double minX, double feetY, double minZ, double maxX, double maxZ) {
		if (!Double.isFinite(minX) || !Double.isFinite(feetY) || !Double.isFinite(minZ)
			|| !Double.isFinite(maxX) || !Double.isFinite(maxZ) || minX >= maxX || minZ >= maxZ
			|| Math.abs(feetY - Math.rint(feetY)) > FLOOR_HEIGHT_EPSILON) {
			return Optional.empty();
		}
		return Optional.of(new SupportRegion(
			(int) Math.floor(minX - EDGE_MARGIN_BLOCKS), (int) Math.rint(feetY) - 1,
			(int) Math.floor(minZ - EDGE_MARGIN_BLOCKS), (int) Math.floor(maxX + EDGE_MARGIN_BLOCKS),
			(int) Math.floor(maxZ + EDGE_MARGIN_BLOCKS)
		));
	}

	record SupportRegion(int minX, int y, int minZ, int maxX, int maxZ) { }

	record Conditions(
		String supportBlockId,
		boolean stableStanding,
		boolean fullySupported,
		double velocityX,
		double velocityZ
	) { }
}
