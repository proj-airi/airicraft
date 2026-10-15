package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.LiveWorldTerrain;
import ai.moeru.airicraft.agent.navigation.MinecraftCellClassifier;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.navigation.CellInfo;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.TerrainView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs from a fight the body cannot win bare-handed. Twice in playtests the body died with nothing in its hand, once
 * to a zombie and once at night to husks in a desert: the reflex threw more than sixty punches and reposition steps at
 * one husk and handed back a combat_stalemate while it was still being hit. A fist does one damage; a zombie has twenty
 * health and hits for three. So without a real melee weapon (the best one it would equip does less than a wooden
 * sword's four damage) the reflex runs instead, unless the fight is trivially beatable: one weak melee mob (ten health
 * or less, so silverfish or a worn-down zombie, never a creeper or a shooter) while the body has at least four fifths
 * of its health. It sprints, through the navigator, in legs of about twenty blocks to dry open ground away from the
 * threats' centroid, never past a threat, with no terrain edits, no fall deeper than three blocks and water priced
 * high. It stops once no threat is within sixteen blocks, or within ten while out of sight. It goes back to fighting
 * when no leg gets anywhere, when a lone pursuer keeps landing hits (it is faster) or when a weapon turns up. Low
 * health is left to {@link EmergencyBunker}, which runs first: bare hands dig dirt or sand fast enough to dig in, not
 * stone. Client thread only, apart from the static rules.
 */
final class UnarmedFlight {
	/** A wooden sword's attack damage: anything weaker fights like a fist. */
	static final double MIN_WEAPON_DAMAGE = 4.0D;
	static final double FIST_DAMAGE = 1.0D;
	/** A lone melee mob with this much health or less is a fight bare hands win. */
	static final float TRIVIAL_MOB_HEALTH = 10.0F;
	/** Fraction of max health the body needs to take on even a trivial fight unarmed. */
	static final float NEAR_FULL_HEALTH = 0.8F;
	static final int LEG_BLOCKS = 20;
	static final int MAX_DROP = 3;
	static final double WATER_PENALTY = 40.0D;
	static final double CLEAR_DISTANCE = 16.0D;
	static final double HIDDEN_DISTANCE = 10.0D;
	static final int CLEAR_TICKS = 20;
	static final int LEG_TIMEOUT_TICKS = 100;
	/** A leg that brings the body less than this much closer to its end did not get anywhere. */
	static final double MIN_LEG_PROGRESS = 3.0D;
	static final int MAX_FAILED_LEGS = 3;
	/** Hits a lone pursuer lands during one flight before the body turns to fight it. */
	static final int OUTRUN_HITS = 4;
	static final int MAX_TICKS = 1200;
	/** Times an untouched flight starts its clock again before it gives up. */
	static final int MAX_EXTENSIONS = 3;
	static final int HEADINGS = 16;
	/** Headings back toward the threats are never taken; sideways ones are, when nothing better is open. */
	static final double MIN_ALIGNMENT = -0.25D;
	/**
	 * Ticks after a flight found no escape route before another is tried: the body has moved since (to cover, or
	 * knocked about while fighting), and what was a dead end may be open from here. A playtest body gave up running
	 * from one skeleton when a second appeared and punched at both until four arrows killed it.
	 */
	static final long RETRY_TICKS = 60L;
	/** New flights one episode may start after giving up for want of a route. */
	static final int MAX_RETRIES = 3;
	static final String NO_ESCAPE_ROUTE = "no_escape_route";
	static final NavigationOptions OPTIONS = NavigationOptions.DEFAULT.withNoEdits().withMaxSafeFall(MAX_DROP)
		.withWaterPenalty(WATER_PENALTY);

	/** One threat as the flight sees it: where it is, how strong, and whether the body can see it. */
	record Threat(double x, double z, float health, boolean ranged, boolean creeper, double distance, boolean lineOfSight) { }

	/** The end of one leg, how well it leads away, and what the straight way there crosses. */
	record Leg(GridPos stand, double alignment, int waterColumns, int blockedColumns, double score) { }

	enum Outcome { FLEEING, ESCAPED, ABANDONED }

