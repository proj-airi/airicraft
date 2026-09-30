package ai.moeru.airicraft.control;

/** What a lease holder wants from one channel. Sticky: it applies every tick until replaced or released. */
public sealed interface ChannelIntent {
	Channel channel();

	/**
	 * Movement keys, relative to the player's yaw. The adapter turns these into player input.
	 * {@link #NONE} holds the channel with nothing pressed.
	 *
	 * @param autoJump whether the client's auto-jump assist is on while this intent holds locomotion;
	 *                 the adapter restores the player's own setting when the lease ends
	 */
	record Locomotion(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak,
		boolean sprint, boolean autoJump) implements ChannelIntent {
		public static final Locomotion NONE = new Locomotion(false, false, false, false, false, false, false);

		public Locomotion {
			// Opposite keys cancel, as they do on a keyboard; sprint needs forward and no sneak.
			if (forward && back) forward = back = false;
			if (left && right) left = right = false;
			sprint = sprint && forward && !sneak;
		}

		public Locomotion(boolean forward, boolean back, boolean left, boolean right, boolean jump, boolean sneak,
			boolean sprint) {
			this(forward, back, left, right, jump, sneak, sprint, false);
		}

		@Override
		public Channel channel() {
			return Channel.LOCOMOTION;
		}

		/** Nothing pressed; the auto-jump assist does not count as a key. */
		public boolean idle() {
			return !forward && !back && !left && !right && !jump && !sneak && !sprint;
		}
	}

	/** A world-space point to aim at; the camera controller owns the smoothing. */
	record Look(double x, double y, double z, String reason) implements ChannelIntent {
		@Override
		public Channel channel() {
			return Channel.LOOK;
		}
	}

	/** A hotbar slot, 0 to 8. */
	record Hotbar(int slot) implements ChannelIntent {
		public Hotbar {
			if (slot < 0 || slot > 8) throw new IllegalArgumentException("hotbar slot out of range: " + slot);
		}

		@Override
		public Channel channel() {
			return Channel.HOTBAR;
		}
	}
}
