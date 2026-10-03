package ai.moeru.airicraft.agent.control;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CameraControllerTest {
	@Test void aDirectAimIsRememberedUntilTheNextCameraTick() {
		var camera = new CameraController();
		assertFalse(camera.aimedThisTick());
		camera.noteDirectAim();
		assertTrue(camera.aimedThisTick());
		camera.tick(null);
		assertFalse(camera.aimedThisTick());
	}

	@Test void externalCorrectionIsObservedEvenWithoutAnotherTargetRequest() {
		var camera = new CameraController();
		camera.startMotion(new CameraController.Rotation(0, 0), new CameraController.Rotation(90, 0), 0, "vision");
		camera.tickMotion();
		var waiting = camera.whenAligned();
		var next = camera.tickMotion(new CameraController.Rotation(100, 0)).orElseThrow();
		assertTrue(next.yaw() > 90 && next.yaw() < 100);
		assertFalse(waiting.isCompletedExceptionally());
	}

	@Test void unchangedViewPreservesMomentumAndWrappedYawDoesNotJumpFullCircle() {
		var camera = new CameraController();
		var reference = new CameraController();
		var start = new CameraController.Rotation(350, 0);
		var target = new CameraController.Rotation(80, 0);
		camera.startMotion(start, target, 0, "navigationFacade");
		reference.startMotion(start, target, 0, "navigationFacade");
		var actual = camera.tickMotion().orElseThrow();
		reference.tickMotion();
		var wrapped = new CameraController.Rotation(actual.yaw() - 360, actual.pitch());
		camera.startMotion(wrapped, target, 0, "navigationFacade");
		var next = camera.tickMotion(wrapped).orElseThrow();
		var expected = reference.tickMotion().orElseThrow();
		assertEquals(expected.yaw() - 360, next.yaw(), .001);
		assertTrue(Math.abs(next.yaw() - wrapped.yaw()) < 90);
	}

	@Test void retargetAfterExternalRotationStartsFromActualView() {
		var camera = new CameraController();
		camera.startMotion(new CameraController.Rotation(0, 0), new CameraController.Rotation(90, 30), 0, "navigationFacade");
		camera.tickMotion();
		var actual = new CameraController.Rotation(90, -20);
		var target = new CameraController.Rotation(100, -10);
		camera.startMotion(actual, target, 0, "navigationFacade");
		var next = camera.tickMotion().orElseThrow();
		assertTrue(next.yaw() > 90 && next.yaw() < 100, "Must not jump back to the old spring yaw");
		assertTrue(next.pitch() > -20 && next.pitch() < -10);
	}

	@Test void blockHitAllowsOffCenterAimButRejectsMissOrOccluder() {
		var pos = new net.minecraft.core.BlockPos(0, 0, 3);
		var eye = new Vec3(0.5, 0.5, 0);
		var offCenter = net.minecraft.world.phys.shapes.Shapes.block().clip(eye, new Vec3(0.85, 0.8, 4), pos);
		assertTrue(CameraController.blockHit(offCenter, pos).isPresent(), "Cursor already intersects target before center alignment");
		assertTrue(CameraController.blockHit(offCenter, pos.east()).isEmpty(), "Another block is not the intended target");
		var miss = net.minecraft.world.phys.BlockHitResult.miss(new Vec3(0.5, 0.5, 3.5), net.minecraft.core.Direction.NORTH, pos);
		assertTrue(CameraController.blockHit(miss, pos).isEmpty(), "A MISS can carry the same block coordinates");
	}

	@Test void thinBlockAimFallsInsideOutlineRatherThanBlockCenter() {
		var pos = new net.minecraft.core.BlockPos(-229, 70, -2);
		var shape = net.minecraft.world.phys.shapes.Shapes.box(0, 0, 0, 0.5, 0.0625, 1);
		var eye = new Vec3(-228, 71.62, -1.5);
		var aim = CameraController.blockAim(pos, shape, eye).orElseThrow();
		assertTrue(shape.clip(eye, aim, pos) != null);
		assertTrue(aim.y < 70.0625);
	}

	@Test void closeWallUsesVisibleFaceWhenBlockAboveOccludesTheCenter() {
		var pos = new net.minecraft.core.BlockPos(165, -59, 32);
		var shape = net.minecraft.world.phys.shapes.Shapes.block();
		var eye = new Vec3(165.3667829332416, -57.37999999523163, 33.58445256007759);
		java.util.function.Predicate<Vec3> visible = point -> {
			var target = shape.clip(eye, point, pos);
			var occluder = shape.clip(eye, point, pos.above());
			return target != null && (occluder == null || eye.distanceToSqr(target.getLocation()) < eye.distanceToSqr(occluder.getLocation()));
		};
		assertFalse(visible.test(Vec3.atCenterOf(pos)), "Recorded close stance cannot see the lower block centre");
		var aim = CameraController.blockAim(pos, shape, eye, visible).orElseThrow();
		assertTrue(visible.test(aim));
		assertTrue(aim.z > 32.99, "Aim just inside the exposed front face");
		assertTrue(CameraController.blockAim(pos, shape, eye, point -> false).isEmpty(), "No fabricated aim for a fully occluded block");
	}

	@Test void springCrossesBlockOutlineBeforeItSettles() {
		var controller = new CameraController();
		var eye = new Vec3(0.5, 0.5, 0);
		var pos = new net.minecraft.core.BlockPos(0, 0, 3);
		controller.startMotion(new CameraController.Rotation(-70, 0), new CameraController.Rotation(0, 0), 0, "navigationFacade");
		boolean hitBeforeSettled = false;
		for (int i = 0; i < 30 && controller.activeReason().isPresent(); i++) {
			var rotation = controller.tickMotion().orElseThrow();
			var end = eye.add(Vec3.directionFromRotation(rotation.pitch(), rotation.yaw()).scale(4.5));
			var hit = net.minecraft.world.phys.shapes.Shapes.block().clip(eye, end, pos);
			if (hit != null && CameraController.blockHit(hit, pos).isPresent()) {
				hitBeforeSettled = controller.activeReason().isPresent() && Math.abs(rotation.yaw()) > 0.5;
				break;
			}
		}
		assertTrue(hitBeforeSettled, "Mining should not wait for the old 0.5-degree gate or spring completion");
	}

	@Test
	void lookRotationComputesYawAndClampsPitch() {
		CameraController.Rotation east = CameraController.lookRotation(Vec3.ZERO, new Vec3(1.0D, 0.0D, 0.0D)).orElseThrow();
		assertEquals(-90.0F, east.yaw(), 0.001F);
		assertEquals(0.0F, east.pitch(), 0.001F);

		CameraController.Rotation vertical = CameraController.lookRotation(Vec3.ZERO, new Vec3(0.0D, 10.0D, 0.0D)).orElseThrow();
		assertEquals(-90.0F, vertical.pitch(), 0.001F);
		assertTrue(CameraController.lookRotation(Vec3.ZERO, Vec3.ZERO).isEmpty());
	}

	@Test
	void springAdvancesAcrossYawSeamAndReleasesAtTarget() {
		CameraController controller = new CameraController();
		controller.startMotion(new CameraController.Rotation(170, 0),
			new CameraController.Rotation(-170, 40), 0, "test");
		var first = controller.tickMotion().orElseThrow();
		assertTrue(first.yaw() > 170 && first.yaw() < 190);
		assertTrue(first.pitch() > 0 && first.pitch() < 40);
		assertTrue(controller.activeReason().isPresent());
		CameraController.Rotation last = first;
		for (int i = 0; i < 40 && controller.activeReason().isPresent(); i++) {
			last = controller.tickMotion().orElseThrow();
		}
		assertEquals(190, last.yaw(), 0.1);
		assertEquals(40, last.pitch(), 0.1);
		assertTrue(controller.activeReason().isEmpty());
	}

	@Test
	void repeatedTargetsDoNotRestartTheSpring() {
		CameraController controller = new CameraController();
		var current = new CameraController.Rotation(0, 0);
		var target = new CameraController.Rotation(90, 0);
		for (int i = 0; i < 20; i++) {
			controller.startMotion(current, target, 0, "tracking");
			current = controller.tickMotion().orElseThrow();
		}
		assertEquals(90, current.yaw(), 0.1);
	}

	@Test
	void clearingCancelsCaptureWaitAndReleasesOwnership() {
		CameraController controller = new CameraController();
		controller.startMotion(new CameraController.Rotation(0, 0),
			new CameraController.Rotation(90, 0), 0, "vision");
		var pending = controller.whenAligned();
		assertTrue(controller.capturePending());
		controller.clear();
		assertTrue(pending.isCompletedExceptionally());
		assertTrue(!controller.capturePending());
		assertTrue(controller.activeReason().isEmpty());
	}

	@Test
	void clearCancelsPendingMotion() {
		CameraController controller = new CameraController();
		controller.startMotion(
			new CameraController.Rotation(0.0F, 0.0F),
			new CameraController.Rotation(90.0F, 30.0F),
			3,
			"test"
		);

		controller.clear();

		assertTrue(controller.activeReason().isEmpty());
		assertEquals(Optional.empty(), controller.tickMotion());
	}

}
