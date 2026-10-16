package ced.cedclient.mixin.screen;

import ced.cedclient.features.impl.misc.HideRecipeBook;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** HideRecipeBook "Hide 2x2 Crafting": the inventory's crafting slots are inactive (not drawn, not clickable). */
@Mixin(Slot.class)
public class InventoryCraftingSlotMixin {
    @Shadow @Final public net.minecraft.world.Container container;

    @Inject(method = "isActive", at = @At("HEAD"), cancellable = true)
    private void cedclient$hideInventoryCrafting(CallbackInfoReturnable<Boolean> cir) {
        if ((container instanceof CraftingContainer || container instanceof ResultContainer) && HideRecipeBook.hideCraftingGrid()) cir.setReturnValue(false);
    }
}
