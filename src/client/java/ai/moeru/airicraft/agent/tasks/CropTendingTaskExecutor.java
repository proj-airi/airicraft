package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.session.SessionSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A bounded harvest/collect/replant pass, owned and interruptible by the normal task runtime. */
public final class CropTendingTaskExecutor implements WorldTaskExecutor {
	private final NavigationFacade navigation;
	private final Environment environment;
	private final WorldTaskExecutor planting;
	private WorldTaskRequest request;
	private CropTendingStepArgs args;
	private List<GoalPosition> positions = List.of();
	private int cursor, harvested, planted, growing, missingSeeds, activeTicks, phaseTicks;
	private Phase phase = Phase.SELECT;
	private GoalPosition work;
	private boolean navigationOwned, emitted;
	private TaskTerminalEvent terminal;
	private TaskExecutionSnapshot snapshot = TaskExecutionSnapshot.idle();

	public CropTendingTaskExecutor(NavigationFacade navigation, CameraController camera, WorldTaskExecutor planting) {
		this(navigation, new MinecraftCropTendingEnvironment(camera), planting);
	}

	CropTendingTaskExecutor(NavigationFacade navigation, Environment environment, WorldTaskExecutor planting) {
		this.navigation = navigation;
		this.environment = environment;
		this.planting = planting;
	}

	@Override
	public Optional<TaskTerminalEvent> tick(SessionSnapshot session, Optional<WorldTaskRequest> activeTask) {
		if (activeTask.isEmpty() || activeTask.get().type() != WorldTaskType.TEND_CROPS) {
			release(session);
			snapshot = TaskExecutionSnapshot.idle();
			return Optional.empty();
		}
		WorldTaskRequest next = activeTask.get();
		if (request == null || !request.taskId().equals(next.taskId())) {
			release(session);
			request = next;
			args = ((WorldTaskRequest.TendCrops) request.task()).args();
			cursor = harvested = planted = growing = missingSeeds = activeTicks = phaseTicks = 0;
			terminal = null;
			emitted = false;
			phase = Phase.SELECT;
			String error = environment.validate(args);
			if (error != null) return finish(session, false, error);
			ArrayList<GoalPosition> cells = new ArrayList<>();
			for (long x = args.x1(); x <= args.x2(); x++) for (long z = args.z1(); z <= args.z2(); z++)
				cells.add(new GoalPosition((int) x, args.y(), (int) z, true));
			// Harvest first so its drops can seed empty farmland in the same pass.
			cells.sort(Comparator.comparingInt((GoalPosition pos) -> environment.state(pos, args).ordinal())
				.thenComparingDouble(pos -> distanceSquared(environment.position(), pos)));
			positions = List.copyOf(cells);
		}
		if (terminal != null) return finishRelease(session);
		if (!session.companionActuationAllowed()) {
			release(session);
			setSnapshot(TaskExecutionState.PAUSED_BY_SESSION_GATE, "session_gate");
			return Optional.empty();
		}
		activeTicks++;
		phaseTicks++;
		if (activeTicks > 2400 || phaseTicks > 240) return finish(session, false, "crop_tending_timeout");
		if (phase != Phase.PLANT && phase != Phase.PLANTING && !navigationOwned && !NavigationRelease.idle(navigation)) {
			setSnapshot(TaskExecutionState.RUNNING, "waiting_for_navigation_release");
			return Optional.empty();
		}
		if (phase == Phase.SELECT) {
			while (cursor < positions.size()) {
				Cell state = environment.state(target(), args);
				if (state == Cell.UNLOADED) return finish(session, false, "crop_plot_unloaded");
				if (state == Cell.MATURE) {
					work = environment.workPosition(target());
					if (work == null) return finish(session, false, "crop_has_no_reachable_work_position");
					enter(Phase.APPROACH);
					break;
				}
				if (state == Cell.EMPTY_FARMLAND) {
					if (environment.seedCount(args.seedItemId()) > 0) { enter(Phase.PLANT); break; }
					missingSeeds++;
				}
				if (state == Cell.GROWING) growing++;
				cursor++;
			}
			if (cursor >= positions.size()) return finish(session, true, "crop_pass_complete");
		}
		else if (phase == Phase.APPROACH) {
			if (environment.state(target(), args) != Cell.MATURE) { release(session); enter(Phase.SELECT); }
			else if (environment.canHarvest(target())) { release(session); enter(Phase.HARVEST); }
			else if (!navigate(work)) return finish(session, false, "crop_approach_failed");
		}
		else if (phase == Phase.HARVEST) {
			if (environment.state(target(), args) != Cell.MATURE) enter(Phase.SELECT);
			else if (environment.harvest(target(), args)) { harvested++; work = null; enter(Phase.COLLECT); }
		}
		else if (phase == Phase.COLLECT) {
			Optional<GoalPosition> pickup = environment.pickupPosition(target(), args);
			if (pickup.isEmpty()) {
				release(session);
				if (phaseTicks >= 20) enter(Phase.PLANT);
			}
			else {
				if (!pickup.get().equals(work)) {
					release(session);
					work = pickup.get();
				}
				if (navigation.navigationGoalReached(work)) {
					release(session);
				}
				else if (NavigationRelease.idle(navigation) || navigationOwned) {
					if (!navigate(work)) return finish(session, false, "crop_pickup_approach_failed");
				}
			}
		}
		else if (phase == Phase.PLANT || phase == Phase.PLANTING) {
			if (!NavigationRelease.idle(navigation) && navigationOwned) {
				release(session);
			}
			else if (phase == Phase.PLANT && environment.state(target(), args) != Cell.EMPTY_FARMLAND) {
				planting.tick(session, Optional.empty());
				cursor++;
				enter(Phase.SELECT);
			}
			else if (phase == Phase.PLANT && environment.seedCount(args.seedItemId()) == 0) {
				missingSeeds++;
				cursor++;
				enter(Phase.SELECT);
			}
			else {
				WorldTaskRequest child = WorldTaskRequest.useBlock(request.taskId() + ":plant:" + cursor, request.sourceJobId(),
					new BlockUseStepArgs(args.seedItemId(), target(), "down", List.of("minecraft:farmland"), "air"));
				if (phase == Phase.PLANT) enter(Phase.PLANTING);
				Optional<TaskTerminalEvent> result = planting.tick(session, Optional.of(child));
				if (result.isPresent()) {
					if (result.get().terminalState() != TaskExecutionState.COMPLETED) return finish(session, false, "crop_plant_failed " + result.get().message());
					planted++;
					planting.tick(session, Optional.empty());
					cursor++;
					enter(Phase.SELECT);
				}
			}
		}
		setSnapshot(TaskExecutionState.RUNNING, "tending");
		return Optional.empty();
	}

