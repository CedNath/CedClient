package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.VanillaTextures;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.renderer.special.PlayerHeadSpecialRenderer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla Textures: a SkyBlock head that comes without a skin gets its old one back. */
@Mixin(PlayerHeadSpecialRenderer.class)
public class PlayerHeadRendererMixin {

    @ModifyExpressionValue(
            method = "extractArgument(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/client/renderer/PlayerSkinRenderCache$RenderInfo;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;get(Lnet/minecraft/core/component/DataComponentType;)Ljava/lang/Object;")
    )
    private Object cedclient$oldHeadSkin(Object original, @Local(argsOnly = true) ItemStack stack) {
        if (original != null && !(original instanceof ResolvableProfile)) return original;
        return VanillaTextures.headProfile(stack, (ResolvableProfile) original);
    }
}
