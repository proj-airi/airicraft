package ai.moeru.airicraft.agent;

enum LifecycleBoundary {
	WORLD_LEFT,
	WORLD_LOADED,
	AWAITING_RESPAWN,
	RESPAWNED,
	WORLD_CHANGED,
	PLAYER_UNAVAILABLE,
	SHUTDOWN
}