	/** Built on first use: classifying needs the block registry, which plain construction must not touch. */
	private MinecraftCellClassifier classifier;
	private boolean active;
	private String abandonReason;
	private long startedTick;
	private long lastTick = Long.MIN_VALUE;
	private long countedHitTick = Long.MIN_VALUE;
	private int hitsTaken;
	private int extensions;
	private long clearSinceTick = -1L;
	private GridPos legTarget;
	private long legStartedTick;
	private double legStartDistance;
	private int failedLegs;
	private int legs;
	private boolean armedFlight;
	private long abandonedTick = Long.MIN_VALUE;
	private int retries;

	/**
	 * Why the body should run rather than fight, or null to fight: a real weapon, or one weak melee mob while the body
	 * is near full health.
	 */
	static String fleeReason(double weaponDamage, float health, float maxHealth, List<Threat> threats) {
		return fleeReason(weaponDamage, health, maxHealth, threats, true);
	}

	/**
	 * Like the above, for a body that may not be able to sprint (food 6 or less). Walking, it cannot outpace a zombie:
	 * a playtest body at food 6 ran from three zombies at nine health and took four more hits in the back. It only
	 * walks away from a creeper then, which needs distance rather than speed.
	 */
	static String fleeReason(double weaponDamage, float health, float maxHealth, List<Threat> threats, boolean canSprint) {
		if (threats.isEmpty()) return null;
		if (!canSprint) return threats.stream().allMatch(Threat::creeper) ? "creeper" : null;
		if (weaponDamage >= MIN_WEAPON_DAMAGE) {
			return health <= maxHealth / 2.0F && outmatched(weaponDamage, health, threats) ? OUTMATCHED : null;
		}
		if (threats.size() > 1) return "outnumbered";
		Threat only = threats.getFirst();
		if (only.ranged()) return "ranged_mob";
		if (only.creeper()) return "creeper";
		if (only.health() > TRIVIAL_MOB_HEALTH) return "strong_mob";
		if (health < maxHealth * NEAR_FULL_HEALTH) return "hurt";
		return null;
	}

	/**
	 * Armed and at half health or less, but the melee mobs would deal what health is left before the weapon kills them
	 * all (armor ignored, so this errs toward running). A wooden sword
	 * needs five swings for a zombie, about sixty ticks, in which the zombie lands three hits for nine: a playtest body
	 * at nine health fought exactly that and died, as did one at nine with a stone sword against a zombie and a
	 * skeleton. Zombies walk slower than a sprinting player. Shooters and creepers are left to archery, the bunker
	 * and the creeper run.
	 */
	static boolean outmatched(double weaponDamage, float health, List<Threat> threats) {
		int swings = 0;
		int melee = 0;
		for (Threat threat : threats) {
			if (threat.ranged() || threat.creeper()) continue;
			melee++;
			swings += (int) Math.ceil(threat.health() / weaponDamage);
		}
		if (melee == 0) return false;
		int mobHits = swings * SWING_TICKS / MOB_ATTACK_TICKS;
		return melee * mobHits * MOB_HIT_DAMAGE >= health - OUTMATCHED_MARGIN;
	}

	static final String OUTMATCHED = "outmatched";
	/** A sword's full-strength swing interval, a little rounded up for aiming. */
	static final int SWING_TICKS = 12;
	static final int MOB_ATTACK_TICKS = 20;
	/** A zombie's hit on normal difficulty. */
	static final float MOB_HIT_DAMAGE = 3.0F;
	static final float OUTMATCHED_MARGIN = 2.0F;

	/** Every threat is far, or out of sight and not close. */
	static boolean clear(List<Threat> threats) {
		for (Threat threat : threats) {
			if (threat.distance() < CLEAR_DISTANCE && (threat.lineOfSight() || threat.distance() < HIDDEN_DISTANCE)) return false;
		}
		return true;
	}

	/** A lone pursuer that keeps landing hits is faster than the body; running only shows it the body's back. */
	static boolean outrun(int hitsTaken, List<Threat> threats) {
		return hitsTaken >= OUTRUN_HITS && threats.size() == 1 && threats.getFirst().distance() <= 3.0D;
	}

