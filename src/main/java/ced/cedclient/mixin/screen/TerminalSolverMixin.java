package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.funqol.TerminalSolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Terminal Solver: swaps an F7 terminal chest's drawing and mouse input for TerminalSolver's. */
@Mixin(AbstractContainerScreen.class)
public abstract class TerminalSolverMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void ced$terminalRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!TerminalSolver.INSTANCE.handles(self)) return;
        TerminalSolver.INSTANCE.render(self, graphics, mouseX, mouseY);
        ci.cancel();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ced$terminalClick(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (!TerminalSolver.INSTANCE.handles(self)) return;
        TerminalSolver.INSTANCE.mouseClicked(self, event.x(), event.y(), ced.cedclient.utils.Keys.mouse(event));
        cir.setReturnValue(true);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void ced$terminalKey(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (TerminalSolver.INSTANCE.handles(self) && TerminalSolver.INSTANCE.keyPressed(self, event)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void ced$terminalRelease(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolver.INSTANCE.handles((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void ced$terminalDrag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {
        if (TerminalSolver.INSTANCE.handles((AbstractContainerScreen<?>) (Object) this)) cir.setReturnValue(true);
    }
}
