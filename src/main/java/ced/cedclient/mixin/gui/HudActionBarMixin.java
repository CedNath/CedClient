package ced.cedclient.mixin.gui;

import ced.cedclient.features.impl.misc.ActionBarStats;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Stat Bars / Skill Display: reads every action bar message and cuts the stats those modules show out of it if asked. */
@Mixin(Hud.class)
public abstract class HudActionBarMixin {

    @ModifyVariable(method = "setOverlayMessage", at = @At("HEAD"), argsOnly = true)
    private Component cedclient$actionBarStats(Component message) {
        return message == null ? null : ActionBarStats.process(message);
    }
}