	/**
	 * The best leg end about {@code legBlocks} from the feet, shortened where the far end is unusable: open dry ground
	 * reached by a straight way that follows the ground (no step up of more than one block or drop of more than three),
	 * never crosses lava or fire, and does not end nearer any threat than the body stands now. Away from the threats'
	 * centroid wins; water and walls on the way cost a little. Null when no heading is open.
	 */
	static Leg fleeTarget(TerrainView terrain, GridPos feet, List<Threat> threats, int legBlocks) {
		double ox = feet.x() + 0.5D, oz = feet.z() + 0.5D;
		double cx = 0, cz = 0;
		for (Threat threat : threats) {
			cx += threat.x();
			cz += threat.z();
		}
		double ax = threats.isEmpty() ? 0 : ox - cx / threats.size();
		double az = threats.isEmpty() ? 0 : oz - cz / threats.size();
		double awayLength = Math.hypot(ax, az);
		Leg best = null;
		for (int heading = 0; heading < HEADINGS; heading++) {
			double angle = 2 * Math.PI * heading / HEADINGS;
			double dx = Math.cos(angle), dz = Math.sin(angle);
			double alignment = awayLength < 1.0E-3 ? 0 : (dx * ax + dz * az) / awayLength;
			if (alignment < MIN_ALIGNMENT) continue;
			for (int reach : new int[]{legBlocks, legBlocks * 3 / 4, legBlocks / 2}) {
				Leg leg = leg(terrain, feet, feet.x() + (int) Math.round(dx * reach), feet.z() + (int) Math.round(dz * reach),
					threats, alignment, (legBlocks - reach) * 0.15D);
				if (leg == null) continue;
				if (best == null || leg.score() > best.score()) best = leg;
				break;
			}
		}
		return best;
	}

	private enum Ground { STAND, WATER, DANGER, NONE }

	private record Surface(Ground ground, int y) { }

