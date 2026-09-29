package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.chat.ChatListener;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatListener.class)
public class MessageHandlerMixin {
	@Inject(method = "handlePlayerChatMessage", at = @At("TAIL"))
	private void airicraft$onChatMessage(
		PlayerChatMessage message,
		GameProfile sender,
		ChatType.Bound params,
		CallbackInfo ci
	) {
		if (sender == null) {
			return;
		}

		String content = message.signedContent();
		if (content == null || content.isBlank()) {
			Component fallback = message.decoratedContent();
			content = fallback == null ? "" : fallback.getString();
		}

		if (content.isBlank()) {
			return;
		}

		AiricraftClient.runtimeController().onChatReceived(sender.getName(), content);
	}

	@Inject(method = "handleDisguisedChatMessage", at = @At("TAIL"))
	private void airicraft$onProfilelessMessage(Component content, ChatType.Bound params, CallbackInfo ci) {
		String message = content == null ? "" : content.getString();
		if (message.isBlank()) {
			return;
		}

		AiricraftClient.runtimeController().onSystemChatReceived(message);
	}

	@Inject(method = "handleSystemMessage", at = @At("TAIL"))
	private void airicraft$onGameMessage(Component content, boolean overlay, CallbackInfo ci) {
		if (overlay) {
			return;
		}

		String message = content == null ? "" : content.getString();
		if (message.isBlank()) {
			return;
		}

		AiricraftClient.runtimeController().onSystemChatReceived(message);
	}
}
