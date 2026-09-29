package ai.moeru.airicraft.agent.lighting;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntPredicate;

public final class MiningIlluminationPreflight {
	private static final int HORIZONTAL_SCAN_RADIUS = 16;
	private static final int VERTICAL_SCAN_RADIUS = 12;
	private static final int SURFACE_OPENING_PROBE_BLOCKS = 6;
	private static final int SURFACE_OPENING_HORIZONTAL_RADIUS = 4;
	private static final Set<String> SURFACE_BOOTSTRAP_BLOCK_IDS = Set.of(
		"minecraft:stone",
		"minecraft:cobblestone"
	);
	private static final Set<String> UNDERGROUND_BLOCK_IDS = Set.of(
		"minecraft:stone",
		"minecraft:deepslate",
		"minecraft:tuff",
		"minecraft:netherrack",
		"minecraft:blackstone",
		"minecraft:basalt",
		"minecraft:ancient_debris"
	);

	private MiningIlluminationPreflight() {
	}

	public static Result inspect(Minecraft minecraft, GoalMineSpec spec, int maxLightLevel) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null || spec == null) {
			return Result.notRequired();
		}
		BlockPos origin = minecraft.player.blockPosition();
		if (isLikelyUndergroundPosition(minecraft, origin)) {
			return new Result(true, "current_position_underground");
		}
		LoadedTargetEvidence loadedTargetEvidence = inspectLoadedTargetEvidence(
			minecraft,
			origin,
			spec.blockIds(),
			maxLightLevel
		);
		return assess(
			false,
			loadedTargetEvidence.illuminationRequired(),
			loadedTargetEvidence.surfaceBootstrapObserved(),
			likelyUndergroundTarget(spec.blockIds())
		);
	}

	static Result assess(boolean currentUnderground, boolean loadedTargetUnilluminated, boolean targetTypeLikelyUnderground) {
		return assess(currentUnderground, loadedTargetUnilluminated, false, targetTypeLikelyUnderground);
	}

	static Result assess(
		boolean currentUnderground,
		boolean loadedTargetUnilluminated,
		boolean surfaceBootstrapObserved,
		boolean targetTypeLikelyUnderground
	) {
		if (currentUnderground) {
			return new Result(true, "current_position_underground");
		}
		if (loadedTargetUnilluminated) {
			return new Result(true, "loaded_target_unilluminated");
		}
		if (surfaceBootstrapObserved) {
			return Result.notRequired();
		}
		if (targetTypeLikelyUnderground) {
			return new Result(true, "target_type_likely_underground");
		}
		return Result.notRequired();
	}

	static boolean likelyUndergroundTarget(List<String> blockIds) {
		if (blockIds == null) {
			return false;
		}
		return blockIds.stream().anyMatch(blockId ->
			blockId != null && (blockId.endsWith("_ore") || UNDERGROUND_BLOCK_IDS.contains(blockId))
		);
	}

	public static Admission admit(Result prediction, int torchCount, boolean allowUnilluminated) {
		if (prediction == null || !prediction.illuminationRequired()) {
			return new Admission(true, "illumination_not_required");
		}
		if (allowUnilluminated) {
			return new Admission(true, "unilluminated_explicitly_allowed");
		}
		if (torchCount > 0) {
			return new Admission(true, "torch_available");
		}
		return new Admission(false, "torch_required");
	}

	private static LoadedTargetEvidence inspectLoadedTargetEvidence(
		Minecraft minecraft,
		BlockPos origin,
		List<String> requestedBlockIds,
		int maxLightLevel
	) {
		Set<String> blockIds = new HashSet<>(requestedBlockIds);
		boolean surfaceBootstrapRequest = blockIds.stream().anyMatch(SURFACE_BOOTSTRAP_BLOCK_IDS::contains);
		boolean anyUnilluminatedTarget = false;
		boolean anySurfaceAccessibleTarget = false;
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int x = -HORIZONTAL_SCAN_RADIUS; x <= HORIZONTAL_SCAN_RADIUS; x++) {
			for (int y = -VERTICAL_SCAN_RADIUS; y <= VERTICAL_SCAN_RADIUS; y++) {
				for (int z = -HORIZONTAL_SCAN_RADIUS; z <= HORIZONTAL_SCAN_RADIUS; z++) {
					cursor.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
					if (!minecraft.level.hasChunkAt(cursor)) {
						continue;
					}
					BlockState state = minecraft.level.getBlockState(cursor);
					String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
					if (!blockIds.contains(blockId)) {
						continue;
					}
					boolean illuminationRequired = loadedTargetRequiresIllumination(
						blockId,
						hasSurfaceOpeningWithinProbe(offset -> minecraft.level.canSeeSky(cursor.above(offset))),
						minecraft.level.getMaxLocalRawBrightness(cursor),
						maxLightLevel
					);
					if (illuminationRequired) {
						anyUnilluminatedTarget = true;
						if (!surfaceBootstrapRequest) {
							return new LoadedTargetEvidence(true, false);
						}
					}
					else if (SURFACE_BOOTSTRAP_BLOCK_IDS.contains(blockId)) {
						anySurfaceAccessibleTarget = true;
					}
				}
			}
		}
		return new LoadedTargetEvidence(
			aggregateLoadedTargetEvidence(
				surfaceBootstrapRequest,
				anyUnilluminatedTarget,
				anySurfaceAccessibleTarget
			),
			anySurfaceAccessibleTarget
		);
	}

	static boolean loadedTargetRequiresIllumination(
		String blockId,
		boolean surfaceAccessible,
		int lightLevel,
		int maxLightLevel
	) {
		if (surfaceAccessible && SURFACE_BOOTSTRAP_BLOCK_IDS.contains(blockId)) {
			return false;
		}
		return !surfaceAccessible || lightLevel <= maxLightLevel;
	}

	static boolean hasSurfaceOpeningWithinProbe(IntPredicate skyVisibleAtOffset) {
		for (int offset = 1; offset <= SURFACE_OPENING_PROBE_BLOCKS; offset++) {
			if (skyVisibleAtOffset.test(offset)) {
				return true;
			}
		}
		return false;
	}

	static boolean hasNearbySurfaceOpening(SurfaceVisibilityProbe visibilityProbe) {
		for (int x = -SURFACE_OPENING_HORIZONTAL_RADIUS; x <= SURFACE_OPENING_HORIZONTAL_RADIUS; x++) {
			for (int z = -SURFACE_OPENING_HORIZONTAL_RADIUS; z <= SURFACE_OPENING_HORIZONTAL_RADIUS; z++) {
				for (int y = 1; y <= SURFACE_OPENING_PROBE_BLOCKS; y++) {
					if (visibilityProbe.isSkyVisible(x, y, z)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	static boolean aggregateLoadedTargetEvidence(
		boolean surfaceBootstrapRequest,
		boolean anyUnilluminatedTarget,
		boolean anySurfaceAccessibleTarget
	) {
		return anyUnilluminatedTarget && (!surfaceBootstrapRequest || !anySurfaceAccessibleTarget);
	}

	private static boolean isLikelyUndergroundPosition(Minecraft minecraft, BlockPos position) {
		return !hasNearbySurfaceOpening((x, y, z) -> minecraft.level.canSeeSky(position.offset(x, y, z)));
	}

	@FunctionalInterface
	interface SurfaceVisibilityProbe {
		boolean isSkyVisible(int xOffset, int yOffset, int zOffset);
	}

	private record LoadedTargetEvidence(boolean illuminationRequired, boolean surfaceBootstrapObserved) {
	}

	public record Result(boolean illuminationRequired, String reason) {
		private static Result notRequired() {
			return new Result(false, "not_predicted");
		}
	}

	public record Admission(boolean allowed, String reason) {
	}
}
