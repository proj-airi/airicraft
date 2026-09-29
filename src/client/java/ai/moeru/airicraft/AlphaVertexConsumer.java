package ai.moeru.airicraft;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Delegating {@link VertexConsumer} that multiplies vertex alpha by a fixed
 * factor. Used to render leaf blocks semi-transparent during world-camera
 * captures.
 */
public final class AlphaVertexConsumer implements VertexConsumer {
	private final VertexConsumer delegate;
	private final float alphaScale;

	public AlphaVertexConsumer(VertexConsumer delegate, float alphaScale) {
		this.delegate = delegate;
		this.alphaScale = alphaScale;
	}

	private int scaleAlpha(int alpha) {
		return Math.round(alpha * alphaScale);
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		delegate.addVertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setColor(int red, int green, int blue, int alpha) {
		delegate.setColor(red, green, blue, scaleAlpha(alpha));
		return this;
	}

	@Override
	public VertexConsumer setColor(int argb) {
		delegate.setColor((argb & 0x00FFFFFF) | (scaleAlpha((argb >>> 24) & 0xFF) << 24));
		return this;
	}

	@Override
	public VertexConsumer setColor(float red, float green, float blue, float alpha) {
		delegate.setColor(red, green, blue, alpha * alphaScale);
		return this;
	}

	@Override
	public VertexConsumer setUv(float u, float v) {
		delegate.setUv(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv1(int u, int v) {
		delegate.setUv1(u, v);
		return this;
	}

	@Override
	public VertexConsumer setUv2(int u, int v) {
		delegate.setUv2(u, v);
		return this;
	}

	@Override
	public VertexConsumer setNormal(float x, float y, float z) {
		delegate.setNormal(x, y, z);
		return this;
	}

	@Override
	public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
		int alpha = scaleAlpha((color >>> 24) & 0xFF);
		delegate.addVertex(x, y, z, (color & 0x00FFFFFF) | (alpha << 24), u, v, overlay, light, nx, ny, nz);
	}

	@Override
	public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float red, float green, float blue, float alpha, int light, int overlay) {
		delegate.putBulkData(pose, quad, red, green, blue, alpha * alphaScale, light, overlay);
	}

	@Override
	public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha, int[] light, int overlay, boolean shade) {
		delegate.putBulkData(pose, quad, brightness, red, green, blue, alpha * alphaScale, light, overlay, shade);
	}

	@Override
	public VertexConsumer addVertex(Vector3f pos) {
		delegate.addVertex(pos);
		return this;
	}

	@Override
	public VertexConsumer addVertex(PoseStack.Pose pose, Vector3f pos) {
		delegate.addVertex(pose, pos);
		return this;
	}

	@Override
	public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
		delegate.addVertex(pose, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer addVertex(Matrix4f matrix, float x, float y, float z) {
		delegate.addVertex(matrix, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer addVertexWith2DPose(Matrix3x2f matrix, float x, float y, float z) {
		delegate.addVertexWith2DPose(matrix, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) {
		delegate.setNormal(pose, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setNormal(PoseStack.Pose pose, Vector3f normal) {
		delegate.setNormal(pose, normal);
		return this;
	}
}
