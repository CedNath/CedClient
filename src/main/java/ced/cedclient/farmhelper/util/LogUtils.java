//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient (chat via CedClient's prefix, no webhooks). See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.util;

import ced.cedclient.features.impl.funqol.FarmHelper;
import ced.cedclient.utils.Debug;

public class LogUtils {
    public static void sendSuccess(String msg) {
        FarmHelper.msg("§a" + msg);
    }

    public static void sendWarning(String msg) {
        FarmHelper.msg("§e" + msg);
    }

    public static void sendError(String msg) {
        FarmHelper.msg("§c" + msg);
    }

    public static void sendFailsafeMessage(String msg) {
        FarmHelper.msg("§d§l" + msg);
    }

    /** Only with CedClient's Debug on (keeps chat clean). */
    public static void sendDebug(String msg) {
        if (Debug.INSTANCE.getEnabled()) FarmHelper.msg("§7" + msg);
    }

    public static String formatTime(long millis) {
        long s = millis / 1000;
        return s / 60 + ":" + String.format("%02d", s % 60) + "." + (millis % 1000) / 100;
    }
}
//#endif
