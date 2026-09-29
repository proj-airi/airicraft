package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
	@Accessor("initialized")
	void airicraft$setReady(boolean ready);

	@Accessor("detached")
	void airicraft$setThirdPerson(boolean thirdPerson);
	@Accessor("partialTickTime")
	void airicraft$setLastTickProgress(float lastTickProgress);

	@Invoker("setPosition")
	void airicraft$invokeSetPos(double x, double y, double z);

	@Invoker("setRotation")
	void airicraft$invokeSetRotation(float yaw, float pitch);
}
