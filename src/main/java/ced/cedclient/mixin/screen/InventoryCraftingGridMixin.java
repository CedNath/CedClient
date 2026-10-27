package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.misc.HideRecipeBook;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * HideRecipeBook "Hide 2x2 Crafting": no "Crafting" label, and the inventory background is drawn around the
 * 2x2 grid / arrow / result box instead of over it (painting over it broke packs with see-through backgrounds).
 */
@Mixin(InventoryScreen.class)
public class InventoryCraftingGridMixin {
    // The grid, arrow and result box inside the 176x166 inventory texture.
    private static final int HOLE_X0 = 96, HOLE_Y0 = 16, HOLE_X1 = 173, HOLE_Y1 = 58;

    @Inject(method = "extractLabels", at = @At("HEAD"), cancellable = true)
    private void cedclient$hideCraftingLabel(GuiGraphicsExtractor graphics, int xm, int ym, CallbackInfo ci) {
        if (HideRecipeBook.hideCraftingGrid()) ci.cancel();
    }

    @WrapOperation(method = "extractBackground", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIFFIIII)V"))
    private void cedclient$backgroundAroundGrid(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier texture, int x, int y,
                                                float u, float v, int w, int h, int texW, int texH, Operation<Void> original) {
        if (!HideRecipeBook.hideCraftingGrid()) {
            original.call(graphics, pipeline, texture, x, y, u, v, w, h, texW, texH);
            return;
        }
        int[][] parts = {
            {0, 0, w, HOLE_Y0},                                   // above
            {0, HOLE_Y1, w, h - HOLE_Y1},                          // below
            {0, HOLE_Y0, HOLE_X0, HOLE_Y1 - HOLE_Y0},              // left
            {HOLE_X1, HOLE_Y0, w - HOLE_X1, HOLE_Y1 - HOLE_Y0},    // right
        };
        for (int[] p : parts) {
            original.call(graphics, pipeline, texture, x + p[0], y + p[1], u + p[0], v + p[1], p[2], p[3], texW, texH);
        }
        // Fill the hole with the texture's own empty band under the grid (v 62-76): grey in vanilla,
        // see-through in packs whose inventory is see-through there, so it always matches.
        for (int row = 0; row < 3; row++) {
            original.call(graphics, pipeline, texture, x + HOLE_X0, y + HOLE_Y0 + row * 14, u + HOLE_X0, v + 62, HOLE_X1 - HOLE_X0, 14, texW, texH);
        }
    }
}
