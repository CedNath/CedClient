//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.handler.RotationHandler;
import ced.cedclient.farmhelper.macro.AntiStuck;
import ced.cedclient.farmhelper.util.Clock;
import ced.cedclient.farmhelper.util.LogUtils;
import ced.cedclient.farmhelper.util.Rotation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.Relative;

public class RotationFailsafe extends Failsafe {
    private static RotationFailsafe instance;
    private final Clock triggerCheck = new Clock();
    private Rotation rotationBeforeReacting = null;

    public static RotationFailsafe getInstance() {
        if (instance == null) instance = new RotationFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 4; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.ROTATION_CHECK; }

    @Override
    public void onReceivedPacketDetection(Packet<?> packet) {
        if (MacroHandler.getInstance().isTeleporting()) return;
        if (!(packet instanceof ClientboundPlayerPositionPacket pos)) return;
        if (AntiStuck.getInstance().isRunning()) return;
        float playerYaw = mc.player.getYRot();
        float playerPitch = mc.player.getXRot();
        double packetYaw = pos.change().yRot() + (pos.relatives().contains(Relative.Y_ROT) ? playerYaw : 0);
        double packetPitch = pos.change().xRot() + (pos.relatives().contains(Relative.X_ROT) ? playerPitch : 0);
        double yawDiff = Math.abs(packetYaw - playerYaw);
        double pitchDiff = Math.abs(packetPitch - playerPitch);
        if (yawDiff == 360 && pitchDiff == 0) return; // prevents false checks
        if (shouldTriggerCheck(packetYaw, packetPitch) && rotationBeforeReacting == null) {
            rotationBeforeReacting = new Rotation(playerYaw, playerPitch);
        }
        triggerCheck.schedule(FarmConfig.DETECTION_TIME_WINDOW);
    }

    @Override
    public void onTickDetection() {
        if (triggerCheck.isScheduled() && triggerCheck.passed()) {
            if (FailsafeManager.getInstance().getEmergencyQueue().isEmpty()) evaluateRotation();
            else rotationBeforeReacting = null;
            triggerCheck.reset();
        }
    }

    private void evaluateRotation() {
        if (rotationBeforeReacting == null) return;
        if (shouldTriggerCheck(rotationBeforeReacting.getYaw(), rotationBeforeReacting.getPitch())) {
            FailsafeManager.getInstance().possibleDetection(this);
            if (RotationHandler.getInstance().isRotating()) RotationHandler.getInstance().reset();
        } else {
            FailsafeManager.getInstance().emergencyQueue.remove(this);
            if (FailsafeManager.getInstance().emergencyQueue.isEmpty()) {
                LogUtils.sendWarning("[Failsafe] Rotation check was triggered but the admin rotated you back. DO NOT REACT TO THIS!");
            }
        }
        rotationBeforeReacting = null;
    }

    private boolean shouldTriggerCheck(double newYaw, double newPitch) {
        double yawDiff = Math.abs(newYaw - mc.player.getYRot()) % 360;
        double pitchDiff = Math.abs(newPitch - mc.player.getXRot()) % 360;
        double threshold = FarmConfig.pitchSensitivity();
        return yawDiff >= threshold || pitchDiff >= threshold;
    }

    @Override
    public void resetStates() {
        triggerCheck.reset();
        rotationBeforeReacting = null;
    }
}
//#endif
