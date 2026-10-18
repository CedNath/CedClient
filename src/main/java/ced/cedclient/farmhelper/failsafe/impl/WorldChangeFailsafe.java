//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; detection only (no auto-warp back). See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.GameStateHandler;

public class WorldChangeFailsafe extends Failsafe {
    private static WorldChangeFailsafe instance;

    public static WorldChangeFailsafe getInstance() {
        if (instance == null) instance = new WorldChangeFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 2; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.WORLD_CHANGE_CHECK; }

    @Override
    public void onTickDetection() {
        if (FailsafeManager.getInstance().emergencyQueue.contains(this)) return;
        if (GameStateHandler.getInstance().getLocation() == GameStateHandler.Location.LIMBO) {
            FailsafeManager.getInstance().possibleDetection(this);
        }
    }
}
//#endif
