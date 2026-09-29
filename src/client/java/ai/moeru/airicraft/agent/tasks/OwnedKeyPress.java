package ai.moeru.airicraft.agent.tasks;

import net.minecraft.client.KeyMapping;

public final class OwnedKeyPress {
	private boolean ownsKey;

	public void press(KeyMapping keyMapping) {
		if (keyMapping == null) {
			return;
		}
		keyMapping.setDown(true);
		ownsKey = true;
	}

	public void release(KeyMapping keyMapping) {
		if (!ownsKey) {
			return;
		}
		ownsKey = false;
		if (keyMapping != null) {
			keyMapping.setDown(false);
		}
	}
}
