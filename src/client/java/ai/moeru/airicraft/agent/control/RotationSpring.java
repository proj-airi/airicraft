package ai.moeru.airicraft.agent.control;

import net.minecraft.util.Mth;

/** Critically damped angular spring. Retargeting preserves angular velocity. */
final class RotationSpring {
	private double yaw;
	private double pitch;
	private double yawVelocity;
	private double pitchVelocity;

	RotationSpring(CameraController.Rotation start) {
		yaw = start.yaw();
		pitch = start.pitch();
	}

	/** Rebase after external view changes, without discarding normal tracking momentum. */
	void synchronize(CameraController.Rotation actual) {
		if (Math.abs(Mth.wrapDegrees(actual.yaw() - yaw)) > 0.01D
			|| Math.abs(actual.pitch() - pitch) > 0.01D) {
			yaw = actual.yaw();
			pitch = actual.pitch();
			yawVelocity = 0;
			pitchVelocity = 0;
		} else {
			// Equivalent wrapped angles must also retain the player's representation for rendering.
			yaw += 360.0D * Math.rint((actual.yaw() - yaw) / 360.0D);
		}
	}

	boolean atRest() { return Math.abs(yawVelocity) < 0.1D && Math.abs(pitchVelocity) < 0.1D; }

	CameraController.Rotation advance(CameraController.Rotation target, double seconds, double frequency) {
		double yawError = Mth.wrapDegrees(yaw - target.yaw());
		double pitchError = pitch - Mth.clamp(target.pitch(), -90.0F, 90.0F);
		double decay = Math.exp(-frequency * seconds);
		double yawTerm = (yawVelocity + frequency * yawError) * seconds;
		double pitchTerm = (pitchVelocity + frequency * pitchError) * seconds;
		yaw += (yawError + yawTerm) * decay - yawError;
		pitch += (pitchError + pitchTerm) * decay - pitchError;
		yawVelocity = (yawVelocity - frequency * yawTerm) * decay;
		pitchVelocity = (pitchVelocity - frequency * pitchTerm) * decay;
		if (pitch < -90.0D || pitch > 90.0D) {
			pitch = Mth.clamp(pitch, -90.0D, 90.0D);
			pitchVelocity = 0.0D;
		}
		return new CameraController.Rotation((float) yaw, (float) pitch);
	}
}
