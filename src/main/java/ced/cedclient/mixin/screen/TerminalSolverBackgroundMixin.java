package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.funqol.TerminalSolver;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Terminal Solver: no chest texture behind the solver panel. */
@Mixin(ContainerScreen.class)
public abstract class TerminalSolverBackgroundMixin {

    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void ced$terminalBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        if (TerminalSolver.INSTANCE.handles((ContainerScreen) (Object) this)) ci.cancel();
    }
}
