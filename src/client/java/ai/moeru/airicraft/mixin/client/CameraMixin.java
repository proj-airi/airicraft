package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow private boolean initialized;
	@Shadow private BlockGetter level;
	@Shadow private Entity entity;
	@Shadow private boolean detached;
	@Shadow private float partialTickTime;

	@Shadow protected abstract void setPosition(double x, double y, double z);
	@Shadow protected abstract void setRotation(float yRot, float xRot);

	@Inject(method = "setup", at = @At("HEAD"), cancellable = true)
	private void airicraft$applyWorldCameraPose(
		BlockGetter level,
		Entity entity,
		boolean detached,
		boolean thirdPersonReverse,
		float partialTick,
		CallbackInfo ci
	) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		WorldCameraService.CameraPose pose = service.pose(net.minecraft.client.Minecraft.getInstance());
		if (pose == null) {
			return;
		}
		this.initialized = true;
		this.level = level;
		this.entity = entity;
		this.detached = true;
		this.partialTickTime = partialTick;
		setPosition(pose.x(), pose.y(), pose.z());
		setRotation(pose.yaw(), pose.pitch());
		service.onWorldFrame(net.minecraft.client.Minecraft.getInstance());
		ci.cancel();
	}
}
