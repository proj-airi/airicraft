package ai.moeru.airicraft.agent.control;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

public final class CameraController {
	private int defaultLerpTicks;
	private CameraMotion activeMotion;
	private RotationSpring spring;
	private boolean directRequest;
	/** Someone other than the control plane aimed this tick; navigation's look must not override it. */
	private boolean aimedThisTick;
	private LocalPlayer controlledPlayer;
	private CompletableFuture<Void> alignment;

	public CameraController() { this(0); }

	public CameraController(int defaultLerpTicks) {
		updateDefaultLerpTicks(defaultLerpTicks);
	}

	public void updateDefaultLerpTicks(int ticks) { defaultLerpTicks = Math.max(0, ticks); }
	public int defaultLerpTicks() { return defaultLerpTicks; }

	/** Submit an aim target. Exact interactions use isLookingAt; mining uses blockHit. */
	public Optional<Rotation> lookAt(Minecraft minecraft, Vec3 target) {
		noteDirectAim();
		return startLookAt(minecraft, target, defaultLerpTicks, "action");
	}

	void noteDirectAim() {
		aimedThisTick = true;
	}

	/** Whether a reflex, executor or tool aimed since the last camera tick. Reset by {@link #tick}. */
	public boolean aimedThisTick() {
		return aimedThisTick;
	}

	public Optional<Rotation> faceDirection(LocalPlayer player, String direction) {
		if (player == null) return Optional.empty();
		noteDirectAim();
		Optional<Rotation> rotation = directionRotation(direction);
		rotation.ifPresent(value -> request(player, value, defaultLerpTicks, "vision", true));
		return rotation;
	}

	public Optional<Rotation> startLookAt(Minecraft minecraft, Vec3 target, String reason) {
		return startLookAt(minecraft, target, defaultLerpTicks, reason);
	}

