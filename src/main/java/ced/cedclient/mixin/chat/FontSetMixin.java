package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.render.ChatEmotes;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Animated chat emotes (ChatEmotes): the emote's glyph is swapped for the current animation frame on lookup. */
@Mixin(FontSet.class)
public class FontSetMixin {

    @ModifyVariable(method = "getGlyph", at = @At("HEAD"), argsOnly = true)
    private int cedclient$animatedEmote(int codepoint) {
        return ChatEmotes.animatedGlyph(codepoint);
    }
}
