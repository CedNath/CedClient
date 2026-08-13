package cedclient.mixin;


import ced.cedclient.features.impl.funqol.InventoryButtons;
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

@Mixin(ContainerEventHandler.class)
public interface ContainerEventHandlerMixin {

    // ============================================================
    // MOUSE DOWN
    // ============================================================
    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void ced$mouseDown(MouseButtonEvent event, boolean bl, CallbackInfoReturnable<Boolean> cir) {

        if (!((Object)this instanceof Screen self)) return;
        if (!(self instanceof InventoryScreen)) return;

        double mouseX = event.x();
        double mouseY = event.y();

        // --------------------------------------------------------
        // INVINCIBILITY HUD DRAG START
        // --------------------------------------------------------


        // --------------------------------------------------------
        // INVENTORY BUTTON LOGIC
        // --------------------------------------------------------
        if (!InventoryButtons.INSTANCE.isEnabled()) return;

        int button = event.button();

        if (InventoryButtons.INSTANCE.isEditorMode()) {
            boolean handled = InventoryButtonEditorOverlay.INSTANCE.mouseClicked(
                    (int) mouseX, (int) mouseY, button
            );
            if (handled) cir.setReturnValue(true);
            return;
        }

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

    // ============================================================
    // MOUSE RELEASED
    // ============================================================
    @Inject(method = "mouseReleased", at = @At("HEAD"))
    private void ced$mouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {

        if (!((Object)this instanceof Screen self)) return;
        if (!(self instanceof InventoryScreen)) return;



        if (!InventoryButtons.INSTANCE.isEnabled()) return;

        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();

        if (InventoryButtons.INSTANCE.isEditorMode()) {
            InventoryButtonEditorOverlay.INSTANCE.mouseReleased(
                    (int) mouseX, (int) mouseY, button
            );
        }
    }

    // ============================================================
    // MOUSE DRAGGED
    // ============================================================
    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void ced$mouseDrag(MouseButtonEvent event, double dx, double dy, CallbackInfoReturnable<Boolean> cir) {

        if (!((Object)this instanceof Screen self)) return;
        if (!(self instanceof InventoryScreen)) return;

        double mouseX = event.x();
        double mouseY = event.y();

        // --------------------------------------------------------
        // INVINCIBILITY HUD DRAGGING
        // --------------------------------------------------------


        // --------------------------------------------------------
        // INVENTORY BUTTON EDITOR DRAGGING
        // --------------------------------------------------------
        if (!InventoryButtons.INSTANCE.isEnabled()) return;

        int button = event.button();

        if (InventoryButtons.INSTANCE.isEditorMode()) {
            boolean handled = InventoryButtonEditorOverlay.INSTANCE.mouseDragged(
                    (int) mouseX, (int) mouseY, button, dx, dy
            );
            if (handled) cir.setReturnValue(true);
        }
    }
}
