package ai.moeru.airicraft.agent;

import java.util.Objects;

/** Session choice for inventory eating; combat healing is a separate survival reflex. */
public record FoodPolicy(Goal goal, FoodChoice foodChoice) {
	public FoodPolicy {
		Objects.requireNonNull(goal, "goal");
		Objects.requireNonNull(foodChoice, "foodChoice");
	}

	static final float HURT_FRACTION = 0.7F;
	static final int MOVEMENT_TOP_UP = 12;

	public static FoodPolicy defaults() {
		return new FoodPolicy(Goal.MOVEMENT, FoodChoice.ANY);
	}

	public boolean shouldEatIdle(int hunger, float health, float maxHealth) {
		return shouldEatIdle(hunger, health, maxHealth, false);
	}

	/**
	 * {@code resting}: the body waits with nothing to do (a night shelter, a quiet hold). Health only comes back at 18
	 * food, and a playtest body sat a whole night in its shelter at 14.6 health and 17 food; at rest any hurt is worth a
	 * bite unless eating is off.
	 */
	public boolean shouldEatIdle(int hunger, float health, float maxHealth, boolean resting) {
		if (resting && goal != Goal.OFF && health > 0 && health < maxHealth && hunger < 18) return true;
		return switch (goal) {
			case OFF -> false;
			// Also eat when hurt: health only comes back at 18 food, and playtest bodies walked into fights at
			// 13 health and 8 food with food in the pack.
			// Top up at 12: at 6 the body can no longer sprint, and a playtest body walking at food 6 could not
			// outrun three zombies.
			case MOVEMENT -> hunger <= MOVEMENT_TOP_UP || hunger < 18 && health > 0 && health <= maxHealth * HURT_FRACTION;
			case HEAL -> health > 0 && health < maxHealth && hunger < 18;
		};
	}

	public boolean shouldSeekCombatHeal(int hunger, float health, float maxHealth) {
		return health > 0 && health < maxHealth * .5F && hunger < 18;
	}

	public enum Goal { OFF, MOVEMENT, HEAL }
	public enum FoodChoice { ANY, COOKED_ONLY }
}
