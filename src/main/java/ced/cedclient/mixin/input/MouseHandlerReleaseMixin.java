package ced.cedclient.mixin.input;

import ced.cedclient.features.impl.misc.CursorTweaks;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * No Cursor Reset: vanilla's releaseMouse() puts the cursor in the middle of the window every time a GUI opens. While the
 * option is on, it goes back to where it was when the mouse was last grabbed (where it sat in the previous GUI) instead.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerReleaseMixin {
    @Shadow private boolean mouseGrabbed;
    @Shadow private double xpos;
    @Shadow private double ypos;
    @Shadow @Final private Minecraft minecraft;

    @Unique private double ced$x;
    @Unique private double ced$y;
    @Unique private boolean ced$known;
    @Unique private long ced$grabbedAt;

    @Inject(method = "grabMouse", at = @At("HEAD"))
    private void ced$remember(CallbackInfo ci) {
        if (mouseGrabbed) return;
        ced$x = xpos;
        ced$y = ypos;
        ced$known = true;
        ced$grabbedAt = System.currentTimeMillis();
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
    private void ced$keep(CallbackInfo ci) {
        if (!mouseGrabbed || !ced$known || !CursorTweaks.keepCursor(System.currentTimeMillis() - ced$grabbedAt)) return;
        mouseGrabbed = false;
        xpos = ced$x;
        ypos = ced$y;
        InputConstants.releaseMouse(minecraft.getWindow(), xpos, ypos);
        ci.cancel();
    }
}
