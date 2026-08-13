package cedclient.mixin;

import ced.cedclient.features.impl.funqol.InventoryButtons;
import ced.cedclient.ui.inventory.editor.InventoryButtonEditorOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractContainerScreen.class)
public abstract class InventoryButtonMixin {

    @Shadow protected int leftPos;
    @Shadow protected int topPos;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void cedclient$renderOverlay(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo ci
    ) {
        if (!((Object)this instanceof InventoryScreen))
            return;

        InventoryButtonEditorOverlay.INSTANCE.setLastPos(leftPos, topPos);

        if (!InventoryButtons.INSTANCE.isEnabled())
            return;

        if (InventoryButtons.INSTANCE.isEditorMode()) {
            graphics.nextStratum();
        }

        InventoryButtonEditorOverlay.INSTANCE.render(
                graphics,
                mouseX,
                mouseY,
                delta
        );
    }
}