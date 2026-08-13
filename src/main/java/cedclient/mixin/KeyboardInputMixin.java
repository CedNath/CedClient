package cedclient.mixin;

import ced.cedclient.features.impl.render.Freecam;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class KeyboardInputMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void ced$cancelMovement(CallbackInfo ci) {
        if (!Freecam.INSTANCE.isEnabled()) return;

        ClientInputAccessor accessor = (ClientInputAccessor) this;
        accessor.ced$setKeyPresses(new Input(false, false, false, false, false, false, false));
        accessor.ced$setMoveVector(Vec2.ZERO);
    }
}