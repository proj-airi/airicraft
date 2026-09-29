package ai.moeru.airicraft;

import ai.moeru.airicraft.agent.control.CameraController;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class PlayerViewService {
	private final CameraController cameraController;

	public PlayerViewService() {
		this(new CameraController());
	}

	public PlayerViewService(CameraController cameraController) {
		this.cameraController = Objects.requireNonNull(cameraController, "cameraController");
	}

	public Map<String, Object> lookAt(double x, double y, double z) {
		return lookAt(x, y, z, null);
	}

	public Map<String, Object> lookAt(double x, double y, double z, Integer durationTicks) {
		Minecraft minecraft = requireClient();
		if (minecraft.level == null || minecraft.player == null) {
			throw new PlayerViewException("world_not_loaded", "No world is currently loaded");
		}

		Vec3 target = new Vec3(x, y, z);
		int effectiveDurationTicks = durationTicks == null
			? cameraController.defaultLerpTicks()
			: Math.max(0, durationTicks);
		CameraController.Rotation rotation = cameraController.startLookAt(minecraft, target, effectiveDurationTicks, "player_look_at")
			.orElseThrow(() -> new PlayerViewException("invalid_request", "Target must differ from the current camera position"));

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("available", true);
		payload.put("worldLoaded", true);
		payload.put("durationTicks", effectiveDurationTicks);
		payload.put("scheduled", true);
		payload.put("target", Map.of(
			"x", x,
			"y", y,
			"z", z
		));
		payload.put("rotation", Map.of(
			"yaw", rotation.yaw(),
			"pitch", rotation.pitch()
		));
		return payload;
	}

	private static Minecraft requireClient() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			throw new PlayerViewException("minecraft_unavailable", "Minecraft client is not initialized");
		}
		return minecraft;
	}

	public static final class PlayerViewException extends RuntimeException {
		private final String code;

		PlayerViewException(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}
}
