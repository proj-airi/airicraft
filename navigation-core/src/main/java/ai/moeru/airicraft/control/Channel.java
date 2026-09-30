package ai.moeru.airicraft.control;

/**
 * One independently leased part of the player's body. Eating can hold the hotbar while navigation
 * holds locomotion and look.
 */
public enum Channel {
	/** Movement keys: forward, back, strafe, jump, sneak and sprint. */
	LOCOMOTION,
	/** Where the camera points. */
	LOOK,
	/** The selected hotbar slot. */
	HOTBAR
}
