package cedclient.mixin;

import ced.cedclient.events.ChatMessageEvent;
import ced.cedclient.events.PlaySoundEvent;
import ced.cedclient.features.impl.misc.ChatFilter;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
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
    // CHAT MESSAGE HANDLER (raw chat tap + chat filter)
    // ============================================================
    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void cedclient$onHandleSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        Component content = packet.content();
        if (content == null) return;

        if (!packet.overlay()) {
            new ChatMessageEvent(content.getString()).post();
        }

        if (ChatFilter.INSTANCE.shouldHide(content.getString())) {
            // Cancelling handleSystemChat entirely also skips vanilla's own
            // chat-log call inside ChatComponent.addMessage() (that's where
            // the "[CHAT] ..." log lines come from), since addMessage()
            // never runs. Log it ourselves first so filtered lines still
            // show up in the log file -- they just won't render in-game.
            System.out.println("[ChatFilter] hidden: " + content.getString());
            ci.cancel();
        }
    }

    // ============================================================
    // SOUND HANDLER (posts PlaySoundEvent for every ClientboundSoundPacket)
    // ============================================================
    // NOTE: "handleSoundEvent" and the accessor names below (getSound() /
    // getPitch() / getVolume()) are the Mojmap names as of ~1.21 -- this
    // packet has changed shape across versions before (it went from a plain
    // class to closer-to-record-style accessors), so if this fails to
    // compile, Navigate > Declaration on ClientboundSoundPacket in IntelliJ
    // and fix the method names to match. getSound() returns a
    // Holder<SoundEvent>; .value().location() gets the actual resource
    // location, which is what shows up in the "Log All Sounds" output that
    // ItemCooldowns prints.
    //
    // Mojmap has renamed getLocation() -> location() (and similar
    // getFoo() -> foo()) across several recent versions as part of moving
    // toward record-style accessors -- if location() also doesn't resolve,
    // try .value().getLocation(), or open SoundEvent's declaration directly
    // and use whatever it actually exposes.
    @Inject(method = "handleSoundEvent", at = @At("HEAD"))
    private void cedclient$onHandleSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
        String soundName = packet.getSound().value().location().toString();
        new PlaySoundEvent(soundName, packet.getPitch(), packet.getVolume()).post();
    }
}