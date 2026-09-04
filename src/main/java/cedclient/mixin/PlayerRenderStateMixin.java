package cedclient.mixin;

import cedclient.accessor.CedClientPlayerRenderStateAccessor;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class PlayerRenderStateMixin
        implements CedClientPlayerRenderStateAccessor {

    @Unique
    private boolean cedclient$isSelf = false;

    @Unique
    private boolean cedclient$isHardcodedTarget = false;

    @Unique
    private float cedclient$syncedScaleX = 1.0F;

    @Unique
    private float cedclient$syncedScaleY = 1.0F;

    @Unique
    private float cedclient$syncedScaleZ = 1.0F;

    @Override
    public boolean cedclient$isSelf() {
        return cedclient$isSelf;
    }

    @Override
    public void cedclient$setSelf(boolean value) {
        cedclient$isSelf = value;
    }

    @Override
    public boolean cedclient$isHardcodedTarget() {
        return cedclient$isHardcodedTarget;
    }

    @Override
    public void cedclient$setHardcodedTarget(boolean value) {
        cedclient$isHardcodedTarget = value;
    }

    @Override
    public float cedclient$getSyncedScaleX() {
        return cedclient$syncedScaleX;
    }

    @Override
    public float cedclient$getSyncedScaleY() {
        return cedclient$syncedScaleY;
    }

    @Override
    public float cedclient$getSyncedScaleZ() {
        return cedclient$syncedScaleZ;
    }

    @Override
    public void cedclient$setSyncedScale(
            float x,
            float y,
            float z
    ) {
        cedclient$syncedScaleX = x;
        cedclient$syncedScaleY = y;
        cedclient$syncedScaleZ = z;
    }
}