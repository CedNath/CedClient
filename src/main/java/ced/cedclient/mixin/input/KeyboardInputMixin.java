package ced.cedclient.mixin.input;


import ced.cedclient.features.impl.funqol.TinyDancerHelper;
import ced.cedclient.features.impl.render.Freecam;
import ced.cedclient.mixin.accessor.ClientInputAccessor;
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

        if (Freecam.INSTANCE.isActive()) {
            accessor.ced$setKeyPresses(new Input(false, false, false, false, false, false, false));
            accessor.ced$setMoveVector(Vec2.ZERO);
            return;
        }

        // Tiny Dancer helper: force the scripted key presses for this tick.
        Input forced = TinyDancerHelper.overrideInput();
        if (forced != null) {
            accessor.ced$setKeyPresses(forced);
            float forward = (forced.forward() ? 1.0F : 0.0F) - (forced.backward() ? 1.0F : 0.0F);
            float left = (forced.left() ? 1.0F : 0.0F) - (forced.right() ? 1.0F : 0.0F);
            // Only one direction is held at a time, so no diagonal normalisation needed.
            accessor.ced$setMoveVector(new Vec2(left, forward));
        }
    }
}