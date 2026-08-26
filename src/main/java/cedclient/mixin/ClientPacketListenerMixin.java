package cedclient.mixin;

import ced.cedclient.features.impl.misc.ChatFilter;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import cedclient.events.EntityMetadataEvent;


@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    // ============================================================
    // ENTITY METADATA HANDLER (existing CedClient logic)
    // ============================================================
    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void cedclient$onHandleSetEntityData(ClientboundSetEntityDataPacket packet,
                                                 CallbackInfo ci,
                                                 @Local Entity entity) {

        if (entity == null) return;

        if (new EntityMetadataEvent(entity, packet).postAndCatch() && this.level != null) {
            this.level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    // ============================================================
    // CHAT MESSAGE HANDLER (chat filter)
    // ============================================================

    // NOTE: verify this against the real decompiled ClientPacketListener for
    // your MC version before building -- FernFlower it in IntelliJ
    // (Navigate > Declaration on ClientPacketListener) and confirm both the
    // method name "handleSystemChat" and that ClientboundSystemChatPacket
    // still exposes content() the same way. This handles server-sent system
    // messages (the kind most spam/join-leave lines are); player chat
    // (handlePlayerChat, real player messages) is deliberately NOT hooked
    // here so ChatFilter never risks hiding an actual player's message.
    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void cedclient$onHandleSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        Component content = packet.content();
        if (content == null) return;

        if (ChatFilter.INSTANCE.shouldHide(content.getString())) {
            ci.cancel();
        }
    }
}