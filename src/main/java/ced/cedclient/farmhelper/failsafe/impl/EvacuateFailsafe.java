/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; stops instead of evacuating. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.GameStateHandler;

public class EvacuateFailsafe extends Failsafe {
    private static EvacuateFailsafe instance;

    public static EvacuateFailsafe getInstance() {
        if (instance == null) instance = new EvacuateFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 1; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.EVACUATE; }

    @Override
    public void onTickDetection() {
        GameStateHandler.getInstance().getServerClosingSeconds().ifPresent(seconds -> {
            if (seconds < 30) FailsafeManager.getInstance().possibleDetection(this);
        });
    }

    @Override
    public void onChatDetection(String message) {
        if (message.startsWith("You can't use this when the server is about to")) FailsafeManager.getInstance().possibleDetection(this);
    }
}
