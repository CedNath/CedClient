package ced.cedclient.mixin.input;

import ced.cedclient.features.impl.render.Zoom;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Forwards mouse scroll to Zoom.adjustZoom() and, while zooming, cancels
 * vanilla's own handling of the same scroll event so it doesn't also cycle
 * the hotbar underneath you.
 *
 * NOTE: "onScroll(JDD)V" (window handle, horizontal, vertical) is the
 * mapped signature I'd expect on a recent-enough MC version to have
 * trackpad/horizontal-scroll support -- confirm against your mappings
 * before building. If your MouseHandler#onScroll only takes a single
 * double instead, drop the `horizontal` parameter and the two zero-arg
 * mismatches will show up as a compile error pointing right at this file.
 */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void ced$onScroll(long windowHandle, double horizontal, double vertical, CallbackInfo ci) {
        if (!Zoom.INSTANCE.isZooming()) return;

        Zoom.INSTANCE.adjustZoom(vertical);

        // Swallow the scroll so vanilla doesn't also switch hotbar slots
        // while we're using it to change zoom level.
        ci.cancel();
    }
}