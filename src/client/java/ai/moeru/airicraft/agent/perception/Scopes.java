package ai.moeru.airicraft.agent.perception;

import net.minecraft.client.MinecraftClient;

/** Memory scopes: one per connection (world) and dimension, so a return to a dimension keeps what was noticed there. */
final class Scopes {
	private Scopes() {
	}

	static String of(MinecraftClient client) {
		return Integer.toHexString(System.identityHashCode(client.getNetworkHandler())) + "|"
			+ client.world.getRegistryKey().getValue();
	}

	static boolean ready(MinecraftClient client) {
		return client != null && client.world != null && client.player != null && client.player.isAlive();
	}
}
