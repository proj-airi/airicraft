package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.control.MovementController;
import ai.moeru.airicraft.control.Priority;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;

/**
 * Keeps an idle body at the surface of deep water, as a player holds jump while swimming. In a random-seed
 * playtest every drowning alarm came from the same pattern: a walk ended (or was paused) with the body
 * afloat, nothing pressed jump, it sank five blocks in ten seconds and the drowning reflex fired, held the
 * work for the planner, and the next walk ended in the water again. This holds jump with background
 * priority only while nothing else is working, so any task or reflex that moves the body takes over at once.
 * Client thread only.
 */
public final class TreadWater {
	private final MovementController movement = new MovementController("tread_water", Priority.BACKGROUND);
	private boolean treading;

	/** Whether to hold jump: idle, in water, and either the eyes are under or there is no floor under the feet. */
	static boolean shouldTread(boolean idle, boolean inWater, boolean eyesInWater, boolean onGround) {
		return idle && inWater && (eyesInWater || !onGround);
	}

	public void tick(Minecraft minecraft, boolean idle) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		boolean tread = player != null && !player.isDeadOrDying() && !player.isSpectator()
			&& shouldTread(idle, player.isInWater(), player.isEyeInFluid(FluidTags.WATER), player.onGround());
		if (tread) {
			movement.hold(minecraft, true, false);
			treading = true;
		} else if (treading) {
			movement.stop(minecraft);
			treading = false;
		}
	}

	public void reset(Minecraft minecraft) {
		if (treading) movement.stop(minecraft);
		treading = false;
	}
}
