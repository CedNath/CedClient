package ced.cedclient.mixin.accessor;

import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Gui.class)
public interface GuiAccessor {
    /** The action bar text currently shown (e.g. Hypixel's "NOT ENOUGH MANA"), null when none. */
    @Accessor("overlayMessageString")
    Component ced$getOverlayMessage();
}
