package ai.moeru.airicraft;

import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;

public final class FirstPersonScreenshotService {
	private static final String FORMAT = "png";
	private static final int TARGET_WIDTH = 854;
	private static final int TARGET_HEIGHT = 480;

	private final Object lock = new Object();

	private CaptureJob activeJob;

	public CompletableFuture<CapturedScreenshot> requestCapture(Minecraft minecraft) {
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}

		synchronized (lock) {
			if (activeJob != null) {
				throw new BridgeUnavailableException("capture_in_progress", "A screenshot capture is already in progress");
			}

			activeJob = new CaptureJob(new CompletableFuture<>());
			return activeJob.future();
		}
	}

	public void onWorldRendered(Minecraft minecraft) {
		CaptureJob job;
		synchronized (lock) {
			if (activeJob == null || activeJob.phase() != CapturePhase.PENDING) {
				return;
			}
			activeJob = activeJob.withPhase(CapturePhase.CAPTURING);
			job = activeJob;
		}

		try {
			Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> completeCapture(job, image));
		}
		catch (Throwable throwable) {
			fail(job, new BridgeUnavailableException("capture_failed", "Failed to capture screenshot"));
			Airicraft.LOGGER.warn("Failed to capture screenshot", throwable);
		}
	}

	public void failActiveCapture(String code, String message) {
		CaptureJob job;
		synchronized (lock) {
			job = activeJob;
			activeJob = null;
		}

		if (job != null) {
			job.future().completeExceptionally(new BridgeUnavailableException(code, message));
		}
	}

	private void completeCapture(CaptureJob job, NativeImage image) {
		try (image) {
			synchronized (lock) {
				if (activeJob != job || job.phase() != CapturePhase.CAPTURING) {
					return;
				}
				activeJob = job.withPhase(CapturePhase.ENCODING);
			}

			CapturedScreenshot screenshot = encode(image);
			synchronized (lock) {
				if (activeJob != null && activeJob.future() == job.future()) {
					activeJob = null;
				}
			}
			job.future().complete(screenshot);
		}
		catch (BridgeUnavailableException exception) {
			fail(job, exception);
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Failed to encode screenshot", exception);
			fail(job, new BridgeUnavailableException("capture_failed", "Failed to encode screenshot"));
		}
	}

	private void fail(CaptureJob job, RuntimeException exception) {
		synchronized (lock) {
			if (activeJob != null && activeJob.future() == job.future()) {
				activeJob = null;
			}
		}
		job.future().completeExceptionally(exception);
	}

	private static CapturedScreenshot encode(NativeImage image) {
		BufferedImage sourceImage = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_ARGB);
		sourceImage.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getPixels(), 0, image.getWidth());

		BufferedImage scaledImage = LetterboxImageScaler.scaleToCanvas(sourceImage, TARGET_WIDTH, TARGET_HEIGHT);
		byte[] pngBytes = writePng(scaledImage);
		return new CapturedScreenshot(
			FORMAT,
			TARGET_WIDTH,
			TARGET_HEIGHT,
			image.getWidth(),
			image.getHeight(),
			Instant.now().toEpochMilli(),
			pngBytes
		);
	}

	private static byte[] writePng(BufferedImage image) {
		try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
			if (!ImageIO.write(image, FORMAT, outputStream)) {
				throw new IOException("No PNG writer is available");
			}
			return outputStream.toByteArray();
		}
		catch (IOException exception) {
			throw new BridgeUnavailableException("capture_failed", "Failed to encode screenshot");
		}
	}

	private enum CapturePhase {
		PENDING,
		CAPTURING,
		ENCODING
	}

	private record CaptureJob(CapturePhase phase, CompletableFuture<CapturedScreenshot> future) {
		private CaptureJob(CompletableFuture<CapturedScreenshot> future) {
			this(CapturePhase.PENDING, future);
		}

		private CaptureJob withPhase(CapturePhase nextPhase) {
			return new CaptureJob(nextPhase, future);
		}
	}

	public record CapturedScreenshot(
		String format,
		int width,
		int height,
		int sourceWidth,
		int sourceHeight,
		long capturedAtMs,
		byte[] imageBytes
	) {
		public CapturedScreenshot {
			imageBytes = imageBytes.clone();
		}

		@Override
		public byte[] imageBytes() {
			return imageBytes.clone();
		}
	}
}
