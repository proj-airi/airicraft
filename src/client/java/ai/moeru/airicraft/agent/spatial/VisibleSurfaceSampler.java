package ai.moeru.airicraft.agent.spatial;

import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** Bounded first-hit observations. Sparse rays may miss visible surfaces. */
public final class VisibleSurfaceSampler {
	private VisibleSurfaceSampler() {}

	public static List<BlockHitResult> sample(Vec3 eye, double radius,
		BiFunction<Vec3, Vec3, BlockHitResult> raycast) {
		List<BlockHitResult> hits = new ArrayList<>();
		for (int yaw = 0; yaw < 360; yaw += 8) {
			for (int elevation = -75; elevation <= 75; elevation += 15) {
				double y = Math.toRadians(yaw), pitch = Math.toRadians(elevation);
				Vec3 direction = new Vec3(Math.cos(y) * Math.cos(pitch), Math.sin(pitch), Math.sin(y) * Math.cos(pitch));
				BlockHitResult hit = raycast.apply(eye, eye.add(direction.scale(radius)));
				if (hit.getType() == HitResult.Type.BLOCK) hits.add(hit);
			}
		}
		return List.copyOf(hits);
	}
}
