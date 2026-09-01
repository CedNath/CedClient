package cedclient.mixin;

import ced.cedclient.utils.CustomNametagText;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

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
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @ModifyVariable(method = "addMessage", at = @At("HEAD"), argsOnly = true)
    private Component cedclient$applyCustomNametag(Component contents) {
        return CustomNametagText.INSTANCE.transformChat(contents);
    }
}