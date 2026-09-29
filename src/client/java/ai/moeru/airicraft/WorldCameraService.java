package ai.moeru.airicraft;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Detached "world camera" prototype: positions the render camera at an
 * arbitrary pose (tactical 2.5D by default), optionally auto-frames a focus
 * region by raycast-scoring candidate poses, and captures the framebuffer
 * through {@link FirstPersonScreenshotService}.
 *
 * All methods must run on the Minecraft client thread.
 */
public final class WorldCameraService {
	private static final int MAX_SAMPLE_POINTS = 384;
	private static final int MAX_RADIUS = 32;
	private static final double MIN_DISTANCE = 6.0;
	private static final double MAX_DISTANCE = 48.0;

	private final FirstPersonScreenshotService screenshotService;

	private CameraPose pose;
	private PendingCapture pending;
	private boolean hudHiddenSaved;

	public WorldCameraService(FirstPersonScreenshotService screenshotService) {
		this.screenshotService = screenshotService;
	}

	public record CameraPose(double x, double y, double z, float yaw, float pitch) {
	}

	public record FrameResult(
		CameraPose pose,
		int candidates,
		int samples,
		int visibleSamples,
		double score,
		int fadedBlocks,
		boolean focusClear
	) {
		public FrameResult(CameraPose pose, int candidates, int samples, int visibleSamples, double score) {
			this(pose, candidates, samples, visibleSamples, score, 0, true);
		}
		public FrameResult(CameraPose pose, int candidates, int samples, int visibleSamples, double score, int fadedBlocks) {
			this(pose, candidates, samples, visibleSamples, score, fadedBlocks, true);
		}
	}
	public record TacticalResult(
		FrameResult framing,
		FirstPersonScreenshotService.CapturedScreenshot screenshot,
		String viewId
	) {
		public TacticalResult(FrameResult framing, FirstPersonScreenshotService.CapturedScreenshot screenshot) {
			this(framing, screenshot, null);
		}
	}

	/**
	 * A captured view: camera pose + intrinsics retained so later tools can
	 * back-project image selections into world coordinates.
	 */
	public record ViewRecord(
		String id,
		CameraPose pose,
		double fovY,
		double aspect,
		int width,
		int height,
		long capturedAtMs
	) {
	}

	private final java.util.Map<String, ViewRecord> views = new java.util.LinkedHashMap<>();
	private int viewSeq;

	/** Debug counter: query-tint applications during meshing. */
	public static final java.util.concurrent.atomic.AtomicInteger TINT_HITS = new java.util.concurrent.atomic.AtomicInteger();

	private volatile boolean shoulderActive;
	private volatile boolean playerTranslucent;

	public boolean shoulderActive() {
		return shoulderActive;
	}

	/**
	 * Whether the player currently blocks a significant part of the view:
	 * either a large screen fraction, or the full projection of a nearby
	 * block. Recomputed each frame while shoulder mode is active.
	 */
	public boolean playerTranslucent() {
		return playerTranslucent;
	}

	/**
	 * Shoulder-surf pose: behind and to the right of the player's eye,
	 * tracking the player's look direction each frame.
	 */
	public CameraPose shoulderPose(Minecraft minecraft) {
		if (minecraft == null || minecraft.player == null) {
			return null;
		}
		Vec3 eye = minecraft.player.getEyePosition();
		float yaw = minecraft.player.getYRot();
		float pitch = minecraft.player.getXRot();
		double yawRad = Math.toRadians(yaw);
		double pitchRad = Math.toRadians(pitch);
		Vec3 forward = new Vec3(
			-Math.sin(yawRad) * Math.cos(pitchRad),
			-Math.sin(pitchRad),
			Math.cos(yawRad) * Math.cos(pitchRad));
		Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
		Vec3 pos = eye.subtract(forward.scale(4.0)).add(right.scale(1.1)).add(0.0, 0.3, 0.0);
		// Steep look angles are hard to read from behind the shoulder;
		// clamp to a shallow band around horizontal.
		float clampedPitch = Mth.clamp(pitch, -30.0f, 30.0f);
		return new CameraPose(pos.x, pos.y, pos.z, yaw, clampedPitch);
	}

	private volatile CameraPose lastRenderedPose;

