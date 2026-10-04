package ced.cedclient.mixin.chat;

import ced.cedclient.utils.ChatHide;
import ced.cedclient.events.ChatMessageEvent;
import ced.cedclient.events.EntityMetadataEvent;
import ced.cedclient.events.PlaySoundEvent;
import ced.cedclient.events.SubtitleEvent;
import ced.cedclient.events.TitleEvent;
import ced.cedclient.features.impl.loot.LootTracker;
import ced.cedclient.features.impl.misc.ChatFilter;
import ced.cedclient.state.IslandState;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

    @Shadow
    private ClientLevel level;

    // ============================================================
    // KUUDRA/DUNGEON REWARD SPAM SUPPRESSION (feeds LootTracker instead)
    // ============================================================
    // Folded directly into the existing chat handler below rather than a
    // second mixin on the same method -- two separate @Inject(HEAD,
    // cancellable=true) mixins racing to cancel handleSystemChat is fragile
    // (their relative order isn't something we control), and it would also
    // make Kuudra reward lines silently skip ChatMessageEvent below.
    private static final Pattern REWARD_HEADER = Pattern.compile("^PAID CHEST REWARDS$");
    private static final Pattern RARE_REWARD =
            Pattern.compile("^RARE REWARD! (.+) found a (.+) in their (.+) Chest!$");
    private static final Pattern CHEST_TRACKER = Pattern.compile(".*Chest tracker: (\\d+)/(\\d+).*");

    // ============================================================
    // /locraw RESPONSE SUPPRESSION (IslandState's own request/response)
    // ============================================================
    // IslandState sends `/locraw` itself (see IslandState.requestLocraw())
    // purely to read the JSON back internally -- the response was never
    // meant to be user-visible. Unlike ChatFilter's patterns, this is not
    // gated behind any module/enabled flag: it's not a "hide spam"
    // preference, it's cleanup of our own internal command's echo, so it
    // always applies regardless of ChatFilter's on/off state. Deliberately
    // loose (just checks the "server" field prefix every real locraw reply
    // has -- same sanity check IslandState.handleChat() uses) rather than
    // trying to fully validate JSON shape here.
    //
    // IMPORTANT: matching the shape alone isn't enough to tell "our
    // internal request" apart from the user typing /locraw themselves --
    // both produce an identical-looking response. IslandState tracks a
    // short time window around when IT sent the command
    // (consumeExpectedLocrawResponse()) and only THAT response gets
    // cancelled here; a manually-typed /locraw falls outside the window
    // and is left alone.
    private static final Pattern LOCRAW_RESPONSE = Pattern.compile("^\\{\"server\":.*}$");

    // true while we're inside a "PAID CHEST REWARDS" ... blank-line block
    private boolean cedclient$inRewardBlock = false;

    /**
     * @return true if this line was reward spam and has been fully handled
     *         (caller should cancel the packet and stop processing it).
     */
    private boolean cedclient$handleRewardSpam(String clean) {
        if (!LootTracker.INSTANCE.isEnabled()) {
            return false;
        }

        if (CHEST_TRACKER.matcher(clean).matches()) {
            return true;
        }

        Matcher rare = RARE_REWARD.matcher(clean);
        if (rare.matches()) {
            LootTracker.INSTANCE.recordRareReward(rare.group(1), rare.group(2), rare.group(3));
            return true;
        }

        if (REWARD_HEADER.matcher(clean).matches()) {
            cedclient$inRewardBlock = true;
            LootTracker.INSTANCE.recordChestOpened();
            return true;
        }

        if (cedclient$inRewardBlock) {
            if (clean.isEmpty()) {
                cedclient$inRewardBlock = false; // blank line closes the block
            } else {
                LootTracker.INSTANCE.recordDrop(clean);
            }
            return true;
        }

        // Standalone blank line (spacer before a block starts). NOTE: this
        // suppresses every blank system-chat line, since Hypixel only seems
        // to use them as spacers around these blocks and there's no way to
        // tell in advance that a blank line is about to start one.
        return clean.isEmpty();
    }

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
        ChatHide.pending = false;
        Component content = packet.content();
        if (content == null) return;

        if (!packet.overlay()) {
            new ChatMessageEvent(content.getString()).post();
        }

        // Posted above first so IslandState.handleChat() still gets to parse
        // it. Only cancelled (hidden) if IslandState confirms this is the
        // response to ITS OWN request -- consumeExpectedLocrawResponse()
        // returns false for a manually-typed /locraw, so that case falls
        // through and reaches chat/log normally like any other command.
        if (LOCRAW_RESPONSE.matcher(content.getString().trim()).matches()
                && IslandState.INSTANCE.consumeExpectedLocrawResponse()) {
            ci.cancel();
            return;
        }

        String stripped = content.getString().replaceAll("§.", "").trim();
        // NOTE: reward-spam and ChatFilter hiding no longer call ci.cancel() -- they set
        // ChatHide.pending and ChatComponentMixin drops the line at display time instead, so
        // other mods hooking handleSystemChat (NoammAddons' key/door detection) still see it.
        if (cedclient$handleRewardSpam(stripped)) {
            ChatHide.pending = true;
            return;
        }

        if (ChatFilter.INSTANCE.shouldHide(content.getString())) {
            // Hiding the line skips vanilla's own chat-log call inside
            // ChatComponent.addMessage() (that's where the "[CHAT] ..." log
            // lines come from), since addMessage() gets cancelled. Log it
            // ourselves first so filtered lines still show up in the log
            // file -- they just won't render in-game.
            System.out.println(content.getString());
            if (packet.overlay()) {
                // Action-bar text never goes through ChatComponent.addMessage, so the
                // display-time hide below can't catch it -- cancel it the old way.
                ci.cancel();
            } else {
                ChatHide.pending = true;
            }
        }
    }

    @Inject(method = "handleSystemChat", at = @At("TAIL"))
    private void cedclient$clearChatHide(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        ChatHide.pending = false;
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

    // ============================================================
    // SUBTITLE HANDLER (posts SubtitleEvent -- Rift Dance Room "Move!" etc.)
    // ============================================================
    // NOTE: setSubtitleText is the Mojmap handler name and text() the record
    // accessor on ClientboundSetSubtitleTextPacket. If either fails to resolve,
    // Navigate > Declaration on the packet / ClientPacketListener in IntelliJ.
    @Inject(method = "setSubtitleText", at = @At("HEAD"))
    private void cedclient$onSetSubtitleText(ClientboundSetSubtitleTextPacket packet, CallbackInfo ci) {
        new SubtitleEvent(packet.text().getString()).post();
    }

    // ============================================================
    // TITLE HANDLER (posts TitleEvent -- Rift Dance Room "Punch!" etc.)
    // ============================================================
    @Inject(method = "setTitleText", at = @At("HEAD"))
    private void cedclient$onSetTitleText(ClientboundSetTitleTextPacket packet, CallbackInfo ci) {
        new TitleEvent(packet.text().getString()).post();
    }
}