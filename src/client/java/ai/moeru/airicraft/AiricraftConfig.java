package ai.moeru.airicraft;

import ai.moeru.airicraft.dashboard.DebugDashboardConfig;

public record AiricraftConfig(
	int socialChatMaxDistanceBlocks,
	boolean readSystemChatMessages,
	boolean enableProactiveSocialMode,
	boolean suppressAutoPauseOnFocusLost,
	int blockInteractionDelayTicks,
	int cameraLerpDefaultTicks,
	DebugDashboardConfig debugDashboard,
	String navigationBackend
) {
	public static final int DEFAULT_BLOCK_INTERACTION_DELAY_TICKS = 2;
	public static final int DEFAULT_CAMERA_LERP_DEFAULT_TICKS = 0;
	/** Navigation backends during the in-house rollout; the flip removes the key. */
	public static final String NAVIGATION_BARITONE = "baritone";
	public static final String NAVIGATION_AIRICRAFT = "airicraft";

	public static AiricraftConfig defaults() {
		return new AiricraftConfig(
			-1,
			true,
			false,
			true,
			DEFAULT_BLOCK_INTERACTION_DELAY_TICKS,
			DEFAULT_CAMERA_LERP_DEFAULT_TICKS,
			DebugDashboardConfig.defaults(),
			NAVIGATION_BARITONE
		);
	}

	public AiricraftConfig {
		blockInteractionDelayTicks = Math.max(0, blockInteractionDelayTicks);
		cameraLerpDefaultTicks = Math.max(0, cameraLerpDefaultTicks);
		debugDashboard = debugDashboard == null ? DebugDashboardConfig.defaults() : debugDashboard;
		navigationBackend = navigationBackend == null || navigationBackend.isBlank() ? NAVIGATION_BARITONE
			: navigationBackend.trim().toLowerCase(java.util.Locale.ROOT);
		if (!navigationBackend.equals(NAVIGATION_BARITONE) && !navigationBackend.equals(NAVIGATION_AIRICRAFT)) {
			throw new IllegalArgumentException("navigation.backend must be baritone or airicraft, got " + navigationBackend);
		}
	}

	public AiricraftConfig(
		int socialChatMaxDistanceBlocks,
		boolean readSystemChatMessages,
		boolean enableProactiveSocialMode,
		boolean suppressAutoPauseOnFocusLost,
		int blockInteractionDelayTicks,
		int cameraLerpDefaultTicks,
		DebugDashboardConfig debugDashboard
	) {
		this(socialChatMaxDistanceBlocks, readSystemChatMessages, enableProactiveSocialMode, suppressAutoPauseOnFocusLost,
			blockInteractionDelayTicks, cameraLerpDefaultTicks, debugDashboard, NAVIGATION_BARITONE);
	}

	/** The configured backend, unless the {@code airicraft.navigation.backend} system property overrides it. */
	public String effectiveNavigationBackend() {
		String override = System.getProperty("airicraft.navigation.backend", "").trim().toLowerCase(java.util.Locale.ROOT);
		return override.equals(NAVIGATION_BARITONE) || override.equals(NAVIGATION_AIRICRAFT) ? override : navigationBackend;
	}

	public AiricraftConfig(
		int socialChatMaxDistanceBlocks,
		boolean readSystemChatMessages,
		boolean enableProactiveSocialMode,
		boolean suppressAutoPauseOnFocusLost,
		int blockInteractionDelayTicks,
		int cameraLerpDefaultTicks
	) {
		this(
			socialChatMaxDistanceBlocks,
			readSystemChatMessages,
			enableProactiveSocialMode,
			suppressAutoPauseOnFocusLost,
			blockInteractionDelayTicks,
			cameraLerpDefaultTicks,
			DebugDashboardConfig.defaults()
		);
	}

	public boolean socialChatDistanceUnlimited() {
		return socialChatMaxDistanceBlocks < 0;
	}
}
