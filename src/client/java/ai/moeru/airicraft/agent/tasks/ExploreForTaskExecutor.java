package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntToDoubleFunction;

/**
 * Walks out to find a world feature that is not in loaded chunks yet, for as long as it takes. Playtest spawns in deserts
 * had no tree within about 200 blocks: find_world_features only searches loaded chunks, so the driver walked back and
 * forth by hand, routed into a lake several times and was killed at night; a first version of this task found a forest
 * but stopped at every dusk and at every lake and forgot what it had seen between calls.
 * <p>
 * The walk goes in legs of about 48 blocks and searches the loaded chunks again after each one. A leg heads where the
 * land looks most like the feature (tree tops and grass for a forest, water for water, read off the top surface of every
 * chunk that was ever loaded, as a player looks across the land), then where the most ground is new: far from everywhere
 * the body walked (on this call or an earlier one in this world), outward from the start, with unloaded chunks ahead,
 * roughly keeping the last heading. A leg ends on dry ground and is walked without breaking, placing or sprinting, with
 * water priced high; when only water is left on every new heading it swims across to the nearest dry shore it can see.
 * <p>
 * On the way it eats carried food when hungry or hurt, and hunts a passing cow, pig, sheep, chicken or rabbit when hungry
 * with nothing to eat. At dusk it digs three blocks down and roofs the hole with a block that does not fall, waits for
 * morning (eating if it must) and walks on; with no such block it walks on through the night and leaves the mobs to the
 * survival reflex. Once the feature is in loaded chunks it walks to it and succeeds with its position. It fails with
 * the explored extent on max_distance (no new ground left within maxDistance of the start, or twice maxDistance walked),
 * route_blocked (no new dry ground reachable even by swimming), low_health with no food, or timeout.
 */
public final class ExploreForTaskExecutor implements WorldTaskExecutor {
	static final int LEG_BLOCKS = 48;
	/** Shorter ends tried, and sideways offsets, when a heading's full-length end is not dry ground. */
	static final int[] LEG_REACHES = {48, 40, 32, 24};
	static final int[] LEG_OFFSETS = {0, 8, -8};
	static final int HEADINGS = 16;
	/** Search radius after each leg; the loaded chunks around the body usually reach about this far. */
	static final int SEARCH_RADIUS = 160;
	/** Surface is read off every loaded chunk this close to the body that has not been read before. */
	static final int SURFACE_RADIUS = 256;
	static final double ARRIVE_BLOCKS = 4.0D;
	/** A leg that ended this much closer to its end than it started was walked, even if it fell short. */
	static final double PARTIAL_LEG_BLOCKS = 16.0D;
	/** A heading whose legs failed this often is not tried again. */
	static final int MAX_HEADING_FAILURES = 2;
	static final int LEG_TIMEOUT_TICKS = 900;
	static final int CROSSING_TIMEOUT_TICKS = 2_000;
	/** Farthest swim to a shore seen across the water. */
	static final int MAX_CROSSING_BLOCKS = 128;
	/** Gives up on a leg before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	static final float LOW_HEALTH = 6.0F;
	/** Swims only this healthy: drowning and a mob in the water both cost health with nowhere to stand. */
	static final float SWIM_HEALTH = 12.0F;
	/** Ends nearer than this to every point already walked show nothing new. */
	static final double MIN_NOVELTY_BLOCKS = 16.0D;
	static final double NOVELTY_CAP_BLOCKS = 96.0D;
	static final double VISIT_SPACING_BLOCKS = 8.0D;
	/** How much the look of the land toward a heading counts against novelty (4), outwardness (2) and the rest. */
	static final double LURE_WEIGHT = 6.0D;
	/** The walk may cover this many times maxDistance before it counts as done. */
	static final int WALK_BUDGET_FACTOR = 2;
	/** Two in-game days, one of them spent in a shelter. */
	static final int TIMEOUT_TICKS = 48_000;
	static final double WATER_PENALTY = 50.0D;
	static final NavigationOptions OPTIONS = NavigationOptions.WALK_ONLY.withWaterPenalty(WATER_PENALTY);
	/** Swimming across: water at its usual price, still no digging or bridging. */
	static final NavigationOptions CROSSING_OPTIONS = NavigationOptions.WALK_ONLY;
	/** Out of a shelter: the roof and walls may be broken and blocks placed to climb. */
	static final NavigationOptions EXIT_OPTIONS = NavigationOptions.DEFAULT;
	/** Sunset to sunrise in day ticks: hostile mobs spawn on the surface from about 13,000. */
	static final long NIGHT_START = 12_500L, NIGHT_END = 23_200L;
	/** Eats at this hunger or below, or when hurt and below {@link #HEAL_FOOD} (health only regenerates from 18). */
	static final int EAT_FOOD = 6;
	static final int HEAL_FOOD = 18;
	static final float HURT_HEALTH = 14.0F;
	/** Hunts at this hunger or below when nothing edible is carried. */
	static final int HUNT_FOOD = 10;
	static final double HUNT_RADIUS = 24.0D;
	static final double MELEE_REACH = 3.0D;
	static final int HUNT_TIMEOUT_TICKS = 500;
	static final int MAX_HUNT_FAILURES = 3;
	/** An animal that vanished this close to the body was killed, not unloaded. */
	static final double KILL_DISTANCE = 6.0D;
	static final int LOOT_TICKS = 120;
	static final int EAT_TIMEOUT_TICKS = 120;
	static final int EAT_RETRY_TICKS = 200;
	static final int SHELTER_GO_TICKS = 300;
	static final int SHELTER_DIG_TICKS = 600;
	static final int SHELTER_EXIT_TICKS = 600;
	static final int MAX_SHELTER_FAILURES = 3;
	/** How often to look again for a shelter column at night when none fit. */
	static final int SHELTER_RETRY_TICKS = 100;

