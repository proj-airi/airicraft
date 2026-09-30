package ai.moeru.airicraft.agent.navigation;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** A door needs interaction only when its panel intersects the upcoming body sweep. */
public final class DoorPassage {
	private DoorPassage() {}

	public static boolean blocks(AABB body, Vec3 travel, AABB panel) {
		return body.expandTowards(travel).intersects(panel);
	}

	public static boolean cleared(AABB body, Vec3 travel, AABB panel) {
		if (Math.abs(travel.x) > Math.abs(travel.z))
			return travel.x > 0 ? body.minX > panel.maxX : body.maxX < panel.minX;
		return travel.z > 0 ? body.minZ > panel.maxZ : body.maxZ < panel.minZ;
	}
}
