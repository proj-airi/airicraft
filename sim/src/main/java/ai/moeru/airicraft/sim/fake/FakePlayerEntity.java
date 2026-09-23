package ai.moeru.airicraft.sim.fake;

import ai.moeru.airicraft.sim.SimRuntime;
import ai.moeru.airicraft.sim.input.SimInputExecutor;
import com.mojang.authlib.GameProfile;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;

/**
 * Carpet-style fake player: a real {@link ServerPlayerEntity} registered through
 * {@code PlayerManager.onPlayerConnect} with an inert {@link FakeClientConnection}.
 * All interaction with the world goes through the vanilla tick path; the policy
 * only sets legal inputs (look, {@link net.minecraft.util.PlayerInput}, attack presses).
 */
public class FakePlayerEntity extends ServerPlayerEntity {
	private SimInputExecutor executor;

	public FakePlayerEntity(MinecraftServer server, ServerWorld world, GameProfile profile, SyncedClientOptions options) {
		super(server, world, profile, options);
	}

	public void setSimExecutor(SimInputExecutor executor) {
		this.executor = executor;
	}

	public SimInputExecutor getSimExecutor() {
		return executor;
	}

	/**
	 * Writes the same movement-input fields the real client fills from the
	 * keyboard ({@link net.minecraft.entity.LivingEntity#forwardSpeed} etc.).
	 * Values are scaled by 0.98 exactly like vanilla client input.
	 */
	public void applyMovementInput(float forward, float sideways, float upward, boolean jump) {
		this.forwardSpeed = forward * 0.98f;
		this.sidewaysSpeed = sideways * 0.98f;
		this.upwardSpeed = upward * 0.98f;
		this.jumping = jump;
	}

	public int getLastAttackedTicks() {
		return lastAttackedTicks;
	}

	public boolean isImmobileForSim() {
		return isImmobile();
	}

	/**
	 * In-place revive for arena resets: clears the dead flag and restores full
	 * health without going through PlayerManager respawn (the fake player is
	 * never disconnected, so vanilla respawn would replace the entity).
	 */
	public void reviveForSim() {
		this.dead = false;
		this.deathTime = 0;
		setHealth(getMaxHealth());
	}

	/**
	 * Vanilla returns false on the server (player movement is client-authoritative);
	 * for a simulated player the executor is the local controller, so voluntary
	 * movement is allowed and the full input→travel physics path runs.
	 */
	@Override
	public boolean canMoveVoluntarily() {
		return true;
	}

	@Override
	public boolean canActVoluntarily() {
		return true;
	}

	@Override
	public void tick() {
		if (executor != null) {
			executor.apply(this);
		}
		// Real clients increment this inside the move-packet path; with no client
		// packets the counter must be ticked here so attack cooldowns advance.
		++this.lastAttackedTicks;
		// Vanilla drives the physics tick from ServerPlayNetworkHandler.tick()
		// (playerTick -> PlayerEntity.tick) via the connection loop. A fake
		// connection never ticks its handler (and the handler early-outs on a
		// dead channel anyway), so invoke the physics tick directly.
		this.playerTick();
		super.tick();
	}

	@Override
	public void onDeath(DamageSource source) {
		SimRuntime.onEntityDeath(this, source);
		super.onDeath(source);
	}

	public static FakePlayerEntity spawn(MinecraftServer server, ServerWorld world, String name, Vec3d pos, float yaw) {
		GameProfile profile = new GameProfile(UUID.randomUUID(), name);
		FakePlayerEntity player = new FakePlayerEntity(server, world, profile, SyncedClientOptions.createDefault());
		FakeClientConnection connection = new FakeClientConnection();
		ConnectedClientData clientData = ConnectedClientData.createDefault(profile, false);
		server.getPlayerManager().onPlayerConnect(connection, player, clientData);
		player.teleport(world, pos.x, pos.y, pos.z, Set.<PositionFlag>of(), yaw, 0.0f, false);
		player.changeGameMode(GameMode.SURVIVAL);
		return player;
	}
}
