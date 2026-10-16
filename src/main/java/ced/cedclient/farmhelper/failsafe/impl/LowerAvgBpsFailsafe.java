/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; BPS counted here from crop block updates near
 * the player (the original's BPSTracker); detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.handler.RotationHandler;
import ced.cedclient.farmhelper.util.BlockUtils;
import ced.cedclient.farmhelper.util.Clock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;

public class LowerAvgBpsFailsafe extends Failsafe {
    private static LowerAvgBpsFailsafe instance;
    private static final long WINDOW_MS = 5_000;
    private final ArrayDeque<Long> breaks = new ArrayDeque<>();
    private final Clock clock = new Clock();
    private long farmingSince = 0;
    private long lastTriggered = 0;

    public static LowerAvgBpsFailsafe getInstance() {
        if (instance == null) instance = new LowerAvgBpsFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 6; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.LOWER_AVERAGE_BPS; }

    @Override
    public void onBlockChange(BlockPos pos, BlockState old, BlockState update) {
        if (!BlockUtils.isCrop(old.getBlock()) || update.getBlock() != Blocks.AIR) return;
        if (pos.distSqr(mc.player.blockPosition()) > 36) return;
        breaks.add(System.currentTimeMillis());
    }

    public float getBps() {
        long now = System.currentTimeMillis();
        while (!breaks.isEmpty() && now - breaks.peekFirst() > WINDOW_MS) breaks.removeFirst();
        return breaks.size() / (WINDOW_MS / 1000f);
    }

    private boolean dontCheck() {
        return MacroHandler.getInstance().isTeleporting() || RotationHandler.getInstance().isRotating()
                || MacroHandler.getInstance().isCurrentMacroPaused()
                || System.currentTimeMillis() - farmingSince < 10_000;
    }

    @Override
    public void onTickDetection() {
        if (farmingSince == 0) farmingSince = System.currentTimeMillis();
        float bps = getBps();
        if (!FarmConfig.enableBpsCheck() || dontCheck() || bps >= FarmConfig.minBpsThreshold()) {
            clock.reset();
            return;
        }
        if (!clock.isScheduled()) {
            clock.schedule(4500L + (long) (Math.random() * 1000L));
        } else if (clock.passed()) {
            clock.reset();
            if (System.currentTimeMillis() - lastTriggered < 60_000L) return;
            lastTriggered = System.currentTimeMillis();
            FailsafeManager.getInstance().possibleDetection(this);
        }
    }

    @Override
    public void resetStates() {
        clock.reset();
        breaks.clear();
        farmingSince = 0;
    }
}
