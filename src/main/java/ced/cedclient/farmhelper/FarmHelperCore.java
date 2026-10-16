/*
 * Part of the FarmHelper port (https://github.com/JellyLabScripts/FarmHelper, by JellyLab and contributors).
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: replaces the original's Forge event subscriptions with one entry point for CedClient. See NOTICE.md.
 */
package ced.cedclient.farmhelper;

import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.GameStateHandler;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.handler.RotationHandler;
import ced.cedclient.farmhelper.util.Clock;
import ced.cedclient.farmhelper.util.LogUtils;
import ced.cedclient.features.impl.funqol.FarmHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;

/** Where CedClient's events enter the port (tick, chat, packets, frames, disconnect). All on the client thread. */
public final class FarmHelperCore {
    private static final Minecraft mc = Minecraft.getInstance();
    private static boolean pausedForGuest = false;
    private static final Clock guestGoneFor = new Clock();

    private FarmHelperCore() {}

    private static boolean active() {
        return FarmHelper.INSTANCE.isEnabled();
    }

    public static void onTick() {
        if (!active()) return;
        GameStateHandler.getInstance().onTick();
        FailsafeManager.getInstance().onTick();
        MacroHandler.getInstance().onTick();
        tickGuest();
    }

    public static void onChat(String message) {
        if (!active()) return;
        GameStateHandler.getInstance().onChat(message);
        MacroHandler.getInstance().onChat(message);
        FailsafeManager.getInstance().onChat(message);
        if (!message.contains(":") && message.contains("is visiting Your Garden") && FarmConfig.pauseOnGuestArrival()
                && MacroHandler.getInstance().isMacroToggled() && !MacroHandler.getInstance().isCurrentMacroPaused()) {
            LogUtils.sendWarning("Paused the macro because of a guest visit; it resumes once they leave.");
            MacroHandler.getInstance().pauseMacro();
            pausedForGuest = true;
            guestGoneFor.reset();
        }
    }

    private static void tickGuest() {
        if (!pausedForGuest) return;
        if (!MacroHandler.getInstance().isMacroToggled()) {
            pausedForGuest = false;
            return;
        }
        if (GameStateHandler.getInstance().isGuestInGarden()) {
            guestGoneFor.reset();
            return;
        }
        if (!guestGoneFor.isScheduled()) guestGoneFor.schedule(5_000);
        else if (guestGoneFor.passed()) {
            pausedForGuest = false;
            guestGoneFor.reset();
            LogUtils.sendSuccess("Guest left, resuming.");
            MacroHandler.getInstance().resumeMacro();
        }
    }

    /** Before the packet is applied (FarmPacketMixin queues this ahead of vanilla's handler). */
    public static void onPacket(Packet<?> packet) {
        if (!active() || mc.player == null || mc.level == null) return;
        if (packet instanceof ClientboundBlockUpdatePacket block) {
            FailsafeManager.getInstance().onBlockChange(block.getPos(), mc.level.getBlockState(block.getPos()), block.getBlockState());
        } else if (packet instanceof ClientboundSectionBlocksUpdatePacket section) {
            section.runUpdates((pos, state) -> FailsafeManager.getInstance().onBlockChange(pos.immutable(), mc.level.getBlockState(pos), state));
        }
        FailsafeManager.getInstance().onPacket(packet);
    }

    public static void onFrame() {
        if (!active()) return;
        RotationHandler.getInstance().onFrame();
    }

    public static void onDisconnect() {
        FailsafeManager.getInstance().onDisconnect();
        if (MacroHandler.getInstance().isMacroToggled()) MacroHandler.getInstance().disableMacro();
    }
}
