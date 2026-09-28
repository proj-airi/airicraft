package ai.moeru.airicraft.navigation;

/** Drives one step of a path. Implementations are stateless; per-step memory lives in {@link StepContext}. */
interface MoveExecutor {
	Outcome tick(StepContext context);

	/** The intent for this tick, and whether the step is done or needs a new plan. */
	record Outcome(MotorIntent intent, boolean done, String replanReason) {
		static Outcome running(MotorIntent intent) {
			return new Outcome(intent, false, null);
		}

		static Outcome done(MotorIntent intent) {
			return new Outcome(intent, true, null);
		}

		static Outcome replan(String reason) {
			return new Outcome(MotorIntent.IDLE, false, reason);
		}
	}

	static MoveExecutor forType(MoveType type) {
		return switch (type) {
			case TRAVERSE, DIAGONAL -> MoveExecutors.WALK;
			case BRIDGE -> MoveExecutors.BRIDGE;
			case ASCEND -> MoveExecutors.ASCEND;
			case DESCEND, FALL -> MoveExecutors.DROP;
			case SWIM_UP -> MoveExecutors.SWIM_UP;
			case SWIM_DOWN -> MoveExecutors.SWIM_DOWN;
			case CLIMB_UP -> MoveExecutors.CLIMB_UP;
			case CLIMB_DOWN -> MoveExecutors.CLIMB_DOWN;
			case PILLAR -> MoveExecutors.PILLAR;
		};
	}
}
