package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.BridgeUnavailableException;
import ai.moeru.airicraft.FirstPersonScreenshotService;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.observability.AgentObservability;
import ai.moeru.airicraft.agent.observability.NoopObservability;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public final class CurrentViewVisionService implements CurrentViewVisionTool {
	public static final String DEFAULT_DESCRIBE_PROMPT =
		"Describe the current Minecraft first-person view in one short paragraph. " +
			"Mention terrain, nearby landmarks, hazards, structures, and whether the scene feels indoors or outdoors.";

	private final FirstPersonScreenshotService screenshotService;
	private final VisionBackend visionBackend;
	private final Supplier<Minecraft> clientSupplier;
	private final ExecutorService executorService;
	private final AgentObservability observability;
	private final CameraController cameraController;

	public CurrentViewVisionService(
		FirstPersonScreenshotService screenshotService,
		VisionBackend visionBackend,
		Supplier<Minecraft> clientSupplier
	) {
		this(screenshotService, visionBackend, clientSupplier, NoopObservability.INSTANCE);
	}

	public CurrentViewVisionService(
		FirstPersonScreenshotService screenshotService,
		VisionBackend visionBackend,
		Supplier<Minecraft> clientSupplier,
		AgentObservability observability
	) {
		this(screenshotService, visionBackend, clientSupplier, observability, new CameraController());
	}

	public CurrentViewVisionService(
		FirstPersonScreenshotService screenshotService,
		VisionBackend visionBackend,
		Supplier<Minecraft> clientSupplier,
		AgentObservability observability,
		CameraController cameraController
	) {
		this.screenshotService = Objects.requireNonNull(screenshotService, "screenshotService");
		this.visionBackend = Objects.requireNonNull(visionBackend, "visionBackend");
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.observability = Objects.requireNonNull(observability, "observability");
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
		this.executorService = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "airicraft-vision");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public boolean isConfigured() {
		return visionBackend.isConfigured();
	}

	@Override
	public CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> requestCapture() {
		return requestCapture(ViewCaptureRequest.current()).thenApply(ViewCaptureResult::screenshot);
	}

	@Override
	public CompletableFuture<ViewCaptureResult> requestCapture(ViewCaptureRequest request) {
		Minecraft minecraft = clientSupplier.get();
		if (minecraft == null || minecraft.level == null || minecraft.player == null) {
			return CompletableFuture.failedFuture(
				new BridgeUnavailableException("world_not_loaded", "No world is currently loaded")
			);
		}

		try {
			Context captureContext = observability.startChildSpan(
				AgentObservability.TOOL_CAPTURE_SPAN_NAME,
				Context.current()
			);
			CompletableFuture<ViewCaptureResult> captureFuture = new CompletableFuture<>();
			Runnable captureTask = () -> {
				try {
					if (request != null && !request.isCurrent() && cameraController.capturePending()) {
						throw new BridgeUnavailableException("capture_busy", "Camera is aligning for another capture");
					}
					List<String> metadataLines = prepareCaptureTarget(minecraft, request);
					(request == null || request.isCurrent()
						? CompletableFuture.<Void>completedFuture(null) : cameraController.whenAligned())
						.thenCompose(ignored -> screenshotService.requestCapture(minecraft)).whenComplete((capture, throwable) -> {
						if (throwable == null) {
							captureFuture.complete(new ViewCaptureResult(capture, metadataLines));
						}
						else {
							captureFuture.completeExceptionally(throwable);
						}
					});
				}
				catch (RuntimeException exception) {
					captureFuture.completeExceptionally(exception);
				}
			};
			if (minecraft.isSameThread()) {
				captureTask.run();
			}
			else {
				minecraft.execute(captureTask);
			}
			return captureFuture.whenComplete((captureResult, throwable) -> {
				try {
					if (captureResult != null && captureResult.screenshot() != null) {
						observability.recordImageCapture(captureContext, captureResult.screenshot());
					}
					else if (throwable != null) {
						observability.recordFailure(
							captureContext,
							LlmFailureType.PROVIDER_ERROR.name(),
							"Vision capture failed",
							Throwable.class.isAssignableFrom(throwable.getClass()) ? throwable : new RuntimeException(throwable)
						);
					}
				}
				finally {
					observability.endSpan(captureContext);
				}
			});
		}
		catch (RuntimeException exception) {
			observability.recordFailure(Context.current(), LlmFailureType.PROVIDER_ERROR.name(), "Vision capture failed", exception);
			return CompletableFuture.failedFuture(exception);
		}
	}

	private List<String> prepareCaptureTarget(Minecraft minecraft, ViewCaptureRequest request) {
		if (request == null || request.isCurrent()) {
			return List.of();
		}
		if (minecraft.level == null || minecraft.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}

		return switch (request.targetType()) {
			case CURRENT -> List.of();
			case DIRECTION -> {
				faceDirection(minecraft.player, request.direction());
				yield List.of("lookTarget=direction direction=" + request.direction());
			}
			case BLOCK -> {
				BlockPos targetPos = new BlockPos(request.x(), request.y(), request.z());
				Vec3 targetCenter = Vec3.atCenterOf(targetPos);
				cameraController.lookAt(minecraft, targetCenter);
				List<String> metadata = new ArrayList<>();
				metadata.add("lookTarget=block x=" + targetPos.getX() + " y=" + targetPos.getY() + " z=" + targetPos.getZ());
				blockLineOfSightWarning(minecraft, minecraft.player, targetPos, targetCenter).ifPresent(metadata::add);
				yield metadata;
			}
			case PLAYER -> {
				AbstractClientPlayer target = findPlayer(minecraft, request.targetPlayer());
				cameraController.lookAt(minecraft, target.getBoundingBox().getCenter());
				yield List.of("lookTarget=player targetPlayer=" + target.getName().getString());
			}
		};
	}

	private void faceDirection(LocalPlayer player, String direction) {
		if (cameraController.faceDirection(player, direction).isEmpty()) {
			throw new BridgeUnavailableException("invalid_request", "Unsupported direction: " + direction);
		}
	}

	private static java.util.Optional<String> blockLineOfSightWarning(
		Minecraft minecraft,
		LocalPlayer player,
		BlockPos targetPos,
		Vec3 targetCenter
	) {
		if (!minecraft.level.hasChunkAt(targetPos)) {
			return java.util.Optional.of("LOOK_WARNING: target_block_los_unknown reason=target_chunk_not_loaded");
		}
		Vec3 start = player.getEyePosition();
		BlockHitResult hitResult = minecraft.level.clip(new ClipContext(
			start,
			targetCenter,
			ClipContext.Block.VISUAL,
			ClipContext.Fluid.NONE,
			player
		));
		if (hitResult.getType() == HitResult.Type.BLOCK && !hitResult.getBlockPos().equals(targetPos)) {
			BlockPos blockerPos = hitResult.getBlockPos();
			if (!minecraft.level.hasChunkAt(blockerPos)) {
				return java.util.Optional.of("LOOK_WARNING: target_block_los_unknown reason=blocking_chunk_not_loaded");
			}
			BlockState blockerState = minecraft.level.getBlockState(blockerPos);
			if (!blockerState.isAir() && !blockerState.propagatesSkylightDown()) {
				String blockId = BuiltInRegistries.BLOCK.getKey(blockerState.getBlock()).toString();
				return java.util.Optional.of(
					"LOOK_WARNING: target_block_los_blocked blockingBlockId=" + blockId
						+ " blockingPos=" + blockerPos.getX() + "," + blockerPos.getY() + "," + blockerPos.getZ()
				);
			}
		}
		return java.util.Optional.empty();
	}

	private static AbstractClientPlayer findPlayer(Minecraft minecraft, String targetPlayer) {
		if (targetPlayer == null || targetPlayer.isBlank()) {
			throw new BridgeUnavailableException("invalid_request", "targetPlayer must be non-empty");
		}
		for (AbstractClientPlayer player : minecraft.level.players()) {
			if (player == minecraft.player) {
				continue;
			}
			if (player.getName().getString().equals(targetPlayer)) {
				return player;
			}
		}
		throw new BridgeUnavailableException("player_not_found", "No loaded player named " + targetPlayer);
	}

	@Override
	public CompletableFuture<VisionDescription> requestDescription(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) {
		if (!isConfigured()) {
			return CompletableFuture.failedFuture(
				new LlmBackendException(LlmFailureType.PROVIDER_UNAVAILABLE, "Vision provider is not configured")
			);
		}

		try {
			Context parentContext = Context.current();
			return CompletableFuture.supplyAsync(() -> {
				Context describeContext = observability.startChildSpan(
					AgentObservability.VISION_DESCRIBE_SPAN_NAME,
					parentContext
				);
				try (Scope scope = describeContext.makeCurrent()) {
					return describeWithinCurrentSpan(screenshot, prompt);
				}
				catch (LlmBackendException exception) {
					throw new CompletionException(exception);
				}
				finally {
					observability.endSpan(describeContext);
				}
			}, executorService);
		}
		catch (RuntimeException exception) {
			return CompletableFuture.failedFuture(exception);
		}
	}

	@Override
	public CompletableFuture<VisionDescription> requestDescription(String prompt) {
		return CurrentViewVisionTool.super.requestDescription(prompt);
	}

	public VisionDescription describe(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		Context describeContext = observability.startChildSpan(
			AgentObservability.VISION_DESCRIBE_SPAN_NAME,
			Context.current()
		);
		try (Scope scope = describeContext.makeCurrent()) {
			return describeWithinCurrentSpan(screenshot, prompt);
		}
		finally {
			observability.endSpan(describeContext);
		}
	}

	private VisionDescription describeWithinCurrentSpan(FirstPersonScreenshotService.CapturedScreenshot screenshot, String prompt) throws LlmBackendException {
		Objects.requireNonNull(screenshot, "screenshot");
		return visionBackend.describe(new VisionRequest(
			normalizePrompt(prompt),
			"image/png",
			screenshot.imageBytes(),
			screenshot.capturedAtMs()
		));
	}

	public void shutdown() {
		executorService.shutdownNow();
	}

	private static String normalizePrompt(String prompt) {
		if (prompt == null || prompt.isBlank()) {
			return DEFAULT_DESCRIBE_PROMPT;
		}
		return prompt;
	}
}
