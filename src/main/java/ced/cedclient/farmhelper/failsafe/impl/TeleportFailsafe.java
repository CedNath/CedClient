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
import ced.cedclient.farmhelper.macro.AbstractMacro;
import ced.cedclient.farmhelper.macro.AntiStuck;
import ced.cedclient.farmhelper.util.BlockUtils;
import ced.cedclient.farmhelper.util.Clock;
import ced.cedclient.farmhelper.util.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Iterator;

public class TeleportFailsafe extends Failsafe {
    private static TeleportFailsafe instance;
    private final Clock triggerCheck = new Clock();
    private Vec3 originalPosition = null;
    /** Recent block positions + macro state, to tell a lag back into the previous row from a teleport check. */
    private final ArrayDeque<Object[]> lastWalkedPositions = new ArrayDeque<>();

    public static TeleportFailsafe getInstance() {
        if (instance == null) instance = new TeleportFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 5; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.TELEPORT_CHECK; }

    @Override
    public void onReceivedPacketDetection(Packet<?> packet) {
        if (MacroHandler.getInstance().isTeleporting()) return;
        if (!(packet instanceof ClientboundPlayerPositionPacket pos)) return;
        if (AntiStuck.getInstance().isRunning()) return;
        Vec3 current = mc.player.position();
        Vec3 change = pos.change().position();
        Vec3 target = new Vec3(
                change.x + (pos.relatives().contains(Relative.X) ? current.x : 0),
                change.y + (pos.relatives().contains(Relative.Y) ? current.y : 0),
                change.z + (pos.relatives().contains(Relative.Z) ? current.z : 0));
        BlockPos targetBlock = BlockPos.containing(target);
        if (current.y < 0) return;

        AbstractMacro.State walkedState = null;
        boolean walkedThere = false;
        Iterator<Object[]> it = lastWalkedPositions.descendingIterator();
        while (it.hasNext()) {
            Object[] entry = it.next();
            if (entry[0].equals(targetBlock)) {
                walkedThere = true;
                walkedState = (AbstractMacro.State) entry[1];
                break;
            }
        }
        if (walkedThere) {
            if (target.distanceTo(current) < 1) return; // AntiStuck territory
            AbstractMacro.State currentState = MacroHandler.getInstance().getCurrentMacro().map(AbstractMacro::getCurrentState).orElse(null);
            LowerAvgBpsFailsafe.getInstance().resetStates();
            if (currentState == null || currentState != walkedState) {
                LogUtils.sendWarning("[Failsafe] You got lag backed into the previous row! Fixing state");
                AbstractMacro.State fixed = walkedState;
                MacroHandler.getInstance().getCurrentMacro().ifPresent(macro -> {
                    macro.setCurrentState(fixed);
                    long delay = (long) (1_500 + Math.random() * 1_000);
                    macro.setBreakTime(delay, Math.max(500, delay - 500));
                });
                return;
            }
            LogUtils.sendWarning("[Failsafe] You got lag backed! Not reacting");
            return;
        }
        if (target.y >= 90 || BlockUtils.bedrockCount() > 2) return; // bedrock cage check handles that

        double dx = target.x - current.x;
        double dy = target.y - current.y;
        double dz = target.z - current.z;
        Vec3 motion = mc.player.getDeltaMovement();
        if (motion.x * dx + motion.z * dz > 0 && current.y <= target.y && Math.abs(dy) < 1 && (Math.abs(dx) < 1 || Math.abs(dz) < 1)) {
            LogUtils.sendWarning("[Failsafe] Teleported in the same direction you were moving. DO NOT REACT TO THIS!");
            return;
        }
        double distance = current.distanceTo(target);
        boolean layerJump = MacroHandler.getInstance().getCurrentMacro().isPresent()
                && Math.abs(target.y) - Math.abs(MacroHandler.getInstance().getCurrentMacro().get().getLayerY()) > 0.8;
        if (distance >= FarmConfig.teleportDistanceThreshold() || layerJump) {
            if (originalPosition == null) originalPosition = current;
            triggerCheck.schedule(FarmConfig.DETECTION_TIME_WINDOW);
        }
    }

    @Override
    public void onTickDetection() {
        if (MacroHandler.getInstance().isTeleporting()) {
            lastWalkedPositions.clear();
            return;
        }
        if (triggerCheck.isScheduled() && triggerCheck.passed()) {
            boolean onlyRotation = FailsafeManager.getInstance().getEmergencyQueue().stream().anyMatch(f -> f.getType() == FailsafeManager.EmergencyType.ROTATION_CHECK);
            if (FailsafeManager.getInstance().getEmergencyQueue().isEmpty() || onlyRotation) evaluateDisplacement();
            else originalPosition = null;
            triggerCheck.reset();
        }
        lastWalkedPositions.add(new Object[]{mc.player.blockPosition(), MacroHandler.getInstance().getCurrentMacro().map(AbstractMacro::getCurrentState).orElse(null)});
        while (lastWalkedPositions.size() > 100) lastWalkedPositions.removeFirst();
    }

    private void evaluateDisplacement() {
        if (originalPosition == null) return;
        if (mc.player.position().distanceTo(originalPosition) > FarmConfig.teleportDistanceThreshold()) {
            FailsafeManager.getInstance().possibleDetection(this);
        } else {
            FailsafeManager.getInstance().emergencyQueue.remove(this);
            if (FailsafeManager.getInstance().emergencyQueue.isEmpty()) {
                LogUtils.sendWarning("[Failsafe] Teleport check was triggered, but the admin teleported you back. DO NOT REACT TO THIS!");
            }
        }
        originalPosition = null;
    }

    @Override
    public void resetStates() {
        triggerCheck.reset();
        originalPosition = null;
        lastWalkedPositions.clear();
    }
}
