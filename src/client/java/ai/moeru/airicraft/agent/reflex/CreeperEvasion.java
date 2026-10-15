package ai.moeru.airicraft.agent.reflex;

/**
 * The rules for a creeper, pure so they can be tested. A playtest body in an iron chestplate, with an iron sword and a
 * shield, at 17.5 health walked up to a creeper to melee it, stepped about between nearby cells once the fuse was lit,
 * started a navigator run only at 1.9 blocks and was blown up from full fuse. Vanilla: a creeper lights its fuse when
 * its target is within 3 blocks and stands still while it swells; it explodes 30 ticks later unless the target gets
 * more than 7 blocks away or out of its sight, which winds the fuse back down. A point-blank blast does about 22
 * before armor; a raised shield facing the creeper blocks all of it, but only 5 ticks after it goes up. A sprinting
 * player (about 0.28 blocks a tick) outruns a creeper.
 *
 * <p>So the body never closes in on a creeper: armed and without a bow shot it runs (see
 * {@link UnarmedFlight#fleeReason}) or, when it cannot, keeps {@value #KEEP_AWAY_DISTANCE} blocks away and hits one
 * only when it walks into reach before its fuse is lit. A lit fuse within reach of the blast is answered at once
 * ({@link #response}): sprint straight away while the run can clear 7 blocks before the fuse ends, otherwise stop,
 * face it and hold a shield up (while the shield still has time to come up), otherwise run anyway, since distance
 * cuts the blast.
 */
final class CreeperEvasion {
	/** A creeper's fuse in ticks from lighting to the blast. */
	static final int FUSE_TICKS = 30;
	/** {@code Creeper.getSwelling} divides the swell by two less than the fuse. */
	static final float SWELL_SCALE = FUSE_TICKS - 2;
	/** Beyond this the fuse winds down. */
	static final double DEFUSE_DISTANCE = 7.0D;
	/** Where a run counts as clear: past the defuse distance with half a block to spare. */
	static final double CLEAR_DISTANCE = DEFUSE_DISTANCE + 0.5D;
	/** A lit creeper this close (12 when charged: its blast reaches twice as far) is answered at once. */
	static final double LIT_RANGE = 8.0D;
	static final double CHARGED_LIT_RANGE = 12.0D;
	/** The gap the body keeps when it fights a creeper it cannot run from: past its 3-block ignition range. */
	static final double KEEP_AWAY_DISTANCE = 5.0D;
	static final double CHARGED_KEEP_AWAY_DISTANCE = 6.0D;
	/** A shield blocks once it has been up this long. */
	static final int SHIELD_DELAY_TICKS = 5;
	/** One tick for the server to see the shield go up, one for the client's fuse lagging the server's. */
	static final int SHIELD_MARGIN_TICKS = 2;
	/** The last fuse ticks left at which raising a shield still blocks the blast. */
	static final int LATEST_SHIELD_TICKS = SHIELD_DELAY_TICKS + SHIELD_MARGIN_TICKS;
	static final double SPRINT_BLOCKS_PER_TICK = 0.28D;
	static final double WALK_BLOCKS_PER_TICK = 0.21D;
	/** Turning around and getting up to speed from a standstill. */
	static final int START_TICKS = 4;
	/** Ticks a run must clear with to spare before it is trusted over a shield; a charged blast is fatal far out. */
	static final int RUN_MARGIN_TICKS = 3;
	static final int CHARGED_RUN_MARGIN_TICKS = 6;
	/** Horizontal velocity (blocks a tick, after friction) above which the body counts as already running. */
	static final double MOVING_SPEED = 0.1D;

	enum Response {
		/** No lit fuse near: the ordinary creeper rules (run, or keep away) apply. */
		NONE,
		/** Sprint straight away now. */
		RUN,
		/** Stop, face the creeper and hold the shield up until the blast or until the fuse winds down. */
		BLOCK
	}

	private CreeperEvasion() { }

	/** The fuse is burning or has not wound all the way down yet. */
	static boolean fuseLit(int swellDir, float fuseProgress) {
		return swellDir > 0 || fuseProgress > 0.0F;
	}

	/** A lit fuse close enough that the body must act on it now. */
	static boolean litNear(double distance, int swellDir, float fuseProgress, boolean charged) {
		return fuseLit(swellDir, fuseProgress) && distance <= (charged ? CHARGED_LIT_RANGE : LIT_RANGE);
	}

	/** Ticks until the blast if the fuse keeps burning, from {@code Creeper.getSwelling(1)}. */
	static int fuseTicksLeft(float fuseProgress) {
		return Math.max(0, FUSE_TICKS - Math.round(fuseProgress * SWELL_SCALE));
	}

	/** Ticks a straight run from {@code distance} needs to get past {@link #CLEAR_DISTANCE}. */
	static double ticksToClear(double distance, boolean sprint, boolean moving) {
		double gap = Math.max(0.0D, CLEAR_DISTANCE - distance);
		if (gap == 0.0D) return 0.0D;
		return gap / (sprint ? SPRINT_BLOCKS_PER_TICK : WALK_BLOCKS_PER_TICK) + (moving ? 0 : START_TICKS);
	}

	/** Whether a run gets clear before the fuse ends, with a margin. */
	static boolean runClears(double distance, int ticksLeft, boolean charged, boolean sprint, boolean moving) {
		return ticksToClear(distance, sprint, moving) + (charged ? CHARGED_RUN_MARGIN_TICKS : RUN_MARGIN_TICKS) <= ticksLeft;
	}

	/** Whether a shield raised now is up in time for the blast. */
	static boolean shieldInTime(int ticksLeft) {
		return ticksLeft >= LATEST_SHIELD_TICKS;
	}

	/**
	 * What to do about one creeper this tick.
	 *
	 * @param shieldReady a shield is in the offhand or the inventory and not on cooldown
	 * @param shieldUpTicks ticks the shield has already been held up against this creeper, 0 when it is not
	 * @param moving the body is already moving at speed (no start-up time)
	 */
	static Response response(double distance, int swellDir, float fuseProgress, boolean charged, boolean lineOfSight,
		boolean shieldReady, int shieldUpTicks, boolean sprint, boolean moving) {
		if (!litNear(distance, swellDir, fuseProgress, charged)) return Response.NONE;
		// Winding down: the fuse is not burning, so keep the distance growing.
		if (swellDir <= 0) return Response.RUN;
		// A shield already up stays up: lowering it now would waste the ticks it took to come up.
		if (shieldUpTicks > 0 && shieldReady) return Response.BLOCK;
		int ticksLeft = fuseTicksLeft(fuseProgress);
		if (runClears(distance, ticksLeft, charged, sprint, moving)) return Response.RUN;
		if (shieldReady && lineOfSight && shieldInTime(ticksLeft)) return Response.BLOCK;
		// Too late for the shield, or none: a blast loses most of its force over a few blocks.
		return Response.RUN;
	}

	/** The gap the body keeps from a creeper it fights rather than runs from. */
	static double keepAwayDistance(boolean charged) {
		return charged ? CHARGED_KEEP_AWAY_DISTANCE : KEEP_AWAY_DISTANCE;
	}

	/**
	 * Whether to swing at a creeper: only one that came into reach by itself, before its fuse is lit, with the
	 * weapon ready. The body never walks up to one; positioning backs off again after the hit.
	 */
	static boolean mayHit(double distance, boolean lineOfSight, float attackStrength, int swellDir, float fuseProgress) {
		return !fuseLit(swellDir, fuseProgress) && SurvivalReflexRuntime.shouldAttackCloseThreat(distance, lineOfSight, attackStrength);
	}
}
