package ced.cedclient.mixin.scoreboard;

import ced.cedclient.features.impl.render.nametag.CustomNametagText;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Applies the custom nametag / synced cosmetic text swap to scoreboard lines.
 *
 * Every scoreboard line (and many scoreboard mods' lines) is built by
 * PlayerTeam.formatNameForTeam(team, name), which wraps the team prefix + name + suffix into
 * one component -- that's where Hypixel's sidebar text, e.g. the dungeon party list with
 * player names, comes from. Hooking its return value is far more stable than targeting a
 * private record inside Gui, and it also works with scoreboard mods that reuse this method.
 *
 * The same method is used for the tab list and in-world nametags, which are already handled
 * elsewhere; running the swap on them again is a harmless no-op (the real name is already
 * gone from the text) and just acts as an extra safety net.
 */
@Mixin(PlayerTeam.class)
public abstract class PlayerTeamMixin {

    @ModifyReturnValue(method = "formatNameForTeam", at = @At("RETURN"))
    private static MutableComponent cedclient$applyCustomNametag(MutableComponent original) {
        Component result = CustomNametagText.INSTANCE.transformChat(original);
        if (result == original) return original;
        return result instanceof MutableComponent mutable ? mutable : Component.empty().append(result);
    }
}