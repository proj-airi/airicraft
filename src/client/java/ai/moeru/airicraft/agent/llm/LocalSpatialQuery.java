package ai.moeru.airicraft.agent.llm;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import java.util.*;

final class LocalSpatialQuery {
	private LocalSpatialQuery() { }
	static CurrentWorldQueryService.WorldQueryResult inspect(Minecraft minecraft, String mode, BlockPos center, JsonObject args) {
		var observed = new LinkedHashSet<BlockPos>();
		var report = new LinkedHashMap<String,Object>();
		report.put("mode",mode); report.put("routeGuarantee",false);
		if (mode.equals("check_position")) {
			double feetY = args.has("feetY") ? args.get("feetY").getAsDouble() : center.getY();
			if (!Double.isFinite(feetY) || Math.abs(feetY-center.getY())>1) throw new IllegalArgumentException("feetY must be within one block of query y");
			var feet = new Vec3(center.getX()+.5, feetY, center.getZ()+.5);
			report.put("position", coordinates(feet)); report.put("standing", standing(minecraft,feet,observed));
		} else {
			report.put("targetBlock",coordinates(Vec3.atLowerCornerOf(center)));
			report.put("fromCurrentEyes", interaction(minecraft,minecraft.player.getEyePosition(),center,observed));
			var approaches = new ArrayList<Object>();
			for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3,-2,-3),center.offset(3,2,3))) {
				if (approaches.size() >= 8) break;
				if (!minecraft.level.hasChunkAt(pos.below())) continue;
				// Include partial support surfaces, not only integer feet heights.
				var floor = pos.below();
				for (var shape : minecraft.level.getBlockState(floor).getCollisionShape(minecraft.level,floor).toAabbs()) {
					Vec3 feet = new Vec3(pos.getX()+.5,floor.getY()+shape.maxY,pos.getZ()+.5);
					var standing = standing(minecraft,feet,observed);
					if (!standing.standable()) continue;
					var interaction = interaction(minecraft,feet.add(0,1.62,0),center,observed);
					if (Boolean.TRUE.equals(interaction.get("reachable")) && "clear".equals(interaction.get("lineOfSight"))) {
						approaches.add(Map.of("feet",coordinates(feet),"standing",standing,"interaction",interaction)); break;
					}
				}
			}
			report.put("suitableApproaches",approaches);
			report.put("approachSearch", "bounded local candidates; absence is not proof that no approach or route exists");
			if (minecraft.level.hasChunkAt(center)) DoorPassageGeometry.describe(minecraft.level,center).ifPresent(value -> report.put("doorPassage",value));
		}
		return new CurrentWorldQueryService.WorldQueryResult("Tool result for inspect_world: " + new Gson().toJson(report),List.copyOf(observed));
	}
	private static StandingGeometry.Assessment standing(Minecraft minecraft, Vec3 feet, Set<BlockPos> observed) {
		BlockPos p = BlockPos.containing(feet); var shapes = new ArrayList<AABB>(); boolean loaded = true;
		for (BlockPos cell : BlockPos.betweenClosed(p.offset(-1,-1,-1),p.offset(1,2,1))) {
			if (!minecraft.level.hasChunkAt(cell)) { loaded=false; continue; }
			observed.add(cell.immutable());
			for (AABB b : minecraft.level.getBlockState(cell).getCollisionShape(minecraft.level,cell).toAabbs()) shapes.add(b.move(cell));
		}
		return StandingGeometry.assess(feet.x,feet.y,feet.z,shapes,loaded);
	}
	private static Map<String,Object> interaction(Minecraft minecraft, Vec3 eyes, BlockPos target, Set<BlockPos> observed) {
		var result = new LinkedHashMap<String,Object>();
		Vec3 end = Vec3.atCenterOf(target);
		boolean loaded = true;
		for (int i=0;i<=32;i++) {
			BlockPos cell = BlockPos.containing(eyes.lerp(end,i/32.0));
			if (!minecraft.level.hasChunkAt(cell)) loaded=false; else observed.add(cell);
		}
		result.put("eyes",coordinates(eyes)); result.put("distanceToCenter",eyes.distanceTo(end));
		double reach = minecraft.player.blockInteractionRange();
		result.put("reach",reach);
		if (!loaded) { result.put("lineOfSight","unknown"); result.put("reachable","unknown"); return result; }
		var hit = minecraft.level.clip(new ClipContext(eyes,end,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,minecraft.player));
		boolean clear = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
		result.put("lineOfSight", clear ? "clear" : hit.getType()==HitResult.Type.MISS ? "no_target_collision" : "obstructed");
		result.put("reachable", eyes.distanceToSqr(hit.getLocation())<=reach*reach);
		if (!clear && hit.getType()==HitResult.Type.BLOCK) result.put("obstruction",coordinates(Vec3.atLowerCornerOf(hit.getBlockPos())));
		return result;
	}
	private static Map<String,Double> coordinates(Vec3 v) { return Map.of("x",v.x,"y",v.y,"z",v.z); }
}
