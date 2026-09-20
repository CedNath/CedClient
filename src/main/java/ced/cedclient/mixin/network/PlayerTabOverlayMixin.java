package ced.cedclient.mixin.network;

import ced.cedclient.features.impl.render.CompactTab;
import ced.cedclient.features.impl.render.nametag.CustomNametagText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;

@Mixin(PlayerTabOverlay.class)
public abstract class PlayerTabOverlayMixin {

    @ModifyReturnValue(method = "getNameForDisplay", at = @At("RETURN"))
    private Component cedclient$applyCustomNametag(Component original, PlayerInfo playerInfo) {
        Component override = CustomNametagText.INSTANCE.transformTabListName(
                playerInfo.getProfile().name(), playerInfo.getProfile().id(), original);
        return override != null ? override : original;
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void cedclient$compactTab(
            GuiGraphicsExtractor graphics, int screenWidth, Scoreboard scoreboard,
            @Nullable Objective displayObjective, CallbackInfo ci
    ) {
        if (CompactTab.INSTANCE.shouldRender()) {
            CompactTab.INSTANCE.render((PlayerTabOverlay) (Object) this, graphics, screenWidth);
            ci.cancel();
        }
    }
}