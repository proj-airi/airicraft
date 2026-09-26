package ai.moeru.airicraft.agent;

import java.util.Objects;

/** Session choice for inventory eating; combat healing is a separate survival reflex. */
public record FoodPolicy(Goal goal, FoodChoice foodChoice) {
	public FoodPolicy {
		Objects.requireNonNull(goal, "goal");
		Objects.requireNonNull(foodChoice, "foodChoice");
	}

	public static FoodPolicy defaults() {
		return new FoodPolicy(Goal.MOVEMENT, FoodChoice.ANY);
	}

	public boolean shouldEatIdle(int hunger, float health, float maxHealth) {
		return switch (goal) {
			case OFF -> false;
			case MOVEMENT -> hunger <= 6;
			case HEAL -> health > 0 && health < maxHealth && hunger < 18;
		};
	}

	public boolean shouldSeekCombatHeal(int hunger, float health, float maxHealth) {
		return health > 0 && health < maxHealth * .5F && hunger < 18;
	}

	public enum Goal { OFF, MOVEMENT, HEAL }
	public enum FoodChoice { ANY, COOKED_ONLY }
}
