package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Collects blaze rods at a Nether fortress without waiting on the planner. In a playtest an external driver found the
 * fortress at once, then broke the soul soil under its own feet: the body fell about seven blocks into the blaze spawner
 * room at 18 health and burned to death in about 15 seconds while the reflex meleed one blaze and the others shot
 * fireballs, and the whole kit was lost. This task never enters the room from above. It finds a blaze spawner the body
 * can see (or blazes in sight), walks with falls capped at three blocks to a fighting spot near it (solid floor, no lava
 * or fire within two blocks, no deeper drop beside it, a clear view of the spawner, preferring a wall or roof edge) and
 * fights from there. Every tick, in order: with more than three blazes in sight, hurt with blazes in sight, or hit on the
 * way in, walk to cover out of (most of) their sight first, with the shield down so the walk is not slowed; raise the
 * shield against a fireball about to hit, else strafe off its line; hit a blaze in reach; at low health, eat and heal;
 * pick up dropped rods from safe ground with no blaze close; shoot a blaze farther away with a bow, or without one hide
 * so it comes close. It ends once the inventory holds the requested rods.
 *
 * <p>In live play a body with a sword, a shield and no bow walked toward a spawner room where eight blazes saw it. The
 * shield answered every incoming fireball and stopped the walk, so with eight shooters there was always one incoming:
 * the body stood in the open for 700 ticks, shielding one direction while the others hit it, and never ran the cover
 * step. Its cover search also wanted a cell no blaze at all could see and gave up after one empty search, where the
 * reflex's {@code RangedCover} found a cell seen by two of them 8.6 blocks away. It handed back at 6 health, too late
 * to survive the walk there. Cover now comes before the shield, the search takes the cell seen by the fewest blazes
 * (as {@code RangedCover} does), and with no cover the task hands back while health is still at least
 * {@link #RETREAT_HEALTH}.
 */
public final class CollectBlazeRodsTaskExecutor implements WorldTaskExecutor {
	/** Fifteen minutes; a spawner makes two to four blazes every 10 to 40 seconds while a player is within 16 blocks. */
	static final int TIMEOUT_TICKS = 18_000;
	/** At the fighting spot with nothing to fight or pick up for this long, the spawner is not spawning. */
	static final int NO_BLAZE_TICKS = 1_800;
	/** Looking around before deciding there is nothing here; entities reach the client over a few ticks. */
	static final int LOOK_TICKS = 40;
	/**
	 * Hurt this far, go out of sight to eat; with no cover to go to, hand back now, while the reflex still has the
	 * health to walk to cover through a few more fireballs (a hit with its burning costs about five).
	 */
	static final float RETREAT_HEALTH = 12.0F;
	static final float RESUME_HEALTH = 16.0F;
	/** Out in the open this low, one more burst kills: hand back rather than keep looking for cover. */
	static final float CRITICAL_HEALTH = 4.0F;
	/** Natural regeneration needs this much hunger. */
	static final int REGEN_FOOD = 18;
	static final int FULL_FOOD = 20;
	static final int HEAL_TIMEOUT_TICKS = 2_400;
	static final int MAX_ENGAGED = 3;
	static final double ENGAGE_RANGE = 20.0D;
	static final double BOW_MIN_DISTANCE = 2.5D;
	static final double BOW_MAX_DISTANCE = 24.0D;
	static final double SPOT_MIN_DISTANCE = 4.0D;
	/** A spawner only spawns with a player within 16 blocks of it. */
	static final double SPOT_MAX_DISTANCE = 12.0D;
	static final double SPOT_PREFERRED_DISTANCE = 8.0D;
	static final int SPOT_ATTEMPTS = 4;
	/** Knocked this far off the spot, walk back to it. */
	static final double SPOT_LEASH = 2.5D;
	static final double PICKUP_RADIUS = 16.0D;
	/** No blaze in sight this close to the rod or the body while walking over it. */
	static final double PICKUP_BLAZE_CLEARANCE = 8.0D;
	static final int PICKUP_TIMEOUT_TICKS = 300;
	/** A fireball passing this close to the body's centre hits it. */
	static final double FIREBALL_HIT_DISTANCE = 1.4D;
	static final double FIREBALL_WATCH_DISTANCE = 24.0D;
	/** Keeps the shield up this long after the last incoming fireball; a blaze fires three in a burst. */
	static final int SHIELD_HOLD_TICKS = 8;
	/** A step of about a block and a half to the side, enough to leave a small fireball's line. */
	static final int STRAFE_TICKS = 6;
	/** Hiding so blazes come close: wait this long behind cover before choosing again. */
	static final int COVER_HOLD_TICKS = 120;
	/** The reflex's {@code RangedCover} radius. */
	static final double COVER_RADIUS = 8.0D;
	/** Cover cells this far below the feet, the safe fall. */
	static final int COVER_MAX_DROP = 3;
	static final int COVER_MAX_RISE = 2;
	/** Cover cells whose walks failed before the task stops looking. */
	static final int COVER_ATTEMPTS = 3;
	/** A walk to a cover cell a few blocks away that has not arrived after this long has failed. */
	static final int COVER_WALK_TICKS = 100;
	/** After a search found no cover cell, search again only after this long (blazes move, so cells open up). */
	static final int COVER_RETRY_TICKS = 20;
	/** At a cover cell more blazes see than when it was picked, look for a better one this often. */
	static final int COVER_REPICK_TICKS = 20;
	/** A blaze this close to the body in cover came looking for it, as the ambush wants: it does not make the cell bad. */
	static final double AMBUSH_DISTANCE = 5.0D;
	/** Health lost with a blaze in sight within this many ticks: the body is under fire. */
	static final int UNDER_FIRE_TICKS = 40;
	/** A blaze with no clear arrow arc from here is left to come closer for this long. */
	static final int NO_ARC_TICKS = 40;
	static final int FULL_DRAW_TICKS = 22;
	static final int MAX_AIM_TICKS = 50;
	static final int NAVIGATION_TIMEOUT_TICKS = 1_200;
	static final int SPAWNER_RESCAN_TICKS = 100;
	/** Gives up on a route before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	/** A blaze that vanishes this close was killed, not unloaded. */
	static final double KILL_DISTANCE = 24.0D;
	/** Falls deeper than this are how the playtest body ended up in the spawner room. */
	static final int MAX_SAFE_FALL = 3;
	static final NavigationOptions OPTIONS = NavigationOptions.DEFAULT.withMaxSafeFall(MAX_SAFE_FALL);
	/** Cover is a few blocks away and wanted now: walk there, never stop to dig or build under fire. */
	static final NavigationOptions COVER_OPTIONS = OPTIONS.withNoEdits();

	private final NavigationFacade navigation;
	private final Environment environment;
	private WorldTaskRequest request;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Mode mode = Mode.APPROACH;
	private int activeTicks, modeTicks, idleTicks;
	private int count, rods, startRods = -1;
	private Anchor anchor;
	private GoalPosition spawner;
	private boolean everSawBlaze;
	private GoalPosition spot;
	private int spotFailures;
	private final Set<GoalPosition> rejectedSpots = new HashSet<>();
	private String spotRefusals = "";
	private GoalPosition cover;
	private int coverSeenBy, coverPickedTick, noCoverUntil;
	private int coverFailures;
	private String coverReason;
	private final Set<GoalPosition> rejectedCovers = new HashSet<>();
	private float lastHealth = -1.0F;
	private int lastHitTick = -1;
	private boolean overwhelmed;
	private Vec3 pickupTarget;
	private int pickupTicks;
	private final List<Vec3> skippedRods = new ArrayList<>();
	private final Map<Integer, Vec3> knownBlazes = new LinkedHashMap<>();
	private final Map<Integer, Integer> noArcUntil = new HashMap<>();
	private int kills, meleeHits, arrowsShot, shieldRaises, strafes, eats;
	private boolean shielding;
	private int shieldHold;
	private Vec3 shieldFace;
	private int strafeTicks;
	private Side strafeSide = Side.NONE;
	private Vec3 strafeFace;
	private int drawTicks, bowTarget = -1;
	private boolean consuming;
	private GoalPosition navigationGoal;
	private boolean navigationOwned;
	private int navigationTicks;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);
	/** The tick a stalled route was dropped; no route starts in the same tick, so the dispatcher's watchdog sees it end. */
	private int navigationRestTick = -1;
	private String lastAction = "none";

	public CollectBlazeRodsTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftBlazeEnvironment(camera));
	}

	CollectBlazeRodsTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.COLLECT_BLAZE_RODS) {
			if (request != null) release();
			request = null;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			reset(((WorldTaskRequest.CollectBlazeRods) next.task()).args().count());
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate", null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", "", TaskFailureCode.ENVIRONMENT_CHANGED);
		Arena arena = environment.observe();
		if (arena == null) return finish(false, "world_unavailable", "", TaskFailureCode.ENVIRONMENT_CHANGED);
		activeTicks++;
		modeTicks++;
		rods = arena.rods();
		if (startRods < 0) startRods = rods;
		if (rods >= count) return finish(true, "blaze_rods_collected", "", null);
		if (!arena.inNether())
			return finish(false, "wrong_dimension", "blazes spawn only in Nether fortresses", TaskFailureCode.INVALID_ACTION);
		if (activeTicks > TIMEOUT_TICKS)
			return finish(false, "timeout", "the task stops after " + TIMEOUT_TICKS + " ticks; call it again to go on", TaskFailureCode.TRANSIENT);
		observeBlazes(arena);
		if (spawner == null && (activeTicks == 1 || activeTicks % SPAWNER_RESCAN_TICKS == 0))
			spawner = environment.blazeSpawner(arena.feet()).orElse(null);
		if (spawner == null && !everSawBlaze && activeTicks > LOOK_TICKS) {
			String fortress = environment.fortress(arena.feet())
				.map(pos -> "; nether bricks at " + compact(pos) + ": walk the fortress corridors toward open rooms (a blaze spawner sits on a "
					+ "raised platform reached by stairs), use find_world_features featureKind=blaze_spawner, and call again")
				.orElse("; no fortress in loaded chunks either: use explore_for featureKind=nether_fortress first");
			return finish(false, "no_blazes_found", "no blaze spawner or blaze in sight" + fortress, TaskFailureCode.MISSING_FACT);
		}
		if (idleTicks > NO_BLAZE_TICKS)
			return finish(false, "no_blazes_found", "no blaze in sight for " + NO_BLAZE_TICKS + " ticks at "
				+ (spawner == null ? "the spot where blazes were" : "the spawner at " + compact(spawner))
				+ "; a spawner spawns only in light 11 or darker with the body within 16 blocks", TaskFailureCode.ENVIRONMENT_CHANGED);
		if (mode != Mode.HEAL && arena.health() <= RETREAT_HEALTH) enter(Mode.HEAL);
		if (lastHealth >= 0.0F && arena.health() < lastHealth - 0.01F) lastHitTick = activeTicks;
		lastHealth = arena.health();
		boolean inSight = arena.blazes().stream().anyMatch(Blaze::visible);
		overwhelmed = engaged(arena).size() > MAX_ENGAGED;

		// Cover before the shield: shielding stops the walk, and with many blazes one fireball is always incoming.
		String need = coverNeed(arena, inSight);
		if (need != null) {
			if (mode == Mode.HEAL && arena.health() <= CRITICAL_HEALTH)
				return finish(false, "low_health", String.format(Locale.ROOT, "health=%.1f with blazes still in sight; back away out "
					+ "of the fortress and eat, then call again", arena.health()), TaskFailureCode.ENVIRONMENT_CHANGED);
			CoverStep step = takeCover(arena, need);
			if (step == CoverStep.MOVING) {
				// Using an item slows a walking body to a fifth of its speed: no shield, strafe or meal on the way.
				lowerShield();
				stopStrafe();
				stopConsuming();
				return running(arena);
			}
			if (step == CoverStep.NONE) {
				if (mode == Mode.HEAL)
					return finish(false, "low_health", String.format(Locale.ROOT, "health=%.1f and no cover out of the blazes' sight "
						+ "could be reached; back away and eat, then call again", arena.health()), TaskFailureCode.ENVIRONMENT_CHANGED);
				if (overwhelmed)
					return finish(false, "overwhelmed", String.format(Locale.ROOT, "%d blazes in sight within %d blocks and no cell out "
						+ "of most of their sight within %d blocks could be reached (health=%.1f); back out of their sight the way you "
						+ "came, then call again from a corridor corner where they come one at a time, or with a bow and arrows",
						engaged(arena).size(), (int) ENGAGE_RANGE, (int) COVER_RADIUS, arena.health()), TaskFailureCode.ENVIRONMENT_CHANGED);
				// Hit on the way in with nowhere to hide: go on to the spot; low health hands back.
			}
		}

		Blaze reach = nearest(arena, blaze -> blaze.visible() && blaze.inReach());
		if (defend(arena, reach)) return running(arena);
		if (reach != null) {
			stopConsuming();
			stopBow();
			stopNavigation();
			lastAction = "melee";
			if (environment.melee(reach.id()) == MeleeResult.HIT) meleeHits++;
			return running(arena);
		}
		Optional<TaskTerminalEvent> end = mode == Mode.HEAL ? heal(arena) : fight(arena);
		if (end.isPresent()) return end;
		return running(arena);
	}

	/**
	 * Why cover comes before anything else this tick, or null: hurt with blazes in sight, more than
	 * {@value #MAX_ENGAGED} blazes in sight, hit by one on the way in, or a walk to cover under way.
	 */
	private String coverNeed(Arena arena, boolean inSight) {
		if (mode == Mode.HEAL) return inSight ? "heal" : null;
		if (overwhelmed) return "thin_out";
		if (mode == Mode.APPROACH && inSight && lastHitTick >= 0 && activeTicks - lastHitTick <= UNDER_FIRE_TICKS) return "under_fire";
		if (mode == Mode.COVER && cover != null && !atSpot(arena.feet(), cover)) return coverReason;
		return null;
	}

	private static List<Blaze> engaged(Arena arena) {
		return arena.blazes().stream().filter(b -> b.visible() && b.distance() <= ENGAGE_RANGE).toList();
	}

	private Optional<TaskTerminalEvent> running(Arena arena) {
		if (!navigationOwned) navigationStall.clear();
		setSnapshot(TaskExecutionState.RUNNING, "collecting", arena);
		return Optional.empty();
	}

	/** Shield or strafe against a fireball about to hit; true when that took this tick. */
	private boolean defend(Arena arena, Blaze reach) {
		Fireball fireball = arena.fireballs().stream().filter(f -> incoming(arena.feet(), f))
			.min(Comparator.comparingDouble(f -> f.position().distanceToSqr(arena.feet()))).orElse(null);
		if (fireball == null) {
			if (strafeTicks > 0) {
				strafeTicks--;
				environment.strafe(strafeSide, strafeFace);
				return true;
			}
			stopStrafe();
			// A blaze in reach is hit at once; otherwise keep the shield up through the rest of the burst.
			if (shielding && reach == null && shieldHold-- > 0 && environment.raiseShield(shieldFace)) return true;
			lowerShield();
			return false;
		}
		Vec3 face = fireball.origin() != null ? fireball.origin() : fireball.position();
		stopBow();
		stopConsuming();
		if (arena.hasShield() && environment.raiseShield(face)) {
			stopStrafe();
			stopNavigation();
			if (!shielding) shieldRaises++;
			shielding = true;
			shieldHold = SHIELD_HOLD_TICKS;
			shieldFace = face;
			lastAction = "shield";
			return true;
		}
		lowerShield();
		if (strafeTicks > 0) {
			strafeTicks--;
			environment.strafe(strafeSide, strafeFace);
			return true;
		}
		Vec3 facing = facing(arena.feet(), fireball);
		if (facing == null) return false;
		StrafeOptions options = environment.strafeOptions(arena.feet(), facing);
		Side side = strafeSide(arena.feet(), fireball, options.leftSafe(), options.rightSafe());
		if (side == Side.NONE) return false;
		stopNavigation();
		strafes++;
		strafeSide = side;
		strafeFace = face;
		strafeTicks = STRAFE_TICKS - 1;
		lastAction = "strafe_" + side.name().toLowerCase(Locale.ROOT);
		environment.strafe(side, face);
		return true;
	}

	private Optional<TaskTerminalEvent> fight(Arena arena) {
		List<Blaze> engaged = engaged(arena);
		if (arena.onFire() && arena.hasFireResistancePotion() && !arena.fireResistant()) {
			// Every blaze hit and fireball sets the body alight; a potion ends that for the rest of the fight.
			stopNavigation();
			consume(Consumable.FIRE_RESISTANCE);
			return Optional.empty();
		}
		if (arena.blazes().stream().anyMatch(Blaze::visible) || !arena.droppedRods().isEmpty()) idleTicks = 0;
		else if (mode == Mode.HOLD) idleTicks++;
		if (pickup(arena)) return Optional.empty();
		if (mode == Mode.PICKUP) enter(Mode.APPROACH);
		if (mode == Mode.COVER) {
			// Blazes that lose sight of the body fly to where it was; give them time to come into reach. Out of their sight
			// they come around the corner one or two at a time, so while too many see the body it stays in cover.
			if ((modeTicks < COVER_HOLD_TICKS || overwhelmed) && takeCover(arena, coverReason) != CoverStep.NONE) return Optional.empty();
			enter(Mode.APPROACH);
		}
		if (spot == null) {
			anchor = spawner != null ? new Anchor(spawner, true) : blazeAnchor(arena);
			if (anchor == null) {
				stopNavigation();
				lastAction = "look";
				return Optional.empty();
			}
			List<Spot> spots = environment.fightingSpots(arena, anchor);
			spot = chooseFightingSpot(spots, arena.feet(), rejectedSpots).map(Spot::position).orElse(null);
			if (spot == null) {
				spotRefusals = refusals(spots, rejectedSpots);
				return finish(false, "no_safe_position", "no spot " + (int) SPOT_MIN_DISTANCE + ".." + (int) SPOT_MAX_DISTANCE
					+ " blocks from the " + (anchor.spawner() ? "spawner" : "blazes") + " at " + compact(anchor.position())
					+ " has solid floor, no lava or fire within two blocks, no drop deeper than " + MAX_SAFE_FALL
					+ " beside it and a clear view, reached with falls of at most " + MAX_SAFE_FALL + " blocks (" + spotRefusals
					+ "). Build a platform or wall off lava near the spawner, then call again", TaskFailureCode.ENVIRONMENT_CHANGED);
			}
			enter(Mode.APPROACH);
		}
		if (mode == Mode.APPROACH || horizontal(arena.feet(), center(spot)) > SPOT_LEASH) {
			if (atSpot(arena.feet(), spot)) {
				stopNavigation();
				enter(Mode.HOLD);
			} else {
				stopBow();
				lastAction = "approach";
				if (!navigate(spot) || navigationTicks > NAVIGATION_TIMEOUT_TICKS) {
					stopNavigation();
					rejectedSpots.add(spot);
					spot = null;
					spotFailures++;
					if (spotFailures >= SPOT_ATTEMPTS)
						return finish(false, "no_safe_position", spotFailures + " fighting spots near " + compact(anchor.position())
							+ " could not be reached with falls of at most " + MAX_SAFE_FALL + " blocks; dig a staircase down to the spawner "
							+ "room's level with navigate_to, then call again", TaskFailureCode.ENVIRONMENT_CHANGED);
				}
				return Optional.empty();
			}
		}
		stopNavigation();
		Blaze target = !arena.hasBow() ? null : engaged.stream().filter(b -> noArcUntil.getOrDefault(b.id(), 0) < activeTicks)
			.filter(b -> weapon(b, true) == Weapon.BOW).min(Comparator.comparingDouble(Blaze::distance)).orElse(null);
		if (target != null) {
			shoot(target);
			return Optional.empty();
		}
		stopBow();
		if (engaged.isEmpty()) {
			lastAction = "wait_for_spawn";
			return Optional.empty();
		}
		// No bow, or no clear arc: out of sight a blaze flies in to look for the body, into reach.
		if (takeCover(arena, "ambush") == CoverStep.NONE) lastAction = "wait_in_the_open";
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> heal(Arena arena) {
		if (arena.health() >= RESUME_HEALTH) {
			stopConsuming();
			enter(Mode.APPROACH);
			return Optional.empty();
		}
		if (modeTicks > HEAL_TIMEOUT_TICKS)
			return finish(false, "low_health", String.format(Locale.ROOT, "health=%.1f did not recover in %d ticks behind cover; eat, "
				+ "then call again", arena.health(), HEAL_TIMEOUT_TICKS), TaskFailureCode.ENVIRONMENT_CHANGED);
		// The cover step ran first: here the body is out of sight, or in the cell the fewest blazes see.
		stopNavigation();
		if (arena.onFire() && arena.hasFireResistancePotion() && !arena.fireResistant()) {
			consume(Consumable.FIRE_RESISTANCE);
			return Optional.empty();
		}
		if (arena.food() < FULL_FOOD && arena.hasFood()) {
			consume(Consumable.FOOD);
			return Optional.empty();
		}
		stopConsuming();
		if (!arena.hasFood() && arena.food() < REGEN_FOOD)
			return finish(false, "out_of_food", String.format(Locale.ROOT, "health=%.1f food=%d and nothing to eat; health does not "
				+ "come back below %d food. Bring cooked food, then call again", arena.health(), arena.food(), REGEN_FOOD),
				TaskFailureCode.MISSING_ITEM);
		lastAction = "regenerate";
		return Optional.empty();
	}

	/**
	 * Walks to or waits at the cover cell the fewest blazes see. NONE when there is none: no cell found (searched again
	 * after {@value #COVER_RETRY_TICKS} ticks), or {@value #COVER_ATTEMPTS} walks failed.
	 */
	private CoverStep takeCover(Arena arena, String reason) {
		Vec3 feet = arena.feet();
		int shooters = (int) arena.blazes().stream().filter(b -> b.visible() && b.distance() > AMBUSH_DISTANCE).count();
		if (cover != null && atSpot(feet, cover) && shooters > coverSeenBy && activeTicks - coverPickedTick >= COVER_REPICK_TICKS) {
			// More blazes see this cell than when it was picked: move on if another cell is seen by fewer, else stay.
			coverPickedTick = activeTicks;
			Spot better = chooseCover(environment.coverSpots(arena), feet, shooters, rejectedCovers)
				.filter(next -> next.blazesInSight() < shooters && !next.position().equals(cover)).orElse(null);
			if (better != null) {
				cover = better.position();
				coverSeenBy = better.blazesInSight();
			}
		}
		if (cover == null) {
			if (coverFailures >= COVER_ATTEMPTS || activeTicks < noCoverUntil) return CoverStep.NONE;
			int inSight = (int) arena.blazes().stream().filter(Blaze::visible).count();
			Spot next = chooseCover(environment.coverSpots(arena), feet, inSight, rejectedCovers).orElse(null);
			if (next == null) {
				noCoverUntil = activeTicks + COVER_RETRY_TICKS;
				return CoverStep.NONE;
			}
			if (mode != Mode.COVER && mode != Mode.HEAL) enter(Mode.COVER);
			cover = next.position();
			coverSeenBy = next.blazesInSight();
			coverPickedTick = activeTicks;
		}
		coverReason = reason;
		stopBow();
		lastAction = "cover_" + reason;
		if (atSpot(feet, cover)) {
			stopNavigation();
			coverFailures = 0;
			return CoverStep.HIDDEN;
		}
		if (!navigate(cover, COVER_OPTIONS) || navigationTicks > COVER_WALK_TICKS) {
			stopNavigation();
			rejectedCovers.add(cover);
			cover = null;
			coverFailures++;
			return coverFailures < COVER_ATTEMPTS ? CoverStep.MOVING : CoverStep.NONE;
		}
		return CoverStep.MOVING;
	}

	/** Walks over a dropped rod when that is safe; false when there is none to fetch. */
	private boolean pickup(Arena arena) {
		if (pickupTarget != null) {
			Vec3 target = pickupTarget;
			boolean present = arena.droppedRods().stream().anyMatch(rod -> rod.position().distanceTo(target) <= 1.5D);
			if (!present) pickupTarget = null;
			else if (++pickupTicks > PICKUP_TIMEOUT_TICKS) {
				skippedRods.add(target);
				pickupTarget = null;
				stopNavigation();
			}
		}
		if (pickupTarget == null) {
			pickupTarget = arena.droppedRods().stream()
				.filter(rod -> skippedRods.stream().noneMatch(skipped -> skipped.distanceTo(rod.position()) <= 1.5D))
				.filter(rod -> rod.position().distanceTo(arena.feet()) <= PICKUP_RADIUS)
				.filter(rod -> safeToFetch(rod, arena.blazes(), arena.feet()))
				.min(Comparator.comparingDouble(rod -> rod.position().distanceToSqr(arena.feet())))
				.map(DroppedRod::position).orElse(null);
			pickupTicks = 0;
			if (pickupTarget == null) return false;
		} else {
			Vec3 target = pickupTarget;
			DroppedRod rod = arena.droppedRods().stream().filter(r -> r.position().distanceTo(target) <= 1.5D).findFirst().orElse(null);
			if (rod == null || !safeToFetch(rod, arena.blazes(), arena.feet())) {
				pickupTarget = null;
				stopNavigation();
				return false;
			}
		}
		if (mode != Mode.PICKUP) enter(Mode.PICKUP);
		stopBow();
		lastAction = "pickup";
		GoalPosition goal = new GoalPosition((int) Math.floor(pickupTarget.x), (int) Math.floor(pickupTarget.y + 0.25D),
			(int) Math.floor(pickupTarget.z), true);
		if (!navigate(goal)) {
			skippedRods.add(pickupTarget);
			pickupTarget = null;
			stopNavigation();
		}
		return true;
	}

	private void shoot(Blaze target) {
		if (bowTarget != target.id()) {
			stopBow();
			bowTarget = target.id();
		}
		lastAction = "shoot";
		AimResult aim = environment.aimBow(target.id());
		if (aim == AimResult.NO_SOLUTION) {
			stopBow();
			noArcUntil.put(target.id(), activeTicks + NO_ARC_TICKS);
			return;
		}
		if (!environment.drawBow()) {
			stopBow();
			noArcUntil.put(target.id(), activeTicks + NO_ARC_TICKS);
			return;
		}
		drawTicks++;
		if (drawTicks >= FULL_DRAW_TICKS && (aim == AimResult.ALIGNED || drawTicks >= MAX_AIM_TICKS)) {
			environment.releaseBow();
			arrowsShot++;
			drawTicks = 0;
		}
	}

	private void consume(Consumable kind) {
		stopBow();
		lastAction = kind == Consumable.FOOD ? "eat" : "drink_fire_resistance";
		ConsumeResult result = environment.consume(kind);
		if (result == ConsumeResult.DONE) {
			consuming = false;
			eats++;
		} else consuming = result == ConsumeResult.CONSUMING;
	}

	private void observeBlazes(Arena arena) {
		Set<Integer> present = new HashSet<>();
		for (Blaze blaze : arena.blazes()) {
			present.add(blaze.id());
			knownBlazes.put(blaze.id(), blaze.position());
			if (blaze.visible()) everSawBlaze = true;
		}
		var it = knownBlazes.entrySet().iterator();
		while (it.hasNext()) {
			var known = it.next();
			if (present.contains(known.getKey())) continue;
			it.remove();
			noArcUntil.remove(known.getKey());
			if (known.getValue().distanceTo(arena.feet()) <= KILL_DISTANCE) kills++;
		}
	}

	private Anchor blazeAnchor(Arena arena) {
		return arena.blazes().stream().filter(Blaze::visible).min(Comparator.comparingDouble(Blaze::distance))
			.map(blaze -> new Anchor(new GoalPosition((int) Math.floor(blaze.position().x), (int) Math.floor(blaze.position().y),
				(int) Math.floor(blaze.position().z), true), false))
			.orElse(null);
	}

	private void enter(Mode next) {
		if (mode != next) {
			stopNavigation();
			// A cover walk under way when health drops goes on as the heal cover; anything else starts the search afresh.
			boolean keepCover = next == Mode.COVER || next == Mode.HEAL && mode == Mode.COVER;
			if (!keepCover) {
				cover = null;
				coverReason = null;
				coverFailures = 0;
				noCoverUntil = 0;
				rejectedCovers.clear();
			}
			if (mode == Mode.PICKUP) pickupTarget = null;
		}
		mode = next;
		modeTicks = 0;
	}

	// ---- pure decisions, tested directly ----

	/** Why a fighting spot is refused, or null when it is acceptable. */
	static String refusal(Spot spot, Set<GoalPosition> rejected) {
		if (!spot.standable()) return "not_standable";
		if (spot.hazardNear()) return "lava_or_fire";
		if (spot.edgeDrop()) return "edge_drop";
		if (!spot.sightToAnchor()) return "no_sight";
		if (spot.anchorDistance() < SPOT_MIN_DISTANCE) return "too_close";
		if (spot.anchorDistance() > SPOT_MAX_DISTANCE) return "too_far";
		if (rejected.contains(spot.position())) return "unreachable";
		return null;
	}

	/** The safe spot with a view of the spawner that is near the preferred range, has the most cover and is near the body. */
	static Optional<Spot> chooseFightingSpot(List<Spot> spots, Vec3 feet, Set<GoalPosition> rejected) {
		return spots.stream().filter(spot -> refusal(spot, rejected) == null)
			.min(Comparator.comparingDouble((Spot spot) -> spotCost(spot, feet))
				.thenComparingInt(spot -> spot.position().x()).thenComparingInt(spot -> spot.position().z())
				.thenComparingInt(spot -> spot.position().y()));
	}

	static double spotCost(Spot spot, Vec3 feet) {
		return center(spot.position()).distanceTo(feet) * 0.25D + Math.abs(spot.anchorDistance() - SPOT_PREFERRED_DISTANCE)
			- Math.min(spot.cover(), 4) * 1.5D;
	}

	/**
	 * The cover cell, by the reflex's {@code RangedCover} rule: safe, within reach and not a failed walk; seen by fewer
	 * blazes than the {@code shooters} that see the body now (by none when one does) and by fewer than
	 * {@value #MAX_ENGAGED}; then the fewest blazes, then walls and roof, then a short walk. Asking for a cell no blaze
	 * at all sees found nothing in a fortress room where the reflex found one seen by two of eight.
	 */
	static Optional<Spot> chooseCover(List<Spot> spots, Vec3 feet, int shooters, Set<GoalPosition> rejected) {
		int allowedSeenBy = Math.min(Math.max(1, shooters), MAX_ENGAGED);
		return spots.stream()
			.filter(spot -> spot.standable() && !spot.hazardNear() && !spot.edgeDrop() && spot.blazesInSight() < allowedSeenBy)
			.filter(spot -> center(spot.position()).distanceTo(feet) <= COVER_RADIUS + 1.0D && !rejected.contains(spot.position()))
			.min(Comparator.comparingInt(Spot::blazesInSight)
				.thenComparing(Comparator.comparingDouble((Spot spot) -> coverScore(spot, feet)).reversed())
				.thenComparingInt(spot -> spot.position().x()).thenComparingInt(spot -> spot.position().z())
				.thenComparingInt(spot -> spot.position().y()));
	}

	/** Higher is better: walls and roof, as {@code RangedCover} weighs them, then a short walk. */
	static double coverScore(Spot spot, Vec3 feet) {
		return Math.min(spot.cover(), 4) * 1.5D - center(spot.position()).distanceTo(feet) * 0.5D;
	}

	static Weapon weapon(Blaze target, boolean hasBow) {
		if (target.inReach()) return Weapon.MELEE;
		if (hasBow && target.visible() && target.distance() >= BOW_MIN_DISTANCE && target.distance() <= BOW_MAX_DISTANCE) return Weapon.BOW;
		return Weapon.NONE;
	}

	/** Whether a small fireball's straight path passes through the body. */
	static boolean incoming(Vec3 feet, Fireball fireball) {
		Vec3 body = feet.add(0.0D, 0.9D, 0.0D);
		Vec3 relative = body.subtract(fireball.position());
		if (relative.length() > FIREBALL_WATCH_DISTANCE || fireball.velocity().lengthSqr() < 1.0E-4D) return false;
		Vec3 direction = fireball.velocity().normalize();
		double along = relative.dot(direction);
		if (along < 0.0D) return false;
		return relative.subtract(direction.scale(along)).length() <= FIREBALL_HIT_DISTANCE;
	}

	/** The horizontal direction to face while strafing: back along the fireball's flight. Null when it falls straight down. */
	static Vec3 facing(Vec3 feet, Fireball fireball) {
		Vec3 back = new Vec3(-fireball.velocity().x, 0.0D, -fireball.velocity().z);
		if (back.lengthSqr() < 0.0025D) back = new Vec3(fireball.position().x - feet.x, 0.0D, fireball.position().z - feet.z);
		return back.lengthSqr() < 1.0E-4D ? null : back.normalize();
	}

	/** Strafes away from the side of the line the fireball flies on, onto safe ground only. */
	static Side strafeSide(Vec3 feet, Fireball fireball, boolean leftSafe, boolean rightSafe) {
		Vec3 face = facing(feet, fireball);
		if (face == null) return Side.NONE;
		Vec3 left = new Vec3(face.z, 0.0D, -face.x);
		Vec3 relative = new Vec3(feet.x - fireball.position().x, 0.0D, feet.z - fireball.position().z);
		Side preferred = relative.dot(left) >= 0.0D ? Side.LEFT : Side.RIGHT;
		Side other = preferred == Side.LEFT ? Side.RIGHT : Side.LEFT;
		if (preferred == Side.LEFT ? leftSafe : rightSafe) return preferred;
		if (other == Side.LEFT ? leftSafe : rightSafe) return other;
		return Side.NONE;
	}

	/** A rod on safe ground with no blaze in sight near it or the body. */
	static boolean safeToFetch(DroppedRod rod, List<Blaze> blazes, Vec3 feet) {
		return rod.safe() && blazes.stream().noneMatch(blaze -> blaze.visible()
			&& (blaze.position().distanceTo(rod.position()) <= PICKUP_BLAZE_CLEARANCE || blaze.distance() <= PICKUP_BLAZE_CLEARANCE));
	}

	private static String refusals(List<Spot> spots, Set<GoalPosition> rejected) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (Spot spot : spots) {
			String reason = refusal(spot, rejected);
			if (reason != null) counts.merge(reason, 1, Integer::sum);
		}
		StringBuilder text = new StringBuilder("candidates=" + spots.size());
		counts.forEach((reason, n) -> text.append(' ').append(reason).append('=').append(n));
		return text.toString();
	}

	// ---- navigation and actuators ----

	private boolean navigate(GoalPosition goal) {
		return navigate(goal, OPTIONS);
	}

	private boolean navigate(GoalPosition goal, NavigationOptions options) {
		if (navigationRestTick == activeTicks) return true;
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, options);
			navigationOwned = true;
			navigationGoal = goal;
			navigationTicks = 0;
		}
		navigationTicks++;
		String event = navigation.pollPathEvent().orElse("");
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) {
			stopNavigation();
			navigationStall.clear();
			navigationRestTick = activeTicks;
			return false;
		}
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		navigationGoal = null;
		navigationTicks = 0;
	}

	private void stopBow() {
		if (drawTicks > 0) environment.cancelBow();
		drawTicks = 0;
	}

	private void stopConsuming() {
		if (consuming) environment.stopConsuming();
		consuming = false;
	}

	private void lowerShield() {
		if (shielding) environment.lowerShield();
		shielding = false;
		shieldHold = 0;
	}

	private void stopStrafe() {
		if (strafeTicks > 0 || strafeSide != Side.NONE) environment.stopMoving();
		strafeTicks = 0;
		strafeSide = Side.NONE;
	}

	private void release() {
		stopBow();
		stopConsuming();
		lowerShield();
		stopStrafe();
		stopNavigation();
		environment.release();
	}

	private void reset(int requested) {
		terminal = null;
		emitted = false;
		mode = Mode.APPROACH;
		activeTicks = modeTicks = idleTicks = 0;
		count = requested;
		rods = 0;
		startRods = -1;
		anchor = null;
		spawner = null;
		everSawBlaze = false;
		spot = null;
		spotFailures = 0;
		rejectedSpots.clear();
		spotRefusals = "";
		cover = null;
		coverSeenBy = coverPickedTick = noCoverUntil = 0;
		coverFailures = 0;
		coverReason = null;
		rejectedCovers.clear();
		lastHealth = -1.0F;
		lastHitTick = -1;
		overwhelmed = false;
		pickupTarget = null;
		pickupTicks = 0;
		skippedRods.clear();
		knownBlazes.clear();
		noArcUntil.clear();
		kills = meleeHits = arrowsShot = shieldRaises = strafes = eats = 0;
		shielding = false;
		shieldHold = 0;
		shieldFace = null;
		strafeTicks = 0;
		strafeSide = Side.NONE;
		strafeFace = null;
		drawTicks = 0;
		bowTarget = -1;
		consuming = false;
		navigationStall.clear();
		navigationRestTick = -1;
		lastAction = "none";
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, String detail, TaskFailureCode failureCode) {
		release();
		String summary = reason + " rods=" + rods + (detail.isEmpty() ? "" : "; " + detail)
			+ " requested=" + count + " gained=" + Math.max(0, rods - Math.max(0, startRods)) + " kills=" + kills
			+ " meleeHits=" + meleeHits + " arrowsShot=" + arrowsShot + " shieldRaises=" + shieldRaises + " strafes=" + strafes
			+ " eaten=" + eats + " rodsLeft=" + skippedRods.size()
			+ (spawner == null ? "" : " spawner=" + compact(spawner)) + (spot == null ? "" : " spot=" + compact(spot));
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			summary, success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "CollectBlazeRods",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Arena arena) {
		String detail = reason + " mode=" + mode + " action=" + lastAction + " rods=" + rods + "/" + count;
		if (arena != null) {
			detail += String.format(Locale.ROOT, " feet=%.1f,%.1f,%.1f health=%.1f food=%d", arena.feet().x, arena.feet().y,
				arena.feet().z, arena.health(), arena.food())
				+ " blazesInSight=" + arena.blazes().stream().filter(Blaze::visible).count()
				+ " fireballs=" + arena.fireballs().size() + " droppedRods=" + arena.droppedRods().size();
		}
		detail += " kills=" + kills + " meleeHits=" + meleeHits + " arrowsShot=" + arrowsShot + " shieldRaises=" + shieldRaises
			+ " strafes=" + strafes + " spawner=" + (spawner == null ? "unknown" : compact(spawner))
			+ " spot=" + (spot == null ? "none" : compact(spot)) + " cover=" + (cover == null ? "none" : compact(cover))
			+ " activeTicks=" + activeTicks;
		snapshot = new TaskExecutionSnapshot(state, request.taskId(), null, "CollectBlazeRods", detail, null, null);
	}

	private static Blaze nearest(Arena arena, java.util.function.Predicate<Blaze> filter) {
		return arena.blazes().stream().filter(filter).min(Comparator.comparingDouble(Blaze::distance)).orElse(null);
	}

	private static boolean atSpot(Vec3 feet, GoalPosition spot) {
		return horizontal(feet, center(spot)) <= 0.8D && Math.abs(feet.y - spot.y()) <= 1.0D;
	}

	private static Vec3 center(GoalPosition position) {
		return new Vec3(position.x() + 0.5D, position.y(), position.z() + 0.5D);
	}

	private static double horizontal(Vec3 a, Vec3 b) {
		return Math.hypot(a.x - b.x, a.z - b.z);
	}

	private static String compact(GoalPosition position) {
		return position.x() + "," + position.y() + "," + position.z();
	}

	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	/** The reflex hands the same task back: keep the spawner, the spot, rejected spots and the counters; let go of the bow,
	 * shield, meal, strafe and route, which the next tick takes up again. */
	@Override
	public void pauseForReflex() {
		if (request == null) return;
		release();
		navigationStall.clear();
		navigationRestTick = -1;
		// Hits taken and blazes lost from view while the reflex had the body are not this fight's evidence.
		lastHealth = -1.0F;
		knownBlazes.clear();
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}
	@Override public void shutdown() { onWorldLeave(); }

	enum Mode { APPROACH, HOLD, COVER, PICKUP, HEAL }
	enum CoverStep { MOVING, HIDDEN, NONE }
	enum Weapon { MELEE, BOW, NONE }
	enum Side { LEFT, RIGHT, NONE }
	enum AimResult { NO_SOLUTION, AIMING, ALIGNED }
	enum MeleeResult { OUT_OF_REACH, AIMING, COOLDOWN, HIT }
	enum Consumable { FOOD, FIRE_RESISTANCE }
	enum ConsumeResult { NOTHING, CONSUMING, DONE }

	/** A live blaze. {@code position} is its body centre; {@code distance} is from the eyes; {@code visible} is line of sight. */
	record Blaze(int id, Vec3 position, double distance, boolean visible, boolean inReach) {}
	/** A small fireball in flight; {@code origin} is the blaze that shot it, when known. */
	record Fireball(Vec3 position, Vec3 velocity, Vec3 origin) {}
	/** A dropped blaze rod; {@code safe} when it rests on solid ground with no lava or fire beside it and no drop under it. */
	record DroppedRod(Vec3 position, boolean safe) {}
	/** What the fighting spots are chosen around: a blaze spawner, or where a blaze was seen. */
	record Anchor(GoalPosition position, boolean spawner) {}
	/**
	 * A standing cell near the fight. {@code hazardNear}: lava, fire or magma within two blocks. {@code edgeDrop}: a
	 * neighbouring cell drops deeper than {@link #MAX_SAFE_FALL}, where a blaze's knockback throws the body.
	 * {@code cover}: solid walls beside the head and a roof over it, which break fireball lines.
	 */
	record Spot(GoalPosition position, boolean standable, boolean hazardNear, boolean edgeDrop, boolean sightToAnchor,
		int blazesInSight, int cover, double anchorDistance) {}
	record StrafeOptions(boolean leftSafe, boolean rightSafe) {}
	/** One tick of the fight as the client sees it. {@code hasBow} means a bow and arrows. */
	record Arena(boolean inNether, Vec3 feet, float health, int food, boolean onFire, boolean fireResistant, int rods,
		List<Blaze> blazes, List<Fireball> fireballs, List<DroppedRod> droppedRods, boolean hasBow, boolean hasShield,
		boolean hasFood, boolean hasFireResistancePotion) {}

	interface Environment {
		/** Null when no world is loaded. */
		Arena observe();
		/** The nearest blaze spawner with a face open to air in loaded chunks. */
		Optional<GoalPosition> blazeSpawner(Vec3 feet);
		/** Exposed nether bricks in loaded chunks, for the failure hint. */
		Optional<GoalPosition> fortress(Vec3 feet);
		/** Standing cells around the anchor, with their facts. */
		List<Spot> fightingSpots(Arena arena, Anchor anchor);
		/**
		 * Standing cells within {@link #COVER_RADIUS} of the body, {@link #COVER_MAX_RISE} up and {@link #COVER_MAX_DROP}
		 * down, with how many blazes see each.
		 */
		List<Spot> coverSpots(Arena arena);
		/** Whether the ground a step and a half to each side of {@code facing} is safe to strafe onto. */
		StrafeOptions strafeOptions(Vec3 feet, Vec3 facing);
		/** Faces {@code face} and holds the shield up; false when no shield can be raised. */
		boolean raiseShield(Vec3 face);
		void lowerShield();
		/** Faces {@code face} and steps sideways for one tick. */
		void strafe(Side side, Vec3 face);
		void stopMoving();
		MeleeResult melee(int blazeId);
		/** Turns toward where a full-draw arrow meets the blaze, leading its flight. */
		AimResult aimBow(int blazeId);
		/** Holds the bow drawn; false when no bow can be held. */
		boolean drawBow();
		void releaseBow();
		void cancelBow();
		/** Eats or drinks one tick; DONE when an item was used up. */
		ConsumeResult consume(Consumable kind);
		void stopConsuming();
		void release();
	}
}
