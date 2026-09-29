package ai.moeru.airicraft.agent.llm;

import net.minecraft.world.phys.AABB;
import java.util.List;

/** Pure local collision facts, shared by standing and interaction queries. */
final class StandingGeometry {
	private StandingGeometry() { }
	static Assessment assess(double x, double feetY, double z, List<AABB> collisions, boolean loaded) {
		if (!loaded) return new Assessment("unknown", "unknown", "unknown", null, null, feetY, null);
		AABB feet = new AABB(x-.3,feetY+1e-5,z-.3,x+.3,feetY+.9,z+.3);
		AABB head = new AABB(x-.3,feetY+.9,z-.3,x+.3,feetY+1.8,z+.3);
		Double support = collisions.stream().filter(b -> Math.abs(b.maxY-feetY)<1e-4 && horizontalOverlap(feet,b))
			.map(b -> b.maxY).max(Double::compare).orElse(null);
		Double roof = collisions.stream().filter(b -> b.minY >= feetY && horizontalOverlap(feet,b)).map(b -> b.minY).min(Double::compare).orElse(null);
		return new Assessment(support == null ? "unsupported" : "supported", collisions.stream().anyMatch(feet::intersects) ? "obstructed" : "clear",
			collisions.stream().anyMatch(head::intersects) ? "obstructed" : "clear", support == null ? null : (int)Math.floor(support-1e-5), support, feetY, roof);
	}
	private static boolean horizontalOverlap(AABB a, AABB b) { return a.maxX>b.minX && a.minX<b.maxX && a.maxZ>b.minZ && a.minZ<b.maxZ; }
	record Assessment(String support, String feetClearance, String headClearance, Integer floorBlockY, Double supportSurfaceY, double feetY, Double roofBottomY) {
		boolean standable() { return support.equals("supported") && feetClearance.equals("clear") && headClearance.equals("clear"); }
	}
}
