/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.util.List;

public class BadEffectsFailsafe extends Failsafe {
    private static BadEffectsFailsafe instance;
    private static final List<Holder<MobEffect>> BAD = List.of(
            MobEffects.BLINDNESS, MobEffects.SLOWNESS, MobEffects.MINING_FATIGUE, MobEffects.NAUSEA,
            MobEffects.POISON, MobEffects.WITHER, MobEffects.LEVITATION, MobEffects.DARKNESS);

    public static BadEffectsFailsafe getInstance() {
        if (instance == null) instance = new BadEffectsFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 1; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.BAD_EFFECTS_CHECK; }

    @Override
    public void onTickDetection() {
        if (FailsafeManager.getInstance().isHadEmergency()) return;
        for (Holder<MobEffect> effect : BAD) {
            if (mc.player.hasEffect(effect)) {
                FailsafeManager.getInstance().possibleDetection(this);
                return;
            }
        }
    }
}
