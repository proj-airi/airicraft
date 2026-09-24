package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.*;
import ai.moeru.airicraft.agent.session.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ai.moeru.airicraft.agent.tasks.TargetAcquisitionTaskExecutor.*;

class TargetAcquisitionTaskExecutorTest {
	@Test void blockedDropReportsInventoryFullAndReleasesNavigation() {
		Fixture f = new Fixture();
		f.env.sources = List.of(new Candidate(Kind.DROP, "drop", pos(5,64,0), pos(5,64,0)));
		f.tick(2);
		assertTrue(f.nav.active);
		f.env.canCollectDrop = false;
		f.tick(1);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertTrue(f.events.getFirst().message().contains("inventory_full"));
		assertFalse(f.nav.active);
	}
	@Test void dropWithStackCapacityCanStillBeCollected() {
		Fixture f = new Fixture();
		f.env.sources = List.of(new Candidate(Kind.DROP, "drop", f.env.position, f.env.position));
		f.env.interactable = true;
		f.tick(2);
		assertTrue(f.events.isEmpty());
		f.env.count = 1;
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.executor.snapshot().state());
	}

	@Test void confirmedInventoryProgressStartsTheNextBlockWithoutASettlingDelay() {
		Fixture f = visibleVein(2);
		f.tick(6);
		assertEquals(2, f.env.breaks, "Confirmed pickup must not incur a fixed twenty-tick pause");
	}

	@Test void anObservedDropStartsPickupBeforeTheServerUpdateGraceExpires() {
		Fixture f = new Fixture();
		f.env.interactable = true;
		f.tick(3);
		assertEquals(1, f.env.breaks);
		f.env.sources = List.of(new Candidate(Kind.DROP, "drop", f.env.position, f.env.position));
		f.tick(2);
		assertTrue(f.executor.snapshot().lastPathEvent().contains("phase=PICKUP"));
		assertTrue(f.events.isEmpty(), "Seeing a drop is not confirmed inventory");
	}

	@Test void collectingADropDoesNotAddASecondSettlingDelay() {
		Fixture f = visibleVein(3);
		Candidate block = f.env.sources.getFirst();
		f.env.sources = List.of(new Candidate(Kind.DROP, "drop", f.env.position, f.env.position), block);
		f.tick(2);
		f.env.sources = List.of(block);
		f.env.count = 1;
		f.tick(4);
		assertEquals(1, f.env.breaks, "Resume mining immediately after the pickup disappears");
	}

	@Test void delayedDropsStillHaveTimeToAppearBeforeTheSearchFails() {
		Fixture f = new Fixture();
		f.env.interactable = true;
		f.tick(3);
		f.tick(10);
		assertTrue(f.events.isEmpty());
		f.env.sources = List.of(new Candidate(Kind.DROP, "late-drop", f.env.position, f.env.position));
		f.tick(2);
		assertTrue(f.executor.snapshot().lastPathEvent().contains("phase=PICKUP"));
		f.env.count = 1;
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.executor.snapshot().state());
	}

	@Test void missingDropsStillExhaustTheBoundedGracePeriod() {
		Fixture f = new Fixture();
		f.env.interactable = true;
		f.tick(24);
		assertEquals(1, f.env.breaks);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertTrue(f.events.getFirst().message().contains("no_eligible_resource_in_search_region"));
	}

	@Test void retainsSeenVeinWhenPickupMovementHidesItButDoesNotDiscoverHiddenOre() {
		Fixture f = visibleVein(3);
		f.tick(4);
		assertEquals(1, f.env.count);
		f.env.visible = Set.of(); // Pickup moved the eye below the rim of the vein.
		f.tick(60);
		assertEquals(2, f.env.breaks, "Both observed blocks remain usable, but the unseen third does not");
		assertEquals(2, f.env.count);
		assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
		assertTrue(f.events.getFirst().message().contains("no_eligible_resource_in_search_region"));
	}

	@Test void seenVeinSurvivesTemporaryWithdrawalOfTheSameRequest() {
		Fixture f = visibleVein(2);
		f.tick(4);
		f.env.visible = Set.of();
		f.executor.tick(SessionSnapshot.initial(), Optional.empty());
		f.tick(40);
		assertEquals(2, f.env.count);
		assertEquals(TaskExecutionState.COMPLETED, f.events.getFirst().terminalState());
	}

	@Test void replacementTaskAndWorldLeaveDiscardSeenSources() {
		for (boolean worldLeave : List.of(false, true)) {
			Fixture f = visibleVein(2);
			f.tick(4);
			f.env.visible = Set.of();
			if (worldLeave) f.executor.onWorldLeave();
			else f.request = WorldTaskRequest.collectMine("replacement", "other-job", f.request.goal());
			f.tick(1);
			assertEquals(1, f.env.breaks);
			assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
		}
	}

	@Test void disappearedObservedBlocksAreNotHarvestedFromMemory() {
		Fixture f = visibleVein(2);
		f.tick(4);
		f.env.visible = Set.of();
		f.env.sources = List.of();
		f.tick(40);
		assertEquals(1, f.env.breaks);
		assertEquals(TaskExecutionState.FAILED, f.events.getFirst().terminalState());
	}

	private static Fixture visibleVein(int quantity) {
		Fixture f = new Fixture();
		f.request = WorldTaskRequest.collectMine("visible", "job", new GoalSnapshot(GoalType.MINE_BLOCKS, null, null,
			new GoalMineSpec(List.of("ore"), quantity).withConstraints(new AcquisitionConstraints(null, 8, 4, false, true)), 0, "test"));
		f.env.sources = List.of(
			new Candidate(Kind.BLOCK, "ore", pos(1,64,0), pos(0,64,0)),
			new Candidate(Kind.BLOCK, "ore", pos(2,64,0), pos(1,64,0)),
			new Candidate(Kind.BLOCK, "ore", pos(3,64,0), pos(2,64,0)));
		f.env.visible = Set.of(pos(1,64,0), pos(2,64,0));
		f.env.interactable = true;
		f.env.countOnBreak = true;
		return f;
	}

	@Test void anchoringPreservesVisibleDiscoveryAndLegacyDefaults() {
		var constraints = new AcquisitionConstraints(null, 8, 4, false, true).anchoredAt(pos(1, 64, 2));
		assertTrue(constraints.visibleOnly());
		assertEquals(pos(1,64,2), constraints.center());
		assertFalse(AcquisitionConstraints.nearby().visibleOnly());
		assertFalse(new AcquisitionConstraints(null, 8, 4, true).visibleOnly());
	}

	@Test void losingTheRequiredToolDuringApproachFailsWithoutRetryingOtherTargets() {
		Fixture f = new Fixture();
		f.tick(2);
		assertTrue(f.nav.active);
		f.env.requiredToolAvailable = false;
		f.tick(1);
		assertFalse(f.nav.active);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertEquals(TaskFailureCode.MISSING_ITEM, f.events.getFirst().failureCode());
		assertEquals(0, f.env.rejections);
	}
	@Test void inferredHarvestToolFailureStopsBeforeTryingAnotherWorkPosition() {
		Fixture f = new Fixture();
		Candidate ore = new Candidate(Kind.BLOCK, "minecraft:iron_ore", pos(6,127,3), pos(5,127,3));
		f.env.sources = List.of(ore, new Candidate(ore.kind(), ore.id(), ore.position(), pos(7,127,3)));
		f.request = WorldTaskRequest.collectMine("iron", "job", new GoalSnapshot(GoalType.MINE_BLOCKS, null, null,
			new GoalMineSpec(List.of("minecraft:iron_ore"), 3), 0, "test"));
		assertTrue(f.request.goal().mineSpec().requiredToolItemIds().isEmpty());
		f.tick(2);
		assertTrue(f.nav.active);
		f.env.interactable = true;
		String reason = "missing_suitable_tool blockIds=[minecraft:iron_ore]";
		f.env.breakFailure = new ToolFailure(reason);
		f.tick(5);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertEquals(1, f.events.size());
		assertEquals(TaskFailureCode.MISSING_ITEM, f.events.getFirst().failureCode());
		assertEquals(reason, f.events.getFirst().message());
		assertEquals(1, f.env.breaks);
		assertEquals(0, f.env.rejections);
		assertFalse(f.nav.active);
	}

	@Test void unavailableInteractionStillTriesAnotherWorkPosition() {
		Fixture f = new Fixture();
		Candidate first = f.env.sources.getFirst();
		Candidate otherSide = new Candidate(first.kind(), first.id(), first.position(), pos(6,64,0));
		f.env.sources = List.of(first, otherSide);
		f.env.interactable = true;
		f.env.breakFailure = BreakStatus.FAILED;
		f.tick(3);
		f.env.interactable = false;
		f.tick(2);
		assertTrue(f.nav.goals.contains(otherSide.workPosition()));
		assertEquals(1, f.env.rejections);
		assertTrue(f.events.isEmpty());
	}

	@Test void workSearchIncludesGroundFromWhichAnOverheadLogIsInEyeReach() {
		var log = new net.minecraft.util.math.BlockPos(284,69,-138);
		var feet = new net.minecraft.util.math.BlockPos(285,64,-138);
		var eye = net.minecraft.util.math.Vec3d.ofBottomCenter(feet).add(0,1.62,0);
		assertTrue(eye.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(log)) <= 20.25);
		boolean included = false;
		for (var pos : MinecraftAcquisitionEnvironment.workPositions(log)) if (pos.equals(feet)) included = true;
		assertTrue(included, "Reachable ground must be considered before requiring a higher platform");
	}

	@Test void acquiresWithoutAnotherPlannerCallAndWaitsForInventory() {
		Fixture f = new Fixture();
		f.env.interactable = true;
		f.tick(4);
		assertEquals(1, f.env.breaks);
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
		f.env.sources = List.of(new Candidate(Kind.DROP, "drop", f.env.position, f.env.position));
		f.tick(23);
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
		f.env.count = 1;
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.executor.snapshot().state());
		assertEquals(1, f.events.size());
		f.tick(2);
		assertEquals(1, f.events.size());
	}

	@Test void abandonsStalledApproachAndTriesAnotherObservedTarget() {
		Fixture f = new Fixture();
		Candidate alternative = new Candidate(Kind.BLOCK, "log", pos(2,64,0), pos(1,64,0));
		f.env.sources = List.of(f.env.sources.getFirst(), alternative);
		f.tick(86);
		assertTrue(f.env.rejections > 0);
		assertTrue(f.nav.goals.contains(alternative.workPosition()));
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
	}

	@Test void searchBoundaryDoesNotRejectAUsefulSurfaceGatheringDetour() {
		Fixture f = new Fixture();
		f.tick(2);
		f.env.inScope = false;
		f.env.position = pos(20,63,0);
		f.tick(1);
		assertTrue(f.nav.active);
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
		assertTrue(f.events.isEmpty());
		f.env.position = pos(4,64,0); f.env.interactable = true; f.env.countOnBreak = true;
		f.tick(4);
		assertEquals(TaskExecutionState.COMPLETED, f.executor.snapshot().state());
	}

	@Test void failedApproachDoesNotBlacklistOtherSidesOfTheSameBuriedSource() {
		Fixture f = new Fixture();
		Candidate first = f.env.sources.getFirst();
		Candidate otherSide = new Candidate(first.kind(), first.id(), first.position(), pos(6,64,0));
		f.env.sources = List.of(first, otherSide);
		f.tick(86);
		assertTrue(f.nav.goals.contains(otherSide.workPosition()), "Try another excavation side before abandoning the ore");
	}

	@Test void productiveExcavationCanTakeLongerThanTwelveSeconds() {
		Fixture f = new Fixture();
		f.env.sources = List.of(new Candidate(Kind.BLOCK, "ore", pos(12,64,0), pos(11,64,0)));
		for (int x = 0; x < 10; x++) {
			f.env.position = pos(x,64,0);
			f.tick(30); // Digging each segment takes time, but the route keeps advancing.
		}
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
		assertTrue(f.nav.active);
		assertEquals(0, f.env.rejections);
	}

	@Test void detourAwayFromOreIsProgressEvenBeforeBeatingStartingDistance() {
		Fixture f = new Fixture();
		f.env.sources = List.of(new Candidate(Kind.BLOCK, "ore", pos(249,63,489), pos(249,63,488)));
		f.env.position = pos(256,63,480);
		f.tick(2);
		// Recorded route must leave the protected shelter via its eastern door.
		for (GoalPosition step : List.of(pos(258,63,480), pos(260,63,483), pos(260,62,485), pos(260,63,486))) {
			f.env.position = step;
			f.tick(30);
		}
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
		assertTrue(f.nav.active);
		assertEquals(0, f.env.rejections);
	}

	@Test void noObservedTargetsFailsWithoutStartingBaritoneMiningOrExploration() {
		Fixture f = new Fixture();
		f.env.sources = List.of();
		f.tick(1);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertTrue(f.nav.goals.isEmpty());
	}

	@Test void completedBreakBudgetDoesNotHarvestAnExtraBlockWhileWaitingForDrops() {
		Fixture f = new Fixture();
		f.request = f.request.withMineGoalSatisfied(true);
		f.tick(1);
		assertEquals(TaskExecutionState.COMPLETED, f.executor.snapshot().state());
		assertEquals(0, f.env.breaks);
	}

	@Test void sessionPauseReleasesMovementAndDoesNotConsumeAttemptBudget() {
		Fixture f = new Fixture();
		f.tick(2);
		for (int i = 0; i < 3000; i++) f.executor.tick(SessionSnapshot.initial(), Optional.of(f.request));
		assertFalse(f.nav.active);
		f.tick(1);
		assertEquals(TaskExecutionState.RUNNING, f.executor.snapshot().state());
	}

	@Test void reflexWithdrawalDoesNotRestartAnUnproductiveApproachBudget() {
		Fixture f = new Fixture();
		f.tick(70);
		f.executor.tick(SessionSnapshot.initial(), Optional.empty());
		assertFalse(f.nav.active);
		f.tick(20);
		assertEquals(TaskExecutionState.FAILED, f.executor.snapshot().state());
		assertEquals(1, f.env.rejections);
	}

	@Test void requestedAreaIsFixedAndUsesHorizontalCircle() {
		AcquisitionConstraints constraints = new AcquisitionConstraints(pos(0,64,0), 10, 4, true);
		assertTrue(constraints.contains(pos(6,68,8)));
		assertFalse(constraints.contains(pos(8,64,8)));
		assertFalse(constraints.contains(pos(0,69,0)));
		assertSame(constraints, constraints.anchoredAt(pos(80,60,90)));
		assertThrows(IllegalArgumentException.class, () -> new AcquisitionConstraints(null, 0, 8, false));
	}

	private static GoalPosition pos(int x, int y, int z) { return new GoalPosition(x,y,z,true); }
	static final class Fixture {
		final FakeEnvironment env = new FakeEnvironment();
		final FakeNavigation nav = new FakeNavigation();
		final TargetAcquisitionTaskExecutor executor = new TargetAcquisitionTaskExecutor(nav, env);
		final List<TaskTerminalEvent> events = new ArrayList<>();
		WorldTaskRequest request = WorldTaskRequest.collectMine("mine", "job", new GoalSnapshot(GoalType.MINE_BLOCKS, null, null,
			new GoalMineSpec(List.of("log"), 1), 0, "test"));
		long tick;
		void tick(int n) {
			for (int i = 0; i < n; i++) executor.tick(new SessionSnapshot(SessionMode.SINGLEPLAYER_LAN_HOST,
				true,true,"minecraft:overworld",true,25565,++tick), Optional.of(request)).ifPresent(events::add);
		}
	}
	static final class FakeEnvironment implements Environment {
		GoalPosition position = pos(0,64,0);
		List<Candidate> sources = List.of(new Candidate(Kind.BLOCK,"log",pos(5,64,0),pos(4,64,0)));
		boolean interactable, inScope = true;
		boolean requiredToolAvailable = true;
		boolean canCollectDrop = true;
		public boolean canCollectDrop(Candidate target) { return canCollectDrop; }
		Set<GoalPosition> visible;
		boolean countOnBreak;
		BreakResult breakFailure;
		public boolean requiredToolAvailable(GoalMineSpec spec) { return requiredToolAvailable; }
		int count, breaks, rejections;
		public GoalPosition position() { return position; }
		public int inventoryCount(GoalMineSpec s) { return count; }
		public boolean inScope(GoalPosition p, AcquisitionConstraints c, boolean standing) { return inScope && c.contains(p); }
		public Set<GoalPosition> observeSources(GoalMineSpec s, AcquisitionConstraints c) {
			return sources.stream().filter(t -> t.kind() == Kind.BLOCK && (visible == null || visible.contains(t.position())))
				.map(Candidate::position).collect(java.util.stream.Collectors.toSet());
		}
		public List<Candidate> candidates(GoalMineSpec s, AcquisitionConstraints c, Set<String> rejected, Set<GoalPosition> observedSources) {
			rejections = rejected.size();
			return sources.stream().filter(t -> !rejected.contains(t.key()))
				.filter(t -> !c.visibleOnly() || t.kind() == Kind.DROP || observedSources.contains(t.position())).toList();
		}
		public boolean targetPresent(Candidate t) { return sources.contains(t); }
		public boolean dropsAvailable(GoalMineSpec spec, AcquisitionConstraints constraints) {
			return sources.stream().anyMatch(t -> t.kind() == Kind.DROP);
		}
		public boolean canInteract(Candidate t) { return interactable; }
		public BreakResult breakTarget(Candidate t, GoalMineSpec s) {
			breaks++;
			if (breakFailure != null) return breakFailure;
			if (countOnBreak) count++;
			sources = sources.stream().filter(source -> !source.equals(t)).toList();
			return BreakStatus.BROKEN;
		}
		public void cancelBreaking() {}
	}
	static final class FakeNavigation implements BaritoneFacade {
		boolean active;
		boolean goalReached;
		List<GoalPosition> goals = new ArrayList<>();
		public boolean isLoaded() { return true; }
		public void applySettings() {}
		public double walkOnWaterPenalty() { return 1; }
		public void setWalkOnWaterPenalty(double v) {}
		public void startFollow(String s) { fail("unexpected follow"); }
		public void startMine(GoalMineSpec s) { fail("System 1 must not delegate acquisition to Baritone"); }
		public void startNavigate(GoalPosition p) { goals.add(p); active = true; }
		public void startNavigateNear(GoalPosition p, int r) { fail("requires an exact work position"); }
		public boolean mineProcessActive() { return false; }
		public boolean processActive() { return active; }
		public boolean cancel() { active = false; return true; }
		public Optional<String> activeProcessName() { return Optional.empty(); }
		public Optional<Double> estimatedTicksToGoal() { return Optional.empty(); }
		public Optional<String> pollPathEvent() { return Optional.empty(); }
		public boolean navigationGoalReached(GoalPosition p) { return goalReached; }
	}
}
