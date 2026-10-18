//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.util;

import net.minecraft.client.Minecraft;

public class AngleUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public static float get360RotationYaw(float yaw) {
        return (yaw % 360 + 360) % 360;
    }

    public static float get360RotationYaw() {
        if (mc.player == null) return 0;
        return get360RotationYaw(mc.player.getYRot());
    }

    public static float normalizeAngle(float angle) {
        while (angle > 180) angle -= 360;
        while (angle <= -180) angle += 360;
        return angle;
    }

    public static float clockwiseDifference(float initialYaw360, float targetYaw360) {
        return get360RotationYaw(targetYaw360 - initialYaw360);
    }

    public static float antiClockwiseDifference(float initialYaw360, float targetYaw360) {
        return get360RotationYaw(initialYaw360 - targetYaw360);
    }

    public static float smallestAngleDifference(float initialYaw360, float targetYaw360) {
        return Math.min(clockwiseDifference(initialYaw360, targetYaw360), antiClockwiseDifference(initialYaw360, targetYaw360));
    }

    public static float getClosestDiagonal() {
        return getClosestDiagonal(get360RotationYaw());
    }

    public static float getClosestDiagonal(float yaw) {
        float y = get360RotationYaw(yaw);
        if (y < 90 && y > 0) return 45;
        if (y < 180) return 135f;
        if (y < 270) return 225f;
        return 315f;
    }

    public static float getClosest45(float inputAngle) {
        float normalizedAngle = (inputAngle % 360 + 360) % 360;
        float remainder = normalizedAngle % 45;
        if (remainder <= 22.5) return (float) (Math.floor(normalizedAngle / 45) * 45);
        return (float) (Math.ceil(normalizedAngle / 45) * 45);
    }

    public static float getClosest() {
        return getClosest(get360RotationYaw());
    }

    public static float getClosest(float yaw) {
        float y = get360RotationYaw(yaw);
        if (y < 45 || y > 315) return 0f;
        if (y < 135) return 90f;
        if (y < 225) return 180f;
        return 270f;
    }
}
//#endif
