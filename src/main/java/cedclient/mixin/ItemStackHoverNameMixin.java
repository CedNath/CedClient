package cedclient.mixin;

import ced.cedclient.utils.CustomNametagText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/**
 * Applies CustomNametagText's name replacements to an item's hover name --
 * the top line of a tooltip / the label shown on GUI slots (e.g. Hypixel
 * SkyBlock player-head items in coop/party/guild menus, where the display
 * name is baked server-side into the ItemStack rather than delivered via a
 * chat or tab-list packet).
 *
 * getHoverName() feeds both AbstractContainerScreen#renderLabels (slot
 * text) and the first line of the tooltip, so this single hook covers
 * both without a separate mixin per screen.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackHoverNameMixin {

    @ModifyReturnValue(
            method = "getHoverName",
            at = @At("RETURN")
    )
    private Component cedclient$applyCustomNametag(Component original) {
        return CustomNametagText.INSTANCE.transformChat(original);
    }
}