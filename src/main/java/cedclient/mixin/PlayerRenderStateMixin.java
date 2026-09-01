package cedclient.mixin;

import cedclient.accessor.CedClientPlayerRenderStateAccessor;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

// AvatarRenderState, not vanilla's PlayerRenderState -- see
// PlayerRendererMixin.java for why (this build renamed the whole
// player-rendering stack to "Avatar").
@Mixin(AvatarRenderState.class)
public class PlayerRenderStateMixin implements CedClientPlayerRenderStateAccessor {

    @Unique
    private boolean cedclient$isSelf = false;

    @Unique
    private boolean cedclient$isHardcodedTarget = false;

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
}