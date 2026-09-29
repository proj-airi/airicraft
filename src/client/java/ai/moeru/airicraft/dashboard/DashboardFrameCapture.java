package ai.moeru.airicraft.dashboard;

import ai.moeru.airicraft.LetterboxImageScaler;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Passive RGB sampling of this client. One readback/encoding job at a time; no render-thread encoding. */
final class DashboardFrameCapture {
	private final DashboardObservationStore store;
	private final ExecutorService encoder = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "airicraft-live-frame-encoder");
		thread.setDaemon(true);
		return thread;
	});
	private final AtomicBoolean pending = new AtomicBoolean();
	private final UnchangedFrameFilter unchanged = new UnchangedFrameFilter();
	private long lastCaptureTick = Long.MIN_VALUE;
	private String lastSession = "";
	private long lastFrameSequence;
	private volatile long skippedUnchanged;
	private volatile long failedCaptures;

	DashboardFrameCapture(DashboardObservationStore store) {
		this.store = store;
	}

	void onRenderedFrame(Minecraft minecraft, long agentTick, long serverTick, boolean paused, DebugDashboardConfig config) {
		if (!config.visualCaptureEnabled() || minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) {
			return;
		}
		String session = store.sessionId();
		if (!session.equals(lastSession)) {
			lastCaptureTick = Long.MIN_VALUE;
			lastSession = session;
		}
		if (lastCaptureTick != Long.MIN_VALUE && (serverTick == lastCaptureTick
			|| (!paused && serverTick - lastCaptureTick < config.visualCaptureIntervalTicks()))) {
			return;
		}
		if (!pending.compareAndSet(false, true)) {
			return;
		}
		lastCaptureTick = serverTick;
		long capturedAtMs = System.currentTimeMillis();
		try {
			Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> {
				try {
					encoder.execute(() -> encode(image, session, agentTick, serverTick, capturedAtMs));
				}
				catch (RejectedExecutionException exception) {
					image.close();
					pending.set(false);
				}
			});
		}
		catch (RuntimeException exception) {
			failedCaptures++;
			pending.set(false);
		}
	}

	private void encode(NativeImage image, String session, long agentTick, long serverTick, long capturedAtMs) {
		try (image) {
			if (!session.equals(store.sessionId())) {
				return;
			}
			BufferedImage source = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
			source.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getPixels(), 0, image.getWidth());
			BufferedImage scaled = LetterboxImageScaler.scaleToCanvas(source, 640, 360);
			int[] pixels = scaled.getRGB(0, 0, scaled.getWidth(), scaled.getHeight(), null, 0, scaled.getWidth());
			if (!unchanged.changed(session, pixels) && store.extendFrame(session, lastFrameSequence, serverTick)) {
				skippedUnchanged++;
				return;
			}
			BufferedImage rgb = new BufferedImage(scaled.getWidth(), scaled.getHeight(), BufferedImage.TYPE_INT_RGB);
			rgb.setRGB(0, 0, scaled.getWidth(), scaled.getHeight(), pixels, 0, scaled.getWidth());
			try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				if (!ImageIO.write(rgb, "jpeg", output)) {
					throw new IOException("No JPEG writer available");
				}
				DashboardObservation frame = store.appendFrame(session, agentTick, serverTick, capturedAtMs, Map.of(
					"format", "jpeg", "width", rgb.getWidth(), "height", rgb.getHeight(),
					"sourceWidth", image.getWidth(), "sourceHeight", image.getHeight(),
					"source", "active_client_framebuffer", "imageBase64", Base64.getEncoder().encodeToString(output.toByteArray())
				));
				if (frame != null) {
					lastFrameSequence = frame.sequence();
				}
			}
		}
		catch (Exception exception) {
			failedCaptures++;
		}
		finally {
			pending.set(false);
		}
	}

	Map<String, Object> status() {
		return Map.of("pending", pending.get(), "skippedUnchanged", skippedUnchanged, "failedCaptures", failedCaptures);
	}

	void close() {
		encoder.shutdown();
	}

	static final class UnchangedFrameFilter {
		private String session = "";
		private int[] previous;

		boolean changed(String nextSession, int[] pixels) {
			boolean changed = !session.equals(nextSession) || !Arrays.equals(previous, pixels);
			session = nextSession;
			previous = pixels.clone();
			return changed;
		}
	}
}
