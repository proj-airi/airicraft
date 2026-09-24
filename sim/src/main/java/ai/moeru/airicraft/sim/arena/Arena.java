package ai.moeru.airicraft.sim.arena;

import ai.moeru.airicraft.sim.fake.FakePlayerEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

/**
 * A bounded world region hosting one combat episode: a flat platform plus
 * tracked entities. Reset kills tracked mobs, removes projectiles/items, and
 * restores the fake player to a clean state.
 */
public final class Arena {
	private final String name;
	private final ServerWorld world;
	private final Box region;
	private final Vec3d playerSpawn;
	private final float playerYaw;
	private final Set<Entity> trackedMobs = ConcurrentHashMap.newKeySet();
	private final List<FakePlayerEntity> players = new ArrayList<>();
	private int resetCount;

	public Arena(String name, ServerWorld world, Vec3d center, int size, float playerYaw) {
		this.name = name;
		this.world = world;
		double half = size / 2.0;
		this.region = new Box(
				center.x - half, center.y - 4, center.z - half,
				center.x + half, center.y + 16, center.z + half);
		this.playerSpawn = center;
		this.playerYaw = playerYaw;
	}

	public static Arena createFlat(String name, ServerWorld world, Vec3d center, int size, float playerYaw) {
		Arena arena = new Arena(name, world, center, size, playerYaw);
		arena.forceLoadChunks();
		arena.buildPlatform();
		return arena;
	}

	/**
	 * Force-load every chunk overlapping the region (like /forceload). Without
	 * this, entities in edge chunks can unload mid-episode (isAlive() flips
	 * false and untracked copies can materialize from pending chunk data).
	 */
	private void forceLoadChunks() {
		int minCX = (int) Math.floor(region.minX) >> 4;
		int maxCX = (int) Math.floor(region.maxX) >> 4;
		int minCZ = (int) Math.floor(region.minZ) >> 4;
		int maxCZ = (int) Math.floor(region.maxZ) >> 4;
		for (int cx = minCX; cx <= maxCX; cx++) {
			for (int cz = minCZ; cz <= maxCZ; cz++) {
				world.setChunkForced(cx, cz, true);
			}
		}
	}

	public String name() {
		return name;
	}

	public ServerWorld world() {
		return world;
	}

	public Box region() {
		return region;
	}

	public Vec3d playerSpawn() {
		return playerSpawn;
	}

	public float playerYaw() {
		return playerYaw;
	}

	public List<FakePlayerEntity> players() {
		return players;
	}

	public void addPlayer(FakePlayerEntity player) {
		players.add(player);
	}

	public void trackMob(Entity entity) {
		trackedMobs.add(entity);
	}

	public Set<Entity> trackedMobs() {
		return trackedMobs;
	}

	public int resetCount() {
		return resetCount;
	}

	public int aliveTrackedMobs() {
		int count = 0;
		for (Entity mob : trackedMobs) {
			if (mob.isAlive()) {
				count++;
			}
		}
		return count;
	}

	private void buildPlatform() {
		int minX = (int) Math.floor(region.minX);
		int maxX = (int) Math.ceil(region.maxX);
		int minZ = (int) Math.floor(region.minZ);
		int maxZ = (int) Math.ceil(region.maxZ);
		int floorY = (int) Math.floor(playerSpawn.y) - 1;
		for (int x = minX; x <= maxX; x++) {
			for (int z = minZ; z <= maxZ; z++) {
				world.setBlockState(new BlockPos(x, floorY, z), Blocks.STONE.getDefaultState());
				for (int y = floorY + 1; y <= floorY + 4; y++) {
					world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
				}
			}
		}
	}

