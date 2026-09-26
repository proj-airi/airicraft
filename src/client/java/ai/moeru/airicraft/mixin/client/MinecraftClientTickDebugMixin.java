package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientTickDebugMixin {
	@Shadow protected abstract void openChatScreen(String text);
	// Gate the call itself so Fabric/Baritone tick callbacks cannot run ahead of the pause.
	// Render-loop tasks remain available for bridge reads, stepping and frame capture.
	@WrapWithCondition(method = "render", at = @At(
		value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;tick()V"
	))
	private boolean airicraft$gateClientTick(MinecraftClient client) {
		if (client.world != null && AiricraftClient.runtimeController().automaticPlaytest().emptyHostPaused()) {
			// Preserve UI and protocol maintenance without running gameplay or agent callbacks.
			if (client.getOverlay() == null) {
				if (client.currentScreen != null) client.currentScreen.tick();
				else {
					if (client.options.chatKey.wasPressed()) openChatScreen("");
					else if (client.options.commandKey.wasPressed()) openChatScreen("/");
				}
			}
			if (client.getNetworkHandler() != null) client.getNetworkHandler().getConnection().tick();
			AiricraftClient.runtimeController().automaticPlaytest().maintainPausedHost(client);
			return false;
		}
		// Tick debugging only applies in-world. Menus, the connect screen and
		// Fabric tick events must keep running when there is no player.
		if (client.player == null) {
			return true;
		}
		return AiricraftClient.runtimeController().clientTickDebugRuntime().beginClientTick();
	}

	@ModifyExpressionValue(method = "render", at = @At(
		value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;shouldTick()Z"
	))
	private boolean airicraft$freezeTickInterpolation(boolean shouldTick) {
		return AiricraftClient.runtimeController().clientTickDebugRuntime().allowVanillaTick(shouldTick);
	}
}
