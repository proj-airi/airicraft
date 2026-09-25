package ai.moeru.airicraft.settings;

import ai.moeru.airicraft.dashboard.DiagnosticEvidence;
import ai.moeru.airicraft.dashboard.DiagnosticReport;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.List;

/** Paged inspection of the same immutable evidence that the report screen saves. */
final class DiagnosticEvidenceScreen extends Screen {
	private final Screen parent;
	private final List<DiagnosticEvidence.Page> pages;
	private final Identifier textureId = Identifier.of("airicraft", "report-preview-" + java.util.UUID.randomUUID());
	private List<OrderedText> lines = List.of();
	private int pageIndex;
	private int scroll;
	private boolean imageLoaded;
	private int imageWidth;
	private int imageHeight;

	DiagnosticEvidenceScreen(Screen parent, DiagnosticReport report) {
		super(Text.literal("Attached evidence"));
		this.parent = parent;
		this.pages = DiagnosticEvidence.pages(report.attachments());
	}

	@Override protected void init() {
		int w = Math.min(720, width - 24), left = (width - w) / 2;
		var previous = addDrawableChild(ButtonWidget.builder(Text.literal("Previous"), ignored -> move(-1))
			.dimensions(left, height - 28, w / 3 - 4, 20).build());
		previous.active = pageIndex > 0;
		addDrawableChild(ButtonWidget.builder(Text.literal("Back to report"), ignored -> close())
			.dimensions(left + w / 3, height - 28, w / 3 - 4, 20).build());
		var next = addDrawableChild(ButtonWidget.builder(Text.literal("Next"), ignored -> move(1))
			.dimensions(left + w * 2 / 3, height - 28, w / 3, 20).build());
		next.active = pageIndex < pages.size() - 1;
		var page = pages.get(pageIndex);
		String text = page.text();
		releaseImage();
		if (page.imageDataUrl() != null) {
			try {
				byte[] bytes = Base64.getDecoder().decode(page.imageDataUrl().substring(page.imageDataUrl().indexOf(',') + 1));
				NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
				imageWidth = image.getWidth(); imageHeight = image.getHeight();
				client.getTextureManager().registerTexture(textureId, new NativeImageBackedTexture(() -> "Report screenshot", image));
				imageLoaded = true;
			} catch (java.io.IOException | IllegalArgumentException failure) {
				text += "\nImage could not be decoded. Encoded data remains in report.jsonl.";
			}
		}
		lines = textRenderer.wrapLines(Text.literal(text), w - 8);
	}

	private void move(int change) {
		pageIndex = Math.clamp(pageIndex + change, 0, pages.size() - 1);
		scroll = 0;
		clearAndInit();
	}

	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
		scroll = Math.max(0, scroll - (int) (vertical * 24));
		return true;
	}

	@Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN) { scroll += 120; return true; }
		if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) { scroll = Math.max(0, scroll - 120); return true; }
		return super.keyPressed(keyCode, scanCode, modifiers);
	}

	@Override public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int w = Math.min(720, width - 24), left = (width - w) / 2;
		context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFFFF);
		context.drawTextWithShadow(textRenderer, textRenderer.trimToWidth((pageIndex + 1) + " / " + pages.size() + "  " + pages.get(pageIndex).title(), w), left, 30, 0xFFFFFFFF);
		int viewport = Math.max(1, height - 90);
		scroll = Math.min(scroll, Math.max(0, lines.size() * 12 - viewport));
		context.enableScissor(left, 48, left + w, height - 38);
		for (int i = scroll / 12; i < lines.size() && i * 12 - scroll < viewport; i++) {
			context.drawTextWithShadow(textRenderer, lines.get(i), left, 48 + i * 12 - scroll, 0xFFE0E0E0);
		}
		if (imageLoaded) {
			float scale = Math.min((float) w / imageWidth, (float) Math.max(1, viewport - 30) / imageHeight);
			int dw = Math.max(1, (int) (imageWidth * scale)), dh = Math.max(1, (int) (imageHeight * scale));
			context.drawTexture(RenderPipelines.GUI_TEXTURED, textureId, left + (w - dw) / 2, 78, 0, 0,
				dw, dh, imageWidth, imageHeight, imageWidth, imageHeight);
		}
		context.disableScissor();
	}

	private void releaseImage() {
		if (imageLoaded) { client.getTextureManager().destroyTexture(textureId); imageLoaded = false; }
	}

	@Override public void removed() { releaseImage(); }
	@Override public void close() { client.setScreen(parent); }
	@Override public boolean shouldPause() { return false; }
}
