package ai.moeru.airicraft.agent.perception;

import net.minecraft.client.Minecraft;

/** Memory scopes: one per connection (world) and dimension, so a return to a dimension keeps what was noticed there. */
final class Scopes {
	private Scopes() {
	}

	static String of(Minecraft minecraft) {
		return Integer.toHexString(System.identityHashCode(minecraft.getConnection())) + "|"
			+ minecraft.level.dimension().location();
	}

	static boolean ready(Minecraft minecraft) {
		return minecraft != null && minecraft.level != null && minecraft.player != null && minecraft.player.isAlive();
	}
}
