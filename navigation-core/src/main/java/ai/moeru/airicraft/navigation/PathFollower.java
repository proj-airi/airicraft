package ai.moeru.airicraft.navigation;

import java.util.ArrayList;
import java.util.List;

/**
 * Follows a planned path one tick at a time. It runs the current step's executor, rechecks the
 * next step against the live terrain and policy, detects stalls and deviation, and closes doors it
 * opened once the player is past them. It never plans; {@link Status#REPLAN} asks the owner to.
 */
public final class PathFollower {
	/** Ticks a step may take beyond its planned cost before the follower asks for a new plan. */
	private static final int STEP_GRACE_TICKS = 60;
	private static final int OFF_PATH_TICKS = 10;
	private static final int REVALIDATE_INTERVAL = 5;
	private static final int LOOKAHEAD_STEPS = 3;

	public enum Status { RUNNING, ARRIVED, REPLAN, FAILED }

	public record Tick(MotorIntent intent, Status status, String detail) {
		static Tick running(MotorIntent intent) {
			return new Tick(intent, Status.RUNNING, null);
		}
	}

	private final Path path;
	private int index;
	private StepContext current;
	private int offPathTicks;
	private final List<GridPos> doorsToClose = new ArrayList<>();
	private GridPos breaking;

	public PathFollower(Path path) {
		this.path = path;
		this.current = path.steps().isEmpty() ? null : context(0);
	}

	public Path path() {
		return path;
	}

	public int stepIndex() {
		return index;
	}

	public Step currentStep() {
		return current == null ? null : current.step;
	}

	/** The cell being mined this tick, if any. */
	public GridPos breaking() {
		return breaking;
	}

	/** Planned cost of the current and remaining steps, in ticks. */
	public double remainingCost() {
		double cost = 0;
		for (int i = index; i < path.steps().size(); i++) cost += path.steps().get(i).cost();
		return cost;
	}

	public Tick tick(BodyState body, TerrainView live, MovementPolicy policy) {
		breaking = null;
		Moves moves = new Moves(live, policy);
		if (current == null) return new Tick(closeDoor(MotorIntent.IDLE, body, live), Status.ARRIVED, null);
		skipAhead(body);
		if (current == null) return new Tick(closeDoor(MotorIntent.IDLE, body, live), Status.ARRIVED, null);

		StepContext context = current;
		context.body = body;
		context.live = moves;
		context.policy = policy;
		context.ticks++;
		if (context.cooldown > 0) context.cooldown--;

		String offPath = offPath(body);
		if (offPath != null) return new Tick(MotorIntent.IDLE, Status.REPLAN, offPath);
		if (context.ticks > STEP_GRACE_TICKS + context.step.cost() * 3) {
			return new Tick(MotorIntent.IDLE, Status.REPLAN, "step_timeout " + context.step.type() + " to " + context.step.to());
		}
		if (context.ticks % REVALIDATE_INTERVAL == 1) {
			String invalid = revalidate(moves);
			if (invalid != null) return new Tick(MotorIntent.IDLE, Status.REPLAN, invalid);
		}

		MoveExecutor.Outcome outcome = MoveExecutor.forType(context.step.type()).tick(context);
		if (outcome.replanReason() != null) return new Tick(MotorIntent.IDLE, Status.REPLAN, outcome.replanReason());
		MotorIntent intent = outcome.intent();
		if (intent.action() instanceof MotorIntent.Break mining) breaking = mining.pos();
		if (outcome.done()) {
			advance(index + 1);
			if (current == null) return new Tick(closeDoor(intent, body, live), Status.ARRIVED, null);
		}
		return Tick.running(closeDoor(intent, body, live));
	}

	/** A body that fell or was pushed into a later cell of the path continues from there. */
	private void skipAhead(BodyState body) {
		if (!body.supported()) return;
		GridPos feet = body.feet();
		int last = Math.min(path.steps().size() - 1, index + LOOKAHEAD_STEPS);
		for (int i = last; i > index; i--) {
			Step step = path.steps().get(i);
			if (step.edits() || !step.doors().isEmpty()) break;
			if (step.from().equals(feet)) {
				advance(i);
				return;
			}
		}
	}

	private String offPath(BodyState body) {
		if (!body.supported()) {
			offPathTicks = 0;
			return null;
		}
		GridPos feet = body.feet();
		Step step = current.step;
		if (feet.equals(step.from()) || feet.equals(step.to()) || withinEnvelope(step, feet)) {
			offPathTicks = 0;
			return null;
		}
		return ++offPathTicks > OFF_PATH_TICKS ? "off_path at " + feet + " during " + step.type() + " to " + step.to() : null;
	}

	/** Cells a move passes through: the box spanned by its two feet cells. */
	private static boolean withinEnvelope(Step step, GridPos feet) {
		return feet.x() >= Math.min(step.from().x(), step.to().x()) && feet.x() <= Math.max(step.from().x(), step.to().x())
			&& feet.z() >= Math.min(step.from().z(), step.to().z()) && feet.z() <= Math.max(step.from().z(), step.to().z())
			&& feet.y() >= Math.min(step.from().y(), step.to().y()) && feet.y() <= Math.max(step.from().y(), step.to().y()) + 1;
	}

	/** Upcoming plain steps must still be possible; edit steps are rechecked by their executors. */
	private String revalidate(Moves moves) {
		int last = Math.min(path.steps().size() - 1, index + 2);
		for (int i = index + 1; i <= last; i++) {
			Step previous = path.steps().get(i - 1);
			Step step = path.steps().get(i);
			if (previous.edits() || step.edits() || !step.doors().isEmpty()) break;
			if (moves.stepBetween(step.from(), step.to()) == null) return "step_invalid " + step.type() + " to " + step.to();
		}
		return null;
	}

	/** Adds a use of a door this path opened, once the body is clear of it and the tick has no other action. */
	private MotorIntent closeDoor(MotorIntent intent, BodyState body, TerrainView live) {
		if (doorsToClose.isEmpty() || intent.action() != null || !body.onGround()) return intent;
		GridPos feet = body.feet();
		for (GridPos door : doorsToClose) {
			double distance = body.horizontalDistanceTo(door.x() + 0.5, door.z() + 0.5);
			boolean onPath = current != null && (current.step.from().equals(door) || current.step.to().equals(door));
			if (distance < 1.6 || onPath || feet.equals(door) || feet.equals(door.offset(0, -1, 0))) continue;
			doorsToClose.remove(door);
			// Out of reach or no longer a door: leave it.
			if (distance > 4 || live.cell(door).openable() == CellInfo.Openable.NONE) return intent;
			return intent.withAction(new MotorIntent.Use(door));
		}
		return intent;
	}

	private void advance(int next) {
		if (current != null) doorsToClose.addAll(current.usedDoors);
		index = next;
		current = index < path.steps().size() ? context(index) : null;
		offPathTicks = 0;
	}

	private StepContext context(int stepIndex) {
		List<Step> steps = path.steps();
		return new StepContext(steps.get(stepIndex), stepIndex + 1 < steps.size() ? steps.get(stepIndex + 1) : null);
	}
}