	public Optional<Rotation> startLookAt(Minecraft minecraft, Vec3 target, int durationTicks, String reason) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null) return Optional.empty();
		Optional<Rotation> rotation = lookRotation(player.getEyePosition(), target);
		rotation.ifPresent(value -> request(player, value, durationTicks, reason, true));
		return rotation;
	}

	private void request(LocalPlayer player, Rotation target, int ticks, String reason, boolean direct) {
		if (controlledPlayer != player) {
			clear();
			controlledPlayer = player;
		}
		if (alignment != null) return;
		directRequest |= direct;
		startMotion(new Rotation(player.getYRot(), player.getXRot()), target, ticks, reason);
	}

	public boolean isLookingAt(Minecraft minecraft, Vec3 target) {
		return isLookingAt(minecraft, target, 0.01F);
	}

	public boolean isLookingAt(Minecraft minecraft, Vec3 target, float tolerance) {
		if (minecraft == null || minecraft.player == null) return false;
		return lookRotation(minecraft.player.getEyePosition(), target)
			.map(rotation -> aligned(new Rotation(minecraft.player.getYRot(), minecraft.player.getXRot()), rotation, tolerance))
			.orElse(false);
	}

	public boolean isAimingAt(Minecraft minecraft, net.minecraft.world.phys.AABB bounds) {
		if (minecraft == null || minecraft.player == null) return false;
		Vec3 eye = minecraft.player.getEyePosition();
		return bounds.contains(eye) || bounds.clip(eye,
			eye.add(minecraft.player.getViewVector(1.0F).scale(6.0D))).isPresent();
	}

	/** Aim inside the selection shape, including thin blocks such as leaf litter and crops. */
	public Optional<Rotation> lookAtBlock(Minecraft minecraft, BlockPos pos) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null) return Optional.empty();
		Vec3 eye = minecraft.player.getEyePosition();
		return blockAim(pos, minecraft.level.getBlockState(pos).getShape(minecraft.level, pos), eye,
			point -> blockHit(minecraft.level.clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, minecraft.player)), pos).isPresent())
			.flatMap(aim -> lookAt(minecraft, aim));
	}

	static Optional<Vec3> blockAim(BlockPos pos, VoxelShape shape, Vec3 eye) {
		return blockAim(pos, shape, eye, point -> true);
	}

	/** Geometry-only aim query shared by execution and hypothetical access planning. */
	public static Optional<Vec3> blockAim(BlockPos pos, VoxelShape shape, Vec3 eye, java.util.function.Predicate<Vec3> visible) {
		return shape.toAabbs().stream()
			.flatMap(box -> {
				var center = box.getCenter();
				// The centre can be hidden by a neighbouring block even when an outer
				// face is exposed. Stay slightly inside each shape so raycasts hit it.
				double dx = Math.max(0, box.getXsize() / 2 - .001);
				double dy = Math.max(0, box.getYsize() / 2 - .001);
				double dz = Math.max(0, box.getZsize() / 2 - .001);
				return java.util.stream.Stream.of(center, center.add(dx,0,0), center.add(-dx,0,0),
					center.add(0,dy,0), center.add(0,-dy,0), center.add(0,0,dz), center.add(0,0,-dz));
			})
			.map(point -> point.add(pos.getX(), pos.getY(), pos.getZ()))
			.filter(visible)
			.min(java.util.Comparator.comparingDouble(eye::distanceToSqr));
	}

	/** Fresh player-direction raycast: render-frame crosshairTarget can lag camera ticks. */
	public Optional<BlockHitResult> blockHit(Minecraft minecraft, BlockPos target) {
		if (minecraft == null || minecraft.player == null || minecraft.level == null) return Optional.empty();
		return blockHit(raycast(minecraft.player, minecraft.player.getViewVector(1.0F)), target);
	}

	static Optional<BlockHitResult> blockHit(HitResult hit, BlockPos target) {
		return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK && block.getBlockPos().equals(target)
			? Optional.of(block) : Optional.empty();
	}

	private static BlockHitResult raycast(LocalPlayer player, Vec3 direction) {
		Vec3 eye = player.getEyePosition();
		return player.level().clip(new ClipContext(eye, eye.add(direction.scale(player.blockInteractionRange())),
			ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
	}

	public boolean capturePending() { return alignment != null; }

	public CompletableFuture<Void> whenAligned() {
		if (activeMotion == null) return CompletableFuture.completedFuture(null);
		if (alignment == null) alignment = new CompletableFuture<>();
		return alignment;
	}

	public void tick(Minecraft minecraft) {
		aimedThisTick = false;
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null || !player.isAlive() || (controlledPlayer != null && controlledPlayer != player)) {
			clear();
			return;
		}
		tickMotion(new Rotation(player.getYRot(), player.getXRot())).ifPresent(rotation -> {
			// Leave previous angles intact for Minecraft's render interpolation.
			player.setYRot(rotation.yaw());
			player.setXRot(rotation.pitch());
			player.setYHeadRot(rotation.yaw());
		});
		directRequest = false;
		if (activeMotion == null && alignment != null) {
			var completed = alignment;
			alignment = null;
			completed.complete(null);
		}
	}

	public void clear() {
		activeMotion = null;
		spring = null;
		controlledPlayer = null;
		directRequest = false;
		aimedThisTick = false;
		if (alignment != null) {
			var cancelled = alignment;
			alignment = null;
			cancelled.completeExceptionally(new CancellationException("Camera control released"));
		}
	}

	public Optional<String> activeReason() {
		return activeMotion == null ? Optional.empty() : Optional.of(activeMotion.reason());
	}

	void startMotion(Rotation start, Rotation target, int durationTicks, String reason) {
		if (spring == null) spring = new RotationSpring(Objects.requireNonNull(start));
		else spring.synchronize(Objects.requireNonNull(start));
		activeMotion = new CameraMotion(Objects.requireNonNull(target),
			durationTicks > 0 ? 120.0D / durationTicks : 18.0D, normalizeReason(reason));
	}

	Optional<Rotation> tickMotion(Rotation actual) {
		if (spring != null) spring.synchronize(actual);
		return tickMotion();
	}

	Optional<Rotation> tickMotion() {
		if (activeMotion == null) return Optional.empty();
		Rotation rotation = spring.advance(activeMotion.target(), 0.05D, activeMotion.frequency());
		if (aligned(rotation, activeMotion.target(), 0.01F) && spring.atRest()) {
			rotation = new Rotation(rotation.yaw() + Mth.wrapDegrees(activeMotion.target().yaw() - rotation.yaw()),
				activeMotion.target().pitch());
			activeMotion = null;
			spring = null;
		}
		return Optional.of(rotation);
	}

	private static boolean aligned(Rotation current, Rotation target, float tolerance) {
		return Math.abs(Mth.wrapDegrees(target.yaw() - current.yaw())) < tolerance
			&& Math.abs(target.pitch() - current.pitch()) < tolerance;
	}

	public static Optional<Rotation> lookRotation(Vec3 eyePos, Vec3 target) {
		if (eyePos == null || target == null) {
			return Optional.empty();
		}
		Vec3 delta = target.subtract(eyePos);
		double horizontalDistance = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
		if (horizontalDistance < 1.0E-7D && Math.abs(delta.y) < 1.0E-7D) {
			return Optional.empty();
		}
		float yaw = (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0F;
		float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, horizontalDistance));
		return Optional.of(new Rotation(yaw, Mth.clamp(pitch, -90.0F, 90.0F)));
	}

	public static Optional<Rotation> directionRotation(String direction) {
		return switch (direction == null ? "" : direction) {
			case "north" -> Optional.of(new Rotation(180.0F, 0.0F));
			case "northeast" -> Optional.of(new Rotation(-135.0F, 0.0F));
			case "east" -> Optional.of(new Rotation(-90.0F, 0.0F));
			case "southeast" -> Optional.of(new Rotation(-45.0F, 0.0F));
			case "south" -> Optional.of(new Rotation(0.0F, 0.0F));
			case "southwest" -> Optional.of(new Rotation(45.0F, 0.0F));
			case "west" -> Optional.of(new Rotation(90.0F, 0.0F));
			case "northwest" -> Optional.of(new Rotation(135.0F, 0.0F));
			default -> Optional.empty();
		};
	}

	private static String normalizeReason(String reason) {
		return reason == null || reason.isBlank() ? "unspecified" : reason.trim();
	}

	public record Rotation(float yaw, float pitch) {
	}

	private record CameraMotion(Rotation target, double frequency, String reason) { }

}
