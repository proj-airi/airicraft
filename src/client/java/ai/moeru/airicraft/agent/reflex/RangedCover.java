package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.tasks.CoverCells;
import ai.moeru.airicraft.navigation.GridPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Hides an unarmed body from archers. In a playtest a body with nothing but five red sand ran from a skeleton by day,
 * gave up running when a second one appeared, could not dig in without a roof block, and threw 24 punches at the two
 * while four arrows took it from 20 health to death. Bare hands never reach a skeleton that backs off and shoots; a
 * wall between the body and its eyes stops the arrows. So without a real melee weapon (below
 * {@link UnarmedFlight#MIN_WEAPON_DAMAGE}) or a bow shot, with no melee mob within {@value #MELEE_PRIORITY_DISTANCE}
 * blocks and no creeper within {@value #CREEPER_DISTANCE}, the reflex walks to a cell within {@value #RADIUS} blocks
 * that no shooter's eye can see and stands there. It is the first answer to shooters alone and the fallback once a
 * flight from a mixed group has been given up. Skeletons burn by day, lose interest or walk round the wall; a cell a
 * shooter can see again is swapped for another. Client thread only, apart from the static rules.
 */
final class RangedCover {
	static final int RADIUS = 8;
	static final int MAX_RISE = 2;
	static final int MAX_DROP = UnarmedFlight.MAX_DROP;
	/** A melee mob this close is a fight (or a flight) now, not something to hide from. */
	static final double MELEE_PRIORITY_DISTANCE = 4.0D;
	/** Creepers keep the creeper run. */
	static final double CREEPER_DISTANCE = 8.0D;
	/** A cover cell this close to any threat is no hiding place. */
	static final double MIN_THREAT_DISTANCE = 4.0D;
	static final double COVER_WEIGHT = 1.5D;
	static final double WALK_WEIGHT = 0.5D;
	static final double AWAY_WEIGHT = 0.25D;
	static final double AWAY_CAP = 16.0D;
	/** A walk that has not reached its cell after this long has failed. */
	static final int WALK_TIMEOUT_TICKS = 100;
	static final int MAX_FAILED_PICKS = 3;
	/** A fresh cell is kept at least this long before a shooter seeing it again makes the body move on. */
	static final int REPICK_MIN_TICKS = 20;
	static final int CLEAR_TICKS = UnarmedFlight.CLEAR_TICKS;
	static final int MAX_TICKS = 1200;
	/** Ticks before cover is tried again after no cell could be found or reached. */
	static final long RETRY_TICKS = 100L;
	/** Ticks before cover is tried again after something else (a close mob, a weapon, a bow shot) took over. */
	static final long PAUSE_TICKS = 40L;
	static final String RANGED_UNARMED = "ranged_unarmed";
	static final String FLIGHT_ABANDONED = "flight_abandoned";

	/**
	 * One standable cell as a hiding place: whether lava, fire or magma is near, whether a knockback could drop the
	 * body deeper than the safe fall, how many shooters see its eye, solid blocks beside the head (one each) and over
	 * it (two), the walk there and the distance to the nearest threat.
	 */
	record Candidate(GridPos cell, boolean hazardNear, boolean edgeDrop, int seenBy, int cover, double walk, double nearestThreat) { }

	enum Outcome { MOVING, HIDDEN, CLEAR, ABANDONED }

	private boolean active;
	private long startedTick;
	private long retryAfterTick = Long.MIN_VALUE;
	private long pickedTick;
	private long walkStartedTick;
	private long clearSinceTick = -1L;
	private boolean walking;
	private Candidate pick;
	private int picks;
	private int failedPicks;
	private String abandonReason;
	private final Set<GridPos> rejected = new HashSet<>();

	/**
	 * What keeps the reflex from hiding from these threats, or null when cover is the answer: a real weapon, a bow
	 * shot, a melee mob close enough to hit, a creeper near, or no shooter at all. An empty list blocks nothing, so a
	 * body in cover waits out the threats leaving.
	 */
	static String blocker(double weaponDamage, boolean bowShot, List<UnarmedFlight.Threat> threats) {
		if (threats.isEmpty()) return null;
		if (weaponDamage >= UnarmedFlight.MIN_WEAPON_DAMAGE) return "armed";
		if (bowShot) return "bow_ready";
		boolean ranged = false;
		for (UnarmedFlight.Threat threat : threats) {
			if (threat.creeper() && threat.distance() <= CREEPER_DISTANCE) return "creeper";
			if (!threat.ranged() && threat.distance() <= MELEE_PRIORITY_DISTANCE) return "melee_close";
			ranged |= threat.ranged();
		}
		return ranged ? null : "no_ranged_threat";
	}

	/**
	 * Why to start taking cover now, or null: nothing blocks it, a shooter has the body in sight, and either every
	 * threat is a shooter (cover comes before running from them) or a flight from a mixed group has been given up.
	 */
	static String startReason(double weaponDamage, boolean bowShot, List<UnarmedFlight.Threat> threats, boolean flightGivenUp) {
		if (threats.isEmpty() || blocker(weaponDamage, bowShot, threats) != null) return null;
		if (threats.stream().noneMatch(threat -> threat.ranged() && threat.lineOfSight())) return null;
		if (threats.stream().allMatch(UnarmedFlight.Threat::ranged)) return RANGED_UNARMED;
		return flightGivenUp ? FLIGHT_ABANDONED : null;
	}

	/** Higher is better among cells seen by as many shooters: cover, then a short walk, then distance from the threats. */
	static double score(Candidate candidate) {
		return candidate.cover() * COVER_WEIGHT - candidate.walk() * WALK_WEIGHT
			+ Math.min(candidate.nearestThreat(), AWAY_CAP) * AWAY_WEIGHT;
	}

	/**
	 * The cell to hide in: safe (no lava, fire or magma near, no deep drop beside it), within reach, not beside a
	 * threat and not one the walk already failed to reach; seen by the fewest shooters, and by fewer than all of them
	 * (so by none when there is one), then the best {@link #score}.
	 */
	static Optional<Candidate> choose(List<Candidate> candidates, int shooters, Set<GridPos> rejected) {
		int allowedSeenBy = Math.max(1, shooters);
		return candidates.stream()
			.filter(candidate -> !candidate.hazardNear() && !candidate.edgeDrop())
			.filter(candidate -> candidate.walk() <= RADIUS + 1.0D && candidate.nearestThreat() >= MIN_THREAT_DISTANCE)
			.filter(candidate -> candidate.seenBy() < allowedSeenBy && !rejected.contains(candidate.cell()))
			.min(Comparator.comparingInt(Candidate::seenBy).thenComparing(Comparator.comparingDouble(RangedCover::score).reversed()));
	}

	/** Whether the feet are in the cell, or within a little over half a block of its centre at its level. */
	static boolean atCell(GridPos cell, double x, double y, double z) {
		if (Math.abs(y - cell.y()) > 1.0D) return false;
		boolean same = (int) Math.floor(x) == cell.x() && (int) Math.floor(z) == cell.z() && (int) Math.floor(y + 0.1D) == cell.y();
		return same || Math.hypot(cell.x() + 0.5D - x, cell.z() + 0.5D - z) <= 0.6D;
	}

	boolean active() {
		return active;
	}

	/** Not while hiding already, nor during the pause after giving up or stepping aside. */
	boolean mayStart(long tick) {
		return !active && tick >= retryAfterTick;
	}

	String abandonReason() {
		return abandonReason;
	}

	void start(long tick) {
		active = true;
		startedTick = tick;
		pick = null;
		walking = false;
		picks = 0;
		failedPicks = 0;
		clearSinceTick = -1L;
		abandonReason = null;
		rejected.clear();
	}

	/** Ends any cover and forgets pauses, for a new danger episode or another behaviour taking over. */
	void reset(NavigationFacade navigation) {
		stop(navigation);
		retryAfterTick = Long.MIN_VALUE;
		abandonReason = null;
	}

	/** Stops hiding. Giving up (no cell found or reached, timeout) pauses longer than stepping aside for another behaviour. */
	void abandon(String reason, long tick, boolean gaveUp, NavigationFacade navigation) {
		stop(navigation);
		abandonReason = reason;
		retryAfterTick = tick + (gaveUp ? RETRY_TICKS : PAUSE_TICKS);
	}

	private void stop(NavigationFacade navigation) {
		if (active && walking && navigation != null) navigation.cancel();
		active = false;
		walking = false;
		pick = null;
	}

	Map<String, Object> summary(long tick) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("picks", picks);
		summary.put("failedPicks", failedPicks);
		summary.put("ticks", tick - startedTick);
		return summary;
	}

	/**
	 * Runs one tick. {@code threats} are every threat as the flight sees them; {@code shooterEyes} the eyes of the
	 * ranged ones; {@code shootersInSight} how many of those see the body now. A new cell goes into {@code events} as
	 * {@code reflex.took_cover}; on {@code ABANDONED} the reason is {@link #abandonReason()}.
	 */
	Outcome tick(Level level, LocalPlayer player, List<UnarmedFlight.Threat> threats, List<Vec3> shooterEyes,
		int shootersInSight, String startReason, long tick, boolean recentlyHit, NavigationFacade navigation,
		List<SurvivalReflexEvent> events) {
		if (UnarmedFlight.clear(threats) && !recentlyHit) {
			if (clearSinceTick < 0) clearSinceTick = tick;
			if (tick - clearSinceTick >= CLEAR_TICKS) {
				stop(navigation);
				return Outcome.CLEAR;
			}
		}
		else clearSinceTick = -1L;
		if (tick - startedTick > MAX_TICKS) return giveUp("timeout", tick, navigation);
		double px = player.getX(), py = player.getY(), pz = player.getZ();
		String why = pick == null ? (startReason == null ? "start" : startReason) : null;
		if (pick != null) {
			boolean at = atCell(pick.cell(), px, py, pz);
			if (at) {
				if (walking) navigation.cancel();
				walking = false;
				if (tick - pickedTick >= REPICK_MIN_TICKS && shootersInSight > pick.seenBy()) why = "exposed";
			}
			else if (walking && (!navigation.processActive() || tick - walkStartedTick > WALK_TIMEOUT_TICKS)) {
				navigation.cancel();
				walking = false;
				rejected.add(pick.cell());
				if (++failedPicks >= MAX_FAILED_PICKS) return giveUp("unreachable", tick, navigation);
				why = "walk_failed";
			}
			else if (tick - pickedTick >= REPICK_MIN_TICKS && seenBy(level, player, pick.cell(), shooterEyes) > pick.seenBy()) {
				why = "exposed";
			}
		}
		if (why != null) {
			Candidate next = choose(candidates(level, player, threats, shooterEyes), shooterEyes.size(), rejected).orElse(null);
			if (next == null) {
				if (pick != null && !"walk_failed".equals(why)) {
					// Nowhere better: keep the cell rather than stand in the open.
					pickedTick = tick;
				}
				else return giveUp("no_cover", tick, navigation);
			}
			else {
				if (walking) navigation.cancel();
				walking = false;
				pick = next;
				pickedTick = tick;
				picks++;
				events.add(new SurvivalReflexEvent("reflex.took_cover", Map.of(
					"cell", new GoalPosition(next.cell().x(), next.cell().y(), next.cell().z(), true),
					"threatCount", threats.size(), "shooterCount", shooterEyes.size(), "seenBy", next.seenBy(),
					"cover", next.cover(), "walk", Math.round(next.walk() * 10.0D) / 10.0D, "reason", why,
					"health", player.getHealth(), "tick", tick)));
			}
		}
		if (atCell(pick.cell(), px, py, pz)) return Outcome.HIDDEN;
		if (!walking) {
			navigation.startNavigate(new GoalPosition(pick.cell().x(), pick.cell().y(), pick.cell().z(), true), UnarmedFlight.OPTIONS);
			walking = true;
			walkStartedTick = tick;
		}
		return Outcome.MOVING;
	}

	private Outcome giveUp(String reason, long tick, NavigationFacade navigation) {
		abandon(reason, tick, true, navigation);
		return Outcome.ABANDONED;
	}

	/**
	 * Every standable cell within {@value #RADIUS} blocks across, {@value #MAX_RISE} up and {@value #MAX_DROP} down
	 * from the feet, as a hiding place.
	 */
	static List<Candidate> candidates(Level level, LocalPlayer player, List<UnarmedFlight.Threat> threats, List<Vec3> shooterEyes) {
		BlockPos feet = BlockPos.containing(player.position().add(0.0D, 0.1D, 0.0D));
		List<Candidate> candidates = new ArrayList<>();
		for (int dx = -RADIUS; dx <= RADIUS; dx++) {
			for (int dz = -RADIUS; dz <= RADIUS; dz++) {
				double horizontal = Math.hypot(dx, dz);
				if (horizontal > RADIUS + 0.5D) continue;
				for (int dy = -MAX_DROP; dy <= MAX_RISE; dy++) {
					BlockPos cell = feet.offset(dx, dy, dz);
					if (!level.hasChunkAt(cell) || !CoverCells.standable(level, cell)) continue;
					double cx = cell.getX() + 0.5D, cz = cell.getZ() + 0.5D;
					double nearest = Double.POSITIVE_INFINITY;
					for (UnarmedFlight.Threat threat : threats) nearest = Math.min(nearest, Math.hypot(threat.x() - cx, threat.z() - cz));
					if (nearest < MIN_THREAT_DISTANCE) continue;
					GridPos grid = new GridPos(cell.getX(), cell.getY(), cell.getZ());
					candidates.add(new Candidate(grid, CoverCells.hazardNear(level, cell, 2), CoverCells.edgeDrop(level, cell, MAX_DROP),
						seenBy(level, player, grid, shooterEyes), CoverCells.cover(level, cell),
						Math.hypot(cx - player.getX(), cz - player.getZ()) + Math.abs(dy) * 0.5D, nearest));
				}
			}
		}
		return candidates;
	}

	/** Shooters whose eye has a clear line to the eye of a body standing in the cell. */
	static int seenBy(Level level, LocalPlayer player, GridPos cell, List<Vec3> shooterEyes) {
		Vec3 eye = new Vec3(cell.x() + 0.5D, cell.y() + player.getEyeHeight(), cell.z() + 0.5D);
		int seen = 0;
		for (Vec3 shooter : shooterEyes) if (CoverCells.clear(level, player, shooter, eye, null)) seen++;
		return seen;
	}
}