	/** Full reset: kill tracked mobs, drop projectiles/items in region, restore every fake player. */
	public void reset() {
		for (Entity mob : trackedMobs) {
			if (!mob.isRemoved()) {
				mob.remove(Entity.RemovalReason.DISCARDED);
			}
		}
		trackedMobs.clear();
		// Clean with a margin: explosions and knockback fling drops/mobs past the
		// region edge, where they would otherwise accumulate forever. Living
		// entities: 12 blocks stays clear of neighbouring arenas (~15-block gap).
		// Inert debris (items, orbs, projectiles) can't affect a neighbour's
		// episode, so it gets a wider radius covering the inter-arena dead zone.
		Box cleanup = region.expand(12);
		Box debrisBox = region.expand(17);
		for (Entity entity : world.getEntitiesByClass(Entity.class, debrisBox,
				e -> !(e instanceof FakePlayerEntity))) {
			boolean inMargin = cleanup.contains(entity.getX(), entity.getY(), entity.getZ());
			if (entity instanceof net.minecraft.entity.player.PlayerEntity
					|| (inMargin && entity instanceof LivingEntity) || isArenaDebris(entity)) {
				entity.remove(Entity.RemovalReason.DISCARDED);
			}
		}
		for (FakePlayerEntity player : players) {
			resetPlayer(player);
		}
		buildPlatform(); // restore flat floor — wipes any terrain features laid for the last episode
		resetCount++;
	}

	/**
	 * Lay terrain feature blocks inside the arena region. Positions outside the
	 * region or above floor+3 are rejected; the floor itself may be replaced
	 * (e.g. dug-out water pools) but never below it.
	 */
	public int setFeature(List<int[]> positions, Block block) {
		int floorY = (int) Math.floor(playerSpawn.y) - 1;
		int placed = 0;
		for (int[] p : positions) {
			BlockPos bp = new BlockPos(p[0], p[1], p[2]);
			if (p[1] < floorY || p[1] > floorY + 3 || !region.contains(bp.getX() + 0.5, p[1] + 0.5, bp.getZ() + 0.5)) {
				continue;
			}
			world.setBlockState(bp, block.getDefaultState());
			placed++;
		}
		return placed;
	}

	private boolean isArenaDebris(Entity entity) {
		return entity instanceof net.minecraft.entity.projectile.ProjectileEntity
				|| entity instanceof net.minecraft.entity.ItemEntity
				|| entity instanceof net.minecraft.entity.ExperienceOrbEntity;
	}

	private void resetPlayer(FakePlayerEntity player) {
		if (player.isRemoved()) {
			// Corpse-keeping (FakePlayerEntity.remove vetoes KILLED) makes this
			// path rare; still guard the disconnect so a fake-connection quirk
			// cannot abort the reset mid-removal.
			try {
				world.getServer().getPlayerManager().remove(player);
			} catch (Exception ignored) {
			}
			FakePlayerEntity fresh = FakePlayerEntity.spawn(world.getServer(), world,
					player.getNameForScoreboard(), playerSpawn, playerYaw);
			fresh.setSimExecutor(player.getSimExecutor());
			int idx = players.indexOf(player);
			if (idx >= 0) {
				players.set(idx, fresh);
			}
			player = fresh;
		}
		if (player.isDead()) {
			player.reviveForSim();
		}
		player.teleport(world, playerSpawn.x, playerSpawn.y, playerSpawn.z,
				Set.<net.minecraft.network.packet.s2c.play.PositionFlag>of(), playerYaw, 0.0f, false);
		player.setVelocity(Vec3d.ZERO);
		player.setHealth(player.getMaxHealth());
		player.getHungerManager().setFoodLevel(20);
		player.clearStatusEffects();
		player.extinguish();
		player.fallDistance = 0;
		player.setFireTicks(0);
		player.stopUsingItem();
		player.setSprinting(false);
		player.getInventory().clear();
		if (player.getSimExecutor() != null) {
			player.getSimExecutor().reset();
		}
	}

	/** Clears aggro of every mob in the region (used when an episode ends). */
	public void clearAggro() {
		for (Entity entity : world.getEntitiesByClass(MobEntity.class, region, e -> true)) {
			((MobEntity) entity).setTarget(null);
		}
	}
}
