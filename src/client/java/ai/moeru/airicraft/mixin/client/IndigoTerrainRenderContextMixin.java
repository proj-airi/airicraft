package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.AlphaVertexConsumer;
import ai.moeru.airicraft.TintedVertexConsumer;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.AbstractTerrainRenderContext;
import net.fabricmc.fabric.impl.client.indigo.renderer.render.BlockRenderInfo;
import net.minecraft.world.level.block.LeavesBlock;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/** Applies world-camera effects to the block model path used by Fabric Indigo. */
@SuppressWarnings({"UnstableApiUsage"})
@Mixin(AbstractTerrainRenderContext.class)
public abstract class IndigoTerrainRenderContextMixin {
	@Shadow @Final protected BlockRenderInfo blockInfo;

	@ModifyExpressionValue(
		method = "bufferQuad(Lnet/fabricmc/fabric/impl/client/indigo/renderer/mesh/MutableQuadViewImpl;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/fabricmc/fabric/impl/client/indigo/renderer/render/AbstractTerrainRenderContext;getVertexConsumer(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayer;)Lcom/mojang/blaze3d/vertex/VertexConsumer;"
		)
	)
	private VertexConsumer airicraft$worldCameraEffects(VertexConsumer consumer) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
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
