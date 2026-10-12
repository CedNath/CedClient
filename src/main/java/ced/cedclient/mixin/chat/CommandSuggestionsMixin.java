package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.render.ChatEmotes;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.commands.SharedSuggestionProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Emote autocomplete in chat (Cosmetics > Chat Emotes). For plain chat (not a command) vanilla suggests the server's
 * custom completions / player names for the word at the cursor, but only when Tab is pressed. When that word starts
 * with ":" we offer the emote shortcodes too, and open the list straight away like Discord does.
 */
@Mixin(value = CommandSuggestions.class, priority = 1100) // after other mods' TAIL hooks (RFU's EmojiAutocomplete)
public abstract class CommandSuggestionsMixin {

    @Shadow @Final private EditBox input;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow private boolean currentParseIsCommand;

    @Shadow public abstract void showSuggestions(boolean narrateFirstSuggestion);

    // Runs last, so another mod that swaps in its own emoji list for ":" words (RFU) doesn't hide ours: both are merged.
    @Inject(method = "updateCommandInfo", at = @At("TAIL"))
    private void cedclient$emoteSuggestions(CallbackInfo ci) {
        if (this.currentParseIsCommand) return;
        String text = this.input.getValue();
        if (text.startsWith("/")) return;
        String beforeCursor = text.substring(0, Math.min(this.input.getCursorPosition(), text.length()));
        int wordStart = beforeCursor.lastIndexOf(' ') + 1;
        List<String> emotes = ChatEmotes.completionsFor(beforeCursor.substring(wordStart));
        if (emotes == null) return;

        Suggestions ours = SharedSuggestionProvider.suggest(emotes, new SuggestionsBuilder(beforeCursor, wordStart)).join();
        CompletableFuture<Suggestions> other = this.pendingSuggestions;
        Suggestions merged = other != null && other.isDone() && !other.isCompletedExceptionally()
                ? Suggestions.merge(text, List.of(other.join(), ours)) : ours;
        this.pendingSuggestions = CompletableFuture.completedFuture(merged);
        if (!merged.isEmpty()) this.showSuggestions(false);
    }
}
