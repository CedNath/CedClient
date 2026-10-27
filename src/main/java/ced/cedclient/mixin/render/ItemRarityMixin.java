package ced.cedclient.mixin.render;

import ced.cedclient.features.impl.render.ItemRarity;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Item Rarity: every item icon goes through this method, so the rarity background is drawn right before it (behind it). */
@Mixin(GuiGraphicsExtractor.class)
public class ItemRarityMixin {
    @Inject(method = "item(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;III)V", at = @At("HEAD"))
    private void cedclient$rarity(LivingEntity entity, Level level, ItemStack stack, int x, int y, int seed, CallbackInfo ci) {
        ItemRarity.INSTANCE.drawBehind((GuiGraphicsExtractor) (Object) this, stack, x, y);
    }
}
