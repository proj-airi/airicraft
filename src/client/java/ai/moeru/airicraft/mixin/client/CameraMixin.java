package ai.moeru.airicraft.mixin.client;

import ai.moeru.airicraft.AiricraftClient;
import ai.moeru.airicraft.WorldCameraService;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Inject(method = "update", at = @At("HEAD"), cancellable = true)
	private void airicraft$applyWorldCameraPose(
		BlockView area,
		Entity focusedEntity,
		boolean thirdPerson,
		boolean inverseView,
		float tickDelta,
		CallbackInfo ci
	) {
		WorldCameraService service = AiricraftClient.runtimeController().worldCameraService();
		WorldCameraService.CameraPose pose = service.pose(net.minecraft.client.MinecraftClient.getInstance());
		if (pose == null) {
			return;
		}
		CameraAccessor self = (CameraAccessor) this;
		self.airicraft$setReady(true);
		self.airicraft$setThirdPerson(true);
		self.airicraft$setLastTickProgress(tickDelta);
		self.airicraft$invokeSetPos(pose.x(), pose.y(), pose.z());
		self.airicraft$invokeSetRotation(pose.yaw(), pose.pitch());
		service.onWorldFrame(net.minecraft.client.MinecraftClient.getInstance());
		ci.cancel();
	}
}
