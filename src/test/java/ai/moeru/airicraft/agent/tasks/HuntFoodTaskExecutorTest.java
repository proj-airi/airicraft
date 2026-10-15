package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Animal;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.CookDecision;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Drop;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.Fuel;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.SmeltChoice;
import ai.moeru.airicraft.agent.tasks.HuntFoodTaskExecutor.StationSource;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HuntFoodTaskExecutorTest {
	private static final long NOON = 6_000L;
	private static final long MIDNIGHT = 18_000L;

	// ---- Target selection ----

	@Test void neverTargetsBabiesNamedProtectedOrUnseenAnimals() {
		List<AnimalSpec> animals = List.of(
			animal(1, "cow", 3.0).baby(), animal(2, "cow", 4.0).named(), animal(3, "cow", 5.0).protectedArea(),
			animal(4, "cow", 6.0).hidden(), animal(5, "cow", 7.0), animal(6, "cow", 8.0), animal(7, "cow", 9.0), animal(8, "cow", 10.0));
		// Six adults in sight (not the baby or the unseen one): four spare; the named and protected ones still never go.
		assertEquals(5, HuntFoodTaskExecutor.chooseTarget(build(animals), Set.of(), 32).id());
		assertNull(HuntFoodTaskExecutor.chooseTarget(build(List.of(animal(1, "zombie", 2.0), animal(2, "horse", 2.0))), Set.of(), 32),
			"only cows, mooshrooms, pigs, sheep, chickens and rabbits");
		assertNull(HuntFoodTaskExecutor.chooseTarget(build(List.of(animal(1, "pig", 40.0), animal(2, "pig", 41.0), animal(3, "pig", 42.0))),
			Set.of(), 32), "beyond maxDistance");
	}

	@Test void leavesTheLastTwoAdultsOfAKindAlive() {
		assertNull(HuntFoodTaskExecutor.chooseTarget(build(List.of(animal(1, "cow", 3.0), animal(2, "cow", 4.0))), Set.of(), 32));
		assertNull(HuntFoodTaskExecutor.chooseTarget(build(List.of(animal(1, "cow", 3.0), animal(2, "cow", 4.0),
			animal(3, "cow", 2.0).baby(), animal(4, "cow", 2.5).baby())), Set.of(), 32), "babies do not make a breeding pair");
		var three = build(List.of(animal(1, "cow", 9.0), animal(2, "cow", 4.0), animal(3, "cow", 6.0)));
		assertEquals(2, HuntFoodTaskExecutor.chooseTarget(three, Set.of(), 32).id(), "the nearest; the two farthest stay");
		assertNull(HuntFoodTaskExecutor.chooseTarget(three, Set.of(2), 32), "a skipped one is not replaced by a kept one");
		var mixed = build(List.of(animal(1, "cow", 3.0), animal(2, "cow", 4.0), animal(3, "pig", 12.0), animal(4, "pig", 13.0),
			animal(5, "pig", 14.0)));
		assertEquals(3, HuntFoodTaskExecutor.chooseTarget(mixed, Set.of(), 32).id(), "a pair of cows is kept; a spare pig goes");
		var named = build(List.of(animal(1, "sheep", 3.0), animal(2, "sheep", 4.0).named(), animal(3, "sheep", 5.0)));
		assertEquals(1, HuntFoodTaskExecutor.chooseTarget(named, Set.of(), 32).id(), "named adults still count toward the herd");
	}

	@Test void prefersDryLevelAnimals() {
		var animals = build(List.of(animal(1, "pig", 5.0).wet(), animal(2, "pig", 8.0), animal(3, "pig", 20.0), animal(4, "pig", 21.0)));
		assertEquals(2, HuntFoodTaskExecutor.chooseTarget(animals, Set.of(), 32).id(), "a pig in water costs 8 blocks more");
	}

	@Test void describesWhatIsInSightWhenNothingIsHuntable() {
		String text = HuntFoodTaskExecutor.describeSight(build(List.of(animal(1, "cow", 3.0), animal(2, "cow", 4.0),
			animal(3, "cow", 2.0).baby(), animal(4, "pig", 5.0).named())));
		assertEquals("cow adults=2 (kept for the herd) babies=1, pig adults=0 named_or_protected=1", text);
		assertEquals("no food animals in sight", HuntFoodTaskExecutor.describeSight(List.of()));
	}

	// ---- Chase ----

	@Test void aRunningChaseRouteIsReplacedOnlyWhenTheAnimalMovedTwoBlocksOrTheWalkWentIdle() {
		GoalPosition goal = new GoalPosition(10, 64, 10, false);
		assertTrue(HuntFoodTaskExecutor.refreshChase(null, goal, 0, false));
		assertFalse(HuntFoodTaskExecutor.refreshChase(goal, new GoalPosition(11, 64, 10, false), 3, true));
		assertFalse(HuntFoodTaskExecutor.refreshChase(goal, new GoalPosition(11, 64, 11, false), 40, true), "1.4 blocks, still walking");
		assertTrue(HuntFoodTaskExecutor.refreshChase(goal, new GoalPosition(12, 64, 10, false), 3, true));
		assertFalse(HuntFoodTaskExecutor.refreshChase(goal, goal, 3, false), "a fresh request may not be active yet");
		assertTrue(HuntFoodTaskExecutor.refreshChase(goal, goal, 10, false), "idle: walk again");
	}

	@Test void theExecutorKeepsARunningRouteWhileTheAnimalShuffles() {
		Fixture f = new Fixture(new HuntFoodStepArgs(2, false, 32));
		f.nav.arrive = false;
		f.nav.active = true;
		FakeAnimal cow = f.env.add("cow", new Vec3(20.5, 64, 0.5));
		f.env.add("cow", new Vec3(30.5, 64, 0.5));
		f.env.add("cow", new Vec3(31.5, 64, 0.5));
		f.tick(5);
		assertEquals(1, f.nav.starts.size(), f.nav.starts.toString());
		cow.pos = cow.pos.add(1.2, 0, 0);
		f.tick(5);
		assertEquals(1, f.nav.starts.size(), "moved 1.2 blocks: the route runs on");
		cow.pos = cow.pos.add(1.5, 0, 0);
		f.tick(1);
		assertEquals(2, f.nav.starts.size(), "moved 2.7 blocks from the goal: a new route");
		assertEquals(new GoalPosition(23, 64, 0, false), f.nav.starts.get(1));
		assertSame(HuntFoodTaskExecutor.OPTIONS, f.nav.options);
		assertTrue(f.nav.options.walkOnly());
		assertEquals(Integer.valueOf(3), f.nav.options.maxSafeFall());
	}

	@Test void givesUpOnAnAnimalAfterThreeHundredTicksAndHuntsAnother() {
		Fixture f = new Fixture(new HuntFoodStepArgs(2, false, 32));
		f.nav.arrive = false;
		FakeAnimal runner = f.env.add("pig", new Vec3(6.5, 64, 0.5));
		f.env.add("pig", new Vec3(9.5, 64, 0.5));
		f.env.add("pig", new Vec3(25.5, 64, 0.5));
		f.env.add("pig", new Vec3(26.5, 64, 0.5));
		f.tick(HuntFoodTaskExecutor.TARGET_GIVE_UP_TICKS);
		assertTrue(f.snapshotText().contains("target=pig#" + runner.id), f.snapshotText());
		f.tick(3);
		assertTrue(f.snapshotText().contains("skipped=1"), f.snapshotText());
		assertFalse(f.snapshotText().contains("target=pig#" + runner.id), f.snapshotText());
		assertTrue(f.events.isEmpty());
	}

	@Test void anUnreachableAnimalIsSkipped() {
		Fixture f = new Fixture(new HuntFoodStepArgs(2, false, 32));
		f.nav.arrive = false;
		f.nav.failNext = true;
		FakeAnimal stuck = f.env.add("sheep", new Vec3(12.5, 66, 0.5));
		f.env.add("sheep", new Vec3(20.5, 64, 0.5));
		f.env.add("sheep", new Vec3(21.5, 64, 0.5));
		f.tick(5);
		assertTrue(f.snapshotText().contains("skipped=1"), f.snapshotText());
		assertNull(HuntFoodTaskExecutor.chooseTarget(f.env.animals(32), Set.of(stuck.id), 32), "only the herd pair is left");
	}

	// ---- Hunting to the end ----

	@Test void huntsUntilTheInventoryHoldsCountRawMeatAndPicksUpTheDrops() {
		Fixture f = new Fixture(new HuntFoodStepArgs(4, false, 32));
		for (int i = 0; i < 4; i++) f.env.add("cow", new Vec3(5.5 + 3 * i, 64, 0.5));
		f.tick(400);
		var end = f.single();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("food_gathered raw=beef x4 cooked=none killed=2 skipped=0 stopped=count_reached"), end.message());
		assertTrue(end.message().contains("health=20.0 food=14 pos="), end.message());
		assertEquals(2, f.env.living("cow"), "the last two cows live");
		assertEquals(2, f.env.leather, "leather was picked up too");
		assertTrue(f.env.hits >= 6, "three hits per cow on the cooldown");
	}

	@Test void endsWithWhatItHasOnceNothingHuntableWasInSightForFourHundredTicks() {
		Fixture f = new Fixture(new HuntFoodStepArgs(8, false, 32));
		for (int i = 0; i < 3; i++) f.env.add("chicken", new Vec3(5.5 + 3 * i, 64, 0.5));
		f.tick(200);
		assertTrue(f.events.isEmpty());
		assertTrue(f.snapshotText().contains("noAnimalTicks="), f.snapshotText());
		f.tick(HuntFoodTaskExecutor.NO_ANIMAL_TICKS);
		var end = f.single();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("food_gathered raw=chicken x1 cooked=none killed=1 skipped=0 stopped=no_animals_in_sight"),
			end.message());
	}

	@Test void failsNoAnimalsWhenNothingHuntableIsInSightAtTheStart() {
		Fixture f = new Fixture(new HuntFoodStepArgs(6, true, 32));
		f.env.add("cow", new Vec3(5.5, 64, 0.5));
		f.env.add("cow", new Vec3(6.5, 64, 0.5));
		f.tick(1);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertEquals(TaskFailureCode.MISSING_FACT, end.failureCode());
		assertTrue(end.message().startsWith("no_animals raw=none cooked=none killed=0 skipped=0; nothing huntable"), end.message());
		assertTrue(end.message().contains("cow adults=2 (kept for the herd)"), end.message());
		assertTrue(f.env.attacks.isEmpty());
	}

	@Test void alreadyCarryingCountSkipsTheHunt() {
		Fixture f = new Fixture(new HuntFoodStepArgs(3, false, 32));
		f.env.raw.put("minecraft:porkchop", 3);
		f.tick(1);
		var end = f.single();
		assertTrue(end.message().startsWith("food_gathered raw=porkchop x3 cooked=none killed=0 skipped=0 stopped=already_carried"), end.message());
	}

	@Test void stopsAtNightOnTheSurfaceButNotUnderground() {
		assertTrue(HuntFoodTaskExecutor.nightOnSurface(body(MIDNIGHT, true, true)));
		assertFalse(HuntFoodTaskExecutor.nightOnSurface(body(NOON, true, true)));
		assertFalse(HuntFoodTaskExecutor.nightOnSurface(body(MIDNIGHT, true, false)), "under a roof or in a cave");
		assertFalse(HuntFoodTaskExecutor.nightOnSurface(body(MIDNIGHT, false, true)), "the Nether has no night");
		assertFalse(HuntFoodTaskExecutor.nightOnSurface(body(12_000L, true, true)), "sunset, before a bed works");

		Fixture f = new Fixture(new HuntFoodStepArgs(6, true, 32));
		f.env.raw.put("minecraft:beef", 2);
		for (int i = 0; i < 4; i++) f.env.add("cow", new Vec3(15.5 + 3 * i, 64, 0.5));
		f.nav.arrive = false;
		f.tick(10);
		assertTrue(f.events.isEmpty());
		f.env.dayTime = 13_000L;
		f.tick(1);
		var end = f.single();
		assertEquals(TaskExecutionState.FAILED, end.terminalState());
		assertEquals(TaskFailureCode.ENVIRONMENT_CHANGED, end.failureCode());
		assertTrue(end.message().startsWith("night raw=beef x2 cooked=none killed=0 skipped=0; it is night"), end.message());
		assertTrue(f.smelter.requests.isEmpty(), "nothing is cooked at night");
	}

	@Test void failsWithoutAWeaponWhileHurt() {
		Fixture f = new Fixture(new HuntFoodStepArgs(6, true, 32));
		for (int i = 0; i < 3; i++) f.env.add("pig", new Vec3(5.5 + 3 * i, 64, 0.5));
		f.env.weaponDamage = 1.0D;
		f.env.health = 7.0F;
		f.tick(1);
		var end = f.single();
		assertEquals(TaskFailureCode.MISSING_ITEM, end.failureCode());
		assertTrue(end.message().startsWith("no_weapon_and_low_health"), end.message());

		Fixture armed = new Fixture(new HuntFoodStepArgs(6, true, 32));
		for (int i = 0; i < 3; i++) armed.env.add("pig", new Vec3(5.5 + 3 * i, 64, 0.5));
		armed.env.health = 7.0F;
		armed.tick(1);
		assertTrue(armed.events.isEmpty(), "a sword fights at any health; the reflex watches the body");
		assertTrue(HuntFoodTaskExecutor.hasWeapon(4.0D));
		assertFalse(HuntFoodTaskExecutor.hasWeapon(3.0D), "a stone pickaxe is not a weapon");
	}

	@Test void timesOutAfterThirtySixHundredTicksOfHunting() {
		Fixture f = new Fixture(new HuntFoodStepArgs(16, false, 48));
		f.nav.arrive = false;
		for (int i = 0; i < 40; i++) f.env.add("rabbit", new Vec3(20.5 + 0.5 * i, 64, 0.5));
		f.tick(HuntFoodTaskExecutor.TIMEOUT_TICKS + 2);
		var end = f.single();
		assertEquals(TaskFailureCode.TRANSIENT, end.failureCode());
		assertTrue(end.message().startsWith("timeout raw=none cooked=none killed=0 skipped=11"), end.message());
	}

	@Test void aReflexPauseResumesTheSameHunt() {
		Fixture f = new Fixture(new HuntFoodStepArgs(4, false, 32));
		f.nav.arrive = false;
		FakeAnimal first = f.env.add("cow", new Vec3(15.5, 64, 0.5));
		f.env.add("cow", new Vec3(25.5, 64, 0.5));
		f.env.add("cow", new Vec3(26.5, 64, 0.5));
		f.tick(20);
		String before = f.snapshotText();
		assertTrue(before.contains("target=cow#" + first.id), before);
		for (int i = 0; i < 50; i++) f.executor.tick(f.session(), Optional.empty());
		assertEquals(TaskExecutionState.IDLE, f.executor.snapshot().state());
		assertTrue(f.env.released > 0, "the body is let go during the pause");
		f.tick(1);
		assertTrue(f.snapshotText().contains("target=cow#" + first.id + " targetTicks=21"), f.snapshotText());
	}

	// ---- Cooking ----

	@Test void decidesWhetherAndHowToCook() {
		assertEquals(CookDecision.NOTHING, HuntFoodTaskExecutor.cookDecision(0, true, true, 8, 10));
		assertEquals(CookDecision.COOK, HuntFoodTaskExecutor.cookDecision(4, true, false, 0, 10));
		assertEquals(CookDecision.COOK, HuntFoodTaskExecutor.cookDecision(4, false, true, 0, 10));
		assertEquals(CookDecision.CRAFT_FURNACE, HuntFoodTaskExecutor.cookDecision(4, false, false, 8, 10));
		assertEquals(CookDecision.NO_FURNACE, HuntFoodTaskExecutor.cookDecision(4, false, false, 7, 10));
		assertEquals(CookDecision.NO_FUEL, HuntFoodTaskExecutor.cookDecision(4, true, false, 0, 0));
		assertEquals(CookDecision.NO_FUEL, HuntFoodTaskExecutor.cookDecision(4, false, false, 8, 0), "no cobblestone is spent without fuel");
	}

	@Test void burnsCheapFuelNeverToolsAndLogsOnlyWhenNothingElseCovers() {
		List<Fuel> carried = List.of(new Fuel("minecraft:wooden_sword", 1, 200, 1), new Fuel("minecraft:crafting_table", 1, 300, 64),
			new Fuel("minecraft:oak_log", 10, 300, 64), new Fuel("minecraft:coal", 2, 1_600, 64), new Fuel("minecraft:stick", 3, 100, 64));
		List<Fuel> usable = HuntFoodTaskExecutor.usableFuels(carried);
		assertEquals(List.of("minecraft:oak_log", "minecraft:coal", "minecraft:stick"), usable.stream().map(Fuel::itemId).toList());
		assertEquals(16, HuntFoodTaskExecutor.cookableWith(usable, 200), "two coal cook sixteen; a batch uses one kind");
		assertEquals(new HuntFoodTaskExecutor.FuelChoice("minecraft:coal", 1), HuntFoodTaskExecutor.chooseFuel(usable, 6, 200));
		assertEquals(new HuntFoodTaskExecutor.FuelChoice("minecraft:stick", 2), HuntFoodTaskExecutor.chooseFuel(usable, 1, 200));
		assertEquals(new HuntFoodTaskExecutor.FuelChoice("minecraft:coal", 2), HuntFoodTaskExecutor.chooseFuel(usable, 12, 200));
		List<Fuel> logsOnly = HuntFoodTaskExecutor.usableFuels(List.of(new Fuel("minecraft:oak_log", 10, 300, 64)));
		assertEquals(new HuntFoodTaskExecutor.FuelChoice("minecraft:oak_log", 4), HuntFoodTaskExecutor.chooseFuel(logsOnly, 6, 200));
		assertNull(HuntFoodTaskExecutor.chooseFuel(HuntFoodTaskExecutor.usableFuels(List.of(new Fuel("minecraft:stick", 1, 100, 64))), 1, 200));
	}

	@Test void prefersAnOpenOrNearbyStationAndASmokerOverPlacingAFurnace() {
		var place = new SmeltChoice("place", StationSource.PLACE, "furnace", 200, 6);
		var furnace = new SmeltChoice("furnace", StationSource.NEARBY, "furnace", 200, 6);
		var smoker = new SmeltChoice("smoker", StationSource.NEARBY, "smoker", 100, 6);
		assertEquals("smoker", HuntFoodTaskExecutor.chooseStation(List.of(place, furnace, smoker)).optionId());
		assertEquals("furnace", HuntFoodTaskExecutor.chooseStation(List.of(place, furnace)).optionId());
	}

	@Test void cooksTheMeatAfterTheHuntWithAChosenFuel() {
		Fixture f = new Fixture(new HuntFoodStepArgs(4, true, 32));
		for (int i = 0; i < 4; i++) f.env.add("cow", new Vec3(5.5 + 3 * i, 64, 0.5));
		f.env.furnaces = 1;
		f.env.fuels.add(new Fuel("minecraft:coal", 3, 1_600, 64));
		f.tick(800);
		var end = f.single();
		assertEquals(TaskExecutionState.COMPLETED, end.terminalState());
		assertTrue(end.message().startsWith("food_gathered raw=none cooked=cooked_beef x4 killed=2 skipped=0 stopped=count_reached"
			+ " furnaceReclaimed=1,64,0"), end.message());
		assertEquals(List.of("place:minecraft:beef x4 fuel=minecraft:coal x1"), f.env.started);
		assertEquals(List.of(WorldTaskType.SMELT_ITEMS, WorldTaskType.COLLECT_SMELTED_ITEMS),
			f.smelter.requests.stream().map(WorldTaskRequest::type).toList());
		var smelt = ((WorldTaskRequest.SmeltItems) f.smelter.requests.getFirst().task()).args();
		assertEquals(SmeltingFuelMode.MANUAL, smelt.fuelMode());
	}

	@Test void cooksEachKindOfMeatInTurn() {
		Fixture f = new Fixture(new HuntFoodStepArgs(3, true, 32));
		f.env.raw.put("minecraft:porkchop", 2);
		f.env.raw.put("minecraft:mutton", 1);
		f.env.stationNearby = true;
		f.env.fuels.add(new Fuel("minecraft:coal", 3, 1_600, 64));
		f.tick(800);
		var end = f.single();
		assertTrue(end.message().startsWith("food_gathered raw=none cooked=cooked_mutton x1,cooked_porkchop x2 killed=0"), end.message());
		assertEquals(List.of("nearby:minecraft:porkchop x2 fuel=minecraft:coal x1", "nearby:minecraft:mutton x1 fuel=minecraft:coal x1"),
			f.env.started);
	}

	@Test void craftsAFurnaceFromCobblestoneWhenNoneIsCarriedOrNear() {
		Fixture f = new Fixture(new HuntFoodStepArgs(2, true, 32));
		f.env.raw.put("minecraft:chicken", 2);
		f.env.material = 9;
		f.env.fuels.add(new Fuel("minecraft:oak_planks", 4, 300, 64));
		f.tick(800);
		var end = f.single();
		assertEquals(List.of(WorldTaskType.CRAFT_RECIPE), f.crafter.requests.stream().map(WorldTaskRequest::type).toList());
		assertEquals("minecraft:furnace", ((WorldTaskRequest.CraftRecipe) f.crafter.requests.getFirst().task()).args().recipeId());
		assertTrue(end.message().startsWith("food_gathered raw=none cooked=cooked_chicken x2"), end.message());
	}

	@Test void cookingProblemsAreNotesOnASuccess() {
		Fixture noFurnace = new Fixture(new HuntFoodStepArgs(2, true, 32));
		noFurnace.env.raw.put("minecraft:beef", 2);
		noFurnace.env.fuels.add(new Fuel("minecraft:coal", 3, 1_600, 64));
		noFurnace.tick(5);
		assertEquals(TaskExecutionState.COMPLETED, noFurnace.single().terminalState());
		assertTrue(noFurnace.single().message().contains("raw=beef x2 cooked=none killed=0 skipped=0 stopped=already_carried cookSkipped=no_furnace"),
			noFurnace.single().message());

		Fixture noFuel = new Fixture(new HuntFoodStepArgs(2, true, 32));
		noFuel.env.raw.put("minecraft:beef", 2);
		noFuel.env.furnaces = 1;
		noFuel.env.fuels.add(new Fuel("minecraft:wooden_pickaxe", 1, 200, 1));
		noFuel.tick(5);
		assertTrue(noFuel.single().message().contains("cookSkipped=no_fuel"), noFuel.single().message());
		assertTrue(noFuel.smelter.requests.isEmpty());

		Fixture failed = new Fixture(new HuntFoodStepArgs(2, true, 32));
		failed.env.raw.put("minecraft:beef", 2);
		failed.env.furnaces = 1;
		failed.env.fuels.add(new Fuel("minecraft:coal", 3, 1_600, 64));
		failed.smelter.smeltFailure = "furnace_placement_blocked";
		failed.tick(50);
		assertEquals(TaskExecutionState.COMPLETED, failed.single().terminalState());
		assertTrue(failed.single().message().contains("cookSkipped=smelt_failed:furnace_placement_blocked"), failed.single().message());
	}

	@Test void parsesArgumentsWithDefaultsAndBounds() {
		assertEquals(new HuntFoodStepArgs(6, true, 32), HuntFoodStepArgs.parse(new JsonObject()));
		assertEquals(new HuntFoodStepArgs(10, false, 16), HuntFoodStepArgs.parse(json("{\"count\":10,\"cook\":false,\"maxDistance\":16}")));
		for (String bad : List.of("{\"count\":0}", "{\"count\":17}", "{\"maxDistance\":7}", "{\"maxDistance\":49}", "{\"cook\":\"yes\"}",
			"{\"count\":2.5}", "{\"radius\":8}"))
			assertThrows(IllegalArgumentException.class, () -> HuntFoodStepArgs.parse(json(bad)), bad);
	}

	@Test void lootWaitsForTheDropsThenWalksOntoTheNearestSafeOne() {
		Vec3 center = new Vec3(0.5, 64, 0.5);
		List<Drop> drops = List.of(new Drop(new Vec3(3.5, 64, 0.5), true), new Drop(new Vec3(1.5, 64, 0.5), false),
			new Drop(new Vec3(12.5, 64, 0.5), true));
		assertEquals(HuntFoodTaskExecutor.LootStep.Kind.WAIT, HuntFoodTaskExecutor.lootStep(5, drops, center, center).kind());
		var walk = HuntFoodTaskExecutor.lootStep(20, drops, center, center);
		assertEquals(HuntFoodTaskExecutor.LootStep.Kind.WALK, walk.kind());
		assertEquals(new Vec3(3.5, 64, 0.5), walk.drop().position(), "not the unsafe one, not the one 12 blocks off");
		assertEquals(HuntFoodTaskExecutor.LootStep.Kind.DONE, HuntFoodTaskExecutor.lootStep(20, List.of(), center, center).kind());
		assertEquals(HuntFoodTaskExecutor.LootStep.Kind.DONE, HuntFoodTaskExecutor.lootStep(DropPickup.DROP_TIMEOUT_TICKS + 1, drops, center, center).kind());
	}

	// ---- Helpers ----

	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	private static HuntFoodTaskExecutor.Body body(long dayTime, boolean overworld, boolean sky) {
		return new HuntFoodTaskExecutor.Body(Vec3.ZERO, BlockPos.ZERO, 20.0F, 20, dayTime, false, false, overworld, sky);
	}

	private static AnimalSpec animal(int id, String type, double distance) {
		return new AnimalSpec(id, type, distance);
	}

	private static List<Animal> build(List<AnimalSpec> specs) {
		return specs.stream().map(AnimalSpec::build).toList();
	}

	private static final class AnimalSpec {
		final int id;
		final String type;
		final double distance;
		boolean baby, named, protectedArea, hidden, wet;
		AnimalSpec(int id, String type, double distance) { this.id = id; this.type = type; this.distance = distance; }
		AnimalSpec baby() { baby = true; return this; }
		AnimalSpec named() { named = true; return this; }
		AnimalSpec protectedArea() { protectedArea = true; return this; }
		AnimalSpec hidden() { hidden = true; return this; }
		AnimalSpec wet() { wet = true; return this; }
		Animal build() {
			return new Animal(id, type, baby, named, protectedArea, new Vec3(distance, 64, 0), distance, 0.0, !hidden, wet, distance < 3, false);
		}
	}

	private static final class Fixture {
		final Environment env = new Environment();
		final Navigation nav = new Navigation(env);
		final FakeSmelter smelter = new FakeSmelter(env);
		final FakeCrafter crafter = new FakeCrafter(env);
		final HuntFoodTaskExecutor executor = new HuntFoodTaskExecutor(nav, env, smelter, crafter);
		final WorldTaskRequest request;
		final List<TaskTerminalEvent> events = new ArrayList<>();
		long ticks;

		Fixture(HuntFoodStepArgs args) {
			request = WorldTaskRequest.huntFood("hunt", "job", args);
		}

		SessionSnapshot session() {
			return new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST, true, true, "minecraft:overworld", true, 25565, ++ticks);
		}

		void tick(int n) {
			for (int i = 0; i < n && events.isEmpty(); i++) executor.tick(session(), Optional.of(request)).ifPresent(events::add);
		}

		TaskTerminalEvent single() {
			assertEquals(1, events.size(), events + " " + snapshotText());
			return events.getFirst();
		}

		String snapshotText() { return executor.snapshot().lastPathEvent(); }
	}

	static final class FakeAnimal {
		final int id;
		final String type;
		Vec3 pos;
		int health = 3, deadTicks = -1;
		boolean removed;
		FakeAnimal(int id, String type, Vec3 pos) { this.id = id; this.type = type; this.pos = pos; }
	}

	/** Flat ground at y=64; each cow drops two beef and a leather, a chicken one chicken, and so on; drops within 1.5 are picked up. */
	static final class Environment implements HuntFoodTaskExecutor.Environment {
		Vec3 feet = new Vec3(0.5, 64, 0.5);
		long dayTime = NOON;
		float health = 20.0F;
		double weaponDamage = 7.0D;
		boolean overworld = true, sky = true, stationNearby;
		int furnaces, material, leather, hits, released, nextId = 100;
		final Map<String, Integer> raw = new LinkedHashMap<>(), cooked = new LinkedHashMap<>();
		final List<Fuel> fuels = new ArrayList<>();
		final Map<Integer, FakeAnimal> animals = new LinkedHashMap<>();
		final List<Vec3[]> drops = new ArrayList<>();
		final List<String> dropItems = new ArrayList<>();
		final List<Integer> attacks = new ArrayList<>();
		final List<String> started = new ArrayList<>();
		final Map<String, String> optionRaw = new HashMap<>();
		String loadedRaw;
		int loaded;
		long tick, lastAttack = -100;

		FakeAnimal add(String type, Vec3 pos) {
			FakeAnimal animal = new FakeAnimal(nextId++, type, pos);
			animals.put(animal.id, animal);
			return animal;
		}

		int living(String type) {
			return (int) animals.values().stream().filter(a -> a.type.equals(type) && !a.removed && a.health > 0).count();
		}

		public HuntFoodTaskExecutor.Body observe() {
			tick++;
			for (FakeAnimal animal : animals.values()) {
				if (animal.health <= 0 && !animal.removed && ++animal.deadTicks >= 3) animal.removed = true;
			}
			for (int i = drops.size() - 1; i >= 0; i--) {
				if (DropPickup.horizontalDistance(feet, drops.get(i)[0]) <= 1.5) {
					String item = dropItems.remove(i);
					drops.remove(i);
					if (item.equals("minecraft:leather")) leather++;
					else raw.merge(item, 1, Integer::sum);
				}
			}
			return new HuntFoodTaskExecutor.Body(feet, BlockPos.containing(feet), health, 14, dayTime, false, false, overworld, sky);
		}

		public HuntFoodTaskExecutor.Stock stock() {
			return new HuntFoodTaskExecutor.Stock(raw, cooked, weaponDamage, furnaces, material, fuels);
		}

		private Animal view(FakeAnimal animal) {
			Vec3 eye = feet.add(0, 1.62, 0);
			double distance = eye.distanceTo(animal.pos.add(0, 0.5, 0));
			return new Animal(animal.id, animal.type, false, false, false, animal.pos, distance, animal.pos.y - feet.y, true, false,
				distance <= 3.0, animal.health <= 0);
		}

		public List<Animal> animals(double maxDistance) {
			return animals.values().stream().filter(a -> !a.removed && a.health > 0).map(this::view)
				.filter(a -> a.distance() <= maxDistance).toList();
		}

		public Animal animal(int id) {
			FakeAnimal animal = animals.get(id);
			return animal == null || animal.removed ? null : view(animal);
		}

		public HuntFoodTaskExecutor.AttackResult attack(int id) {
			FakeAnimal animal = animals.get(id);
			if (animal == null || !view(animal).inReach()) return HuntFoodTaskExecutor.AttackResult.OUT_OF_REACH;
			if (tick - lastAttack < 12) return HuntFoodTaskExecutor.AttackResult.COOLDOWN;
			lastAttack = tick;
			attacks.add(id);
			hits++;
			if (--animal.health == 0) {
				String meat = switch (animal.type) {
					case "cow" -> "minecraft:beef";
					case "pig" -> "minecraft:porkchop";
					case "sheep" -> "minecraft:mutton";
					case "rabbit" -> "minecraft:rabbit";
					default -> "minecraft:chicken";
				};
				int count = animal.type.equals("chicken") ? 1 : 2;
				for (int i = 0; i < count; i++) dropAt(animal.pos.add(0.3 * i, 0, 0.6), meat);
				if (animal.type.equals("cow")) dropAt(animal.pos.add(-0.6, 0, -0.6), "minecraft:leather");
			}
			return HuntFoodTaskExecutor.AttackResult.HIT;
		}

		private void dropAt(Vec3 position, String item) {
			drops.add(new Vec3[] {position});
			dropItems.add(item);
		}

		public boolean stepToward(int id) {
			FakeAnimal animal = animals.get(id);
			Vec3 heading = new Vec3(animal.pos.x - feet.x, 0, animal.pos.z - feet.z);
			if (heading.length() > 0.3) feet = feet.add(heading.normalize().scale(0.25));
			return true;
		}

		public List<Drop> drops(Vec3 center, double radius) {
			return drops.stream().filter(d -> DropPickup.horizontalDistance(center, d[0]) <= radius).map(d -> new Drop(d[0], true)).toList();
		}

		public List<SmeltChoice> smeltChoices(String rawItemId, long tick) {
			List<SmeltChoice> choices = new ArrayList<>();
			if (stationNearby) choices.add(new SmeltChoice("nearby:" + rawItemId, StationSource.NEARBY, "furnace", 200, raw.getOrDefault(rawItemId, 0)));
			if (furnaces > 0) choices.add(new SmeltChoice("place:" + rawItemId, StationSource.PLACE, "furnace", 200, raw.getOrDefault(rawItemId, 0)));
			choices.forEach(choice -> optionRaw.put(choice.optionId(), rawItemId));
			return choices;
		}

		public HuntFoodTaskExecutor.SmeltStart startSmelt(String optionId, int quantity, String fuelItemId, int fuelQuantity, long tick) {
			started.add(optionId.split(":")[0] + ":" + optionRaw.get(optionId) + " x" + quantity + " fuel=" + fuelItemId + " x" + fuelQuantity);
			return new HuntFoodTaskExecutor.SmeltStart("process-" + started.size(), null);
		}

		public boolean smeltReady(String processId, long tick) { return true; }
		public void stop() {}
		public void release() { released++; }
	}

	/** Arrives at once next to the goal, unless {@code arrive} is off. */
	static final class Navigation implements NavigationFacade {
		final Environment env;
		final List<GoalPosition> starts = new ArrayList<>();
		NavigationOptions options;
		boolean arrive = true, active, failNext;
		String event;
		Navigation(Environment env) { this.env = env; }
		public boolean isLoaded() { return true; }
		public void startFollow(String s) { fail("no following"); }
		public void startNavigate(GoalPosition p, NavigationOptions o) {
			options = o;
			env.feet = new Vec3(p.x() + 0.5, p.y(), p.z() + 0.5);
		}
		public void startNavigateNear(GoalPosition p, int r, NavigationOptions o) {
			starts.add(p);
			options = o;
			if (failNext) {
				event = "CALC_FAILED";
				return;
			}
			if (arrive) env.feet = new Vec3(p.x() - 0.5, 64, p.z() + 0.5);
		}
		public boolean processActive() { return active; }
		public void cancel() {}
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() {
			String polled = event;
			event = null;
			return Optional.ofNullable(polled);
		}
		public boolean navigationGoalReached(GoalPosition p) { return false; }
	}

	/** Loads after a few ticks; the collect turns what was loaded into cooked food. */
	static final class FakeSmelter implements WorldTaskExecutor {
		final Environment env;
		final List<WorldTaskRequest> requests = new ArrayList<>();
		WorldTaskRequest current;
		int ticks;
		String smeltFailure;
		FakeSmelter(Environment env) { this.env = env; }
		public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> task) {
			if (task.isEmpty()) { current = null; return Optional.empty(); }
			if (current == null || !current.taskId().equals(task.get().taskId())) {
				current = task.get();
				requests.add(current);
				ticks = 0;
			}
			if (++ticks < 3) return Optional.empty();
			if (current.task() instanceof WorldTaskRequest.SmeltItems smelt) {
				if (smeltFailure != null)
					return Optional.of(new TaskTerminalEvent(current.taskId(), null, TaskExecutionState.FAILED, smeltFailure, null, TaskFailureCode.UNKNOWN));
				env.loadedRaw = env.optionRaw.get(smelt.args().optionId());
				env.loaded = smelt.args().inputQuantity();
				env.raw.merge(env.loadedRaw, -env.loaded, Integer::sum);
				return Optional.of(new TaskTerminalEvent(current.taskId(), null, TaskExecutionState.COMPLETED, "smelting_started", TaskTerminationCause.GOAL_REACHED));
			}
			env.cooked.merge(HuntFoodTaskExecutor.COOKED.get(env.loadedRaw), env.loaded, Integer::sum);
			env.loaded = 0;
			String note = env.furnaces > 0 ? " furnaceReclaimed=1,64,0" : "";
			return Optional.of(new TaskTerminalEvent(current.taskId(), null, TaskExecutionState.COMPLETED, "smelting_collected" + note, TaskTerminationCause.GOAL_REACHED));
		}
		public TaskExecutionSnapshot snapshot() { return TaskExecutionSnapshot.idle(); }
		public void onWorldLeave() { current = null; }
		public void shutdown() { current = null; }
	}

	/** Crafts a furnace from eight cobblestone. */
	static final class FakeCrafter implements WorldTaskExecutor {
		final Environment env;
		final List<WorldTaskRequest> requests = new ArrayList<>();
		WorldTaskRequest current;
		FakeCrafter(Environment env) { this.env = env; }
		public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> task) {
			if (task.isEmpty()) { current = null; return Optional.empty(); }
			if (current != null && current.taskId().equals(task.get().taskId())) {
				env.material -= 8;
				env.furnaces++;
				return Optional.of(new TaskTerminalEvent(current.taskId(), null, TaskExecutionState.COMPLETED, "crafted", TaskTerminationCause.GOAL_REACHED));
			}
			current = task.get();
			requests.add(current);
			return Optional.empty();
		}
		public TaskExecutionSnapshot snapshot() { return TaskExecutionSnapshot.idle(); }
		public void onWorldLeave() { current = null; }
		public void shutdown() { current = null; }
	}
}
