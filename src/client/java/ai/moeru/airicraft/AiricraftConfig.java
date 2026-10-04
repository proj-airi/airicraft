package ai.moeru.airicraft;

import ai.moeru.airicraft.airi.AiriLinkConfig;
import ai.moeru.airicraft.dashboard.DebugDashboardConfig;

public record AiricraftConfig(
	int socialChatMaxDistanceBlocks,
	boolean readSystemChatMessages,
	boolean enableProactiveSocialMode,
	boolean suppressAutoPauseOnFocusLost,
	int blockInteractionDelayTicks,
	int cameraLerpDefaultTicks,
	DebugDashboardConfig debugDashboard,
	AiriLinkConfig airi
) {
	public static final int DEFAULT_BLOCK_INTERACTION_DELAY_TICKS = 2;
	public static final int DEFAULT_CAMERA_LERP_DEFAULT_TICKS = 0;

	public static AiricraftConfig defaults() {
		return new AiricraftConfig(
			-1,
			true,
			false,
			true,
			DEFAULT_BLOCK_INTERACTION_DELAY_TICKS,
			DEFAULT_CAMERA_LERP_DEFAULT_TICKS,
			DebugDashboardConfig.defaults(),
			AiriLinkConfig.defaults()
		);
	}

	public AiricraftConfig {
		blockInteractionDelayTicks = Math.max(0, blockInteractionDelayTicks);
		cameraLerpDefaultTicks = Math.max(0, cameraLerpDefaultTicks);
		debugDashboard = debugDashboard == null ? DebugDashboardConfig.defaults() : debugDashboard;
		airi = airi == null ? AiriLinkConfig.defaults() : airi;
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
			DebugDashboardConfig.defaults(),
			AiriLinkConfig.defaults()
		);
	}

	public boolean socialChatDistanceUnlimited() {
		return socialChatMaxDistanceBlocks < 0;
	}
}
