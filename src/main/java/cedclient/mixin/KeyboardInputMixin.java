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

    // This runs right after vanilla reads the real keyboard for this tick
    // (tick()'s own body) and right before that input is consumed by player
    // movement later in the same client tick — the only point where
    // overriding keyPresses/moveVector actually affects movement this tick.
    // Setting it from ClientTickEvents.END_CLIENT_TICK instead is a common
    // trap: that fires AFTER movement already ran, so the override just gets
    // wiped out by the next real read before it ever does anything.
    @Inject(method = "tick", at = @At("TAIL"))
    private void ced$cancelMovement(CallbackInfo ci) {
        ClientInputAccessor accessor = (ClientInputAccessor) this;

        if (Freecam.INSTANCE.isEnabled()) {
            accessor.ced$setKeyPresses(new Input(false, false, false, false, false, false, false));
            accessor.ced$setMoveVector(Vec2.ZERO);
            return;
        }
    }
}