package ai.moeru.airicraft.control;

import java.util.Map;
import java.util.Set;

/**
 * The merged control of one client tick. A channel is written only while it is {@link #owners held}
 * or in {@link #released} (its last tick, to be cleared once). Everything else stays with the human,
 * so a hosted-playtest tester's own keys are never overwritten.
 *
 * @param owners   the holder of each held channel, by lease owner name
 * @param intents  the latest intent of each held channel; a held channel with no entry has none yet
 * @param released channels whose lease ended since the previous frame
 */
public record ControlFrame(Map<Channel, String> owners, Map<Channel, ChannelIntent> intents, Set<Channel> released) {
	public static final ControlFrame EMPTY = new ControlFrame(Map.of(), Map.of(), Set.of());

	public ControlFrame {
		owners = Map.copyOf(owners);
		intents = Map.copyOf(intents);
		released = Set.copyOf(released);
	}

	public boolean held(Channel channel) {
		return owners.containsKey(channel);
	}

	public String owner(Channel channel) {
		return owners.get(channel);
	}

	/** What to write to the movement keys, or null when locomotion is not ours this tick. */
	public ChannelIntent.Locomotion locomotion() {
		if (!held(Channel.LOCOMOTION)) return released.contains(Channel.LOCOMOTION) ? ChannelIntent.Locomotion.NONE : null;
		return intents.get(Channel.LOCOMOTION) instanceof ChannelIntent.Locomotion move ? move : ChannelIntent.Locomotion.NONE;
	}

	/** The point to aim at, or null when there is no look request. */
	public ChannelIntent.Look look() {
		return held(Channel.LOOK) && intents.get(Channel.LOOK) instanceof ChannelIntent.Look look ? look : null;
	}

	/** Whether the use key is held this tick. */
	public boolean useHeld() {
		return held(Channel.SECONDARY) && intents.get(Channel.SECONDARY) instanceof ChannelIntent.HoldUse;
	}

	/** The slot to select, or -1 for no request. */
	public int hotbarSlot() {
		return held(Channel.HOTBAR) && intents.get(Channel.HOTBAR) instanceof ChannelIntent.Hotbar hotbar ? hotbar.slot() : -1;
	}
}
