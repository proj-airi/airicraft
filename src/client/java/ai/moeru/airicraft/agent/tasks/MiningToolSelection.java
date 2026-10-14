package ai.moeru.airicraft.agent.tasks;

import java.util.function.IntFunction;

/**
 * Target-specific inventory selection; ties preserve the current tool, except that a hotbar item that does not wear
 * (an empty hand, a block) beats a tool that would only lose durability without breaking any faster.
 */
final class MiningToolSelection {
	private MiningToolSelection() {}

	static int preferredSlot(int selectedSlot, IntFunction<Score> scoreAt) {
		Score best = scoreAt.apply(selectedSlot);
		int bestSlot = best.eligible() ? selectedSlot : -1;
		for (int slot = 0; slot < 36; slot++) {
			Score candidate = scoreAt.apply(slot);
			if (candidate.eligible() && (!best.eligible() || candidate.speed() > best.speed()
				|| slot < 9 && candidate.speed() == best.speed() && best.wears() && !candidate.wears())) {
				best = candidate;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	/** A clearing tool at least this fraction as fast as the fastest carried one is fast enough. */
	static final float ADEQUATE_FRACTION = 0.5F;

	/**
	 * Clearing a route only needs a tool that is fast enough, so of the tools that harvest the block and break it at
	 * least half as fast as the best, the slowest wears instead: Nether travel wore a diamond pickaxe out on
	 * netherrack that a stone one breaks almost as fast. Without such a tool, the fastest one.
	 */
	static int preferredClearanceSlot(int selectedSlot, IntFunction<Score> scoreAt) {
		float best = 0.0F;
		for (int slot = 0; slot < 36; slot++) best = Math.max(best, clearanceSpeed(scoreAt.apply(slot)));
		float enough = best * ADEQUATE_FRACTION;
		int frugal = preferredSlot(selectedSlot, slot -> {
			Score score = scoreAt.apply(slot);
			boolean adequate = score.eligible() && clearanceSpeed(score) >= enough;
			// A hand or block that is fast enough wears nothing; among tools the slowest is the cheapest to wear.
			return new Score(adequate, !adequate ? 0.0F : score.wears() ? 1.0F / score.speed() : Float.MAX_VALUE, score.wears());
		});
		if (frugal >= 0) return frugal;
		return preferredSlot(selectedSlot, slot -> {
			Score score = scoreAt.apply(slot);
			return new Score(true, clearanceSpeed(score), score.wears());
		});
	}

	static float clearanceSpeed(Score score) {
		// Vanilla divides break progress by 30 when harvestable, otherwise by 100.
		return score.speed() * (score.eligible() ? 1.0F : 0.3F);
	}

	/** {@code wears} marks a damageable item, which loses durability on every block it breaks. */
	record Score(boolean eligible, float speed, boolean wears) {
		Score(boolean eligible, float speed) {
			this(eligible, speed, false);
		}
	}
}
