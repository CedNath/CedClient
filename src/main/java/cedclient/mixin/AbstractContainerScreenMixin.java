package cedclient.mixin;

import ced.cedclient.features.impl.misc.InventoryButtons;
import ced.cedclient.ui.inventory.editor.InventoryButtonEditorOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tracks the live InventoryScreen's position and draws the saved buttons on
 * top of it. The editor itself (adding/dragging/deleting buttons) is a
 * separate Screen now (InvButtonEditorScreen), so this mixin no longer needs
 * an editor-mode branch or an extra render stratum for popups.
 */
@Mixin(InventoryScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void ced$updateContainerPos(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci
    ) {
        if (!InventoryButtons.INSTANCE.isEnabled()) return;

        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) (Object) this;
        InventoryButtonEditorOverlay.INSTANCE.setLastPos(accessor.ced$getLeftPos(), accessor.ced$getTopPos());
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void ced$renderOverlay(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick,
            CallbackInfo ci
    ) {
        if (!InventoryButtons.INSTANCE.isEnabled()) return;

        InventoryButtonEditorOverlay.INSTANCE.render(
                graphics,
                mouseX,
                mouseY,
                partialTick
        );
    }
}
