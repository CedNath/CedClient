//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.util;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Holds / releases movement and attack keys for the macro (KeyMapping.setDown, like a held physical key). */
public class KeyBindUtils {
    private static final Minecraft mc = Minecraft.getInstance();

    public static KeyMapping[] allKeys() {
        return new KeyMapping[]{
                mc.options.keyAttack, mc.options.keyUse, mc.options.keyDown, mc.options.keyUp,
                mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump, mc.options.keyShift, mc.options.keySprint,
        };
    }

    private static void realSetKeyBindState(KeyMapping key, boolean pressed) {
        if (key == null) return;
        if (pressed) {
            if (!key.isDown()) key.setDown(true);
        } else if (key.isDown()) {
            key.setDown(false);
        }
    }

    public static void setKeyBindState(KeyMapping key, boolean pressed) {
        if (pressed && mc.screen != null && key != null) {
            realSetKeyBindState(key, false);
            return;
        }
        realSetKeyBindState(key, pressed);
    }

    public static void stopMovement() {
        stopMovement(false);
    }

    public static void stopMovement(boolean ignoreAttack) {
        realSetKeyBindState(mc.options.keyUp, false);
        realSetKeyBindState(mc.options.keyDown, false);
        realSetKeyBindState(mc.options.keyRight, false);
        realSetKeyBindState(mc.options.keyLeft, false);
        if (!ignoreAttack) {
            realSetKeyBindState(mc.options.keyAttack, false);
            realSetKeyBindState(mc.options.keyUse, false);
        }
        realSetKeyBindState(mc.options.keyShift, false);
        realSetKeyBindState(mc.options.keyJump, false);
        realSetKeyBindState(mc.options.keySprint, false);
    }

    public static void holdThese(KeyMapping... keys) {
        releaseAllExcept(keys);
        for (KeyMapping key : keys) {
            if (key != null) realSetKeyBindState(key, true);
        }
    }

    public static void releaseAllExcept(KeyMapping... keys) {
        for (KeyMapping key : allKeys()) {
            if (key != null && !contains(keys, key) && key.isDown()) realSetKeyBindState(key, false);
        }
    }

    public static boolean contains(KeyMapping[] keys, KeyMapping key) {
        for (KeyMapping k : keys) {
            if (k == key) return true;
        }
        return false;
    }

    public static KeyMapping[] getHoldingKeybinds() {
        List<KeyMapping> held = new ArrayList<>();
        for (KeyMapping key : allKeys()) {
            if (key != null && key.isDown()) held.add(key);
        }
        return held.toArray(new KeyMapping[0]);
    }
}
//#endif
