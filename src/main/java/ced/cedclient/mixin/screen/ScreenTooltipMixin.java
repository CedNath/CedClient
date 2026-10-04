package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.render.nametag.CustomNametagText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.stream.Collectors;

/** Real tooltip-assembly point: Screen.getTooltipFromItem(). Replaces ItemLoreMixin. */
@Mixin(Screen.class)
public abstract class ScreenTooltipMixin {

    @ModifyReturnValue(method = "getTooltipFromItem", at = @At("RETURN"))
    private static List<Component> cedclient$applyTooltip(List<Component> original, Minecraft mc, ItemStack stack) {
        return original.stream()
                .map(CustomNametagText.INSTANCE::transformItemText)
                .collect(Collectors.toList());
    }
}