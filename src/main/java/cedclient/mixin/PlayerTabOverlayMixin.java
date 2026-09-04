package cedclient.mixin;

import ced.cedclient.utils.CustomNametagText;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

/**
 * Replaces the final name component actually used by the tab list.
 *
 * PlayerInfo#getTabListDisplayName() is only the optional server-supplied
 * display name. PlayerTabOverlay#getNameForDisplay() is where vanilla
 * determines the name that is actually displayed in the tab list.
 */
@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @ModifyReturnValue(
            method = "getNameForDisplay",
            at = @At("RETURN")
    )
    private Component cedclient$applyCustomNametag(
            Component original,
            PlayerInfo playerInfo
    ) {
        Component override = CustomNametagText.INSTANCE.transformTabListName(
                playerInfo.getProfile().name(),
                playerInfo.getProfile().id(),
                original
        );

        return override != null ? override : original;
    }
}