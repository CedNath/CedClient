package cedclient.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraAccessor {
    @Invoker("setPosition")
    void ced$setPosition(net.minecraft.world.phys.Vec3 position);

    @Invoker("setRotation")
    void ced$setRotation(float yaw, float pitch);
}
