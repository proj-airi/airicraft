package ai.moeru.airicraft.navigation;

import ai.moeru.airicraft.navigation.MotorIntent.Point;

/** One executor per move family. Edits happen from the start cell before the body moves. */
final class MoveExecutors {
	private static final double EYE_HEIGHT = 1.62;
	/** How close to the destination centre a step that turns afterwards must end. */
	private static final double TURN_TOLERANCE = 0.45;

	private MoveExecutors() {
	}

	static final MoveExecutor WALK = context -> {
		MoveExecutor.Outcome edit = prepare(context);
		if (edit != null) return edit;
		return walk(context, context.step.type() == MoveType.TRAVERSE || context.step.type() == MoveType.DIAGONAL);
	};

	static final MoveExecutor BRIDGE = context -> {
		if (context.atStart() && context.body.onGround()) {
			Step fresh = context.live.stepBetween(context.step.from(), context.step.to());
			if (fresh == null) return MoveExecutor.Outcome.replan("bridge_blocked");
			if (fresh.place() != null) {
				if (context.cooldown > 0) return MoveExecutor.Outcome.running(hold(context, true));
				// Stay on the supporting block, sneaking so the edge cannot be walked off.
				MotorIntent steady = centre(context, context.step.from(), true);
				context.cooldown = 2;
				return MoveExecutor.Outcome.running(steady.withAction(new MotorIntent.Place(fresh.place(), fresh.placeAgainst())));
			}
			MoveExecutor.Outcome edit = prepare(context);
			if (edit != null) return edit;
		}
		return walk(context, false);
	};

	static final MoveExecutor ASCEND = context -> {
		MoveExecutor.Outcome edit = prepare(context);
		if (edit != null) return edit;
		BodyState body = context.body;
		Point target = centreOf(context.step.to());
		boolean close = context.distanceToEnd() < 1.15;
		boolean jump = body.inWater() || body.onGround() && (body.horizontalCollision() || close) && !context.atEnd();
		MotorIntent intent = toward(context, target, jump, false, false);
		return finish(context, intent, context.atEnd() && body.onGround());
	};

	static final MoveExecutor DROP = context -> {
		MoveExecutor.Outcome edit = prepare(context);
		if (edit != null) return edit;
		BodyState body = context.body;
		Point target = centreOf(context.step.to());
		MotorIntent intent;
		if (!body.supported() && context.distanceToEnd() < 0.2) {
			// Over the landing column: stop steering so the fall does not overshoot it.
			intent = new MotorIntent(0, 0, false, false, false, lookAhead(context, target), null);
		}
		else intent = toward(context, target, false, false, false);
		return finish(context, intent, context.atEnd() && body.supported());
	};

	static final MoveExecutor SWIM_UP = context -> {
		MotorIntent intent = toward(context, centreOf(context.step.to()), true, false, false);
		BodyState body = context.body;
		return finish(context, intent, body.feet().y() >= context.step.to().y() && sameColumn(body, context.step.to()));
	};

	static final MoveExecutor SWIM_DOWN = context -> {
		MotorIntent intent = toward(context, centreOf(context.step.to()), false, true, false);
		BodyState body = context.body;
		return finish(context, intent, body.feet().y() <= context.step.to().y() && sameColumn(body, context.step.to()));
	};

	static final MoveExecutor CLIMB_UP = context -> {
		// Jumping while on a climbable block climbs it, facing any direction.
		MotorIntent intent = toward(context, centreOf(context.step.to()), true, false, false);
		return finish(context, intent, context.atEnd() && context.body.supported());
	};

	static final MoveExecutor CLIMB_DOWN = context -> {
		MotorIntent intent = toward(context, centreOf(context.step.to()), false, false, false);
		return finish(context, intent, context.atEnd() && context.body.supported());
	};

