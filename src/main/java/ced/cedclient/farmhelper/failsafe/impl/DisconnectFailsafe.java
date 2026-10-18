//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; just turns the macro off (no auto-reconnect). See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.handler.MacroHandler;

public class DisconnectFailsafe extends Failsafe {
    private static DisconnectFailsafe instance;

    public static DisconnectFailsafe getInstance() {
        if (instance == null) instance = new DisconnectFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 1; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.DISCONNECT; }

    @Override
    public void onDisconnectDetection() {
        // No world left to warn in: just make sure nothing keeps running into the next session.
        MacroHandler.getInstance().disableMacro();
    }
}
//#endif