	private final NavigationFacade navigation;
	private final Environment environment;
	private final ExplorationMemory memory = new ExplorationMemory();
	private WorldTaskRequest request;
	/** The task whose state is kept while a reflex or another owner has it paused. */
	private String stateTaskId;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private ExploreForStepArgs args;
	private Phase phase = Phase.SEARCH;
	/** Where EAT goes back to. */
	private Phase resumePhase = Phase.SEARCH;
	private int activeTicks, phaseTicks;
	private Vec3 origin, lastVisit, lastFeet;
	private final int[] headingFailures = new int[HEADINGS];
	private Integer lastHeading;
	private Leg leg;
	private double legStartDistance;
	private double walked, furthest, swum;
	private Vec3 furthestAt;
	private int legs, searches, crossings;
	private Feature found;
	private Animal prey;
	private Vec3 preyLastSeen;
	private int huntFailures, kills, eaten, eatBlockedUntil;
	private ExploreShelter.Plan shelter;
	private int shelterFailures, nightsSheltered, lastShelterLook = Integer.MIN_VALUE / 2;
	private boolean walkingAtNight;
	private String note = "";
	private GoalPosition navigationGoal;
	private NavigationOptions navigationOptions;
	private boolean navigationOwned, navigationArrived;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);

	public ExploreForTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, new MinecraftExploreEnvironment(camera));
	}

	ExploreForTaskExecutor(NavigationFacade navigation, Environment environment) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.EXPLORE_FOR) {
			if (request != null) release();
			request = null;
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			// Paused by a reflex and handed back: carry on from where it was rather than starting over.
			boolean resume = request == null && next.taskId().equals(stateTaskId) && terminal == null;
			request = next;
			if (resume) resume();
			else reset();
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate", null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		Body body = environment.observe();
		if (body == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED);
		memory.enter(environment.worldKey());
		activeTicks++;
		phaseTicks++;
		track(body.feet());
		if (activeTicks > TIMEOUT_TICKS) return finish(false, "timeout", TaskFailureCode.TRANSIENT);

		Optional<TaskTerminalEvent> ended = needs(body);
		if (ended.isEmpty()) ended = switch (phase) {
			case SEARCH -> search(body);
			case WALK -> walk(body);
			case APPROACH -> approach(body);
			case EAT -> eat(body);
			case HUNT -> hunt(body);
			case LOOT -> loot(body);
			case SHELTER_GO -> shelterGo(body);
			case SHELTER_DIG -> shelterDig(body);
			case SHELTER_WAIT -> shelterWait(body);
			case SHELTER_EXIT -> shelterExit(body);
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, "working", body);
		return Optional.empty();
	}

	/** Health, hunger and nightfall come before the walk: eat, hunt, shelter, or give up when none of that helps. */
	private Optional<TaskTerminalEvent> needs(Body body) {
		if (phase == Phase.EAT || phase == Phase.SHELTER_DIG || phase == Phase.SHELTER_EXIT) return Optional.empty();
		boolean starving = body.food() <= EAT_FOOD;
		boolean canEat = activeTicks >= eatBlockedUntil;
		if (body.health() <= LOW_HEALTH) {
			if (body.food() < 20 && canEat && environment.hasFood(true)) return startEating(body);
			if (body.food() >= HEAL_FOOD) {
				// Regenerating: a shelter is the safest place for it, otherwise keep going carefully.
				if (phase != Phase.SHELTER_WAIT) note = "low_health_regenerating";
			}
			else return finish(false, String.format(Locale.ROOT, "low_health health=%.1f food=%d and nothing to eat; eat or find food "
				+ "(health only comes back with food at 18 or more), then call explore_for again", body.health(), body.food()),
				TaskFailureCode.ENVIRONMENT_CHANGED);
		}
		// A hunt or the walk over its drops is short; the meal waits for it.
		boolean hunting = phase == Phase.HUNT || phase == Phase.LOOT;
		if ((starving || body.health() < Math.min(HURT_HEALTH, body.maxHealth()) && body.food() < HEAL_FOOD)
			&& !hunting && canEat && environment.hasFood(body.food() <= 2)) return startEating(body);
		if (phase == Phase.SHELTER_WAIT || phase == Phase.SHELTER_GO) return Optional.empty();
		if (body.food() <= HUNT_FOOD && !body.night() && phase != Phase.HUNT && phase != Phase.LOOT && phase != Phase.APPROACH
			&& huntFailures < MAX_HUNT_FAILURES && !environment.hasFood(body.food() <= 2)) {
			Optional<Animal> animal = environment.animals(body.feet(), HUNT_RADIUS).stream().findFirst();
			if (animal.isPresent()) {
				stopNavigation();
				prey = animal.get();
				preyLastSeen = prey.position();
				enter(Phase.HUNT);
				return Optional.empty();
			}
		}
		if (body.night() && !body.inWater() && phase != Phase.APPROACH && phase != Phase.HUNT && phase != Phase.LOOT
			&& shelterFailures < MAX_SHELTER_FAILURES && activeTicks - lastShelterLook >= SHELTER_RETRY_TICKS) {
			lastShelterLook = activeTicks;
			if (!environment.hasRoofBlock()) {
				walkingAtNight = true;
				note = "walking_at_night: no block that does not fall to roof a shelter with";
				return Optional.empty();
			}
			ExploreShelter.Plan plan = environment.shelterNear(body.feet());
			if (plan == null) {
				walkingAtNight = true;
				note = "walking_at_night: no column here to dig a shelter in";
				return Optional.empty();
			}
			stopNavigation();
			shelter = plan;
			enter(Phase.SHELTER_GO);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> startEating(Body body) {
		stopNavigation();
		environment.stopMoving();
		resumePhase = phase == Phase.SHELTER_WAIT || phase == Phase.APPROACH ? phase : Phase.SEARCH;
		enter(Phase.EAT);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> eat(Body body) {
		ConsumeResult result = environment.eat(body.food() <= 2);
		if (result == ConsumeResult.CONSUMING && phaseTicks <= EAT_TIMEOUT_TICKS) return Optional.empty();
		environment.stopEating();
		if (result == ConsumeResult.DONE) eaten++;
		// Food that would not go down is not tried again at once.
		else eatBlockedUntil = activeTicks + EAT_RETRY_TICKS;
		enter(resumePhase);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> search(Body body) {
		searches++;
		environment.sampleSurface(body.feet(), SURFACE_RADIUS, memory);
		// The surface already shows whether a forest or water is near; the full search only runs where it could succeed.
		boolean worthSearching = !ExplorationMemory.surfaceTells(args.featureKind()) || memory.chunks() == 0
			|| memory.shows(args.featureKind(), body.feet(), SEARCH_RADIUS);
		Optional<Feature> feature = worthSearching
			? environment.findFeature(args.featureKind(), body.feet(), SEARCH_RADIUS, args.minTrees()) : Optional.empty();
		if (feature.isPresent()) {
			found = feature.get();
			enter(Phase.APPROACH);
			return Optional.empty();
		}
		if (walked >= (double) WALK_BUDGET_FACTOR * args.maxDistance())
			return finish(false, "max_distance; walked " + WALK_BUDGET_FACTOR + "x maxDistance without finding " + args.featureKind(),
				TaskFailureCode.MISSING_FACT);
		IntToDoubleFunction lure = heading -> memory.lure(args.featureKind(), body.feet(), 2 * Math.PI * heading / HEADINGS);
		Choice choice = chooseLeg(body.feet(), origin, args.maxDistance(), memory.visited(), headingFailures, lastHeading,
			environment::ground, lure);
		if (choice.leg() == null && !choice.exhausted() && !body.night() && body.health() >= SWIM_HEALTH)
			choice = chooseCrossing(body.feet(), origin, args.maxDistance(), memory.visited(), headingFailures, environment::ground, lure);
		if (choice.leg() == null) {
			if (choice.exhausted()) return finish(false, "max_distance; no new ground left within maxDistance=" + args.maxDistance()
				+ " of the start and no " + args.featureKind() + " found", TaskFailureCode.MISSING_FACT);
			return finish(false, "route_blocked; no heading leads to new dry ground"
				+ (body.night() || body.health() < SWIM_HEALTH ? " (water is only crossed by day and above " + (int) SWIM_HEALTH + " health)"
				: ", not even across water within " + MAX_CROSSING_BLOCKS + " blocks"), TaskFailureCode.TRANSIENT);
		}
		leg = choice.leg();
		if (leg.crossing()) crossings++;
		legStartDistance = horizontal(body.feet(), leg.x() + 0.5D, leg.z() + 0.5D);
		legs++;
		enter(Phase.WALK);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> walk(Body body) {
		double remaining = horizontal(body.feet(), leg.x() + 0.5D, leg.z() + 0.5D);
		boolean arrived = remaining <= ARRIVE_BLOCKS;
		int timeout = leg.crossing() ? CROSSING_TIMEOUT_TICKS : LEG_TIMEOUT_TICKS;
		boolean moving = !arrived && navigate(new GoalPosition(leg.x(), leg.y(), leg.z(), false), 2,
			leg.crossing() ? CROSSING_OPTIONS : OPTIONS);
		if (body.inWater()) swum++;
		if (!arrived && !navigationArrived && moving && phaseTicks <= timeout) return Optional.empty();
		stopNavigation();
		boolean walkedEnough = arrived || navigationArrived || legStartDistance - remaining >= PARTIAL_LEG_BLOCKS;
		if (walkedEnough) lastHeading = leg.heading();
		else headingFailures[leg.heading()]++;
		enter(Phase.SEARCH);
		return Optional.empty();
	}

	/** Walks to the found feature; arriving or not, the feature is found. */
	private Optional<TaskTerminalEvent> approach(Body body) {
		int[] to = found.stand() != null ? found.stand() : new int[]{found.x(), found.y(), found.z()};
		double remaining = horizontal(body.feet(), to[0] + 0.5D, to[2] + 0.5D);
		boolean arrived = remaining <= 2.5D;
		boolean moving = !arrived && navigate(new GoalPosition(to[0], to[1], to[2], false), 1, OPTIONS);
		if (!arrived && !navigationArrived && moving && phaseTicks <= LEG_TIMEOUT_TICKS * 2) return Optional.empty();
		stopNavigation();
		boolean reached = arrived || navigationArrived;
		return finish(true, String.format(Locale.ROOT, "feature_found featureKind=%s pos=%d,%d,%d%s reached=%s distance=%.0f %s;",
			args.featureKind(), found.x(), found.y(), found.z(),
			found.stand() == null ? "" : " standPos=" + found.stand()[0] + "," + found.stand()[1] + "," + found.stand()[2],
			reached, Math.hypot(found.x() + 0.5D - body.feet().x, found.z() + 0.5D - body.feet().z), found.summary()), null);
	}

	private Optional<TaskTerminalEvent> hunt(Body body) {
		Optional<Animal> seen = environment.animals(body.feet(), HUNT_RADIUS + 8).stream().filter(a -> a.id() == prey.id()).findFirst();
		if (seen.isEmpty()) {
			stopNavigation();
			if (horizontal(body.feet(), preyLastSeen.x, preyLastSeen.z) <= KILL_DISTANCE) {
				kills++;
				enter(Phase.LOOT);
			}
			else {
				huntFailures++;
				enter(Phase.SEARCH);
			}
			return Optional.empty();
		}
		prey = seen.get();
		preyLastSeen = prey.position();
		if (phaseTicks > HUNT_TIMEOUT_TICKS) {
			stopNavigation();
			huntFailures++;
			enter(Phase.SEARCH);
			return Optional.empty();
		}
		double distance = body.feet().distanceTo(prey.position());
		if (distance <= MELEE_REACH) {
			stopNavigation();
			environment.melee(prey.id());
			return Optional.empty();
		}
		GoalPosition goal = new GoalPosition((int) Math.floor(prey.position().x), (int) Math.floor(prey.position().y),
			(int) Math.floor(prey.position().z), false);
		boolean far = navigationGoal == null || Math.hypot(navigationGoal.x() - goal.x(), navigationGoal.z() - goal.z()) >= 3;
		if (!navigate(far ? goal : navigationGoal, 1, OPTIONS)) {
			stopNavigation();
			huntFailures++;
			enter(Phase.SEARCH);
		}
		return Optional.empty();
	}

	/** Walks over where the animal died so its drops are picked up. */
	private Optional<TaskTerminalEvent> loot(Body body) {
		boolean there = horizontal(body.feet(), preyLastSeen.x, preyLastSeen.z) <= 0.8D;
		boolean moving = !there && navigate(new GoalPosition((int) Math.floor(preyLastSeen.x), (int) Math.floor(preyLastSeen.y),
			(int) Math.floor(preyLastSeen.z), false), 0, OPTIONS);
		if (!there && moving && !navigationArrived && phaseTicks <= LOOT_TICKS) return Optional.empty();
		if (there && phaseTicks <= 10 && phaseTicks < LOOT_TICKS) return Optional.empty();
		stopNavigation();
		enter(Phase.SEARCH);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> shelterGo(Body body) {
		if (!body.night()) {
			stopNavigation();
			enter(Phase.SEARCH);
			return Optional.empty();
		}
		var top = shelter.top();
		boolean there = (int) Math.floor(body.feet().x) == top.getX() && (int) Math.floor(body.feet().z) == top.getZ()
			&& Math.abs(body.feet().y - top.getY()) < 0.6D && body.onGround();
		if (there) {
			stopNavigation();
			environment.stopMoving();
			enter(Phase.SHELTER_DIG);
			return Optional.empty();
		}
		boolean moving = navigate(new GoalPosition(top.getX(), top.getY(), top.getZ(), true), 0, OPTIONS);
		if ((moving || navigationArrived) && phaseTicks <= SHELTER_GO_TICKS) return Optional.empty();
		stopNavigation();
		return shelterFailed("could_not_reach_column");
	}

	private Optional<TaskTerminalEvent> shelterDig(Body body) {
		ShelterTick result = phaseTicks > SHELTER_DIG_TICKS ? ShelterTick.FAILED : environment.digShelter(shelter);
		if (result == ShelterTick.WORKING) return Optional.empty();
		environment.stopDigging();
		if (result == ShelterTick.FAILED) return shelterFailed(phaseTicks > SHELTER_DIG_TICKS ? "dig_too_slow" : "dig_failed");
		nightsSheltered++;
		note = "";
		enter(Phase.SHELTER_WAIT);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> shelterWait(Body body) {
		environment.stopMoving();
		boolean inside = (int) Math.floor(body.feet().x) == shelter.top().getX() && (int) Math.floor(body.feet().z) == shelter.top().getZ()
			&& body.feet().y < shelter.top().getY() - 1.0D;
		if (!inside) {
			// Knocked or pulled out: out in the night again.
			shelter = null;
			enter(Phase.SEARCH);
			return Optional.empty();
		}
		if (!body.night()) enter(Phase.SHELTER_EXIT);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> shelterExit(Body body) {
		var top = shelter.top();
		if (body.feet().y >= top.getY() - 0.2D && body.onGround()) {
			stopNavigation();
			shelter = null;
			enter(Phase.SEARCH);
			return Optional.empty();
		}
		boolean moving = navigate(new GoalPosition(top.getX(), top.getY(), top.getZ(), true), 1, EXIT_OPTIONS);
		if ((moving || navigationArrived) && phaseTicks <= SHELTER_EXIT_TICKS) return Optional.empty();
		stopNavigation();
		return finish(false, "shelter_exit_failed; morning came but the body could not climb out of its shelter at "
			+ top.getX() + "," + top.getY() + "," + top.getZ() + "; break the roof and climb out (navigate_to the top), then call explore_for again",
			TaskFailureCode.TRANSIENT);
	}

	private Optional<TaskTerminalEvent> shelterFailed(String reason) {
		shelterFailures++;
		environment.stopDigging();
		note = "shelter_failed: " + reason;
		shelter = null;
		enter(Phase.SEARCH);
		return Optional.empty();
	}

	/** Remembers where the body has been, how far it walked and how far it got from the start. */
	private void track(Vec3 feet) {
		if (origin == null) {
			origin = feet;
			furthestAt = feet;
		}
		if (lastFeet == null) {
			lastVisit = feet;
			lastFeet = feet;
			memory.visit(feet);
			return;
		}
		walked += horizontal(feet, lastFeet.x, lastFeet.z);
		lastFeet = feet;
		if (horizontal(feet, lastVisit.x, lastVisit.z) >= VISIT_SPACING_BLOCKS) {
			memory.visit(feet);
			lastVisit = feet;
		}
		double out = horizontal(feet, origin.x, origin.z);
		if (out > furthest) {
			furthest = out;
			furthestAt = feet;
		}
	}

	/** One leg: where it ends, the heading it was chosen on, and whether it swims across water to get there. */
	record Leg(int heading, int x, int y, int z, double score, boolean crossing) {
		Leg(int heading, int x, int y, int z, double score) { this(heading, x, y, z, score, false); }
	}

	/**
	 * The chosen leg, or null with whether that is because nothing new is left within the allowed distance (rather than
	 * new ground that is wet, unsafe or on a heading whose legs failed).
	 */
	record Choice(Leg leg, boolean exhausted) { }

	@FunctionalInterface
	interface GroundView {
		Ground ground(int x, int z, int nearY);
	}

	static Choice chooseLeg(Vec3 feet, Vec3 origin, int maxDistance, List<Vec3> visited, int[] headingFailures,
		Integer lastHeading, GroundView ground) {
		return chooseLeg(feet, origin, maxDistance, visited, headingFailures, lastHeading, ground, heading -> 0.0D);
	}

	/**
	 * Picks the next leg: for each of {@link #HEADINGS} headings, the first dry end along it (full length, then shorter,
	 * then a little to either side) inside maxDistance of the start. Ends near ground already walked are skipped. The best
	 * end is where the land looks most like the feature ({@code lure}, 0..1 per heading), then the most new ground (far
	 * from every walked point, outward from the start, unloaded chunks ahead), keeping roughly to the last heading, minus
	 * water and lava on the straight way there.
	 */
	static Choice chooseLeg(Vec3 feet, Vec3 origin, int maxDistance, List<Vec3> visited, int[] headingFailures,
		Integer lastHeading, GroundView ground, IntToDoubleFunction lure) {
		Leg best = null;
		boolean novelInBounds = false;
		int excluded = 0;
		int nearY = (int) Math.floor(feet.y);
		double fromOrigin = horizontal(feet, origin.x, origin.z);
		for (int heading = 0; heading < HEADINGS; heading++) {
			if (headingFailures[heading] >= MAX_HEADING_FAILURES) {
				excluded++;
				continue;
			}
			double angle = 2 * Math.PI * heading / HEADINGS;
			double dx = Math.cos(angle), dz = Math.sin(angle);
			Leg candidate = null;
			search:
			for (int reach : LEG_REACHES) {
				for (int offset : LEG_OFFSETS) {
					double px = feet.x + dx * reach - dz * offset, pz = feet.z + dz * reach + dx * offset;
					if (horizontal(origin, px, pz) > maxDistance) continue;
					double novelty = novelty(visited, px, pz);
					if (novelty < MIN_NOVELTY_BLOCKS) continue;
					novelInBounds = true;
					int x = (int) Math.floor(px), z = (int) Math.floor(pz);
					Ground end = ground.ground(x, z, nearY);
					if (end.kind() != Ground.Kind.DRY) continue;
					int water = 0, danger = 0;
					int samples = Math.max(1, (int) Math.ceil(Math.hypot(px - feet.x, pz - feet.z) / 4.0D));
					for (int sample = 1; sample < samples; sample++) {
						double t = (double) sample / samples;
						Ground on = ground.ground((int) Math.floor(feet.x + (px - feet.x) * t), (int) Math.floor(feet.z + (pz - feet.z) * t), nearY);
						if (on.kind() == Ground.Kind.WATER) water++;
						else if (on.kind() == Ground.Kind.DANGER) danger++;
					}
					int unloaded = 0;
					for (int ahead : new int[]{16, 48, 80}) {
						if (ground.ground((int) Math.floor(px + dx * ahead), (int) Math.floor(pz + dz * ahead), nearY).kind() == Ground.Kind.UNLOADED)
							unloaded++;
					}
					double outward = (horizontal(origin, px, pz) - fromOrigin) / reach;
					double continuity = lastHeading == null ? 0 : Math.cos(2 * Math.PI * (heading - lastHeading) / HEADINGS);
					double score = novelty / NOVELTY_CAP_BLOCKS * 4.0D + outward * 2.0D + unloaded / 3.0D + continuity
						+ lure.applyAsDouble(heading) * LURE_WEIGHT - water * 0.75D - danger * 3.0D - (LEG_BLOCKS - reach) * 0.02D;
					candidate = new Leg(heading, x, end.y(), z, score);
					break search;
				}
			}
			if (candidate != null && (best == null || candidate.score() > best.score())) best = candidate;
		}
		return new Choice(best, best == null && !novelInBounds && excluded == 0);
	}

	/**
	 * When no heading has new dry ground within a leg: the nearest dry shore seen across water, on a heading with new
	 * ground behind it, at most {@link #MAX_CROSSING_BLOCKS} away. Least water first, then the look of the land.
	 */
	static Choice chooseCrossing(Vec3 feet, Vec3 origin, int maxDistance, List<Vec3> visited, int[] headingFailures,
		GroundView ground, IntToDoubleFunction lure) {
		Leg best = null;
		int nearY = (int) Math.floor(feet.y);
		for (int heading = 0; heading < HEADINGS; heading++) {
			if (headingFailures[heading] >= MAX_HEADING_FAILURES) continue;
			double angle = 2 * Math.PI * heading / HEADINGS;
			double dx = Math.cos(angle), dz = Math.sin(angle);
			int water = 0;
			for (int reach = 4; reach <= MAX_CROSSING_BLOCKS; reach += 4) {
				double px = feet.x + dx * reach, pz = feet.z + dz * reach;
				if (horizontal(origin, px, pz) > maxDistance) break;
				Ground on = ground.ground((int) Math.floor(px), (int) Math.floor(pz), nearY);
				if (on.kind() == Ground.Kind.UNLOADED || on.kind() == Ground.Kind.DANGER) break;
				if (on.kind() == Ground.Kind.WATER) {
					water++;
					continue;
				}
				if (water == 0) continue;
				// Dry ground across the water: a shore; step a few blocks inland so the leg ends standing.
				double ix = px + dx * 4, iz = pz + dz * 4;
				Ground inland = ground.ground((int) Math.floor(ix), (int) Math.floor(iz), nearY);
				boolean useInland = inland.kind() == Ground.Kind.DRY;
				double ex = useInland ? ix : px, ez = useInland ? iz : pz;
				Ground end = useInland ? inland : on;
				if (novelty(visited, ex, ez) < MIN_NOVELTY_BLOCKS) break;
				double score = -water * 4.0D + lure.applyAsDouble(heading) * LURE_WEIGHT * 4.0D;
				Leg candidate = new Leg(heading, (int) Math.floor(ex), end.y(), (int) Math.floor(ez), score, true);
				if (best == null || candidate.score() > best.score()) best = candidate;
				break;
			}
		}
		return new Choice(best, false);
	}

	private static double novelty(List<Vec3> visited, double x, double z) {
		double novelty = NOVELTY_CAP_BLOCKS;
		for (Vec3 point : visited) novelty = Math.min(novelty, horizontal(point, x, z));
		return novelty;
	}

	static boolean night(long timeOfDay) {
		long time = Math.floorMod(timeOfDay, 24_000L);
		return time >= NIGHT_START && time < NIGHT_END;
	}

	/** How far the walk got, for every terminal message. */
	String evidence() {
		if (origin == null) return "";
		StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
			" explored: start=%d,%d furthest=%.0f blocks at %d,%d walked=%.0f legs=%d searches=%d searchRadius=%d chunksSeen=%d",
			(long) Math.floor(origin.x), (long) Math.floor(origin.z), furthest, (long) Math.floor(furthestAt.x), (long) Math.floor(furthestAt.z),
			walked, legs, searches, SEARCH_RADIUS, memory.chunks()));
		if (crossings > 0) text.append(String.format(Locale.ROOT, " crossings=%d swumTicks=%.0f", crossings, swum));
		if (nightsSheltered > 0 || shelterFailures > 0 || walkingAtNight)
			text.append(" nightsSheltered=").append(nightsSheltered).append(" shelterFailures=").append(shelterFailures)
				.append(" walkedAtNight=").append(walkingAtNight);
		if (eaten > 0 || kills > 0 || huntFailures > 0)
			text.append(" eaten=").append(eaten).append(" animalsKilled=").append(kills).append(" huntsFailed=").append(huntFailures);
		return text.toString();
	}

	private boolean navigate(GoalPosition goal, int radius, NavigationOptions options) {
		if (!navigationOwned || navigationGoal == null || !navigationGoal.equals(goal) || !options.equals(navigationOptions)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			if (radius <= 0) navigation.startNavigate(goal, options);
			else navigation.startNavigateNear(goal, radius, options);
			navigationOwned = true;
			navigationGoal = goal;
			navigationOptions = options;
			navigationArrived = false;
			navigationStall.clear();
		}
		String event = navigation.pollPathEvent().orElse("");
		if (event.equals("AT_GOAL")) navigationArrived = true;
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) return false;
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		navigationArrived = false;
		navigationGoal = null;
		navigationOptions = null;
		navigationStall.clear();
	}

	private static double horizontal(Vec3 from, double x, double z) {
		return Math.hypot(from.x - x, from.z - z);
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	private void release() {
		stopNavigation();
		environment.release();
	}

	private void reset() {
		terminal = null;
		emitted = false;
		stateTaskId = request.taskId();
		args = ((WorldTaskRequest.ExploreFor) request.task()).args();
		phase = resumePhase = Phase.SEARCH;
		activeTicks = phaseTicks = 0;
		origin = lastVisit = lastFeet = furthestAt = null;
		Arrays.fill(headingFailures, 0);
		lastHeading = null;
		leg = null;
		walked = furthest = swum = 0;
		legs = searches = crossings = 0;
		found = null;
		prey = null;
		preyLastSeen = null;
		huntFailures = kills = eaten = eatBlockedUntil = 0;
		shelter = null;
		shelterFailures = nightsSheltered = 0;
		lastShelterLook = Integer.MIN_VALUE / 2;
		walkingAtNight = false;
		note = "";
	}

	/** Back after a pause: look again from wherever the body is now, keeping the counts, the start and the heading. */
	private void resume() {
		phase = resumePhase = Phase.SEARCH;
		phaseTicks = 0;
		leg = null;
		prey = null;
		shelter = null;
		lastShelterLook = Integer.MIN_VALUE / 2;
		// Distance tracking restarts at the new feet, so a knockback or a reflex's walk is not counted as explored.
		lastFeet = null;
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode failureCode) {
		release();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			reason + evidence(), success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : failureCode);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, "ExploreFor",
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String reason, Body body) {
		StringBuilder detail = new StringBuilder(reason).append(" phase=").append(phase);
		if (args != null) detail.append(" featureKind=").append(args.featureKind()).append(" maxDistance=").append(args.maxDistance());
		if (body != null) detail.append(String.format(Locale.ROOT, " feet=%.1f,%.1f,%.1f health=%.1f food=%d", body.feet().x,
			body.feet().y, body.feet().z, body.health(), body.food()));
		if (phase == Phase.WALK && leg != null) detail.append(" leg=").append(leg.x()).append(',').append(leg.y()).append(',').append(leg.z())
			.append(leg.crossing() ? " crossing" : "");
		if (!note.isEmpty()) detail.append(' ').append(note);
		detail.append(evidence());
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, "ExploreFor", detail.toString(),
			null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	@Override
	public void onWorldLeave() {
		release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() { release(); }

	enum Phase { SEARCH, WALK, APPROACH, EAT, HUNT, LOOT, SHELTER_GO, SHELTER_DIG, SHELTER_WAIT, SHELTER_EXIT }

	enum ConsumeResult { CONSUMING, DONE, NOTHING }

	enum ShelterTick { WORKING, CLOSED, FAILED }

	/**
	 * What the task reads from the player each tick; {@code night} is false where the sky has no day cycle, and
	 * {@code food} is the hunger bar, 0..20.
	 */
	record Body(Vec3 feet, float health, float maxHealth, int food, boolean night, boolean onGround, boolean inWater) {
		Body(Vec3 feet, float health, boolean night) { this(feet, health, 20.0F, 20, night, true, false); }
	}

	/** A found feature: the block to go to, where to stand by it (null when none was found), and the search's description. */
	record Feature(int x, int y, int z, int[] stand, String summary) {
		Feature(int x, int y, int z, String summary) { this(x, y, z, null, summary); }
	}

	/** An animal that drops food when killed. */
	record Animal(int id, String type, Vec3 position) { }

	/** The top of a column, where the body would stand: dry ground, water, lava or another hazard, or not loaded. */
	record Ground(Kind kind, int y) {
		enum Kind { DRY, WATER, DANGER, UNLOADED }

		static final Ground UNLOADED = new Ground(Kind.UNLOADED, 0);
	}

	interface Environment {
		/** Null when no world is loaded. */
		Body observe();
		/** The world and dimension the body is in, so what was explored is not mixed between them. */
		String worldKey();
		/** The nearest feature of the kind in loaded chunks within {@code radius} blocks of the feet. */
		Optional<Feature> findFeature(String featureKind, Vec3 feet, int radius, int minTrees);
		/** The ground of one column, near {@code nearY} where the world has a ceiling. */
		Ground ground(int x, int z, int nearY);
		/** Reads the top surface of loaded chunks within {@code radius} that memory has not seen yet, under an open sky. */
		void sampleSurface(Vec3 feet, int radius, ExplorationMemory memory);
		/** Whether something safe to eat is carried; when {@code starving}, also food with a risk (raw chicken). */
		boolean hasFood(boolean starving);
		ConsumeResult eat(boolean starving);
		void stopEating();
		/** Food animals within {@code radius} the body can see, nearest first. */
		List<Animal> animals(Vec3 feet, double radius);
		/** Faces the animal and hits it when the attack is charged. */
		void melee(int entityId);
		/** Whether a block that does not fall (and can roof a shelter) is carried. */
		boolean hasRoofBlock();
		/** A shelter column near the feet, or null. */
		ExploreShelter.Plan shelterNear(Vec3 feet);
		/** Digs or roofs the shelter for one tick. */
		ShelterTick digShelter(ExploreShelter.Plan plan);
		void stopDigging();
		void stopMoving();
		void release();
	}
}
