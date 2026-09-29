package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import net.minecraft.util.math.ColorHelper;
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
		if (!(state instanceof PlayerEntityRenderState)) {
			return false;
		}
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		return service != null && service.shoulderActive() && service.playerTranslucent();
	}

	@ModifyReturnValue(method = "getRenderLayer", at = @At("RETURN"))
	private RenderLayer airicraft$translucentPlayerLayer(
		RenderLayer original,
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
		return RenderLayer.getEntityTranslucent(self.getTexture(state));
	}
	@ModifyArg(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/render/entity/model/EntityModel;render(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;III)V"
		),
		index = 4
	)
	private int airicraft$halvePlayerAlpha(int color) {
		if (!AIRICRAFT$SHOULDER_PLAYER.get()) {
			return color;
		}
		AIRICRAFT$SHOULDER_PLAYER.set(false);
		return ColorHelper.withAlpha(ColorHelper.getAlpha(color) / 2, color);
	}
}
