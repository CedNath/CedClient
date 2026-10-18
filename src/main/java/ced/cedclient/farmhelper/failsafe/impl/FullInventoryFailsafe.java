//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.util.Clock;
import ced.cedclient.farmhelper.util.PlayerUtils;

public class FullInventoryFailsafe extends Failsafe {
    private static FullInventoryFailsafe instance;
    private final Clock clock = new Clock();

    public static FullInventoryFailsafe getInstance() {
        if (instance == null) instance = new FullInventoryFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 3; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.FULL_INVENTORY; }

    @Override
    public void onTickDetection() {
        if (FailsafeManager.getInstance().isHadEmergency()) return;
        if (PlayerUtils.isInventoryFull()) {
            if (clock.isScheduled() && clock.passed()) FailsafeManager.getInstance().possibleDetection(this);
            if (!clock.isScheduled()) clock.schedule(555);
        } else {
            clock.reset();
        }
    }

    @Override
    public void resetStates() {
        clock.reset();
    }
}
//#endif
