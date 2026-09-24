package ai.moeru.airicraft.sim.spawn;

import ai.moeru.airicraft.sim.arena.Arena;
import ai.moeru.airicraft.sim.fake.FakePlayerEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** External/manual mob spawning into arenas with distance + collision validation. */
public final class SpawnService {
	private SpawnService() {}

	public static Entity spawn(Arena arena, String typeId, Vec3d pos, boolean targetPlayer, Double minDistance) {
		ServerWorld world = arena.world();
		EntityType<?> type = Registries.ENTITY_TYPE.get(Identifier.of(typeId));
		if (type == null) {
			throw new IllegalArgumentException("unknown entity type: " + typeId);
		}
		List<double[]> playerPositions = new ArrayList<>();
		for (FakePlayerEntity player : arena.players()) {
			Vec3d p = player.getPos();
			playerPositions.add(new double[] {p.x, p.y, p.z});
		}
		if (!playerPositions.isEmpty()) {
			double min = SpawnRules.effectiveMinDistance(minDistance);
			SpawnRules.Result check = SpawnRules.checkMinDistance(pos.x, pos.y, pos.z, playerPositions, min);
			if (!check.ok()) {
				throw new IllegalArgumentException(check.reason());
			}
		}
		Entity entity = type.create(world, SpawnReason.COMMAND);
		if (entity == null) {
			throw new IllegalStateException("entity type cannot be created: " + typeId);
		}
		entity.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0f, 0.0f);
		if (!world.isSpaceEmpty(entity, entity.getBoundingBox())) {
			throw new IllegalArgumentException("no empty space at spawn position " + pos);
		}
		world.spawnNewEntityAndPassengers(entity);
		arena.trackMob(entity);
		if (targetPlayer && entity instanceof MobEntity mob && !arena.players().isEmpty()) {
			mob.setTarget(arena.players().get(0));
		}
		return entity;
	}

	/** Picks a random valid spawn point inside the arena at least minDistance from players. */
	public static Vec3d pickSpawnPos(Arena arena, double minDistance, net.minecraft.util.math.random.Random random) {
		ServerWorld world = arena.world();
		List<double[]> playerPositions = new ArrayList<>();
		for (FakePlayerEntity player : arena.players()) {
			Vec3d p = player.getPos();
			playerPositions.add(new double[] {p.x, p.y, p.z});
		}
		for (int attempt = 0; attempt < 64; attempt++) {
			double x = arena.region().minX + 0.5 + random.nextDouble() * (arena.region().getLengthX() - 1);
			double z = arena.region().minZ + 0.5 + random.nextDouble() * (arena.region().getLengthZ() - 1);
			int y = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, (int) x, (int) z);
			Vec3d pos = new Vec3d(x, y, z);
			if (!arena.region().contains(pos)) {
				continue;
			}
			if (!playerPositions.isEmpty()
					&& !SpawnRules.checkMinDistance(x, y, z, playerPositions, minDistance).ok()) {
				continue;
			}
			BlockPos feet = BlockPos.ofFloored(pos);
			if (world.getBlockState(feet).isAir() && world.getBlockState(feet.up()).isAir()) {
				return pos;
			}
		}
		return null;
	}
}
