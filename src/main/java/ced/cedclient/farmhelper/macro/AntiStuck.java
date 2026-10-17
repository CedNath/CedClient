/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.macro;

import ced.cedclient.farmhelper.util.Clock;
import ced.cedclient.farmhelper.util.KeyBindUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * Gets the player unstuck when a row didn't move them (dirt, half-broken melons, lag back).
 * shortcut: a short step back + jump instead of the original's block-aware escape routine; port the full routine if
 * players still get stuck on odd farm layouts.
 */
public class AntiStuck {
    private static AntiStuck instance;
    private final Minecraft mc = Minecraft.getInstance();
    private final Clock runFor = new Clock();
    private boolean running = false;
    private int lagBackCounter = 0;

    public static AntiStuck getInstance() {
        if (instance == null) instance = new AntiStuck();
        return instance;
    }

    public boolean isRunning() { return running; }
    public int getLagBackCounter() { return lagBackCounter; }
    public void setLagBackCounter(int value) { lagBackCounter = value; }
    public void setDirectionBlockPos(BlockPos pos) {}
    public void setIntersectingBlockPos(BlockPos pos) {}

    public void start() {
        if (running) return;
        running = true;
        runFor.schedule(250 + (long) (Math.random() * 150));
    }

    public void stop() {
        running = false;
        runFor.reset();
        KeyBindUtils.stopMovement();
    }

    public void onTick() {
        if (!running || mc.player == null) return;
        if (runFor.passed()) {
            stop();
            return;
        }
        // Only jump when actually pressed against a block; otherwise a plain step back (jumping every time looked off).
        KeyBindUtils.holdThese(mc.options.keyDown, mc.player.horizontalCollision ? mc.options.keyJump : null);
    }
}
