package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.llm.WorldFeatureSearchService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Reads the client world for explore_for: the player, the feature search over loaded chunks, and the top of a column.
 * Under an open sky the top is the motion-blocking heightmap (water counts, leaves do not); under a ceiling, as in the
 * Nether, it is the first standing cell found scanning down from just above the body.
 */
final class MinecraftExploreEnvironment implements ExploreForTaskExecutor.Environment {
	private static final int CEILING_SCAN_UP = 8;
	private static final int CEILING_SCAN_DOWN = 16;

	private static Minecraft client() { return Minecraft.getInstance(); }

	@Override
	public ExploreForTaskExecutor.Body observe() {
		LocalPlayer player = client().player;
		ClientLevel level = client().level;
		if (player == null || level == null) return null;
		boolean night = !level.dimensionType().hasFixedTime() && ExploreForTaskExecutor.night(level.getDayTime());
		return new ExploreForTaskExecutor.Body(player.position(), player.getHealth(), night);
	}

	@Override
	public Optional<ExploreForTaskExecutor.Feature> findFeature(String featureKind, Vec3 feet, int radius) {
		ClientLevel level = client().level;
		if (level == null) return Optional.empty();
		return WorldFeatureSearchService.nearest(level, BlockPos.containing(feet), featureKind, radius)
			.map(found -> new ExploreForTaskExecutor.Feature(found.targetPos().getX(), found.targetPos().getY(),
				found.targetPos().getZ(), found.summary()));
	}

	@Override
	public ExploreForTaskExecutor.Ground ground(int x, int z, int nearY) {
		ClientLevel level = client().level;
		if (level == null || !level.hasChunkAt(new BlockPos(x, nearY, z))) return ExploreForTaskExecutor.Ground.UNLOADED;
		if (!level.dimensionType().hasCeiling()) {
			return classify(level, x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
		}
		for (int y = nearY + CEILING_SCAN_UP; y >= nearY - CEILING_SCAN_DOWN && y > level.getMinY(); y--) {
			BlockPos feet = new BlockPos(x, y, z);
			if (open(level, feet) && open(level, feet.above()) && !open(level, feet.below())) return classify(level, x, y, z);
		}
		return new ExploreForTaskExecutor.Ground(ExploreForTaskExecutor.Ground.Kind.DANGER, nearY);
	}

	/** What standing with the feet at {@code y} would be like: on dry ground, in water, or on or in something that hurts. */
	private static ExploreForTaskExecutor.Ground classify(ClientLevel level, int x, int y, int z) {
		BlockPos feet = new BlockPos(x, y, z);
		BlockState below = level.getBlockState(feet.below());
		var kind = ExploreForTaskExecutor.Ground.Kind.DRY;
		if (below.getFluidState().is(FluidTags.LAVA) || level.getFluidState(feet).is(FluidTags.LAVA)
			|| below.is(Blocks.MAGMA_BLOCK) || below.is(Blocks.CACTUS) || below.is(BlockTags.CAMPFIRES) || below.is(BlockTags.FIRE)
			|| level.getBlockState(feet).is(BlockTags.FIRE)) {
			kind = ExploreForTaskExecutor.Ground.Kind.DANGER;
		}
		else if (below.getFluidState().is(FluidTags.WATER) || level.getFluidState(feet).is(FluidTags.WATER)) {
			kind = ExploreForTaskExecutor.Ground.Kind.WATER;
		}
		else if (!clear(level, feet) || !clear(level, feet.above())) {
			// Not room to stand, such as under low leaves.
			kind = ExploreForTaskExecutor.Ground.Kind.DANGER;
		}
		return new ExploreForTaskExecutor.Ground(kind, y);
	}

	/** No collision and no fluid. */
	private static boolean open(ClientLevel level, BlockPos pos) {
		return clear(level, pos) && level.getFluidState(pos).isEmpty();
	}

	private static boolean clear(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	@Override
	public void release() { }
}
