package cedclient.mixin;


import ced.cedclient.features.impl.misc.InventoryButtons;
import ced.cedclient.ui.inventory.InventoryButtonManager;
import ced.cedclient.ui.inventory.InvButton;
import ced.cedclient.ui.inventory.editor.InventoryButtonEditorOverlay;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Runs a button's command when the player clicks it on the live
 * InventoryScreen. Editing (dragging, adding, deleting buttons) no longer
 * happens here -- that's InvButtonEditorScreen's own Screen input handling
 * now, so this mixin only has to deal with normal, non-editing clicks.
 */
@Mixin(ContainerEventHandler.class)
public interface ContainerEventHandlerMixin {

    // ============================================================
    // MOUSE DOWN
    // ============================================================
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ced$mouseDown(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {

        if (!((Object)this instanceof Screen self)) return;
        if (!(self instanceof InventoryScreen)) return;

        if (!InventoryButtons.INSTANCE.isEnabled()) return;

        double mouseX = event.x();
        double mouseY = event.y();

        int left = InventoryButtonEditorOverlay.INSTANCE.getLastLeft();
        int top = InventoryButtonEditorOverlay.INSTANCE.getLastTop();

        for (InvButton btn : InventoryButtonManager.buttons) {
            int bx = left + btn.getXOff();
            int by = top + btn.getYOff();

            if (mouseX >= bx && mouseX <= bx + btn.getWidth() &&
                    mouseY >= by && mouseY <= by + btn.getHeight()) {

                InventoryButtonManager.INSTANCE.runButtonCommand(btn);
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
