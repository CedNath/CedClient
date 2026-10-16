/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.util;

/** Stopwatch: schedule() starts it, hasPassed(ms) / getElapsedTime() read it, pause / resume stop the clock. */
public class Timer {
    public long startedAt = 0;
    public long pausedAt = 0;
    public boolean paused = false;

    public boolean hasPassed(long ms) {
        return System.currentTimeMillis() - startedAt > ms;
    }

    public void reset() {
        startedAt = 0;
        pausedAt = 0;
        paused = false;
    }

    public void schedule() {
        startedAt = System.currentTimeMillis();
        pausedAt = 0;
        paused = false;
    }

    public void pause() {
        if (startedAt != 0 && !paused) {
            pausedAt = System.currentTimeMillis();
            paused = true;
        }
    }

    public void resume() {
        if (startedAt != 0 && paused) {
            startedAt += System.currentTimeMillis() - pausedAt;
            paused = false;
        }
    }

    public boolean isScheduled() {
        return startedAt != 0;
    }

    public long getElapsedTime() {
        if (startedAt == 0) return 0;
        if (paused) return pausedAt - startedAt;
        return System.currentTimeMillis() - startedAt;
    }
}
