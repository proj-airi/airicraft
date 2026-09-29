package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.AlphaVertexConsumer;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.LeavesBlock;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * World-camera leaf translucency: wraps the chunk-mesh vertex consumer for
 * leaf blocks while {@code fadeLeaves} is active so they render at ~40%
 * opacity. Pairs with {@link RenderLayersMixin}, which moves leaves onto
 * the translucent render layer.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionBuilderMixin {
	private static final float LEAF_ALPHA = 0.4f;

	@WrapOperation(
		method = "compile",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLjava/util/List;)V"
		)
	)
	private void airicraft$translucentLeaves(
		BlockRenderDispatcher dispatcher,
		BlockState state,
		BlockPos pos,
		BlockAndTintGetter level,
		PoseStack poseStack,
		VertexConsumer consumer,
		boolean cull,
		List<?> overlayVertices,
		Operation<Void> original
	) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		VertexConsumer wrapped = consumer;
		if (service != null && state.getBlock() instanceof LeavesBlock && service.fadeLeavesActive()) {
			wrapped = new AlphaVertexConsumer(wrapped, LEAF_ALPHA);
		}
		if (service != null && service.tintContains(pos)) {
			WorldCameraService.TINT_HITS.incrementAndGet();
			wrapped = new ai.moeru.airicraft.TintedVertexConsumer(wrapped, 0x3080FF, 0.8f);
		}
		original.call(dispatcher, state, pos, level, poseStack, wrapped, cull, overlayVertices);
	}
}
