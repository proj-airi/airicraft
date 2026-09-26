package ai.moeru.airicraft;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
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
	public VertexConsumer vertex(float x, float y, float z) {
		delegate.vertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer color(int red, int green, int blue, int alpha) {
		delegate.color(red, green, blue, scaleAlpha(alpha));
		return this;
	}

	@Override
	public VertexConsumer color(int argb) {
		delegate.color((argb & 0x00FFFFFF) | (scaleAlpha((argb >>> 24) & 0xFF) << 24));
		return this;
	}

	@Override
	public VertexConsumer color(float red, float green, float blue, float alpha) {
		delegate.color(red, green, blue, alpha * alphaScale);
		return this;
	}

	@Override
	public VertexConsumer texture(float u, float v) {
		delegate.texture(u, v);
		return this;
	}

	@Override
	public VertexConsumer overlay(int u, int v) {
		delegate.overlay(u, v);
		return this;
	}

	@Override
	public VertexConsumer light(int u, int v) {
		delegate.light(u, v);
		return this;
	}

	@Override
	public VertexConsumer normal(float x, float y, float z) {
		delegate.normal(x, y, z);
		return this;
	}

	@Override
	public void vertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
		int alpha = scaleAlpha((color >>> 24) & 0xFF);
		delegate.vertex(x, y, z, (color & 0x00FFFFFF) | (alpha << 24), u, v, overlay, light, nx, ny, nz);
	}

	@Override
	public void quad(MatrixStack.Entry entry, BakedQuad quad, float red, float green, float blue, float alpha, int light, int overlay) {
		delegate.quad(entry, quad, red, green, blue, alpha * alphaScale, light, overlay);
	}

	@Override
	public void quad(MatrixStack.Entry entry, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha, int[] light, int overlay, boolean shade) {
		delegate.quad(entry, quad, brightness, red, green, blue, alpha * alphaScale, light, overlay, shade);
	}

	@Override
	public VertexConsumer vertex(Vector3f pos) {
		delegate.vertex(pos);
		return this;
	}

	@Override
	public VertexConsumer vertex(MatrixStack.Entry entry, Vector3f pos) {
		delegate.vertex(entry, pos);
		return this;
	}

	@Override
	public VertexConsumer vertex(MatrixStack.Entry entry, float x, float y, float z) {
		delegate.vertex(entry, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer vertex(Matrix4f matrix, float x, float y, float z) {
		delegate.vertex(matrix, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer vertex(Matrix3x2f matrix, float x, float y, float z) {
		delegate.vertex(matrix, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer normal(MatrixStack.Entry entry, float x, float y, float z) {
		delegate.normal(entry, x, y, z);
		return this;
	}

	@Override
	public VertexConsumer normal(MatrixStack.Entry entry, Vector3f normal) {
		delegate.normal(entry, normal);
		return this;
	}
}
