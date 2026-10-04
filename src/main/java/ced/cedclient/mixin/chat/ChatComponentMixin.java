package ced.cedclient.mixin.chat;

import ced.cedclient.features.impl.misc.ScreenshotTweaks;
import ced.cedclient.features.impl.render.nametag.CustomNametagText;
import ced.cedclient.utils.ChatHide;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Rewrites CustomNametag's matched usernames (CedNath's hardcoded tag, plus
 * the local player's own tag if set) inside chat lines.
 *
 * Targets the private addMessage(Component, MessageSignature,
 * GuiMessageSource, GuiMessageTag) -- confirmed via decompile to be the
 * single choke point addClientSystemMessage/addServerSystemMessage/
 * addPlayerMessage all funnel into, so this one injection catches every
 * kind of chat line (including real player messages) after vanilla has
 * already fully decorated them ("<PlayerName> message text" etc). No need
 * to touch signed-chat handling at the packet level for this reason --
 * same as why ClientPacketListenerMixin deliberately doesn't hook
 * handlePlayerChat, see that file's comment. "addMessage" is unambiguous
 * by bare name in this class (it's the only method with that exact name),
 * so no full descriptor needed.
 */
// High priority number = applied late, so this cancel is as close to "last" as Mixin lets us
// make it: any other mod that also hooks addMessage gets to see the line first.
@Mixin(value = ChatComponent.class, priority = 2000)
public abstract class ChatComponentMixin {

    /**
     * Drops lines ChatFilter / reward-spam marked as hidden (see ChatHide).
     * Handler takes only the CallbackInfo (Mixin allows omitting the target's own arguments),
     * so it doesn't depend on addMessage's exact parameter types.
     */
    @Inject(method = "addMessage", at = @At("HEAD"), cancellable = true)
    private void cedclient$hideFilteredLine(CallbackInfo ci) {
        if (ChatHide.pending) {
            ChatHide.pending = false;
            ci.cancel();
        }
    }

    /**
     * Screenshot tweaks: ScreenshotTweaks copies the screenshot to the clipboard when vanilla's
     * "Saved screenshot" line comes through, and tells us whether to hide that line.
     */
    @Inject(method = "addMessage", at = @At("HEAD"), cancellable = true)
    private void cedclient$screenshotTweaks(CallbackInfo ci, @Local(argsOnly = true) Component contents) {
        if (ScreenshotTweaks.INSTANCE.handleChatMessage(contents)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
    private Component cedclient$applyCustomNametag(Component contents) {
        return CustomNametagText.INSTANCE.transformChat(contents);
    }
}