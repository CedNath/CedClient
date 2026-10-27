package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.CrosshairTweaks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Crosshair module: third-person crosshair and custom shapes. */
@Mixin(Gui.class)
public class CrosshairMixin {

    @WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/CameraType;isFirstPerson()Z"))
    private boolean cedclient$thirdPersonCrosshair(CameraType type, Operation<Boolean> original) {
        return CrosshairTweaks.showInThirdPerson() || original.call(type);
    }

    @WrapOperation(method = "extractCrosshair", at = @At(value = "INVOKE", ordinal = 0,
            target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
    private void cedclient$drawCrosshair(GuiGraphicsExtractor graphics, RenderPipeline pipeline, Identifier sprite,
                                         int x, int y, int w, int h, Operation<Void> original) {
        // The vanilla sprite blends by inverting the screen, so a tint barely shows: custom shapes are drawn as fills.
        if (!CrosshairTweaks.draw(graphics, x + w / 2, y + h / 2)) original.call(graphics, pipeline, sprite, x, y, w, h);
    }
}
