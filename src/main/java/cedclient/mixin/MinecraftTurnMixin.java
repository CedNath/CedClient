package cedclient.mixin;

import ced.cedclient.features.impl.render.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public class MinecraftTurnMixin {
    @Shadow private Minecraft minecraft;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow private SmoothDouble smoothTurnX;
    @Shadow private SmoothDouble smoothTurnY;

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void ced$turnPlayer(double mousea, CallbackInfo ci) {
        // If freecam not active, let vanilla handle rotation
        if (!Freecam.INSTANCE.isEnabled()) {
            return;
        }

        // Replicate vanilla sensitivity / smoothing logic from MouseHandler.turnPlayer
        double ss = this.minecraft.options.sensitivity().get() * 0.6F + 0.2F;
        double sensitivityMod = ss * ss * ss;
        double sens = sensitivityMod * 8.0;
        double xo;
        double yo;

        if (this.minecraft.options.smoothCamera) {
            double dx = this.smoothTurnX.getNewDeltaValue(this.accumulatedDX * sens, mousea * sens);
            double dy = this.smoothTurnY.getNewDeltaValue(this.accumulatedDY * sens, mousea * sens);
            xo = dx;
            yo = dy;
        } else if (this.minecraft.options.getCameraType().isFirstPerson() && this.minecraft.player != null && this.minecraft.player.isScoping()) {
            this.smoothTurnX.reset();
            this.smoothTurnY.reset();
            xo = this.accumulatedDX * sensitivityMod;
            yo = this.accumulatedDY * sensitivityMod;
        } else {
            this.smoothTurnX.reset();
            this.smoothTurnY.reset();
            xo = this.accumulatedDX * sens;
            yo = this.accumulatedDY * sens;
        }

        // Respect invert options (same as vanilla)
        double appliedX = this.minecraft.options.invertMouseX().get() ? -xo : xo;
        double appliedY = this.minecraft.options.invertMouseY().get() ? -yo : yo;

        // Forward to Freecam and cancel vanilla rotation
        Freecam.INSTANCE.changeLookDirection(appliedX, appliedY);
        ci.cancel();
    }
}
