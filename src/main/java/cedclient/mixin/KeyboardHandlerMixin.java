package cedclient.mixin;

import ced.cedclient.features.impl.funqol.InventoryButtons;
import ced.cedclient.ui.inventory.editor.InventoryButtonEditorOverlay;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void cedclient$onCharTyped(long window, CharacterEvent event, CallbackInfo ci) {
        if (!InventoryButtons.INSTANCE.isEditorMode()) {
            return;
        }

        if (InventoryButtonEditorOverlay.INSTANCE.charTyped(event)) {
            ci.cancel();
        }
    }

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void cedclient$onKeyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (!InventoryButtons.INSTANCE.isEditorMode()) {
            return;
        }

        // PRESS
        if (action != 1) {
            return;
        }

        if (InventoryButtonEditorOverlay.INSTANCE.keyPressed(event)) {
            ci.cancel();
        }
    }
}