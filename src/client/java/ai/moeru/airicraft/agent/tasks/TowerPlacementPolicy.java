package ai.moeru.airicraft.agent.tasks;

/** One pinned underfoot block; never infer a new target from a mid-jump feet cell. */
final class TowerPlacementPolicy {
    enum Action { CENTER, JUMP, WAIT, PLACE, FAIL }
    static Action next(double horizontalDistance, double feetY, double targetY, boolean grounded, boolean headroom) {
        if (!Double.isFinite(horizontalDistance) || !Double.isFinite(feetY) || !Double.isFinite(targetY)) return Action.FAIL;
        if (feetY >= targetY + 1) return horizontalDistance <= .2 ? Action.PLACE : Action.FAIL;
        if (!grounded) return Action.WAIT;
        if (Math.abs(feetY - targetY) > .05) return Action.FAIL;
        if (horizontalDistance > .15) return Action.CENTER;
        return headroom ? Action.JUMP : Action.FAIL;
    }
}
