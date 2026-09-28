package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.TerrainView;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/** Reads the client world directly. Client thread only: path following rechecks steps with it. */
public final class LiveWorldTerrain implements TerrainView {
	private final ClientWorld world;
	private final MinecraftCellClassifier classifier;
	private final BlockPos.Mutable pos = new BlockPos.Mutable();

	public LiveWorldTerrain(ClientWorld world, MinecraftCellClassifier classifier) {
		this.world = world;
		this.classifier = classifier;
	}

	@Override
	public CellInfo cell(int x, int y, int z) {
		if (y < world.getBottomY() || y > world.getTopYInclusive()) return CellInfo.UNLOADED;
		pos.set(x, y, z);
		if (!world.isChunkLoaded(pos)) return CellInfo.UNLOADED;
		return classifier.classify(world.getBlockState(pos));
	}
}