	private boolean navigate(GoalPosition position) {
		if (!navigationOwned) {
			navigation.pollPathEvent();
			navigation.startNavigate(position);
			navigationOwned = true;
		}
		String event = navigation.pollPathEvent().orElse("");
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	private void enter(Phase next) { phase = next; phaseTicks = 0; }
	private GoalPosition target() { return positions.get(cursor); }
	private static double distanceSquared(GoalPosition first, GoalPosition second) {
		double dx = (double) first.x() - second.x(), dy = (double) first.y() - second.y(), dz = (double) first.z() - second.z();
		return dx * dx + dy * dy + dz * dz;
	}

	private void release(SessionSnapshot session) {
		planting.tick(session, Optional.empty());
		if (navigationOwned) { navigation.cancel(); navigationOwned = false; }
	}

	private Optional<TaskTerminalEvent> finish(SessionSnapshot session, boolean success, String reason) {
		release(session);
		TaskExecutionState state = success ? TaskExecutionState.COMPLETED : TaskExecutionState.FAILED;
		String message = reason + " harvested=" + harvested + " planted=" + planted + " growing=" + growing + " missingSeedPlots=" + missingSeeds;
		terminal = new TaskTerminalEvent(request.taskId(), null, state, message,
			success ? TaskTerminationCause.GOAL_REACHED : null, success ? null : TaskFailureCode.MISSING_FACT);
		return finishRelease(session);
	}

	private Optional<TaskTerminalEvent> finishRelease(SessionSnapshot session) {
		release(session);
		NavigationRelease.release(navigation);
		setSnapshot(terminal.terminalState(), terminal.message());
		if (emitted) return Optional.empty();
		emitted = true;
		return Optional.of(terminal);
	}

	private void setSnapshot(TaskExecutionState state, String message) {
		snapshot = new TaskExecutionSnapshot(state, request.taskId(), null, "CropTending",
			message + " phase=" + phase + " cursor=" + cursor + "/" + positions.size()
				+ (cursor < positions.size() ? " target=" + target() : "") + " workPosition=" + work
				+ " harvested=" + harvested + " planted=" + planted,
			null, null);
	}

	@Override public TaskExecutionSnapshot snapshot() { return snapshot; }
	@Override public void onWorldLeave() { release(null); request = null; snapshot = TaskExecutionSnapshot.idle(); }
	@Override public void shutdown() { onWorldLeave(); }

	enum Phase { SELECT, APPROACH, HARVEST, COLLECT, PLANT, PLANTING }
	enum Cell { MATURE, EMPTY_FARMLAND, GROWING, OTHER, UNLOADED }
	interface Environment {
		String validate(CropTendingStepArgs args);
		Cell state(GoalPosition pos, CropTendingStepArgs args);
		GoalPosition position();
		GoalPosition workPosition(GoalPosition crop);
		boolean canHarvest(GoalPosition crop);
		boolean harvest(GoalPosition crop, CropTendingStepArgs args);
		Optional<GoalPosition> pickupPosition(GoalPosition crop, CropTendingStepArgs args);
		int seedCount(String seedItemId);
	}
}
