package ai.moeru.airicraft.mixin.client;

import net.minecraft.client.render.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
	@Accessor("ready")
	void airicraft$setReady(boolean ready);

	@Accessor("thirdPerson")
	void airicraft$setThirdPerson(boolean thirdPerson);
	@Accessor("lastTickProgress")
	void airicraft$setLastTickProgress(float lastTickProgress);

	@Invoker("setPos")
	void airicraft$invokeSetPos(double x, double y, double z);

	@Invoker("setRotation")
	void airicraft$invokeSetRotation(float yaw, float pitch);
}
