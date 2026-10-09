package ai.moeru.airicraft.agent.navigation;

/**
 * What one navigation request changes about the planner's defaults. Owners pass it with the request,
 * so nothing outlives the request and nothing has to be restored afterward.
 *
 * @param walkOnly     no breaking, placing or sprinting, for owners that must not edit terrain, such as leading animals
 * @param requireCompletePath reject partial routes before moving, for local interaction stances
 * @param waterPenalty cost per move ending in water, or null for the configured value
 */
public record NavigationOptions(boolean walkOnly, Double waterPenalty, boolean requireCompletePath) {
	public NavigationOptions(boolean walkOnly, Double waterPenalty) {
        this(walkOnly, waterPenalty, false);
    }

    public static final NavigationOptions PLACEMENT_STANCE = new NavigationOptions(false, null, true);

    public boolean acceptsPath(ai.moeru.airicraft.navigation.SearchResult result) {
        return result instanceof ai.moeru.airicraft.navigation.SearchResult.Found
            || !requireCompletePath && result instanceof ai.moeru.airicraft.navigation.SearchResult.Partial;
    }

	public static final NavigationOptions DEFAULT = new NavigationOptions(false, null);
	public static final NavigationOptions WALK_ONLY = new NavigationOptions(true, null);

	public NavigationOptions withWaterPenalty(double penalty) {
		return new NavigationOptions(walkOnly, penalty, requireCompletePath);
	}
}
