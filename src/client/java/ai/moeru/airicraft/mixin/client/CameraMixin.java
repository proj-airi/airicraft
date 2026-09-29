package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "setup", at = @At("HEAD"), cancellable = true)
	private void airicraft$applyWorldCameraPose(
		BlockGetter area,
		Entity focusedEntity,
		boolean thirdPerson,
		boolean inverseView,
		float tickDelta,
		CallbackInfo ci
	) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		WorldCameraService.CameraPose pose = service.pose(net.minecraft.client.Minecraft.getInstance());
		if (pose == null) {
			return;
		}
		CameraAccessor self = (CameraAccessor) this;
		self.airicraft$setReady(true);
		self.airicraft$setThirdPerson(true);
		self.airicraft$setLastTickProgress(tickDelta);
		self.airicraft$invokeSetPos(pose.x(), pose.y(), pose.z());
		self.airicraft$invokeSetRotation(pose.yaw(), pose.pitch());
		service.onWorldFrame(net.minecraft.client.Minecraft.getInstance());
		ci.cancel();
	}
}
