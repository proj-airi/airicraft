package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.core.BlockPos;
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
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Hunts passive food animals and cooks the meat, as one task. In a live survival playtest every respawn and every Nether
 * trip started with the driver hunting by hand, one animal at a time (inspect_nearby_entities, attack_entity, walking onto
 * the drops, then craft, smelt and collect), and it often ran short of food, which ended fights early.
 *
 * <p>Targets are adult cows, mooshrooms, pigs, sheep, chickens and rabbits the eyes can see (eye-to-eye line of sight; no
 * X-ray) within {@code maxDistance}: never babies, named or leashed animals, or animals inside a preserved place, and never
 * the last {@link #KEEP_PER_KIND} adults of a kind in sight, so a herd survives to breed. The nearest one goes first. The
 * body walks to it (walk only, falls of at most 3, water avoided where it can), fights it with the best carried weapon on
 * the attack cooldown, chases a fleeing one with a route replaced only when the animal moved 2 or more blocks or the walk
 * went idle, and picks up the drops. One animal is given up after {@link #TARGET_GIVE_UP_TICKS} ticks. The hunt ends once
 * the inventory holds {@code count} raw meat, or nothing huntable has been in sight for {@link #NO_ANIMAL_TICKS} ticks.
 *
 * <p>With {@code cook}, the raw meat then goes through the smelting machinery as child tasks: an empty furnace or smoker
 * nearby, else a carried furnace placed (and picked back up by collect_smelted_items), else one crafted from eight
 * cobblestone. Fuel is chosen here (never tools, weapons or a crafting table; whole logs last), the batch is loaded,
 * waited for and collected. A cooking problem is a note on the success, never a failure.
 *
 * <p>The survival reflex keeps hostiles; a reflex pause resumes the same hunt. At night on the overworld surface the hunt
 * stops ({@code night}) so the driver can sleep or shelter. The decisions are pure static methods behind {@link Environment}.
 */
public final class HuntFoodTaskExecutor implements WorldTaskExecutor {
	/** Entity type paths of the animals hunted for meat. */
	static final Set<String> FOOD_ANIMALS = Set.of("cow", "mooshroom", "pig", "sheep", "chicken", "rabbit");
	/** Raw meat and what it cooks into. */
	static final Map<String, String> COOKED = Map.of(
		"minecraft:beef", "minecraft:cooked_beef",
		"minecraft:porkchop", "minecraft:cooked_porkchop",
		"minecraft:mutton", "minecraft:cooked_mutton",
		"minecraft:chicken", "minecraft:cooked_chicken",
		"minecraft:rabbit", "minecraft:cooked_rabbit");
	/** Adults of one kind in sight that are never hunted, so the herd can breed. */
	static final int KEEP_PER_KIND = 2;
	/** The hunt itself; cooking has its own budget. */
	static final int TIMEOUT_TICKS = 3_600;
	/** Nothing huntable in sight this long ends the hunt with what it has. */
	static final int NO_ANIMAL_TICKS = 400;
	/** Time spent on one animal before it is skipped. */
	static final int TARGET_GIVE_UP_TICKS = 300;
	/** Below this health without a real weapon the body does not fight. */
	static final float MIN_HEALTH_UNARMED = 8.0F;
	/** A wooden sword's attack damage: anything weaker is not a weapon. */
	static final double MIN_WEAPON_DAMAGE = 4.0D;
	/** Within this, with the animal in sight and a safe step, the body walks straight at it instead of routing. */
	static final double DIRECT_CHASE_DISTANCE = 4.0D;
	static final int MAX_SAFE_FALL = 3;
	static final double WATER_PENALTY = 40.0D;
	static final NavigationOptions OPTIONS = NavigationOptions.WALK_ONLY.withMaxSafeFall(MAX_SAFE_FALL).withWaterPenalty(WATER_PENALTY);
	/** Gives up on a route before the dispatcher's 100-tick stall watchdog would end the whole task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	/** Score added for an animal standing in water, and per block of height difference beyond 2. */
	static final double WATER_COST = 8.0D;
	static final double HEIGHT_COST = 2.0D;
	/** Cobblestone (or another stone crafting material) a furnace takes. */
	static final int FURNACE_MATERIAL = 8;
	/** A furnace cooks one item in this long; a smoker in half. */
	static final int FURNACE_COOK_TICKS = 200;
	static final int MAX_BATCH = 64;
	/** The whole cooking part: crafting, placing, loading, waiting and collecting. */
	static final int COOK_BASE_TICKS = 1_200;
	static final int COOK_PER_ITEM_TICKS = 220;
	/** Waiting for a batch past its cook time before collecting anyway (collect waits at the furnace for the rest). */
	static final int COOK_WAIT_SLACK_TICKS = 200;
	/** Stackable fuels kept for building: they are not burned. */
	static final Set<String> KEPT_FUELS = Set.of("minecraft:crafting_table", "minecraft:chest", "minecraft:trapped_chest",
		"minecraft:barrel", "minecraft:ladder", "minecraft:scaffolding");

	private static final String PROCESS = "HuntFood";

	private final NavigationFacade navigation;
	private final Environment environment;
	private final WorldTaskExecutor smelter;
	private final WorldTaskExecutor crafter;
	private final DropPickup pickup;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);

	private WorldTaskRequest request;
	private SessionSnapshot session;
	private HuntFoodStepArgs args;
	private TaskTerminalEvent terminal;
	private boolean emitted;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	private Phase phase = Phase.START;
	private int activeTicks, phaseTicks, huntTicks, noAnimalTicks, targetTicks, chaseRefreshTicks, cookTicks, cookBudget;
	private int kills, hits, childRuns;
	private final Set<Integer> skipped = new HashSet<>();
	private final List<String> skipNotes = new ArrayList<>();
	private Integer targetId;
	private String targetType;
	private Vec3 targetLast;
	private boolean targetDead, targetHit;
	private Vec3 lootCenter;
	private final Set<BlockPos> leftDrops = new HashSet<>();
	private GoalPosition chaseGoal;
	private boolean navigationOwned;
	private String stopReason = "";
	private Map<String, Integer> cookedAtStart = Map.of();
	private final Set<String> cookAttempted = new HashSet<>();
	private boolean furnaceCrafted;
	private String cookNote = "";
	private final List<String> stationNotes = new ArrayList<>();
	private String cookingRaw;
	private SmeltItemsStepArgs smeltArgs;
	private String processId;
	private int batchTicks;
	/** A batch sits in the furnace: a cooking failure from here on leaves raw meat in it. */
	private boolean batchLoaded;
	private String step = "";

	public HuntFoodTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera) {
		this(navigation, camera, new SmeltingProcessManager());
	}

	private HuntFoodTaskExecutor(NavigationFacade navigation, ai.moeru.airicraft.agent.control.CameraController camera,
		SmeltingProcessManager smelting) {
		// Its own process manager: the planner's smelting.output_ready events and collect_smelted_items stay out of the hunt.
		this(navigation, new MinecraftHuntEnvironment(camera, smelting), new SmeltingTaskExecutor(smelting, navigation, camera),
			new CraftingTaskExecutor(navigation, camera));
	}

	HuntFoodTaskExecutor(NavigationFacade navigation, Environment environment, WorldTaskExecutor smelter, WorldTaskExecutor crafter) {
		this.navigation = Objects.requireNonNull(navigation, "navigation");
		this.environment = Objects.requireNonNull(environment, "environment");
		this.smelter = Objects.requireNonNull(smelter, "smelter");
		this.crafter = Objects.requireNonNull(crafter, "crafter");
		this.pickup = new DropPickup(navigation, OPTIONS);
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		this.session = session;
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.HUNT_FOOD) {
			// A reflex pause hands the same task back afterwards: keep the target and the counters, only let go of the body.
			if (request != null) release();
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release();
			request = next;
			args = ((WorldTaskRequest.HuntFood) next.task()).args();
			reset();
		}
		if (terminal != null) return finishRelease();
		if (session == null || !session.companionActuationAllowed()) {
			release();
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, null, null);
			return Optional.empty();
		}
		if (!navigation.isLoaded()) return finish(false, "navigation_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null, null);
		Body body = environment.observe();
		if (body == null) return finish(false, "world_unavailable", TaskFailureCode.ENVIRONMENT_CHANGED, null, null);
		Stock stock = environment.stock();
		activeTicks++;
		phaseTicks++;
		if (phase == Phase.START) {
			cookedAtStart = stock.cooked();
			Optional<TaskTerminalEvent> ended = start(body, stock);
			if (ended.isPresent()) return ended;
		}
		Optional<TaskTerminalEvent> ended = switch (phase) {
			case START, HUNT, LOOT -> hunt(body, stock);
			case COOK_PLAN -> planCooking(body, stock);
			case CRAFT_FURNACE -> craftFurnace(body, stock);
			case SMELT -> smelt(body, stock);
			case COOK_WAIT -> waitForBatch(body, stock);
			case COLLECT -> collect(body, stock);
		};
		if (ended.isPresent()) return ended;
		setSnapshot(TaskExecutionState.RUNNING, body, stock);
		return Optional.empty();
	}

	// ---- Hunting ----

	private Optional<TaskTerminalEvent> start(Body body, Stock stock) {
		if (rawTotal(stock) >= args.count()) {
			stopReason = "already_carried";
			if (!args.cook()) return finishGathered(body, stock);
			enter(Phase.COOK_PLAN);
			return Optional.empty();
		}
		Optional<TaskTerminalEvent> stop = huntStop(body, stock);
		if (stop.isPresent()) return stop;
		List<Animal> animals = environment.animals(args.maxDistance());
		if (chooseTarget(animals, skipped, args.maxDistance(), keep()) == null) {
			boolean onlyKept = args.keepHerd() && chooseTarget(animals, skipped, args.maxDistance(), 0) != null;
			String next = onlyKept
				? "Only adults kept for the herd are in sight: call again with keepHerd=false to hunt them too, or walk to another herd"
				: "Walk to grassland (explore_for forest finds open ground nearby) and call again";
			return finish(false, "no_animals; nothing huntable in sight within " + args.maxDistance() + " blocks ("
				+ describeSight(animals, keep()) + "). It hunts adult cows, mooshrooms, pigs, sheep, chickens and rabbits you can see, "
				+ (args.keepHerd() ? "keeps the last " + KEEP_PER_KIND + " adults of a kind for the herd unless keepHerd=false, " : "")
				+ "and leaves babies and named animals. " + next, TaskFailureCode.MISSING_FACT, body, stock);
		}
		enter(Phase.HUNT);
		return Optional.empty();
	}

	/** Night, the overall timeout and fighting unarmed while hurt end the hunt as failures, with what was gathered. */
	private Optional<TaskTerminalEvent> huntStop(Body body, Stock stock) {
		if (nightOnSurface(body))
			return finish(false, "night; it is night on the overworld surface (day time " + Math.floorMod(body.dayTime(), 24_000L)
				+ "): hostile mobs spawn. Sleep (sleep_in_bed) or dig in (shelter_for_night), then call hunt_food again",
				TaskFailureCode.ENVIRONMENT_CHANGED, body, stock);
		if (huntTicks > TIMEOUT_TICKS)
			return finish(false, "timeout; hunted for " + huntTicks + " ticks without reaching " + args.count() + " raw meat (the animals"
				+ " in sight ran off or could not be reached). Walk to open grassland with more animals and call again",
				TaskFailureCode.TRANSIENT, body, stock);
		if (!hasWeapon(stock.weaponDamage()) && body.health() < MIN_HEALTH_UNARMED)
			return finish(false, String.format(Locale.ROOT, "no_weapon_and_low_health; health %.1f is below %.0f and you carry"
				+ " no weapon (a sword or an axe). Eat and let health recover, or craft a sword, then call again", body.health(),
				MIN_HEALTH_UNARMED), TaskFailureCode.MISSING_ITEM, body, stock);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> hunt(Body body, Stock stock) {
		huntTicks++;
		if (rawTotal(stock) >= args.count()) return endHunt(body, stock, "count_reached");
		Optional<TaskTerminalEvent> stop = huntStop(body, stock);
		if (stop.isPresent()) return stop;
		if (phase == Phase.LOOT) return loot(body);
		if (targetId == null) {
			Animal next = chooseTarget(environment.animals(args.maxDistance()), skipped, args.maxDistance(), keep());
			if (next == null) {
				stopNavigation();
				environment.stop();
				step = "nothing_in_sight";
				if (++noAnimalTicks >= NO_ANIMAL_TICKS) return endHunt(body, stock, "no_animals_in_sight");
				return Optional.empty();
			}
			noAnimalTicks = 0;
			targetId = next.id();
			targetType = next.type();
			targetLast = next.position();
			targetDead = targetHit = false;
			targetTicks = 0;
			chaseGoal = null;
		}
		return chase();
	}

	private Optional<TaskTerminalEvent> chase() {
		Animal animal = environment.animal(targetId);
		if (animal == null || animal.dead()) {
			if (animal != null) targetLast = animal.position();
			boolean killed = animal != null || targetDead;
			if (killed) {
				if (targetHit) kills++;
				lootCenter = targetLast;
				dropTarget();
				enter(Phase.LOOT);
			}
			else skip("lost");
			return Optional.empty();
		}
		targetLast = animal.position();
		targetDead = animal.dead();
		if (++targetTicks > TARGET_GIVE_UP_TICKS) {
			skip("gave_up");
			return Optional.empty();
		}
		if (animal.inReach() && animal.visible()) {
			stopNavigation();
			AttackResult result = environment.attack(animal.id());
			step = "attack " + result.name().toLowerCase(Locale.ROOT);
			if (result == AttackResult.HIT) {
				hits++;
				targetHit = true;
			}
			if (result != AttackResult.OUT_OF_REACH) return Optional.empty();
		}
		if (animal.visible() && animal.distance() <= DIRECT_CHASE_DISTANCE && environment.stepToward(animal.id())) {
			stopNavigation();
			step = "direct_chase";
			return Optional.empty();
		}
		step = "chase";
		GoalPosition next = chaseGoal(animal.position());
		if (refreshChase(chaseGoal, next, chaseRefreshTicks, navigationOwned && navigation.processActive())) {
			environment.stop();
			if (navigationOwned) navigation.cancel();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigateNear(next, 1, OPTIONS);
			navigationOwned = true;
			chaseGoal = next;
			chaseRefreshTicks = 0;
			navigationStall.clear();
			return Optional.empty();
		}
		chaseRefreshTicks++;
		String event = navigation.pollPathEvent().orElse("");
		if (event.contains("CALC_FAILED")) {
			skip("unreachable");
			return Optional.empty();
		}
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) skip("stuck");
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> loot(Body body) {
		List<Drop> drops = environment.drops(lootCenter, DropPickup.DROP_SEARCH_RADIUS).stream()
			.filter(drop -> !leftDrops.contains(BlockPos.containing(drop.position()))).toList();
		LootStep decision = lootStep(phaseTicks, drops, body.feet(), lootCenter);
		switch (decision.kind()) {
			case WAIT -> {
				step = "waiting_for_drops";
				pickup.stop();
				environment.stop();
			}
			case WALK -> {
				step = "picking_up";
				if (!pickup.walkTo(decision.drop().position(), activeTicks)) {
					pickup.stop();
					leftDrops.add(BlockPos.containing(decision.drop().position()));
				}
			}
			case DONE -> {
				pickup.stop();
				leftDrops.clear();
				lootCenter = null;
				enter(Phase.HUNT);
			}
		}
		return Optional.empty();
	}

	private void skip(String why) {
		if (targetId != null) {
			skipped.add(targetId);
			if (skipNotes.size() < 6) skipNotes.add(targetType + ":" + why);
		}
		dropTarget();
	}

	private void dropTarget() {
		stopNavigation();
		environment.stop();
		targetId = null;
		targetType = null;
		targetDead = targetHit = false;
		targetTicks = 0;
		chaseGoal = null;
	}

	private Optional<TaskTerminalEvent> endHunt(Body body, Stock stock, String reason) {
		if (targetId != null && targetHit) {
			// The killing blow's drops can fill the count before the chase sees the body fall.
			Animal animal = environment.animal(targetId);
			if (animal == null || animal.dead()) kills++;
		}
		dropTarget();
		pickup.stop();
		stopReason = reason;
		if (!args.cook()) return finishGathered(body, stock);
		enter(Phase.COOK_PLAN);
		return Optional.empty();
	}

	// ---- Cooking ----

	private Optional<TaskTerminalEvent> planCooking(Body body, Stock stock) {
		if (!cookingStarted()) {
			cookBudget = COOK_BASE_TICKS + COOK_PER_ITEM_TICKS * rawTotal(stock);
		}
		if (cookTimedOut()) return cookingDone(body, stock, "smelt_failed:timeout");
		String raw = nextRaw(stock.raw(), cookAttempted);
		if (raw == null) return cookingDone(body, stock, null);
		List<SmeltChoice> choices = environment.smeltChoices(raw, session.tickCount());
		boolean stationNearby = choices.stream().anyMatch(choice -> choice.source() != StationSource.PLACE);
		boolean furnaceCarried = stock.furnaces() > 0 || choices.stream().anyMatch(choice -> choice.source() == StationSource.PLACE);
		List<Fuel> fuels = usableFuels(stock.fuels());
		CookDecision decision = cookDecision(stock.raw().getOrDefault(raw, 0), stationNearby, furnaceCarried,
			furnaceCrafted ? 0 : stock.furnaceMaterial(), cookableWith(fuels, FURNACE_COOK_TICKS));
		switch (decision) {
			case NOTHING -> {
				return cookingDone(body, stock, null);
			}
			case NO_FURNACE -> {
				return cookingDone(body, stock, "no_furnace");
			}
			case NO_FUEL -> {
				return cookingDone(body, stock, "no_fuel");
			}
			case CRAFT_FURNACE -> {
				enter(Phase.CRAFT_FURNACE);
				childRuns++;
				return Optional.empty();
			}
			case COOK -> {}
		}
		if (choices.isEmpty()) {
			// A furnace is carried (or was just crafted) but no smelting option came up: no open, placeable ground near.
			return cookingDone(body, stock, "smelt_failed:no_furnace_spot");
		}
		SmeltChoice choice = chooseStation(choices);
		int quantity = Math.min(Math.min(stock.raw().getOrDefault(raw, 0), MAX_BATCH),
			Math.min(choice.maxInput(), cookableWith(fuels, choice.cookTicks())));
		if (quantity <= 0) return cookingDone(body, stock, "no_fuel");
		FuelChoice fuel = chooseFuel(fuels, quantity, choice.cookTicks());
		if (fuel == null) return cookingDone(body, stock, "no_fuel");
		cookAttempted.add(raw);
		SmeltStart start = environment.startSmelt(choice.optionId(), quantity, fuel.itemId(), fuel.quantity(), session.tickCount());
		if (start.processId() == null) return cookingDone(body, stock, "smelt_failed:" + token(start.error()));
		cookingRaw = raw;
		processId = start.processId();
		smeltArgs = new SmeltItemsStepArgs(choice.optionId(), quantity, SmeltingFuelMode.MANUAL, fuel.itemId(), fuel.quantity(), null);
		batchTicks = quantity * choice.cookTicks() + COOK_WAIT_SLACK_TICKS;
		childRuns++;
		enter(Phase.SMELT);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> craftFurnace(Body body, Stock stock) {
		if (cookTimedOut()) return cookingDone(body, stock, "smelt_failed:timeout");
		step = "crafting_furnace";
		Optional<TaskTerminalEvent> result = crafter.tick(session, Optional.of(WorldTaskRequest.craftRecipe(childId("craft"),
			request.sourceJobId(), new CraftRecipeStepArgs("minecraft:furnace", 1))));
		if (result.isEmpty()) return Optional.empty();
		crafter.tick(session, Optional.empty());
		furnaceCrafted = true;
		if (result.get().terminalState() != TaskExecutionState.COMPLETED)
			return cookingDone(body, stock, "smelt_failed:furnace_craft_" + token(result.get().message()));
		enter(Phase.COOK_PLAN);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> smelt(Body body, Stock stock) {
		if (cookTimedOut()) return cookingDone(body, stock, "smelt_failed:timeout");
		step = "loading_furnace " + shortId(cookingRaw);
		Optional<TaskTerminalEvent> result = smelter.tick(session, Optional.of(WorldTaskRequest.smeltItems(childId("smelt"),
			request.sourceJobId(), smeltArgs)));
		if (result.isEmpty()) return Optional.empty();
		smelter.tick(session, Optional.empty());
		if (result.get().terminalState() != TaskExecutionState.COMPLETED)
			return cookingDone(body, stock, "smelt_failed:" + token(result.get().message()));
		batchLoaded = true;
		enter(Phase.COOK_WAIT);
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> waitForBatch(Body body, Stock stock) {
		if (cookTimedOut()) return cookingDone(body, stock, "smelt_failed:timeout");
		stopNavigation();
		environment.stop();
		step = "cooking " + shortId(cookingRaw);
		if (environment.smeltReady(processId, session.tickCount()) || phaseTicks > batchTicks) {
			childRuns++;
			enter(Phase.COLLECT);
		}
		return Optional.empty();
	}

	private Optional<TaskTerminalEvent> collect(Body body, Stock stock) {
		if (cookTimedOut()) return cookingDone(body, stock, "smelt_failed:timeout");
		step = "collecting " + shortId(cookingRaw);
		Optional<TaskTerminalEvent> result = smelter.tick(session, Optional.of(WorldTaskRequest.collectSmeltedItems(childId("collect"),
			request.sourceJobId(), new CollectSmeltedItemsStepArgs(processId, null))));
		if (result.isEmpty()) return Optional.empty();
		smelter.tick(session, Optional.empty());
		if (result.get().terminalState() != TaskExecutionState.COMPLETED)
			return cookingDone(body, stock, "smelt_failed:" + token(result.get().message()));
		String note = result.get().message().replaceFirst("^smelting_collected\\s*", "").trim();
		if (!note.isEmpty() && stationNotes.size() < 4) stationNotes.add(note);
		processId = null;
		smeltArgs = null;
		batchLoaded = false;
		enter(Phase.COOK_PLAN);
		return Optional.empty();
	}

	private boolean cookingStarted() {
		return cookBudget > 0;
	}

	private boolean cookTimedOut() {
		return ++cookTicks > cookBudget;
	}

	private Optional<TaskTerminalEvent> cookingDone(Body body, Stock stock, String skipped) {
		if (skipped != null) cookNote = skipped;
		return finishGathered(body, environment.stock());
	}

	private String childId(String kind) {
		return request.taskId() + ":" + kind + ":" + childRuns;
	}

	// ---- Pure decisions ----

	/** Whether this animal may be hunted at all: an adult food animal in sight, not named, leashed or protected. */
	static boolean huntable(Animal animal, double maxDistance) {
		return FOOD_ANIMALS.contains(animal.type()) && !animal.baby() && !animal.dead() && !animal.named() && !animal.protectedArea()
			&& animal.visible() && animal.distance() <= maxDistance;
	}

	/**
	 * The animal to hunt next: the nearest huntable one (water and steep height cost extra), never a skipped one, and never one
	 * of the last {@link #KEEP_PER_KIND} adults of its kind in sight (counting skipped, named and protected adults: they live).
	 */
	static Animal chooseTarget(List<Animal> animals, Set<Integer> skipped, double maxDistance) {
		return chooseTarget(animals, skipped, maxDistance, KEEP_PER_KIND);
	}

	/** {@link #chooseTarget(List, Set, double)} sparing {@code keep} adults per kind ({@code 0} when keepHerd is false). */
	static Animal chooseTarget(List<Animal> animals, Set<Integer> skipped, double maxDistance, int keep) {
		Map<String, Integer> adults = new HashMap<>();
		for (Animal animal : animals) {
			if (FOOD_ANIMALS.contains(animal.type()) && !animal.baby() && !animal.dead() && animal.visible())
				adults.merge(animal.type(), 1, Integer::sum);
		}
		Map<String, List<Animal>> byKind = new HashMap<>();
		for (Animal animal : animals) {
			if (huntable(animal, maxDistance)) byKind.computeIfAbsent(animal.type(), ignored -> new ArrayList<>()).add(animal);
		}
		Animal best = null;
		double bestCost = Double.MAX_VALUE;
		for (var entry : byKind.entrySet()) {
			int spare = adults.getOrDefault(entry.getKey(), 0) - keep;
			if (spare <= 0) continue;
			// The farthest ones are the ones left alive.
			List<Animal> nearestFirst = entry.getValue().stream().sorted(Comparator.comparingDouble(Animal::distance)).toList();
			for (Animal animal : nearestFirst.subList(0, Math.min(spare, nearestFirst.size()))) {
				if (skipped.contains(animal.id())) continue;
				double cost = cost(animal);
				if (cost < bestCost) {
					best = animal;
					bestCost = cost;
				}
			}
		}
		return best;
	}

	private int keep() {
		return args.keepHerd() ? KEEP_PER_KIND : 0;
	}

	static double cost(Animal animal) {
		return animal.distance() + (animal.inWater() ? WATER_COST : 0.0D) + HEIGHT_COST * Math.max(0.0D, Math.abs(animal.heightDifference()) - 2.0D);
	}

	/** What is in sight, for a no_animals reason: kinds with adults kept for the herd and babies. */
	static String describeSight(List<Animal> animals) {
		return describeSight(animals, KEEP_PER_KIND);
	}

	static String describeSight(List<Animal> animals, int keep) {
		Map<String, int[]> counts = new TreeMap<>();
		for (Animal animal : animals) {
			if (!FOOD_ANIMALS.contains(animal.type()) || !animal.visible()) continue;
			int[] count = counts.computeIfAbsent(animal.type(), ignored -> new int[3]);
			if (animal.baby()) count[1]++;
			else if (animal.named() || animal.protectedArea()) count[2]++;
			else count[0]++;
		}
		if (counts.isEmpty()) return "no food animals in sight";
		return counts.entrySet().stream().map(entry -> {
			int[] count = entry.getValue();
			StringBuilder text = new StringBuilder(entry.getKey()).append(" adults=").append(count[0]);
			if (count[0] > 0 && count[0] <= keep) text.append(" (kept for the herd)");
			if (count[1] > 0) text.append(" babies=").append(count[1]);
			if (count[2] > 0) text.append(" named_or_protected=").append(count[2]);
			return text.toString();
		}).collect(Collectors.joining(", "));
	}

	/**
	 * Whether a running chase route is replaced: only when the animal moved {@code 2+} blocks from the route's goal or the
	 * walk went idle, as {@link EntityInteractionTaskExecutor#shouldRefreshChaseGoal} explains: restarting a running route
	 * every few ticks kept the body standing while the new search ran.
	 */
	static boolean refreshChase(GoalPosition current, GoalPosition next, int ticksSinceRefresh, boolean navigating) {
		return EntityInteractionTaskExecutor.shouldRefreshChaseGoal(current, next, ticksSinceRefresh, navigating);
	}

	static GoalPosition chaseGoal(Vec3 animal) {
		BlockPos cell = BlockPos.containing(animal.x, animal.y + 0.125D, animal.z);
		return new GoalPosition(cell.getX(), cell.getY(), cell.getZ(), false);
	}

	/** At night on the overworld surface (when a bed works: clear 12,542-23,459, sooner in rain, any thunderstorm). */
	static boolean nightOnSurface(Body body) {
		return body.overworld() && body.skyVisible() && SleepInBedTaskExecutor.sleepWindow(body.dayTime(), body.raining(), body.thundering());
	}

	static boolean hasWeapon(double weaponDamage) {
		return weaponDamage >= MIN_WEAPON_DAMAGE;
	}

	static int rawTotal(Stock stock) {
		return stock.raw().values().stream().mapToInt(Integer::intValue).sum();
	}

	/** After the kill: wait for the drops to land, then walk onto the nearest one in reach, then go on. */
	static LootStep lootStep(int phaseTicks, List<Drop> drops, Vec3 feet, Vec3 center) {
		if (phaseTicks <= DropPickup.PICKUP_WAIT_TICKS) return LootStep.WAIT;
		if (phaseTicks > DropPickup.DROP_TIMEOUT_TICKS) return LootStep.DONE;
		Drop nearest = drops.stream()
			.filter(drop -> drop.safe() && DropPickup.horizontalDistance(center, drop.position()) <= DropPickup.DROP_WALK_RADIUS)
			.min(Comparator.comparingDouble(drop -> DropPickup.horizontalDistance(feet, drop.position()))).orElse(null);
		return nearest == null ? LootStep.DONE : LootStep.walk(nearest);
	}

	/** Whether and how the raw meat can be cooked. */
	static CookDecision cookDecision(int raw, boolean stationNearby, boolean furnaceCarried, int furnaceMaterial, int cookable) {
		if (raw <= 0) return CookDecision.NOTHING;
		boolean furnace = stationNearby || furnaceCarried;
		if (!furnace && furnaceMaterial < FURNACE_MATERIAL) return CookDecision.NO_FURNACE;
		if (cookable <= 0) return CookDecision.NO_FUEL;
		return furnace ? CookDecision.COOK : CookDecision.CRAFT_FURNACE;
	}

	/** The raw meat to cook next: the most carried kind not tried yet. */
	static String nextRaw(Map<String, Integer> raw, Set<String> attempted) {
		return raw.entrySet().stream().filter(entry -> entry.getValue() > 0 && !attempted.contains(entry.getKey()))
			.max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
			.map(Map.Entry::getKey).orElse(null);
	}

	/** Fuel that may burn: never tools, weapons or buckets (they do not stack) or blocks kept for building. */
	static List<Fuel> usableFuels(List<Fuel> fuels) {
		return fuels.stream().filter(fuel -> fuel.maxStack() > 1 && fuel.count() > 0 && fuel.burnTicks() > 0
			&& !KEPT_FUELS.contains(fuel.itemId()) && !COOKED.containsKey(fuel.itemId())).toList();
	}

	/** Most items one kind of fuel can cook: smelting loads a single fuel kind per batch. */
	static int cookableWith(List<Fuel> fuels, int cookTicks) {
		int best = 0;
		for (Fuel fuel : fuels) best = (int) Math.max(best, Math.min(Integer.MAX_VALUE, (long) fuel.count() * fuel.burnTicks() / cookTicks));
		return best;
	}

	/** The cheapest fuel that cooks the whole batch by itself (whole logs last, then the fewest burn ticks committed), or null. */
	static FuelChoice chooseFuel(List<Fuel> fuels, int quantity, int cookTicks) {
		FuelChoice best = null;
		int bestTicks = 0;
		for (Fuel fuel : fuels) {
			int needed = SmeltingTaskExecutor.fuelItemsNeeded(quantity * cookTicks, fuel.burnTicks());
			if (needed <= 0 || needed > fuel.count()) continue;
			if (best == null || SmeltingFuelCost.compare(fuel.itemId(), needed, fuel.burnTicks(), best.itemId(), best.quantity(), bestTicks) < 0) {
				best = new FuelChoice(fuel.itemId(), needed);
				bestTicks = fuel.burnTicks();
			}
		}
		return best;
	}

	/** An open furnace screen, then an empty station nearby (a smoker cooks twice as fast), then a carried furnace placed. */
	static SmeltChoice chooseStation(List<SmeltChoice> choices) {
		return choices.stream().min(Comparator.comparingInt((SmeltChoice choice) -> choice.source().ordinal())
			.thenComparingInt(SmeltChoice::cookTicks)).orElse(null);
	}

	/** {@code a xN,b xM}: item paths with counts, or none. */
	static String describeItems(Map<String, Integer> items) {
		String text = new TreeMap<>(items).entrySet().stream().filter(entry -> entry.getValue() > 0)
			.map(entry -> shortId(entry.getKey()) + " x" + entry.getValue()).collect(Collectors.joining(","));
		return text.isEmpty() ? "none" : text;
	}

	static Map<String, Integer> gained(Map<String, Integer> before, Map<String, Integer> after) {
		Map<String, Integer> gained = new TreeMap<>();
		after.forEach((item, count) -> {
			int delta = count - before.getOrDefault(item, 0);
			if (delta > 0) gained.put(item, delta);
		});
		return gained;
	}

	private static String shortId(String id) {
		return id == null ? "" : id.replaceFirst("^minecraft:", "");
	}

	/** The first word of a child's reason, safe inside one {@code key=value}. */
	static String token(String message) {
		if (message == null || message.isBlank()) return "unknown";
		return message.trim().split("[\\s;,]+", 2)[0].replaceAll("[^A-Za-z0-9_:.=-]", "_");
	}

	// ---- Lifecycle ----

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
		step = "";
	}

	private void stopNavigation() {
		if (navigationOwned) navigation.cancel();
		navigationOwned = false;
		chaseGoal = null;
		chaseRefreshTicks = 0;
		navigationStall.clear();
	}

	private void release() {
		stopNavigation();
		pickup.stop();
		smelter.tick(session, Optional.empty());
		crafter.tick(session, Optional.empty());
		environment.release();
	}

	private void reset() {
		terminal = null;
		emitted = false;
		phase = Phase.START;
		activeTicks = phaseTicks = huntTicks = noAnimalTicks = targetTicks = chaseRefreshTicks = cookTicks = cookBudget = 0;
		kills = hits = childRuns = 0;
		skipped.clear();
		skipNotes.clear();
		targetId = null;
		targetType = null;
		targetLast = null;
		targetDead = targetHit = false;
		lootCenter = null;
		leftDrops.clear();
		chaseGoal = null;
		stopReason = "";
		cookedAtStart = Map.of();
		cookAttempted.clear();
		furnaceCrafted = false;
		cookNote = "";
		stationNotes.clear();
		cookingRaw = null;
		smeltArgs = null;
		processId = null;
		batchTicks = 0;
		batchLoaded = false;
		step = "";
	}

	private String gatheredText(Stock stock) {
		StringBuilder text = new StringBuilder("raw=").append(describeItems(stock.raw()))
			.append(" cooked=").append(describeItems(gained(cookedAtStart, stock.cooked())))
			.append(" killed=").append(kills).append(" skipped=").append(skipped.size());
		if (!skipNotes.isEmpty()) text.append(" skips=").append(String.join(",", skipNotes));
		return text.toString();
	}

	private Optional<TaskTerminalEvent> finishGathered(Body body, Stock stock) {
		StringBuilder message = new StringBuilder("food_gathered ").append(gatheredText(stock));
		if (!stopReason.isEmpty()) message.append(" stopped=").append(stopReason);
		if (!cookNote.isEmpty()) message.append(" cookSkipped=").append(cookNote);
		if (!stationNotes.isEmpty()) message.append(' ').append(String.join(" ", stationNotes));
		if (cookNote.startsWith("smelt_failed") && batchLoaded)
			message.append(" (raw meat may still be in the furnace: collect_smelted_items)");
		return finishRaw(true, message.toString(), null, body);
	}

	private Optional<TaskTerminalEvent> finish(boolean success, String reason, TaskFailureCode code, Body body,
		Stock stock) {
		String text = reason;
		if (stock != null) {
			int split = reason.indexOf(';');
			String head = split < 0 ? reason : reason.substring(0, split);
			String tail = split < 0 ? "" : reason.substring(split);
			text = head + " " + gatheredText(stock) + tail;
		}
		return finishRaw(success, text, code, body);
	}

	private Optional<TaskTerminalEvent> finishRaw(boolean success, String message, TaskFailureCode code, Body body) {
		release();
		terminal = new TaskTerminalEvent(request.taskId(), null, success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED,
			message + summary(body), success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : code);
		return finishRelease();
	}

	private Optional<TaskTerminalEvent> finishRelease() {
		release();
		NavigationRelease.release(navigation);
		snapshot = new TaskExecutionSnapshot(terminal.terminalState(), request.taskId(), null, PROCESS,
			terminal.message(), null, terminal.terminationCause());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private static String summary(Body body) {
		if (body == null) return "";
		return String.format(Locale.ROOT, " health=%.1f food=%d pos=%d,%d,%d", body.health(), body.food(),
			body.block().getX(), body.block().getY(), body.block().getZ());
	}

	private void setSnapshot(TaskExecutionState state, Body body, Stock stock) {
		StringBuilder detail = new StringBuilder("phase=").append(phase.name()).append(step.isEmpty() ? "" : " step=" + step);
		if (targetId != null) detail.append(" target=").append(targetType).append('#').append(targetId).append(" targetTicks=").append(targetTicks);
		detail.append(" killed=").append(kills).append(" hits=").append(hits).append(" skipped=").append(skipped.size());
		if (stock != null) detail.append(" raw=").append(describeItems(stock.raw()));
		detail.append(" huntTicks=").append(huntTicks);
		if (noAnimalTicks > 0) detail.append(" noAnimalTicks=").append(noAnimalTicks);
		if (processId != null) detail.append(" process=").append(processId);
		if (!cookNote.isEmpty()) detail.append(" cookSkipped=").append(cookNote);
		detail.append(summary(body));
		snapshot = new TaskExecutionSnapshot(state, request == null ? null : request.taskId(), null, PROCESS, detail.toString(), null, null);
	}

	@Override
	public TaskExecutionSnapshot snapshot() { return snapshot; }

	/** The reflex hands the same task back: keep the target and the counters, only let go of the body. A smelting or crafting
	 * child step lets go too and runs again from its start: loading tops the furnace up to the batch, so a second run adds nothing twice. */
	@Override
	public void pauseForReflex() {
		if (request != null) release();
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void onWorldLeave() {
		stopNavigation();
		pickup.stop();
		smelter.onWorldLeave();
		crafter.onWorldLeave();
		environment.release();
		request = null;
		snapshot = TaskExecutionSnapshot.idle();
	}

	@Override
	public void shutdown() {
		stopNavigation();
		pickup.stop();
		smelter.shutdown();
		crafter.shutdown();
		environment.release();
	}

	enum Phase { START, HUNT, LOOT, COOK_PLAN, CRAFT_FURNACE, SMELT, COOK_WAIT, COLLECT }

	enum CookDecision { NOTHING, NO_FURNACE, NO_FUEL, CRAFT_FURNACE, COOK }

	enum AttackResult { HIT, COOLDOWN, AIMING, OUT_OF_REACH }

	/** Where a smelting option's station is: the open screen, an empty one nearby, or a carried furnace to place. */
	enum StationSource { OPEN_SCREEN, NEARBY, PLACE }

	record LootStep(Kind kind, Drop drop) {
		enum Kind { WAIT, WALK, DONE }
		static final LootStep WAIT = new LootStep(Kind.WAIT, null);
		static final LootStep DONE = new LootStep(Kind.DONE, null);
		static LootStep walk(Drop drop) { return new LootStep(Kind.WALK, drop); }
	}

	/**
	 * A food animal near the body. {@code distance}: eye to the middle of its box; {@code visible}: an eye-to-eye line of
	 * sight; {@code heightDifference}: its feet above the body's; {@code protectedArea}: inside a preserved place;
	 * {@code named}: a name tag or a leash (someone's animal); {@code dead}: dying or dead.
	 */
	record Animal(int id, String type, boolean baby, boolean named, boolean protectedArea, Vec3 position, double distance,
		double heightDifference, boolean visible, boolean inWater, boolean inReach, boolean dead) {}

	/** An item lying near a kill; {@code safe}: on standable ground with no lava, fire or deep drop beside it. */
	record Drop(Vec3 position, boolean safe) {}

	/** One kind of carried fuel: how many, ticks each burns, and its stack size (1 for tools, weapons and buckets). */
	record Fuel(String itemId, int count, int burnTicks, int maxStack) {}

	record FuelChoice(String itemId, int quantity) {}

	/** A registered smelting option for one raw meat. */
	record SmeltChoice(String optionId, StationSource source, String kind, int cookTicks, int maxInput) {}

	/** The started process, or why it could not start. */
	record SmeltStart(String processId, String error) {}

	/**
	 * What the inventory holds: raw meat and cooked food by item id, the best weapon's attack damage, carried furnaces,
	 * furnace material (cobblestone, cobbled deepslate, blackstone) and fuels.
	 */
	record Stock(Map<String, Integer> raw, Map<String, Integer> cooked, double weaponDamage, int furnaces, int furnaceMaterial,
		List<Fuel> fuels) {
		Stock {
			raw = Map.copyOf(raw);
			cooked = Map.copyOf(cooked);
			fuels = List.copyOf(fuels);
		}
	}

	/** What the task reads each tick. {@code block}: the feet cell; {@code skyVisible}: the sky over the head. */
	record Body(Vec3 feet, BlockPos block, float health, int food, long dayTime, boolean raining, boolean thundering,
		boolean overworld, boolean skyVisible) {}

	interface Environment {
		/** Null when no world is loaded. */
		Body observe();
		Stock stock();
		/** Food animals within {@code maxDistance} blocks, in sight or not. */
		List<Animal> animals(double maxDistance);
		/** The animal with this entity id, or null once it is gone. */
		Animal animal(int id);
		/** Selects the best weapon, aims and swings once the attack is charged. */
		AttackResult attack(int id);
		/** Walks straight at the animal for a tick; false when that step is unsafe (lava, fire, a deep drop, water) or stuck. */
		boolean stepToward(int id);
		/** Items lying within {@code radius} blocks of {@code center}. */
		List<Drop> drops(Vec3 center, double radius);
		/** Registers the smelting options for this raw meat and returns the usable ones (empty stations, a carried furnace). */
		List<SmeltChoice> smeltChoices(String rawItemId, long tick);
		/** Starts tracking a process for the option; the smelting child task then loads the furnace. */
		SmeltStart startSmelt(String optionId, int quantity, String fuelItemId, int fuelQuantity, long tick);
		/** Whether the process's output is ready (seen in the furnace, or its cook time ran out). */
		boolean smeltReady(String processId, long tick);
		/** Lets go of movement. */
		void stop();
		void release();
	}
}
