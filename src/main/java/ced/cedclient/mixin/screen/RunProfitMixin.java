package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.funqol.RunProfit;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Run Profit: Croesus chest profits after the slots, the Fancy Menu's drawing and input, and the click that logs a chest. */
@Mixin(AbstractContainerScreen.class)
public abstract class RunProfitMixin {

    @Inject(method = "extractContents", at = @At("TAIL"))
    private void ced$runProfitOverlay(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        RunProfit.INSTANCE.drawOverlays((AbstractContainerScreen<?>) (Object) this, graphics);
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void ced$runProfitRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!RunProfit.INSTANCE.handles(self)) return;
        RunProfit.INSTANCE.render(self, graphics, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ced$runProfitClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (RunProfit.INSTANCE.onClick(self, event.x(), event.y(), event.button())) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void ced$runProfitRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (RunProfit.INSTANCE.handles((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void ced$runProfitDrag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (RunProfit.INSTANCE.handles((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }
}
