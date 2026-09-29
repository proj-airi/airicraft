package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.AlphaVertexConsumer;
import ai.moeru.airicraft.TintedVertexConsumer;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AbstractTerrainRenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.render.VertexConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Applies world-camera effects to the block model path used by Fabric Indigo. */
@Mixin(AbstractTerrainRenderContext.class)
public abstract class IndigoTerrainRenderContextMixin {
	@Shadow @Final protected BlockRenderInfo blockInfo;

	@ModifyExpressionValue(
		method = "bufferQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/AbstractTerrainRenderContext;getVertexConsumer(Lnet/minecraft/client/render/BlockRenderLayer;)Lnet/minecraft/client/render/VertexConsumer;"
		)
	)
	private VertexConsumer airicraft$worldCameraEffects(VertexConsumer consumer) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		if (service == null) {
			return consumer;
		}
		if (blockInfo.blockState.getBlock() instanceof LeavesBlock && service.fadeLeavesActive()) {
			consumer = new AlphaVertexConsumer(consumer, 0.4f);
		}
		if (service.tintContains(blockInfo.blockPos)) {
			WorldCameraService.TINT_HITS.incrementAndGet();
			consumer = new TintedVertexConsumer(consumer, 0x3080FF, 0.8f);
		}
		return consumer;
	}
}
