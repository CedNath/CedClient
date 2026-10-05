package ced.cedclient.mixin.gui;

import ced.cedclient.features.impl.render.nametag.CustomNametagText;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Applies the custom nametag / synced cosmetic text swap to the sidebar scoreboard.
 *
 * The sidebar builds one DisplayEntry(name, score, scoreWidth) per line every frame, where
 * "name" is the line's text with the team prefix/suffix already wrapped around it. Hypixel
 * puts player names in these lines (e.g. the dungeon party list), so splicing the same way
 * chat and item lore do covers them. Only the name text is touched, not the score number,
 * and widths are measured later from the returned component, so the sidebar resizes itself.
 *
 * @Pseudo: if this nested class has a different name on this Minecraft version the mixin is
 * skipped quietly instead of crashing the game -- the feature just won't do anything. If a
 * scoreboard name doesn't change in game, look up the sidebar line record in Gui and update
 * the target below.
 */
@Pseudo
@Mixin(targets = "net.minecraft.client.gui.Gui$DisplayEntry")
public abstract class ScoreboardEntryMixin {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static Component cedclient$applyCustomNametag(Component name) {
        return CustomNametagText.INSTANCE.transformChat(name);
    }
}