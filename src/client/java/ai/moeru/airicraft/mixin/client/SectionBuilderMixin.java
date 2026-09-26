package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.AlphaVertexConsumer;
import ai.moeru.airicraft.WorldCameraService;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.block.LeavesBlock;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.chunk.SectionBuilder;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * World-camera leaf translucency: wraps the chunk-mesh vertex consumer for
 * leaf blocks while {@code fadeLeaves} is active so they render at ~40%
 * opacity. Pairs with {@link RenderLayersMixin}, which moves leaves onto
 * the translucent render layer.
 */
@Mixin(SectionBuilder.class)
public abstract class SectionBuilderMixin {
	private static final float LEAF_ALPHA = 0.4f;

	@WrapOperation(
		method = "build",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/render/block/BlockRenderManager;renderBlock(Lnet/minecraft/block/BlockState;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/BlockRenderView;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumer;ZLjava/util/List;)V"
		)
	)
	private void airicraft$translucentLeaves(
		BlockRenderManager manager,
		BlockState state,
		BlockPos pos,
		BlockRenderView world,
		MatrixStack matrices,
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
		original.call(manager, state, pos, world, matrices, wrapped, cull, overlayVertices);
	}
}
