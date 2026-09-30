package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.control.ControlPlane;
import ai.moeru.airicraft.control.Channel;
import ai.moeru.airicraft.control.ControlArbiter;
import ai.moeru.airicraft.control.ControlLease;
import ai.moeru.airicraft.control.Priority;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftMotorLeaseTest {
	private static ControlLease take(ControlPlane plane, String owner, Priority priority) {
		return assertInstanceOf(ControlArbiter.Acquisition.Granted.class,
			plane.acquire(owner, priority, Set.of(Channel.LOCOMOTION))).lease();
	}

	@Test
	void theMotorHoldsItsLeaseAcrossTicks() {
		MinecraftMotor motor = new MinecraftMotor(new ControlPlane());
		assertTrue(motor.holdControl());
		assertTrue(motor.holdControl());
	}

	@Test
	void aRevokedMotorPausesWhileTheRevokerHoldsAndResumesOnceItLetsGo() {
		ControlPlane plane = new ControlPlane();
		MinecraftMotor motor = new MinecraftMotor(plane);
		assertTrue(motor.holdControl());

		ControlLease reflex = take(plane, "reflex", Priority.REFLEX);
		assertFalse(motor.holdControl(), "the reflex has the player");
		assertFalse(motor.holdControl(), "still paused while it holds");

		plane.release(null, reflex);
		assertTrue(motor.holdControl(), "navigation takes the player back");
		assertTrue(motor.holdControl());
	}

	@Test
	void aMotorThatStartsUnderAReflexWaitsForIt() {
		ControlPlane plane = new ControlPlane();
		ControlLease reflex = take(plane, "reflex", Priority.REFLEX);
		MinecraftMotor motor = new MinecraftMotor(plane);

		assertFalse(motor.holdControl());

		plane.release(null, reflex);
		assertTrue(motor.holdControl());
	}
}
