//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient (velocity in blocks / tick); detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.util.LogUtils;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;

public class KnockbackFailsafe extends Failsafe {
    private static KnockbackFailsafe instance;

    public static KnockbackFailsafe getInstance() {
        if (instance == null) instance = new KnockbackFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 4; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.KNOCKBACK_CHECK; }

    @Override
    public void onReceivedPacketDetection(Packet<?> packet) {
        if (MacroHandler.getInstance().isTeleporting()) return;
        if (!(packet instanceof ClientboundSetEntityMotionPacket motion)) return;
        if (motion.id() != mc.player.getId()) return;
        if (motion.movement().y < FarmConfig.verticalKnockbackThreshold()) return;
        LogUtils.sendWarning("[Failsafe] Knockback detected! MotionY: " + String.format("%.2f", motion.movement().y));
        FailsafeManager.getInstance().possibleDetection(this);
    }
}
//#endif