	public synchronized CameraPose pose(Minecraft minecraft) {
		CameraPose result;
		if (shoulderActive) {
			result = shoulderPose(minecraft);
			playerTranslucent = result != null && playerBlocksView(minecraft, result);
		}
		else {
			playerTranslucent = false;
			result = pose;
		}
		lastRenderedPose = result;
		return result;
	}
	/**
	 * Draw a compass rose on the captured frame, aligned to world north (-Z).
	 * Camera yaw 0 faces south, so the needle angle is 180 - yaw clockwise
	 * from screen-up.
	 */
	private static FirstPersonScreenshotService.CapturedScreenshot withCompass(
		FirstPersonScreenshotService.CapturedScreenshot screenshot,
		CameraPose cam
	) {
		if (screenshot == null || cam == null) {
			return screenshot;
		}
		try {
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
				new java.io.ByteArrayInputStream(screenshot.imageBytes()));
			java.awt.Graphics2D g = image.createGraphics();
			try {
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				int cx = image.getWidth() - 44;
				int cy = 44;
				int r = 26;
				// Rose background.
				g.setColor(new java.awt.Color(0, 0, 0, 110));
				g.fillOval(cx - r, cy - r, r * 2, r * 2);
				g.setColor(new java.awt.Color(255, 255, 255, 160));
				g.setStroke(new java.awt.BasicStroke(1.5f));
				g.drawOval(cx - r, cy - r, r * 2, r * 2);
				// Cardinal ticks.
				double needleRad = Math.toRadians(180.0 - cam.yaw());
				g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 11));
				String[] labels = {"N", "E", "S", "W"};
				for (int i = 0; i < 4; i++) {
					double angle = needleRad + i * Math.PI / 2;
					int tx = cx + (int) Math.round(Math.sin(angle) * (r - 8));
					int ty = cy - (int) Math.round(Math.cos(angle) * (r - 8));
					g.setColor(i == 0 ? new java.awt.Color(255, 80, 80) : new java.awt.Color(230, 230, 230));
					var fm = g.getFontMetrics();
					g.drawString(labels[i], tx - fm.stringWidth(labels[i]) / 2, ty + fm.getAscent() / 2 - 1);
				}
				// Needle: red half toward north, white half toward south.
				int nx = cx + (int) Math.round(Math.sin(needleRad) * (r - 14));
				int ny = cy - (int) Math.round(Math.cos(needleRad) * (r - 14));
				int sx = cx - (int) Math.round(Math.sin(needleRad) * (r - 14));
				int sy = cy + (int) Math.round(Math.cos(needleRad) * (r - 14));
				g.setStroke(new java.awt.BasicStroke(2.5f));
				g.setColor(new java.awt.Color(255, 80, 80));
				g.drawLine(cx, cy, nx, ny);
				g.setColor(new java.awt.Color(230, 230, 230));
				g.drawLine(cx, cy, sx, sy);
			}
			finally {
				g.dispose();
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(image, "png", out);
			return new FirstPersonScreenshotService.CapturedScreenshot(
				screenshot.format(), screenshot.width(), screenshot.height(),
				screenshot.sourceWidth(), screenshot.sourceHeight(),
				screenshot.capturedAtMs(), out.toByteArray());
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Failed to draw compass overlay", exception);
			return screenshot;
		}
	}


	/**
	 * Project a world point into NDC [-1,1] for the given camera pose.
	 * Returns null when the point is behind the camera.
	 */
	private static double[] projectNdc(Vec3 point, CameraPose cam, double tanHalfFovY, double aspect) {
		double yawRad = Math.toRadians(cam.yaw());
		double pitchRad = Math.toRadians(cam.pitch());
		Vec3 forward = new Vec3(
			-Math.sin(yawRad) * Math.cos(pitchRad),
			-Math.sin(pitchRad),
			Math.cos(yawRad) * Math.cos(pitchRad));
		Vec3 right = new Vec3(-forward.z, 0.0, forward.x).normalize();
		Vec3 up = right.cross(forward);
		Vec3 d = point.subtract(new Vec3(cam.x(), cam.y(), cam.z()));
		double cz = d.dot(forward);
		if (cz < 0.05) {
			return null;
		}
		double cx = d.dot(right) / cz / (tanHalfFovY * aspect);
		double cy = d.dot(up) / cz / tanHalfFovY;
		return new double[] {cx, cy};
	}

	/**
	 * Translucent iff the player's screen rect covers a large fraction of the
	 * frame, or fully contains the projection of some nearby solid block.
	 */
	private boolean playerBlocksView(Minecraft minecraft, CameraPose cam) {
		if (minecraft.player == null || minecraft.level == null) {
			return false;
		}
		double fovY = minecraft.options.fov().get();
		double aspect = minecraft.getWindow().getWidth()
			/ (double) Math.max(1, minecraft.getWindow().getHeight());
		double tanHalfFovY = Math.tan(Math.toRadians(fovY) / 2.0);

		AABB box = minecraft.player.getBoundingBox();
		double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
		double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
		for (double x : new double[] {box.minX, box.maxX}) {
			for (double y : new double[] {box.minY, box.maxY}) {
				for (double z : new double[] {box.minZ, box.maxZ}) {
					double[] ndc = projectNdc(new Vec3(x, y, z), cam, tanHalfFovY, aspect);
					if (ndc == null) {
						// Corner behind the camera: the player fills the view.
						return true;
					}
					minX = Math.min(minX, ndc[0]);
					maxX = Math.max(maxX, ndc[0]);
					minY = Math.min(minY, ndc[1]);
					maxY = Math.max(maxY, ndc[1]);
				}
			}
		}
		double coverW = Math.min(1.0, maxX) - Math.max(-1.0, minX);
		double coverH = Math.min(1.0, maxY) - Math.max(-1.0, minY);
		if (coverW * coverH / 4.0 > 0.12) {
			return true;
		}

		// Whole-block test: does the player rect fully contain some nearby
		// solid block's projection?
		BlockPos center = minecraft.player.blockPosition();
		for (BlockPos pos : BlockPos.betweenClosed(center.offset(-3, -2, -3), center.offset(3, 2, 3))) {
			var state = minecraft.level.getBlockState(pos);
			if (state.isAir() || state.getCollisionShape(minecraft.level, pos).isEmpty()) {
				continue;
			}
			boolean allInside = true;
			for (int i = 0; i < 8 && allInside; i++) {
				Vec3 corner = new Vec3(
					pos.getX() + ((i & 1) == 0 ? 0 : 1),
					pos.getY() + ((i & 2) == 0 ? 0 : 1),
					pos.getZ() + ((i & 4) == 0 ? 0 : 1));
				double[] ndc = projectNdc(corner, cam, tanHalfFovY, aspect);
				if (ndc == null || ndc[0] < minX || ndc[0] > maxX || ndc[1] < minY || ndc[1] > maxY) {
					allInside = false;
				}
			}
			if (allInside) {
				return true;
			}
		}
		return false;
	}

	public synchronized void setPose(CameraPose nextPose) {
		pose = nextPose;
	}

	/**
	 * Blocks the renderer should treat as air. Read from chunk-mesh worker
	 * threads via {@link #isFaded}; must be an immutable snapshot.
	 * {@code fadeLeaves} renders leaf blocks at ~40% opacity through the
	 * terrain renderer mixins instead of hiding them.
	 */
	public record FadeFilter(java.util.Set<BlockPos> blocks, Integer hideAboveY, boolean fadeLeaves) {
		public FadeFilter(java.util.Set<BlockPos> blocks, Integer hideAboveY) {
			this(blocks, hideAboveY, false);
		}
	}

	private volatile FadeFilter fadeFilter;
	private BlockPos fadeBoundsMin;
	private BlockPos fadeBoundsMax;

	public FadeFilter fadeFilter() {
		return fadeFilter;
	}

	public boolean fadeLeavesActive() {
		FadeFilter filter = fadeFilter;
		return filter != null && filter.fadeLeaves();
	}

	public boolean isFaded(BlockPos pos, BlockState state) {
		FadeFilter filter = fadeFilter;
		if (filter == null) {
			return false;
		}
		if (filter.hideAboveY() != null && pos.getY() >= filter.hideAboveY()) {
			return true;
		}
		return filter.blocks() != null && filter.blocks().contains(pos);
	}


	private volatile AABB tintBox;
	private BlockPos tintBoundsMin;
	private BlockPos tintBoundsMax;

	/**
	 * Query-region tint: blocks inside this box get their vertex colors
	 * blended toward blue during meshing. Null when inactive.
	 */
	public AABB tintBox() {
		return tintBox;
	}

	public synchronized void setTintBox(Minecraft minecraft, AABB box, BlockPos boundsMin, BlockPos boundsMax) {
		BlockPos previousMin = tintBoundsMin;
		BlockPos previousMax = tintBoundsMax;
		tintBox = box;
		tintBoundsMin = boundsMin;
		tintBoundsMax = boundsMax;
		scheduleRegionRemesh(minecraft, previousMin, previousMax);
		scheduleRegionRemesh(minecraft, boundsMin, boundsMax);
	}

	public boolean tintContains(BlockPos pos) {
		AABB box = tintBox;
		return box != null && box.contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
	}

	/**
	 * True when the focus has no sky above it — caves, interiors, dense
	 * enclosed spaces. Used to pick shallower camera pitches.
	 */
	public static boolean isUnderground(Minecraft minecraft, Vec3 focus) {
		if (minecraft.level == null) {
			return false;
		}
		return !minecraft.level.canSeeSky(BlockPos.containing(focus));
	}

	/**
	 * Apply a fade filter and schedule remeshing of the affected region.
	 * Client thread only.
	 */
	public synchronized void setFade(Minecraft minecraft, FadeFilter filter, BlockPos boundsMin, BlockPos boundsMax) {
		fadeFilter = filter;
		fadeBoundsMin = boundsMin;
		fadeBoundsMax = boundsMax;
		scheduleFadeRemesh(minecraft);
	}

	private void scheduleFadeRemesh(Minecraft minecraft) {
		scheduleRegionRemesh(minecraft, fadeBoundsMin, fadeBoundsMax);
	}

	private static void scheduleRegionRemesh(Minecraft minecraft, BlockPos boundsMin, BlockPos boundsMax) {
		if (minecraft == null || minecraft.levelRenderer == null || boundsMin == null || boundsMax == null) {
			return;
		}
		// Expand by one: faces of neighbouring blocks become visible too.
		minecraft.levelRenderer.setBlocksDirty(
			boundsMin.getX() - 1, boundsMin.getY() - 1, boundsMin.getZ() - 1,
			boundsMax.getX() + 1, boundsMax.getY() + 1, boundsMax.getZ() + 1);
	}

	public synchronized void clear() {
		shoulderActive = false;
		pose = null;
		if (pending != null) {
			pending.future().completeExceptionally(
				new BridgeUnavailableException("capture_failed", "World camera was cleared during capture"));
			pending = null;
		}
		if (fadeFilter != null || tintBox != null) {
			fadeFilter = null;
			tintBox = null;
			Minecraft minecraft = Minecraft.getInstance();
			if (minecraft != null) {
				scheduleFadeRemesh(minecraft);
				scheduleRegionRemesh(minecraft, tintBoundsMin, tintBoundsMax);
			}
			fadeBoundsMin = null;
			fadeBoundsMax = null;
			tintBoundsMin = null;
			tintBoundsMax = null;
		}
		restoreHud();
	}
	/**
	 * Every collidable block intersected by eye→sample rays, excluding the
	 * sample's own block. These are the blocks to fade for this shot.
	 */
	public java.util.Set<BlockPos> computeOccluders(Minecraft minecraft, CameraPose camera, List<SamplePoint> samples) {
		java.util.Set<BlockPos> occluders = new java.util.HashSet<>();
		java.util.Set<BlockPos> sampleBlocks = new java.util.HashSet<>();
		for (SamplePoint sample : samples) {
			sampleBlocks.add(BlockPos.containing(sample.pos()));
		}
		Vec3 eye = new Vec3(camera.x(), camera.y(), camera.z());
		for (SamplePoint sample : samples) {
			Vec3 target = sample.pos();
			Vec3 delta = target.subtract(eye);
			double distance = delta.length();
			if (distance < 1.0E-6) {
				continue;
			}
			Vec3 step = delta.normalize().scale(0.4);
			// Stop short of the target: the sample's own block is not an occluder.
			int steps = (int) Math.max(0, (distance - 0.5) / 0.4);
			Vec3 cursor = eye;
			for (int i = 0; i < steps; i++) {
				cursor = cursor.add(step);
				BlockPos pos = BlockPos.containing(cursor);
				if (sampleBlocks.contains(pos) || occluders.contains(pos)) {
					continue;
				}
				var state = minecraft.level.getBlockState(pos);
				if (!state.isAir() && !state.getCollisionShape(minecraft.level, pos).isEmpty()) {
					occluders.add(pos);
				}
			}
		}
		return occluders;
	}

	/**
	 * Re-collect samples for a focus region (exposed for occluder computation).
	 */
	public List<SamplePoint> samplesFor(Minecraft minecraft, Vec3 focus, double radius, String purpose) {
		return collectSamples(minecraft, focus, Mth.clamp(radius, 4.0, MAX_RADIUS), purpose);
	}


	/**
	 * Called once per rendered world frame from the Camera mixin.
	 */
	public void onWorldFrame(Minecraft minecraft) {
		PendingCapture current;
		synchronized (this) {
			current = pending;
			if (current == null) {
				return;
			}
			if (current.framesRemaining() > 0) {
				pending = current.tick();
				return;
			}
			pending = null;
		}
		screenshotService.requestCapture(minecraft).whenComplete((screenshot, throwable) -> {
			restoreHud();
			if (throwable == null) {
				current.future().complete(screenshot);
			}
			else {
				current.future().completeExceptionally(throwable);
			}
		});
	}

	/**
	 * Set a pose, wait {@code settleFrames} rendered frames, capture, and
	 * (unless {@code keepPose}) restore the normal camera.
	 */
	public CompletableFuture<TacticalResult> capture(
		Minecraft minecraft,
		CameraPose nextPose,
		FrameResult framing,
		int settleFrames,
		boolean keepPose
	) {
		synchronized (this) {
			if (pending != null) {
				throw new BridgeUnavailableException("capture_in_progress", "A world camera capture is already in progress");
			}
			pose = nextPose;
			if (!minecraft.options.hideGui) {
				hudHiddenSaved = true;
				minecraft.options.hideGui = true;
			}
			CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> future = new CompletableFuture<>();
			pending = new PendingCapture(Math.max(0, settleFrames), future);
			return future.thenApply(screenshot -> {
				if (!keepPose) {
					clear();
				}
				return new TacticalResult(framing, withCompass(screenshot, lastRenderedPose),
					registerView(minecraft, lastRenderedPose, screenshot));
			});
		}
	}
	/**
	 * Shoulder-surf capture: camera tracks behind-right of the player's eye
	 * each frame; the player renders semi-transparent via the entity mixin.
	 */
	public CompletableFuture<TacticalResult> captureShoulder(
		Minecraft minecraft,
		int settleFrames,
		boolean keepPose
	) {
		synchronized (this) {
			if (pending != null) {
				throw new BridgeUnavailableException("capture_in_progress", "A world camera capture is already in progress");
			}
			shoulderActive = true;
			if (!minecraft.options.hideGui) {
				hudHiddenSaved = true;
				minecraft.options.hideGui = true;
			}
			CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> future = new CompletableFuture<>();
			pending = new PendingCapture(Math.max(0, settleFrames), future);
			return future.thenApply(screenshot -> {
				if (!keepPose) {
					clear();
				}
				return new TacticalResult(null, withCompass(screenshot, lastRenderedPose),
					registerView(minecraft, lastRenderedPose, screenshot));
			});
		}
	}


	private void restoreHud() {
		Minecraft minecraft = Minecraft.getInstance();
		if (hudHiddenSaved && minecraft != null) {
			minecraft.options.hideGui = false;
		}
		hudHiddenSaved = false;
	}

	/**
	 * Auto-frame a focus region: generate candidate poses on rings around the
	 * focus at several pitch/distance bands, score each by raycasting to
	 * task-relevant sample points, and return the best.
	 */
	public FrameResult autoFrame(Minecraft minecraft, Vec3 focus, double radius, String purpose) {
		if (minecraft.level == null || minecraft.player == null) {
			throw new BridgeUnavailableException("world_not_loaded", "No world is currently loaded");
		}
		double r = Mth.clamp(radius, 4.0, MAX_RADIUS);
		List<SamplePoint> samples = collectSamples(minecraft, focus, r, purpose);
		if (samples.isEmpty()) {
			throw new BridgeUnavailableException("no_samples", "No relevant geometry found around the focus");
		}

		boolean underground = isUnderground(minecraft, focus);
		// Enclosed spaces read better from a shallow angle; open terrain
		// benefits from the steeper tactical view.
		double[] pitches = underground ? new double[] {15.0, 25.0, 35.0} : new double[] {45.0, 55.0, 65.0};
		double[] distanceScales = underground ? new double[] {0.4, 0.6, 0.8} : new double[] {0.6, 0.8, 1.0};
		int yawSteps = 16;

		CameraPose bestPose = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		double bestVisibleWeight = 0.0;
		boolean bestFocusClear = false;
		double totalWeight = 0.0;
		for (SamplePoint sample : samples) {
			totalWeight += sample.weight();
		}
		List<Vec3> focusSphere = focusSpherePoints(focus);
		int candidates = 0;
		for (double pitchDeg : pitches) {
			double pitch = Math.toRadians(pitchDeg);
			for (double scale : distanceScales) {
				double distance = Mth.clamp(r * scale, MIN_DISTANCE, MAX_DISTANCE);
				double dy = distance * Math.sin(pitch);
				double horizontal = distance * Math.cos(pitch);
				for (int i = 0; i < yawSteps; i++) {
					double yawDeg = i * (360.0 / yawSteps);
					double yaw = Math.toRadians(yawDeg);
					// Camera yaw convention: 0 = looking south (+Z), 90 = west (-X).
					// Place the camera opposite the look direction so it faces the focus.
					double cx = focus.x + Math.sin(yaw) * horizontal;
					double cz = focus.z - Math.cos(yaw) * horizontal;
					double cy = focus.y + dy;
					CameraPose candidate = new CameraPose(cx, cy, cz, (float) yawDeg, (float) pitchDeg);
					candidates++;
					PoseScore scored = scorePose(minecraft, candidate, samples, distance, r);
					boolean focusClear = focusSphereClear(minecraft, candidate, focusSphere);
					// A pose that keeps the focus sphere fully visible always
					// beats one that doesn't, regardless of aggregate score.
					if ((focusClear && !bestFocusClear)
						|| (focusClear == bestFocusClear && scored.score() > bestScore)) {
						bestScore = scored.score();
						bestPose = candidate;
						bestVisibleWeight = scored.visibleWeight();
						bestFocusClear = focusClear;
					}
				}
			}
		}

		// Interior candidates: when the focus is enclosed (cave, room, tunnel),
		// every exterior ring pose is buried in solid geometry. Try air cells
		// inside the volume looking back at the focus instead.
		if (bestScore <= 0.0) {
			for (CameraPose candidate : interiorCandidates(minecraft, focus, r)) {
				candidates++;
				PoseScore scored = scorePose(minecraft, candidate, samples, r, r);
				boolean focusClear = focusSphereClear(minecraft, candidate, focusSphere);
				if ((focusClear && !bestFocusClear)
					|| (focusClear == bestFocusClear && scored.score() > bestScore)) {
					bestScore = scored.score();
					bestPose = candidate;
					bestVisibleWeight = scored.visibleWeight();
					bestFocusClear = focusClear;
				}
			}
		}

		int visibleSamples = totalWeight > 0
			? (int) Math.round(samples.size() * bestVisibleWeight / totalWeight)
			: 0;
		return new FrameResult(bestPose, candidates, samples.size(), visibleSamples, bestScore, 0, bestFocusClear);
	}

	/**
	 * A small sphere of points around the focus: center + 6 axis offsets.
	 * All must be COLLIDER-visible for the focus to count as clear — leaves
	 * and other VISUAL-transparent blocks still occlude the subject.
	 */
	private static List<Vec3> focusSpherePoints(Vec3 focus) {
		double r = 0.9;
		return List.of(
			focus,
			focus.add(r, 0, 0), focus.add(-r, 0, 0),
			focus.add(0, r, 0), focus.add(0, -r, 0),
			focus.add(0, 0, r), focus.add(0, 0, -r));
	}

	private boolean focusSphereClear(Minecraft minecraft, CameraPose candidate, List<Vec3> sphere) {
		Vec3 eye = new Vec3(candidate.x(), candidate.y(), candidate.z());
		for (Vec3 point : sphere) {
			Vec3 delta = point.subtract(eye);
			double distance = delta.length();
			if (distance < 1.0E-6) {
				continue;
			}
			Vec3 end = eye.add(delta.normalize().scale(Math.max(0.0, distance - 0.3)));
			BlockHitResult hit = minecraft.level.clip(new ClipContext(
				eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
				net.minecraft.world.phys.shapes.CollisionContext.empty()));
			if (hit.getType() != HitResult.Type.MISS) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Focus sphere as sample points for occluder computation.
	 */
	public List<SamplePoint> focusSphereSamples(Vec3 focus) {
		List<SamplePoint> samples = new ArrayList<>();
		for (Vec3 point : focusSpherePoints(focus)) {
			samples.add(new SamplePoint(point, 1.0));
		}
		return samples;
	}
	/**
	 * Register a captured view for later back-projection. Returns its id.
	 */
	public synchronized String registerView(Minecraft minecraft, CameraPose pose, FirstPersonScreenshotService.CapturedScreenshot screenshot) {
		String id = "view_" + (++viewSeq);
		double fovY = minecraft.options.fov().get();
		double aspect = minecraft.getWindow().getWidth()
			/ (double) Math.max(1, minecraft.getWindow().getHeight());
		views.put(id, new ViewRecord(
			id, pose, fovY, aspect,
			screenshot.width(), screenshot.height(), screenshot.capturedAtMs()));
		// Bounded history: keep the last 32 views.
		while (views.size() > 32) {
			views.remove(views.keySet().iterator().next());
		}
		return id;
	}

	public synchronized ViewRecord view(String id) {
		return views.get(id);
	}

	/**
	 * Camera basis vectors for a pose: forward, right, up.
	 */
	private static Vec3[] cameraBasis(CameraPose cam) {
		double yawRad = Math.toRadians(cam.yaw());
		double pitchRad = Math.toRadians(cam.pitch());
		Vec3 forward = new Vec3(
			-Math.sin(yawRad) * Math.cos(pitchRad),
			-Math.sin(pitchRad),
			Math.cos(yawRad) * Math.cos(pitchRad));
		Vec3 right = new Vec3(-forward.z, 0.0, forward.x).normalize();
		Vec3 up = right.cross(forward);
		return new Vec3[] {forward, right, up};
	}

	/**
	 * Back-project a normalized image box into world blocks by raycasting a
	 * grid of pixels through the stored camera. {@code expand} controls how
	 * the visible hits become a query volume: "visible" (exact hits),
	 * "volume" (bounding box + 1), "connected" (flood-fill non-natural
	 * blocks from the hits).
	 */
	public java.util.Map<String, Object> inspectRegion(
		Minecraft minecraft,
		ViewRecord view,
		double x1, double y1, double x2, double y2,
		String expand
	) {
		Vec3 eye = new Vec3(view.pose().x(), view.pose().y(), view.pose().z());
		Vec3[] basis = cameraBasis(view.pose());
		double tanHalfFovY = Math.tan(Math.toRadians(view.fovY()) / 2.0);

		java.util.Set<BlockPos> hits = new java.util.HashSet<>();
		int grid = 24;
		for (int gy = 0; gy <= grid; gy++) {
			for (int gx = 0; gx <= grid; gx++) {
				double nx = x1 + (x2 - x1) * gx / grid;
				double ny = y1 + (y2 - y1) * gy / grid;
				Vec3 dir = basis[0]
					.add(basis[1].scale(nx * tanHalfFovY * view.aspect()))
					.add(basis[2].scale(ny * tanHalfFovY))
					.normalize();
				BlockHitResult hit = minecraft.level.clip(new ClipContext(
					eye, eye.add(dir.scale(96.0)),
					ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
					net.minecraft.world.phys.shapes.CollisionContext.empty()));
				if (hit.getType() == HitResult.Type.BLOCK) {
					hits.add(hit.getBlockPos());
				}
			}
		}

		java.util.Set<BlockPos> result = hits;
		if ("volume".equals(expand) && !hits.isEmpty()) {
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (BlockPos p : hits) {
				minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
				minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
				minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
			}
			result = new java.util.HashSet<>();
			for (BlockPos p : BlockPos.betweenClosed(
				new BlockPos(minX - 1, minY - 1, minZ - 1),
				new BlockPos(maxX + 1, maxY + 1, maxZ + 1))) {
				result.add(p.immutable());
			}
		}
		else if ("connected".equals(expand) && !hits.isEmpty()) {
			result = new java.util.HashSet<>();
			java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>(hits);
			while (!queue.isEmpty() && result.size() < 4096) {
				BlockPos p = queue.poll();
				if (!result.add(p)) {
					continue;
				}
				for (var dir : net.minecraft.core.Direction.values()) {
					BlockPos next = p.relative(dir);
					var state = minecraft.level.getBlockState(next);
					if (!state.isAir() && !result.contains(next)) {
						queue.add(next);
					}
				}
			}
		}

		java.util.Map<String, Integer> histogram = new java.util.TreeMap<>();
		for (BlockPos p : result) {
			String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(minecraft.level.getBlockState(p).getBlock()).toString();
			histogram.merge(name, 1, Integer::sum);
		}
		java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
		out.put("viewId", view.id());
		out.put("expand", expand);
		out.put("hitBlocks", hits.size());
		out.put("blocks", result.size());
		out.put("histogram", histogram);
		if (!result.isEmpty()) {
			int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
			int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
			for (BlockPos p : result) {
				minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
				minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
				minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
			}
			out.put("bounds", java.util.Map.of(
				"min", java.util.List.of(minX, minY, minZ),
				"max", java.util.List.of(maxX, maxY, maxZ)));
		}
		return out;
	}

	/**
	 * Draw a query box overlay on a captured frame: project the 3D box's 12
	 * edges into screen space and draw them as a wireframe.
	 */
	public static FirstPersonScreenshotService.CapturedScreenshot withQueryOverlay(
		FirstPersonScreenshotService.CapturedScreenshot screenshot,
		CameraPose cam,
		double fovY,
		double aspect,
		BlockPos min,
		BlockPos max
	) {
		if (screenshot == null || cam == null || min == null || max == null) {
			return screenshot;
		}
		try {
			java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(
				new java.io.ByteArrayInputStream(screenshot.imageBytes()));
			java.awt.Graphics2D g = image.createGraphics();
			try {
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				double tanHalfFovY = Math.tan(Math.toRadians(fovY) / 2.0);
				double w = image.getWidth(), h = image.getHeight();
				Vec3[] basis = cameraBasis(cam);
				Vec3 eye = new Vec3(cam.x(), cam.y(), cam.z());
				// 8 corners, 12 edges, 6 faces.
				double[][] c = new double[8][];
				for (int i = 0; i < 8; i++) {
					c[i] = new double[] {
						(i & 1) == 0 ? min.getX() : max.getX() + 1,
						(i & 2) == 0 ? min.getY() : max.getY() + 1,
						(i & 4) == 0 ? min.getZ() : max.getZ() + 1};
				}

				int[][] edges = {
					{0,1},{1,3},{3,2},{2,0},
					{4,5},{5,7},{7,6},{6,4},
					{0,4},{1,5},{2,6},{3,7}};
				g.setColor(new java.awt.Color(80, 160, 255, 220));
				g.setStroke(new java.awt.BasicStroke(2.0f));
				for (int[] e : edges) {
					double[] a = projectToScreen(c[e[0]], eye, basis, tanHalfFovY, aspect, w, h);
					double[] b = projectToScreen(c[e[1]], eye, basis, tanHalfFovY, aspect, w, h);
					if (a != null && b != null) {
						g.drawLine((int) a[0], (int) a[1], (int) b[0], (int) b[1]);
					}
				}
			}
			finally {
				g.dispose();
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(image, "png", out);
			return new FirstPersonScreenshotService.CapturedScreenshot(
				screenshot.format(), screenshot.width(), screenshot.height(),
				screenshot.sourceWidth(), screenshot.sourceHeight(),
				screenshot.capturedAtMs(), out.toByteArray());
		}
		catch (Exception exception) {
			Airicraft.LOGGER.warn("Failed to draw query overlay", exception);
			return screenshot;
		}
	}

	private static double[] projectToScreen(
		double[] point, Vec3 eye, Vec3[] basis, double tanHalfFovY, double aspect, double w, double h
	) {
		Vec3 d = new Vec3(point[0], point[1], point[2]).subtract(eye);
		double cz = d.dot(basis[0]);
		if (cz < 0.05) {
			return null;
		}
		double nx = d.dot(basis[1]) / cz / (tanHalfFovY * aspect);
		double ny = d.dot(basis[2]) / cz / tanHalfFovY;
		return new double[] {(nx + 1.0) / 2.0 * w, (1.0 - ny) / 2.0 * h};
	}


	/**
	 * Air cells inside the focus volume, aimed back at the focus. Covers
	 * tunnels and rooms where no exterior vantage exists.
	 */
	private List<CameraPose> interiorCandidates(Minecraft minecraft, Vec3 focus, double radius) {
		List<CameraPose> candidates = new ArrayList<>();
		int r = (int) Math.ceil(Math.min(radius, 12));
		BlockPos origin = BlockPos.containing(focus);
		for (int dx = -r; dx <= r; dx += 2) {
			for (int dz = -r; dz <= r; dz += 2) {
				for (int dy = -r; dy <= r; dy += 2) {
					BlockPos pos = origin.offset(dx, dy, dz);
					if (pos.distSqr(origin) > r * r) {
						continue;
					}
					if (!minecraft.level.getBlockState(pos).getCollisionShape(minecraft.level, pos).isEmpty()) {
						continue;
					}
					Vec3 eye = Vec3.atCenterOf(pos);
					Vec3 delta = focus.subtract(eye);
					double horizontal = Math.hypot(delta.x, delta.z);
					double distance = delta.length();
					// Too close: the frame is just the player's head. Too far in
					// a tunnel is fine — the view still reads.
					if (distance < 2.5) {
						continue;
					}
					float yaw = (float) (Math.toDegrees(Math.atan2(-delta.x, delta.z)));
					float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontal));
					candidates.add(new CameraPose(eye.x, eye.y, eye.z, yaw, Mth.clamp(pitch, -90.0F, 90.0F)));
				}
			}
		}
		return candidates;
	}

	private record SamplePoint(Vec3 pos, double weight) {
	}

	private record PoseScore(double score, double visibleWeight) {
	}

	private List<SamplePoint> collectSamples(Minecraft minecraft, Vec3 focus, double radius, String purpose) {
		List<SamplePoint> samples = new ArrayList<>();
		boolean nav = "navigation".equals(purpose) || "surroundings".equals(purpose);
		boolean threats = "threats".equals(purpose);
		boolean inspect = "inspect".equals(purpose) || "structure".equals(purpose);

		int r = (int) Math.ceil(radius);
		BlockPos origin = BlockPos.containing(focus);
		int step = radius > 16 ? 2 : 1;
		int vertical = Math.min(r, 8);
		for (int dx = -r; dx <= r; dx += step) {
			for (int dz = -r; dz <= r; dz += step) {
				for (int dy = -vertical; dy <= vertical; dy++) {
					if (samples.size() >= MAX_SAMPLE_POINTS) {
						break;
					}
					BlockPos pos = origin.offset(dx, dy, dz);
					var state = minecraft.level.getBlockState(pos);
					if (state.isAir()) {
						continue;
					}
					if (!minecraft.level.getBlockState(pos.above()).isAir()) {
						continue;
					}
					// Walkable floor heuristic: collidable top with two air above.
					boolean walkable = !state.getCollisionShape(minecraft.level, pos).isEmpty()
						&& minecraft.level.getBlockState(pos.above(2)).isAir();
					double weight = 1.0;
					if (nav && walkable) {
						weight = 2.0;
					}
					else if (inspect) {
						weight = 1.5;
					}
					samples.add(new SamplePoint(Vec3.upFromBottomCenterOf(pos, 1.0), weight));
				}
			}
		}

		// The focus itself is the most important sample: when framing "self",
		// the player must be visible or the shot is useless.
		samples.add(new SamplePoint(focus, 8.0));

		AABB entityBox = AABB.ofSize(focus, radius * 2, 16, radius * 2);
		for (Entity entity : minecraft.level.entitiesForRendering()) {
			if (entity == minecraft.player || !entityBox.contains(entity.position())) {
				continue;
			}
			samples.add(new SamplePoint(entity.getEyePosition(), threats ? 4.0 : 2.0));
		}
		return samples;
	}

	private PoseScore scorePose(
		Minecraft minecraft,
		CameraPose candidate,
		List<SamplePoint> samples,
		double distance,
		double radius
	) {
		Vec3 eye = new Vec3(candidate.x(), candidate.y(), candidate.z());
		// Hard penalty: camera inside any collidable geometry.
		BlockPos eyeBlock = BlockPos.containing(eye);
		double penalty = minecraft.level.getBlockState(eyeBlock).getCollisionShape(minecraft.level, eyeBlock).isEmpty() ? 0.0 : 1.0;
		// Preference for closer shots: tactical views should read, not survey.
		penalty += 0.15 * (distance / radius - 0.6);
		double totalWeight = 0.0;
		double visibleWeight = 0.0;
		// Camera forward vector (MC convention: yaw 0 = +Z, pitch + = down).
		double yawRad = Math.toRadians(candidate.yaw());
		double pitchRad = Math.toRadians(candidate.pitch());
		Vec3 forward = new Vec3(
			-Math.sin(yawRad) * Math.cos(pitchRad),
			-Math.sin(pitchRad),
			Math.cos(yawRad) * Math.cos(pitchRad));
		// Samples outside the view cone do not count even if unoccluded.
		double minDot = Math.cos(Math.toRadians(45.0));
		for (SamplePoint sample : samples) {
			totalWeight += sample.weight();
			Vec3 target = sample.pos();
			double targetDistance = eye.distanceTo(target);
			Vec3 direction = target.subtract(eye).normalize();
			if (forward.dot(direction) < minDot) {
				continue;
			}
			// Raycast slightly short of the target so the target block itself
			// does not count as an occluder. VISUAL shape type means foliage
			// and other non-opaque blocks do not count as occluders either —
			// they are fadeable, not blocking.
			Vec3 end = eye.add(direction.scale(Math.max(0.0, targetDistance - 0.35)));
			BlockHitResult hit = minecraft.level.clip(new ClipContext(
				eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
				net.minecraft.world.phys.shapes.CollisionContext.empty()));
			if (hit.getType() == HitResult.Type.MISS) {
				visibleWeight += sample.weight();
			}
		}
		return new PoseScore((totalWeight > 0 ? visibleWeight / totalWeight : 0.0) - penalty, visibleWeight);
	}

	private record PendingCapture(int framesRemaining, CompletableFuture<FirstPersonScreenshotService.CapturedScreenshot> future) {
		PendingCapture tick() {
			return new PendingCapture(framesRemaining - 1, future);
		}
	}
}
