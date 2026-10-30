package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.funqol.LeapMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Leap Menu: swaps the Spirit Leap chest's drawing and input for LeapMenu's (Escape / inventory key still close it). */
@Mixin(AbstractContainerScreen.class)
public abstract class LeapMenuMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void ced$leapRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!LeapMenu.INSTANCE.handles(self)) return;
        LeapMenu.INSTANCE.render(self, graphics, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ced$leapClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!LeapMenu.INSTANCE.handles(self)) return;
        LeapMenu.INSTANCE.mouseClicked(self, event.x(), event.y(), ced.cedclient.utils.Keys.mouse(event));
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void ced$leapRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (LeapMenu.INSTANCE.handles((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void ced$leapDrag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (LeapMenu.INSTANCE.handles((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void ced$leapKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (LeapMenu.INSTANCE.handles(self) && LeapMenu.INSTANCE.keyPressed(self, event.key())) cir.setReturnValue(true);
    }
}
