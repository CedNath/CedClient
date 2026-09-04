package cedclient.mixin;

import ced.cedclient.utils.CustomNametagText;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.component.ItemLore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Applies CustomNametagText's name replacements to item lore -- the extra
 * lines below an item's hover name (e.g. the "Players:" list in Hypixel
 * SkyBlock's Visit-player-island menu). Since 1.21.5, lore lives as the
 * ItemLore data component rather than being appended directly to a
 * tooltip list, so hooking its accessors here catches it wherever it's
 * read (tooltip building included) instead of chasing the specific
 * tooltip-assembly method for this MC version.
 *
 * NOTE: verify lines()/styledLines() are actually the accessor names on
 * your mapped ItemLore before building -- Ctrl+click into the class in
 * your IDE to confirm.
 */
@Mixin(ItemLore.class)
public abstract class ItemLoreMixin {

    @ModifyReturnValue(method = "lines", at = @At("RETURN"))
    private List<Component> cedclient$applyToLines(List<Component> original) {
        return original.stream()
                .map(CustomNametagText.INSTANCE::transformChat)
                .collect(Collectors.toList());
    }

    @ModifyReturnValue(method = "styledLines", at = @At("RETURN"))
    private List<Component> cedclient$applyToStyledLines(List<Component> original) {
        return original.stream()
                .map(CustomNametagText.INSTANCE::transformChat)
                .collect(Collectors.toList());
    }
}