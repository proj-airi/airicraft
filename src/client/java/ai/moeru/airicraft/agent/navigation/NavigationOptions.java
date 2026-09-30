package ai.moeru.airicraft.agent.navigation;

/**
 * What one navigation request changes about the planner's defaults. Owners pass it with the request,
 * so nothing outlives the request and nothing has to be restored afterward.
 *
 * @param walkOnly     no breaking, placing or sprinting, for owners that must not edit terrain, such as leading animals
 * @param waterPenalty cost per move ending in water, or null for the configured value
 */
public record NavigationOptions(boolean walkOnly, Double waterPenalty) {
	public static final NavigationOptions DEFAULT = new NavigationOptions(false, null);
	public static final NavigationOptions WALK_ONLY = new NavigationOptions(true, null);

	public NavigationOptions withWaterPenalty(double penalty) {
		return new NavigationOptions(walkOnly, penalty);
	}
}
