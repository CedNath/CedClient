/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; values come from the FarmHelper module. See NOTICE.md.
 */
package ced.cedclient.farmhelper;

import ced.cedclient.features.impl.funqol.FarmHelper;

/** The parts of the original FarmHelperConfig the port uses, read from CedClient's FarmHelper module settings. */
public final class FarmConfig {
    private FarmConfig() {}

    public enum CropEnum {
        NONE("None"),
        CARROT("Carrot"),
        NETHER_WART("Nether Wart"),
        POTATO("Potato"),
        WHEAT("Wheat"),
        SUGAR_CANE("Sugar Cane"),
        MELON("Melon"),
        PUMPKIN("Pumpkin"),
        PUMPKIN_MELON_UNKNOWN("Pumpkin/Melon"),
        CACTUS("Cactus"),
        COCOA_BEANS("Cocoa Beans"),
        MUSHROOM("Mushroom"),
        MUSHROOM_ROTATE("Mushroom"),
        SUNFLOWER("Sunflower"),
        MOONFLOWER("Moonflower"),
        ROSE("Wild Rose");

        public final String label;

        CropEnum(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    public static long getRandomTimeBetweenChangingRows() { return FarmHelper.randomRowDelay(); }
    public static long getRandomRotationTime() { return FarmHelper.randomRotationTime(); }
    public static long getRandomRewarpDelay() { return FarmHelper.randomRewarpDelay(); }

    public static boolean alwaysHoldW() { return FarmHelper.INSTANCE.getAlwaysHoldW().getValue(); }
    public static boolean rotateAfterDrop() { return FarmHelper.INSTANCE.getRotateAfterDrop().getValue(); }
    public static boolean rotateAfterWarped() { return FarmHelper.INSTANCE.getRotateAfterWarped().getValue(); }
    public static boolean dontFixAfterWarping() { return FarmHelper.INSTANCE.getDontFixAfterWarping().getValue(); }
    public static boolean holdLeftClickWhenChangingRow() { return FarmHelper.INSTANCE.getHoldClickOnRowChange().getValue(); }
    public static boolean autoSwitchTool() { return FarmHelper.INSTANCE.getAutoSwitchCrop().getValue(); }

    public static long failsafeStopDelay() { return FarmHelper.INSTANCE.getFailsafeDelay().getValue().longValue(); }
    public static float pitchSensitivity() { return FarmHelper.INSTANCE.getRotationSensitivity().getValue().floatValue(); }
    public static float teleportDistanceThreshold() { return FarmHelper.INSTANCE.getTeleportDistance().getValue().floatValue(); }
    public static double verticalKnockbackThreshold() { return FarmHelper.INSTANCE.getKnockbackThreshold().getValue(); }
    public static boolean enableBpsCheck() { return FarmHelper.INSTANCE.getBpsCheck().getValue(); }
    public static float minBpsThreshold() { return FarmHelper.INSTANCE.getMinBps().getValue().floatValue(); }
    public static boolean enableFailsafeSound() { return FarmHelper.INSTANCE.getFailsafeSound().getValue(); }
    public static boolean pauseOnGuestArrival() { return FarmHelper.INSTANCE.getPauseOnGuest().getValue(); }

    /** Time the server has to "turn / move you back" before a check counts (original default). */
    public static final int DETECTION_TIME_WINDOW = 500;
}
