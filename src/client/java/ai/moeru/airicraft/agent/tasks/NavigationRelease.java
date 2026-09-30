package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;

/**
 * Handoff between owners of navigation. Cancellation is synchronous, so a new owner may start in the
 * same tick that the previous one is released; there is nothing to wait for.
 */
final class NavigationRelease {
	private NavigationRelease() {
	}

	/** Whether no request controls movement, or navigation is unavailable. */
	static boolean idle(NavigationFacade navigation) {
		return navigation == null || !navigation.isLoaded() || !navigation.processActive();
	}

	/** Ends the current request, if any. Movement is released when this returns. */
	static void release(NavigationFacade navigation) {
		if (!idle(navigation)) navigation.cancel();
	}
}
