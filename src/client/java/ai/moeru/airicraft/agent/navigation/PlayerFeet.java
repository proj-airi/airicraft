package ai.moeru.airicraft.agent.navigation;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

/** The block cell a player stands in. */
public final class PlayerFeet {
	/** Raises the sample point so a player on soul sand or a dirt path is in the cell above the floor. */
	private static final double FEET_OFFSET = 0.1251;

	private PlayerFeet() {
	}

	public static BlockPos of(LocalPlayer player) {
		return BlockPos.containing(player.getX(), player.getY() + FEET_OFFSET, player.getZ());
	}
}
