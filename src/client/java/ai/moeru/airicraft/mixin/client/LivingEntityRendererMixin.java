package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Shoulder-surf mode renders the player semi-transparent so the model does
 * not block the view. Two hooks: force the translucent render layer, and
 * halve the vertex color alpha passed to the model.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	private static final ThreadLocal<Boolean> AIRICRAFT$SHOULDER_PLAYER = ThreadLocal.withInitial(() -> false);

	private static boolean airicraft$isShoulderPlayer(LivingEntityRenderState state) {
		if (!(state instanceof PlayerRenderState)) {
			return false;
		}
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		return service != null && service.shoulderActive() && service.playerTranslucent();
	}

	@ModifyReturnValue(method = "getRenderType", at = @At("RETURN"))
	private RenderType airicraft$translucentPlayerLayer(
		RenderType original,
		@Local(argsOnly = true) LivingEntityRenderState state
	) {
		boolean shoulder = airicraft$isShoulderPlayer(state);
		AIRICRAFT$SHOULDER_PLAYER.set(shoulder);
		if (!shoulder || original == null) {
			return original;
		}
		@SuppressWarnings("unchecked")
		LivingEntityRenderer<?, LivingEntityRenderState, ?> self =
			(LivingEntityRenderer<?, LivingEntityRenderState, ?>) (Object) this;
		return RenderType.entityTranslucent(self.getTextureLocation(state));
	}
	@ModifyArg(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;III)V"
		),
		index = 4
	)
	private int airicraft$halvePlayerAlpha(int color) {
		if (!AIRICRAFT$SHOULDER_PLAYER.get()) {
			return color;
		}
		AIRICRAFT$SHOULDER_PLAYER.set(false);
		return ARGB.color(ARGB.alpha(color) / 2, color);
	}
}
