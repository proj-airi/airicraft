package ai.moeru.airicraft;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.block.model.BakedQuad;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Delegating {@link VertexConsumer} that blends vertex colors toward a tint.
 * Used to mark blocks inside a query region directly in the world mesh.
 */
public final class TintedVertexConsumer implements VertexConsumer {
	private final VertexConsumer delegate;
	private final float tintR, tintG, tintB, amount;

	public TintedVertexConsumer(VertexConsumer delegate, int tintRgb, float amount) {
		this.delegate = delegate;
		this.tintR = ((tintRgb >> 16) & 0xFF) / 255.0f;
		this.tintG = ((tintRgb >> 8) & 0xFF) / 255.0f;
		this.tintB = (tintRgb & 0xFF) / 255.0f;
		this.amount = amount;
	}

	private int tint(int argb) {
		int a = (argb >>> 24) & 0xFF;
		int r = (argb >> 16) & 0xFF;
		int g = (argb >> 8) & 0xFF;
		int b = argb & 0xFF;
		r = Math.round(r + (tintR * 255 - r) * amount);
		g = Math.round(g + (tintG * 255 - g) * amount);
		b = Math.round(b + (tintB * 255 - b) * amount);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	@Override
	public VertexConsumer addVertex(float x, float y, float z) {
		delegate.addVertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer setColor(int red, int green, int blue, int alpha) {
		delegate.setColor(
			Math.round(red + (tintR * 255 - red) * amount),
			Math.round(green + (tintG * 255 - green) * amount),
			Math.round(blue + (tintB * 255 - blue) * amount),
			alpha);
		return this;
	}

	@Override
	public VertexConsumer setColor(int argb) {
		delegate.setColor(tint(argb));
		return this;
	}

	@Override
	public VertexConsumer setColor(float red, float green, float blue, float alpha) {
		delegate.setColor(
			red + (tintR - red) * amount,
			green + (tintG - green) * amount,
			blue + (tintB - blue) * amount,
			alpha);
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
		delegate.addVertex(x, y, z, tint(color), u, v, overlay, light, nx, ny, nz);
	}

	@Override
	public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float red, float green, float blue, float alpha, int light, int overlay) {
		delegate.putBulkData(pose, quad,
			red + (tintR - red) * amount,
			green + (tintG - green) * amount,
			blue + (tintB - blue) * amount,
			alpha, light, overlay);
	}

	@Override
	public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha, int[] light, int overlay, boolean shade) {
		delegate.putBulkData(pose, quad, brightness,
			red + (tintR - red) * amount,
			green + (tintG - green) * amount,
			blue + (tintB - blue) * amount,
			alpha, light, overlay, shade);
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
