package ai.moeru.airicraft;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.model.BakedQuad;
import net.minecraft.client.util.math.MatrixStack;
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
	public VertexConsumer vertex(float x, float y, float z) {
		delegate.vertex(x, y, z);
		return this;
	}

	@Override
	public VertexConsumer color(int red, int green, int blue, int alpha) {
		delegate.color(
			Math.round(red + (tintR * 255 - red) * amount),
			Math.round(green + (tintG * 255 - green) * amount),
			Math.round(blue + (tintB * 255 - blue) * amount),
			alpha);
		return this;
	}

	@Override
	public VertexConsumer color(int argb) {
		delegate.color(tint(argb));
		return this;
	}

	@Override
	public VertexConsumer color(float red, float green, float blue, float alpha) {
		delegate.color(
			red + (tintR - red) * amount,
			green + (tintG - green) * amount,
			blue + (tintB - blue) * amount,
			alpha);
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
		delegate.vertex(x, y, z, tint(color), u, v, overlay, light, nx, ny, nz);
	}

	@Override
	public void quad(MatrixStack.Entry entry, BakedQuad quad, float red, float green, float blue, float alpha, int light, int overlay) {
		delegate.quad(entry, quad,
			red + (tintR - red) * amount,
			green + (tintG - green) * amount,
			blue + (tintB - blue) * amount,
			alpha, light, overlay);
	}

	@Override
	public void quad(MatrixStack.Entry entry, BakedQuad quad, float[] brightness, float red, float green, float blue, float alpha, int[] light, int overlay, boolean shade) {
		delegate.quad(entry, quad, brightness,
			red + (tintR - red) * amount,
			green + (tintG - green) * amount,
			blue + (tintB - blue) * amount,
			alpha, light, overlay, shade);
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
