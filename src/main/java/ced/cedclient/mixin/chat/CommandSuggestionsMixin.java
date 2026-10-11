package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.render.ChatEmotes;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
 * with ":" we offer the emote shortcodes instead, and open the list straight away like Discord does.
 */
@Mixin(CommandSuggestions.class)
public abstract class CommandSuggestionsMixin {

    @Shadow @Final private EditBox input;
    @Shadow private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow private boolean currentParseIsCommand;

    @Shadow public abstract void showSuggestions(boolean narrateFirstSuggestion);

    @WrapOperation(method = "updateCommandInfo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/commands/SharedSuggestionProvider;suggest(Ljava/lang/Iterable;Lcom/mojang/brigadier/suggestion/SuggestionsBuilder;)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<Suggestions> cedclient$emoteSuggestions(Iterable<String> values, SuggestionsBuilder builder,
                                                                      Operation<CompletableFuture<Suggestions>> original) {
        List<String> emotes = ChatEmotes.completionsFor(builder.getRemaining());
        return original.call(emotes != null ? emotes : values, builder);
    }

    @Inject(method = "updateCommandInfo", at = @At("TAIL"))
    private void cedclient$autoShowEmotes(CallbackInfo ci) {
        if (this.currentParseIsCommand || this.pendingSuggestions == null || !this.pendingSuggestions.isDone()) return;
        String text = this.input.getValue();
        int cursor = Math.min(this.input.getCursorPosition(), text.length());
        if (text.startsWith("/")) return;
        String beforeCursor = text.substring(0, cursor);
        String word = beforeCursor.substring(beforeCursor.lastIndexOf(' ') + 1);
        if (ChatEmotes.completionsFor(word) == null) return;
        if (!this.pendingSuggestions.join().isEmpty()) this.showSuggestions(false);
    }
}
