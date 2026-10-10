package ced.cedclient.mixin.client;

import ced.cedclient.accessor.BorderlessWindowAccess;
import ced.cedclient.features.impl.misc.BorderlessFullscreen;
import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.ScreenManager;
import com.mojang.blaze3d.platform.VideoMode;
import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Borderless Fullscreen: while the module is on, "fullscreen" (F11 / the video setting) is an undecorated window
 * covering the monitor at its current resolution instead of exclusive fullscreen -- no mode switch, instant alt-tab.
 * Every fullscreen change goes through Window.setMode(), so that is the only hook needed.
 */
@Mixin(Window.class)
public abstract class WindowBorderlessMixin implements BorderlessWindowAccess {

    @Shadow @Final private ScreenManager screenManager;
    @Shadow @Final private long handle;
    @Shadow private int windowedX;
    @Shadow private int windowedY;
    @Shadow private int windowedWidth;
    @Shadow private int windowedHeight;
    @Shadow private boolean fullscreen;
    @Shadow private int x;
    @Shadow private int y;
    @Shadow private int width;
    @Shadow private int height;
    @Shadow private boolean isResized;
    @Shadow private boolean vsync;

    @Shadow protected abstract void updateFullscreen(boolean enableVsync);

    @Unique
    private boolean cedclient$borderless = false;

    @Inject(method = "setMode", at = @At("HEAD"), cancellable = true)
    private void cedclient$borderlessMode(CallbackInfo ci) {
        if (this.fullscreen && BorderlessFullscreen.INSTANCE.isEnabled()) {
            Monitor monitor = this.screenManager.findBestMonitor((Window) (Object) this);
            if (monitor == null) return; // vanilla logs it and falls back to windowed

            // Coming from a normal window: remember it so leaving fullscreen restores it. (From exclusive
            // fullscreen vanilla already saved it; from borderless we did.)
            boolean exclusive = GLFW.glfwGetWindowMonitor(this.handle) != 0L;
            if (!exclusive && !this.cedclient$borderless) {
                this.windowedX = this.x;
                this.windowedY = this.y;
                this.windowedWidth = this.width;
                this.windowedHeight = this.height;
            }

            VideoMode mode = monitor.getCurrentMode();
            this.x = monitor.getX();
            this.y = monitor.getY();
            this.width = mode.getWidth();
            this.height = mode.getHeight();
            this.isResized = true;
            GLFW.glfwSetWindowAttrib(this.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
            GLFW.glfwSetWindowMonitor(this.handle, 0L, this.x, this.y, this.width, this.height, GLFW.GLFW_DONT_CARE);
            this.cedclient$borderless = true;
            ci.cancel();
        } else if (this.cedclient$borderless) {
            // Leaving borderless (to a window, or to exclusive fullscreen when the module was turned off).
            // Put the remembered window back first so vanilla's exclusive branch doesn't save the monitor-sized
            // borderless window as the "windowed" size.
            this.cedclient$borderless = false;
            GLFW.glfwSetWindowAttrib(this.handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
            this.x = this.windowedX;
            this.y = this.windowedY;
            this.width = this.windowedWidth;
            this.height = this.windowedHeight;
        }
    }

    @Override
    public void cedclient$reapplyMode() {
        this.updateFullscreen(this.vsync);
    }
}
