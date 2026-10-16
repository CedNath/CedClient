package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.VanillaTextures;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla Textures: the item_model component every lookup here reads ("hypixel_skyblock:..." on SkyBlock items)
 * is swapped for the item's old vanilla model.
 */
@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {

    @ModifyExpressionValue(
            method = {"appendItemLayers", "shouldPlaySwapAnimation", "swapAnimationScale"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;")
    )
    private Object cedclient$vanillaModel(Object original, @Local(argsOnly = true) ItemStack stack) {
        return original instanceof Identifier id ? VanillaTextures.itemModel(stack, id) : original;
    }
}
