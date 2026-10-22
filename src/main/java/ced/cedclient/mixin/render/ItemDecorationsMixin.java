package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.OldStars;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Old Stars > Stars as Stack Size: swaps the text drawn where the stack size goes. */
@Mixin(GuiGraphicsExtractor.class)
public class ItemDecorationsMixin {

    @WrapOperation(method = "itemDecorations(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;itemCount(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;IILjava/lang/String;)V"))
    private void cedclient$starCount(GuiGraphicsExtractor self, Font font, ItemStack stack, int x, int y, String text, Operation<Void> original) {
        String stars = OldStars.stackText(stack, text != null || stack.getCount() != 1 ? "x" : null);
        original.call(self, font, stack, x, y, stars != null ? stars : text);
    }
}
