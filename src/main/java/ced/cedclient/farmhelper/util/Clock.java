/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.util;

/** Countdown: schedule(ms), then passed() once it ran out. */
public class Clock {
    private long remainingTime;
    private boolean paused;
    private boolean scheduled;
    private long endTime;

    public void schedule(long milliseconds) {
        this.endTime = System.currentTimeMillis() + milliseconds;
        this.remainingTime = milliseconds;
        this.scheduled = true;
        this.paused = false;
    }

    public void schedule(double milliseconds) {
        schedule((long) milliseconds);
    }

    public long getRemainingTime() {
        if (paused) return remainingTime;
        return Math.max(0, endTime - System.currentTimeMillis());
    }

    public boolean passed() {
        return System.currentTimeMillis() >= endTime;
    }

    public boolean isScheduled() {
        return scheduled;
    }

    public void pause() {
        if (scheduled && !paused) {
            remainingTime = endTime - System.currentTimeMillis();
            paused = true;
        }
    }

    public void resume() {
        if (scheduled && paused) {
            endTime = System.currentTimeMillis() + remainingTime;
            paused = false;
        }
    }

    public void reset() {
        scheduled = false;
        paused = false;
        endTime = 0;
        remainingTime = 0;
    }
}
