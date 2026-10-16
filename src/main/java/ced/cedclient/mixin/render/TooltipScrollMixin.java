package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CustomTooltips;
import ced.cedclient.features.impl.render.ScrollableTooltips;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.joml.Vector2ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scrollable Tooltips + Custom Tooltips: places the tooltip (wheel / Ctrl wheel, slide-in) and shrinks it around its top-left corner (Fit To Screen). */
@Mixin(GuiGraphicsExtractor.class)
public class TooltipScrollMixin {
    @Unique
    private boolean cedclient$scaled;

    @WrapOperation(
            method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;positionTooltip(IIIIII)Lorg/joml/Vector2ic;"))
    private Vector2ic cedclient$placeTooltip(ClientTooltipPositioner positioner, int screenW, int screenH, int mouseX, int mouseY, int w, int h, Operation<Vector2ic> original) {
        Vector2ic placed = ScrollableTooltips.position(original.call(positioner, screenW, screenH, mouseX, mouseY, w, h), screenW, screenH, w, h);
        Vector2ic pos = CustomTooltips.slide(placed, w, h);
        float scale = ScrollableTooltips.getDrawScale();
        if (scale != 1f) {
            GuiGraphicsExtractor g = (GuiGraphicsExtractor) (Object) this;
            g.pose().pushMatrix();
            g.pose().translate(pos.x(), pos.y());
            g.pose().scale(scale, scale);
            g.pose().translate(-pos.x(), -pos.y());
            cedclient$scaled = true;
        }
        return pos;
    }

    @Inject(method = "tooltip(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/client/gui/screens/inventory/tooltip/ClientTooltipPositioner;Lnet/minecraft/resources/Identifier;)V", at = @At("RETURN"))
    private void cedclient$unscaleTooltip(CallbackInfo ci) {
        if (cedclient$scaled) {
            ((GuiGraphicsExtractor) (Object) this).pose().popMatrix();
            cedclient$scaled = false;
        }
    }
}
