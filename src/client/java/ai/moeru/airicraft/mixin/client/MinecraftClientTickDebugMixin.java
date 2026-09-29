package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class MinecraftClientTickDebugMixin {
	@Shadow protected abstract void openChatScreen(String text);
	// Gate the call itself so Fabric/Baritone tick callbacks cannot run ahead of the pause.
	// Render-loop tasks remain available for bridge reads, stepping and frame capture.
	@WrapWithCondition(method = "runTick", at = @At(
		value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;tick()V"
	))
	private boolean airicraft$gateClientTick(Minecraft minecraft) {
		if (minecraft.level != null && AiricraftClient.runtimeController().automaticPlaytest().emptyHostPaused()) {
			// Preserve UI and protocol maintenance without running gameplay or agent callbacks.
			if (minecraft.getOverlay() == null) {
				if (minecraft.screen != null) minecraft.screen.tick();
				else {
					if (minecraft.options.keyChat.consumeClick()) openChatScreen("");
					else if (minecraft.options.keyCommand.consumeClick()) openChatScreen("/");
				}
			}
			if (minecraft.getConnection() != null) minecraft.getConnection().getConnection().tick();
			AiricraftClient.runtimeController().automaticPlaytest().maintainPausedHost(minecraft);
			return false;
		}
		// Tick debugging only applies in-world. Menus, the connect screen and
		// Fabric tick events must keep running when there is no player.
		if (minecraft.player == null) {
			return true;
		}
		return AiricraftClient.runtimeController().clientTickDebugRuntime().beginClientTick();
	}

	@ModifyExpressionValue(method = "runTick", at = @At(
		value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;isLevelRunningNormally()Z"
	))
	private boolean airicraft$freezeTickInterpolation(boolean shouldTick) {
		return AiricraftClient.runtimeController().clientTickDebugRuntime().allowVanillaTick(shouldTick);
	}
}