	private static Leg leg(TerrainView terrain, GridPos feet, int tx, int tz, List<Threat> threats, double alignment, double shortened) {
		int steps = (int) Math.ceil(Math.hypot(tx - feet.x(), tz - feet.z()));
		if (steps <= 0) return null;
		int y = feet.y(), water = 0, blocked = 0;
		Surface last = null;
		for (int step = 1; step <= steps; step++) {
			int x = feet.x() + (int) Math.round((tx - feet.x()) * (double) step / steps);
			int z = feet.z() + (int) Math.round((tz - feet.z()) * (double) step / steps);
			last = surface(terrain, x, y, z);
			switch (last.ground()) {
				case DANGER -> { return null; }
				case WATER -> water++;
				case NONE -> blocked++;
				case STAND -> y = last.y();
			}
		}
		if (last == null || last.ground() != Ground.STAND) return null;
		GridPos stand = new GridPos(tx, last.y(), tz);
		int nearbyWater = 0;
		int open = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 0; dy++) {
					CellInfo cell = terrain.cell(tx + dx, stand.y() + dy, tz + dz);
					if (cell.fluid() == CellInfo.Fluid.LAVA || cell.hazard(CellInfo.HAZARD_BODY)) return null;
					if (cell.fluid() == CellInfo.Fluid.WATER) nearbyWater++;
				}
				if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && standable(terrain, tx + dx, stand.y(), tz + dz)) open++;
			}
		}
		// A one-block ledge or pillar is no place to stop with mobs behind.
		if (open < 5) return null;
		double ox = feet.x() + 0.5D, oz = feet.z() + 0.5D, sx = tx + 0.5D, sz = tz + 0.5D;
		double length = Math.hypot(sx - ox, sz - oz);
		for (Threat threat : threats) {
			double fromFeet = Math.hypot(threat.x() - ox, threat.z() - oz);
			double fromStand = Math.hypot(threat.x() - sx, threat.z() - sz);
			if (fromStand < fromFeet + length * 0.5D) return null;
			// Never run past a threat on the way.
			double t = ((threat.x() - ox) * (sx - ox) + (threat.z() - oz) * (sz - oz)) / (length * length);
			if (t > 0.0D && t < 1.0D) {
				double px = ox + (sx - ox) * t, pz = oz + (sz - oz) * t;
				if (Math.hypot(threat.x() - px, threat.z() - pz) < 3.0D) return null;
			}
		}
		double score = alignment * 10.0D - water * 1.5D - blocked - nearbyWater * 0.3D - shortened;
		return new Leg(stand, alignment, water, blocked, score);
	}

	/** The ground in one column near {@code y}: level first, then a step up, then down to the safe drop. */
	private static Surface surface(TerrainView terrain, int x, int y, int z) {
		for (int dy : new int[]{0, 1, -1, -2, -3}) {
			int feetY = y + dy;
			CellInfo feet = terrain.cell(x, feetY, z), head = terrain.cell(x, feetY + 1, z), floor = terrain.cell(x, feetY - 1, z);
			if (!feet.loaded() || !head.loaded() || !floor.loaded()) return new Surface(Ground.DANGER, feetY);
			if (feet.fluid() == CellInfo.Fluid.LAVA || head.fluid() == CellInfo.Fluid.LAVA || floor.fluid() == CellInfo.Fluid.LAVA
				|| feet.hazard(CellInfo.HAZARD_BODY) || head.hazard(CellInfo.HAZARD_BODY)) return new Surface(Ground.DANGER, feetY);
			if (feet.hasCollision() || head.hasCollision()) continue;
			if (feet.fluid() == CellInfo.Fluid.WATER || floor.fluid() == CellInfo.Fluid.WATER) return new Surface(Ground.WATER, y);
			if (standable(terrain, x, feetY, z)) return new Surface(Ground.STAND, feetY);
		}
		return new Surface(Ground.NONE, y);
	}

	static boolean standable(TerrainView terrain, int x, int y, int z) {
		CellInfo floor = terrain.cell(x, y - 1, z), feet = terrain.cell(x, y, z), head = terrain.cell(x, y + 1, z);
		return floor.loaded() && feet.loaded() && head.loaded()
			&& floor.hasCollision(CellInfo.CENTER) && floor.fluid() == CellInfo.Fluid.NONE && !floor.hazard(CellInfo.HAZARD_FLOOR)
			&& !feet.hasCollision() && !head.hasCollision()
			&& feet.fluid() == CellInfo.Fluid.NONE && head.fluid() == CellInfo.Fluid.NONE
			&& feet.hazards() == 0 && head.hazards() == 0;
	}

	/** Attack damage of a held stack: a fist's one plus the stack's own main-hand bonus. */
	static double attackDamage(ItemStack stack) {
		double[] damage = {FIST_DAMAGE};
		if (stack != null && !stack.isEmpty()) stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
			if (attribute.value() == Attributes.ATTACK_DAMAGE.value() && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
				damage[0] += modifier.amount();
			}
		});
		return damage[0];
	}

	boolean active() {
		return active;
	}

	/** A flight given up in this episode; only one given up for want of a route may be tried again (see {@link #mayRetry}). */
	boolean abandoned() {
		return abandonReason != null;
	}

	/** Whether a flight given up earlier in this episode may start again now. */
	boolean mayRetry(long tick) {
		return abandonReason != null && retryable(abandonReason, abandonedTick, tick, retries);
	}

	/**
	 * Only a flight that found no route is tried again, {@value #RETRY_TICKS} ticks after it gave up and at most
	 * {@value #MAX_RETRIES} times an episode. A weapon in hand, a pursuer that outruns the body and a flight that ran
	 * out of time keep their verdict until the episode ends.
	 */
	static boolean retryable(String reason, long abandonedTick, long tick, int retries) {
		return NO_ESCAPE_ROUTE.equals(reason) && retries < MAX_RETRIES && tick - abandonedTick >= RETRY_TICKS;
	}

	int retries() {
		return retries;
	}

	String abandonReason() {
		return abandonReason;
	}

	/** Whether this flight runs from a fight the weapon in hand would lose, so picking one up does not end it. */
	boolean armedFlight() {
		return armedFlight;
	}

	void start(long tick, long lastMobDamageTick) {
		start(tick, lastMobDamageTick, false);
	}

	void start(long tick, long lastMobDamageTick, boolean armedFlight) {
		this.armedFlight = armedFlight;
		if (abandonReason != null) retries++;
		active = true;
		abandonReason = null;
		startedTick = tick;
		lastTick = Long.MIN_VALUE;
		countedHitTick = lastMobDamageTick;
		hitsTaken = 0;
		extensions = 0;
		clearSinceTick = -1L;
		legTarget = null;
		failedLegs = 0;
		legs = 0;
	}

	/** Whether a flight that ran out its time keeps going: nothing has hit it, and it has not been renewed too often. */
	static boolean keepsRunning(int hitsTaken, int extensions) {
		return hitsTaken == 0 && extensions < MAX_EXTENSIONS;
	}

	/** Ends any flight and forgets an abandoned one, for a new danger episode. */
	void reset(NavigationFacade navigation) {
		stop(navigation);
		abandonReason = null;
		retries = 0;
	}

	/** Gives up on this flight: the reflex fights (or takes cover) instead, until the episode ends or a retry. */
	void abandon(String reason, long tick, NavigationFacade navigation) {
		stop(navigation);
		abandonReason = reason;
		abandonedTick = tick;
	}

	private void stop(NavigationFacade navigation) {
		if (active && legTarget != null && navigation != null) navigation.cancel();
		active = false;
		legTarget = null;
	}

	Map<String, Object> summary(long tick) {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("legs", legs);
		summary.put("hitsTaken", hitsTaken);
		summary.put("ticks", tick - startedTick);
		return summary;
	}

	/** Runs one tick of the flight; on {@code ABANDONED} the reason is {@link #abandonReason()}. */
	Outcome tick(Minecraft minecraft, LocalPlayer player, List<Threat> threats, long tick, long lastMobDamageTick,
		int threatCooldownTicks, NavigationFacade navigation) {
		if (lastMobDamageTick != Long.MIN_VALUE && lastMobDamageTick > countedHitTick) {
			hitsTaken++;
			countedHitTick = lastMobDamageTick;
		}
		boolean resumed = lastTick != tick - 1;
		lastTick = tick;
		if (clear(threats) && !SurvivalReflexRuntime.recentlyDamagedByMob(tick, lastMobDamageTick, threatCooldownTicks)) {
			if (clearSinceTick < 0) clearSinceTick = tick;
			if (tick - clearSinceTick >= CLEAR_TICKS) {
				stop(navigation);
				return Outcome.ESCAPED;
			}
		}
		else clearSinceTick = -1L;
		if (tick - startedTick > MAX_TICKS) {
			// Untouched after a full minute the flight is working; turning to fight bare-handed is what killed a
			// playtest body that had outrun a zombie villager for 1200 ticks without a hit.
			if (!keepsRunning(hitsTaken, extensions)) return giveUp("timeout", tick, navigation);
			extensions++;
			startedTick = tick;
		}
		if (outrun(hitsTaken, threats)) return giveUp("outrun", tick, navigation);
		double px = player.getX(), pz = player.getZ();
		boolean legOver = legTarget == null || resumed || !navigation.processActive()
			|| tick - legStartedTick >= LEG_TIMEOUT_TICKS;
		if (!legOver && threatNearer(legTarget, px, pz, threats)) legOver = true;
		if (legOver) {
			if (legTarget != null && !resumed) {
				failedLegs = legStartDistance - distance(legTarget, px, pz) < MIN_LEG_PROGRESS ? failedLegs + 1 : 0;
				if (failedLegs >= MAX_FAILED_LEGS) return giveUp(NO_ESCAPE_ROUTE, tick, navigation);
			}
			var feet = player.blockPosition();
			if (classifier == null) classifier = MinecraftCellClassifier.forPlayer(null);
			Leg leg = fleeTarget(new LiveWorldTerrain(minecraft.level, classifier), new GridPos(feet.getX(), feet.getY(), feet.getZ()),
				threats, LEG_BLOCKS);
			if (leg == null) return giveUp(NO_ESCAPE_ROUTE, tick, navigation);
			navigation.startNavigateNear(new GoalPosition(leg.stand().x(), leg.stand().y(), leg.stand().z(), false), 2, OPTIONS);
			legTarget = leg.stand();
			legStartedTick = tick;
			legStartDistance = distance(legTarget, px, pz);
			legs++;
		}
		return Outcome.FLEEING;
	}

	private Outcome giveUp(String reason, long tick, NavigationFacade navigation) {
		abandon(reason, tick, navigation);
		return Outcome.ABANDONED;
	}

	/** A threat has got closer to the leg's end than the body: the leg now runs into it. */
	static boolean threatNearer(GridPos target, double px, double pz, List<Threat> threats) {
		double own = distance(target, px, pz);
		for (Threat threat : threats) {
			if (distance(target, threat.x(), threat.z()) < own) return true;
		}
		return false;
	}

	static double distance(GridPos target, double x, double z) {
		return Math.hypot(target.x() + 0.5D - x, target.z() + 0.5D - z);
	}
}
