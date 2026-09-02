package cedclient.mixin;

import ced.cedclient.utils.CustomNametagText;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Swaps the tab-list row's display name for CustomNametag's matched
 * players -- CedNath's hardcoded tag always, the local player's own tag
 * while the module is enabled. getTabListDisplayName() legitimately
 * vanilla-returns null (falls back to the plain username; team
 * prefix/suffix is normally what populates it), so this still applies for
 * a matched player even when original is null, rather than only rewriting
 * an already-present value.
 */
@Mixin(PlayerInfo.class)
public abstract class PlayerInfoMixin {

    @Shadow public abstract com.mojang.authlib.GameProfile getProfile();

    @ModifyReturnValue(method = "getTabListDisplayName", at = @At("RETURN"))
    private Component cedclient$applyCustomNametag(Component original) {
        Component override = CustomNametagText.INSTANCE.transformTabListName(getProfile().name());
        return override != null ? override : original;
    }
}