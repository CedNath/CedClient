package ced.cedclient.mixin.cosmetic;

import ced.cedclient.render.cosmetic.CosmeticHooks;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Custom Water Color: overrides the biome water tint everywhere. */
@Mixin(BiomeColors.class)
public class BiomeColorsMixin {

    @Inject(method = "getAverageColor", at = @At("HEAD"), cancellable = true)
    private static void cedclient$customWaterColor(BlockAndTintGetter level, BlockPos pos, ColorResolver resolver, CallbackInfoReturnable<Integer> cir) {
        if (resolver == BiomeColors.WATER_COLOR_RESOLVER && CosmeticHooks.customWater()) {
            cir.setReturnValue(CosmeticHooks.waterRgb());
        }
    }
}
