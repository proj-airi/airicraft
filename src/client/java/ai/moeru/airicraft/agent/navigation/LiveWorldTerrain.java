package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.TerrainView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/** Reads the client world directly. Client thread only: path following rechecks steps with it. */
public final class LiveWorldTerrain implements TerrainView {
	private final ClientLevel level;
	private final MinecraftCellClassifier classifier;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

	public LiveWorldTerrain(ClientLevel level, MinecraftCellClassifier classifier) {
		this.level = level;
		this.classifier = classifier;
	}

	@Override
	public CellInfo cell(int x, int y, int z) {
		if (y < level.getMinY() || y > level.getMaxY()) return CellInfo.UNLOADED;
		pos.set(x, y, z);
		if (!level.hasChunkAt(pos)) return CellInfo.UNLOADED;
		return classifier.classify(level.getBlockState(pos));
	}
}