	static final MoveExecutor PILLAR = context -> {
		BodyState body = context.body;
		GridPos from = context.step.from();
		CellInfo feet = context.terrain().cell(from);
		boolean placed = feet.loaded() && !feet.replaceable();
		if (placed) {
			MotorIntent wait = new MotorIntent(0, 0, false, true, false, null, null);
			return finish(context, wait, context.atEnd() && body.onGround());
		}
		if (context.atStart() && body.onGround()) {
			MoveExecutor.Outcome edit = prepare(context);
			if (edit != null) return edit;
			if (body.horizontalDistanceTo(from.x() + 0.5, from.z() + 0.5) > 0.2) {
				return MoveExecutor.Outcome.running(centre(context, from, true));
			}
		}
		Point floor = new Point(from.x() + 0.5, from.y(), from.z() + 0.5);
		MotorIntent intent = new MotorIntent(0, 0, body.onGround(), true, false, floor, null);
		// The block fits only once the feet are above the cell being filled.
		if (body.y() >= from.y() + 1.0 && context.cooldown == 0) {
			context.cooldown = 1;
			intent = intent.withAction(new MotorIntent.Place(from, from.offset(0, -1, 0)));
		}
		return MoveExecutor.Outcome.running(intent);
	};

	/**
	 * Breaks blocks and uses doors the step still needs, re-evaluated on the live terrain. Returns
	 * null when nothing is left to do, so the executor can move.
	 */
	private static MoveExecutor.Outcome prepare(StepContext context) {
		if (!context.atStart() || !context.body.supported()) return null;
		if (context.step.breaks().isEmpty() && context.step.doors().isEmpty() && context.step.place() == null) return null;
		Step fresh = context.live.stepBetween(context.step.from(), context.step.to());
		if (fresh == null) return MoveExecutor.Outcome.replan("step_blocked");
		if (context.cooldown > 0) return MoveExecutor.Outcome.running(hold(context, false));
		if (!fresh.doors().isEmpty()) {
			GridPos door = fresh.doors().getFirst();
			context.cooldown = StepContext.ACTION_COOLDOWN;
			if (!context.usedDoors.contains(door)) context.usedDoors.add(door);
			return MoveExecutor.Outcome.running(hold(context, false).withAction(new MotorIntent.Use(door)));
		}
		if (!fresh.breaks().isEmpty()) {
			GridPos target = fresh.breaks().getFirst();
			MotorIntent breaking = new MotorIntent(0, 0, false, false, false, Point.center(target), new MotorIntent.Break(target));
			return MoveExecutor.Outcome.running(breaking);
		}
		return null;
	}

	private static MoveExecutor.Outcome walk(StepContext context, boolean maySprint) {
		BodyState body = context.body;
		GridPos to = context.step.to();
		boolean descending = context.next != null && context.next.type() == MoveType.SWIM_DOWN;
		boolean jump = body.inWater() ? !descending : body.onGround() && body.horizontalCollision();
		boolean sprint = maySprint && context.policy.allowSprint() && body.onGround() && !body.inWater() && context.distanceToEnd() > 0.6;
		MotorIntent intent = toward(context, centreOf(to), jump, false, sprint);
		boolean done = context.atEnd() && body.supported() && (!context.turnsAfter() || context.distanceToEnd() < TURN_TOLERANCE);
		return finish(context, intent, done);
	}

	private static MoveExecutor.Outcome finish(StepContext context, MotorIntent intent, boolean done) {
		return done ? MoveExecutor.Outcome.done(intent) : MoveExecutor.Outcome.running(intent);
	}

	private static MotorIntent toward(StepContext context, Point target, boolean jump, boolean sneak, boolean sprint) {
		BodyState body = context.body;
		double dx = target.x() - body.x(), dz = target.z() - body.z();
		double length = Math.sqrt(dx * dx + dz * dz);
		if (length < 0.05) return new MotorIntent(0, 0, jump, sneak, false, lookAhead(context, target), null);
		return new MotorIntent(dx / length, dz / length, jump, sneak, sprint, lookAhead(context, target), null);
	}

	private static MotorIntent centre(StepContext context, GridPos cell, boolean sneak) {
		return toward(context, centreOf(cell), false, sneak, false);
	}

	private static MotorIntent hold(StepContext context, boolean sneak) {
		return new MotorIntent(0, 0, false, sneak, false, null, null);
	}

	/** Looks along the route at eye height, one step past the current target when there is one. */
	private static Point lookAhead(StepContext context, Point target) {
		GridPos further = context.next == null ? null : context.next.to();
		Point focus = further == null ? target : centreOf(further);
		return new Point(focus.x(), context.body.y() + EYE_HEIGHT, focus.z());
	}

	private static Point centreOf(GridPos pos) {
		return new Point(pos.x() + 0.5, pos.y(), pos.z() + 0.5);
	}

	private static boolean sameColumn(BodyState body, GridPos pos) {
		return (int) Math.floor(body.x()) == pos.x() && (int) Math.floor(body.z()) == pos.z();
	}
}
