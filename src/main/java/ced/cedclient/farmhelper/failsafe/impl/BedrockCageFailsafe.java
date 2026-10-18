//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient (bedrock counted over a few ticks instead of a
 * background thread); detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.GameStateHandler;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.util.BlockUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.Relative;

public class BedrockCageFailsafe extends Failsafe {
    private static BedrockCageFailsafe instance;
    /** Ticks left to look for a bedrock cage after a high teleport. */
    private int checksLeft = 0;

    public static BedrockCageFailsafe getInstance() {
        if (instance == null) instance = new BedrockCageFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 1; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.BEDROCK_CAGE_CHECK; }

    @Override
    public void onReceivedPacketDetection(Packet<?> packet) {
        if (MacroHandler.getInstance().isTeleporting()) return;
        if (!(packet instanceof ClientboundPlayerPositionPacket pos)) return;
        if (mc.player.blockPosition().getY() < 66) return;
        double y = pos.change().position().y + (pos.relatives().contains(Relative.Y) ? mc.player.getY() : 0);
        if (y > 80) checksLeft = 8;
    }

    @Override
    public void onTickDetection() {
        if (checksLeft <= 0) return;
        checksLeft--;
        if (GameStateHandler.getInstance().getLocation() != GameStateHandler.Location.GARDEN) {
            checksLeft = 0;
            return;
        }
        if (BlockUtils.bedrockCount() > 3) {
            checksLeft = 0;
            FailsafeManager.getInstance().possibleDetection(this);
        }
    }

    @Override
    public void resetStates() {
        checksLeft = 0;
    }
}
//#endif
