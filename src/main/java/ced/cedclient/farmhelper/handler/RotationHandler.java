/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; client-side easing only (the crop macros never
 * rotate server-side); driven every frame from FarmFrameMixin. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.handler;

import ced.cedclient.farmhelper.util.AngleUtils;
import ced.cedclient.farmhelper.util.Rotation;
import net.minecraft.client.Minecraft;

import java.util.Optional;
import java.util.Random;
import java.util.function.Function;

public class RotationHandler {
    private static RotationHandler instance;
    private final Minecraft mc = Minecraft.getInstance();
    private final Rotation startRotation = new Rotation(0f, 0f);
    private final Rotation targetRotation = new Rotation(0f, 0f);
    private final Random random = new Random();
    private boolean rotating;
    private long startTime;
    private long endTime;
    private Configuration configuration;
    private float easingModifier = 0;

    public static RotationHandler getInstance() {
        if (instance == null) instance = new RotationHandler();
        return instance;
    }

    /** One planned turn: from where the player looks now to [to] in about [time] ms. */
    public static class Configuration {
        private final Rotation to;
        private final long time;
        private final Optional<Runnable> callback;
        private boolean easeOutBack = false;

        public Configuration(Rotation to, long time, Runnable callback) {
            this.to = to;
            this.time = time;
            this.callback = Optional.ofNullable(callback);
        }

        public Configuration easeOutBack(boolean value) {
            this.easeOutBack = value;
            return this;
        }
    }

    public boolean isRotating() {
        return rotating;
    }

    public void easeTo(Configuration configuration) {
        if (mc.player == null) return;
        this.configuration = configuration;
        easingModifier = random.nextFloat() * 0.5f - 0.25f;
        startTime = System.currentTimeMillis();
        startRotation.setRotation(new Rotation(mc.player.getYRot(), mc.player.getXRot()));
        Rotation neededChange = getNeededChange(startRotation, configuration.to);
        targetRotation.setYaw(startRotation.getYaw() + neededChange.getYaw());
        targetRotation.setPitch(startRotation.getPitch() + neededChange.getPitch());

        float absYaw = Math.max(Math.abs(neededChange.getYaw()), 1);
        float absPitch = Math.max(Math.abs(neededChange.getPitch()), 1);
        float pythagoras = (float) Math.sqrt(absYaw * absYaw + absPitch * absPitch);
        float time = getTime(pythagoras, configuration.time);
        endTime = (long) (System.currentTimeMillis() + Math.max(time, 50 + Math.random() * 100));
        rotating = true;
    }

    private float getTime(float pythagoras, float time) {
        if (pythagoras < 25) return (long) (time * 0.65);
        if (pythagoras < 45) return (long) (time * 0.77);
        if (pythagoras < 80) return (long) (time * 0.9);
        if (pythagoras > 100) return (long) (time * 1.1);
        return (long) time;
    }

    public Rotation getNeededChange(Rotation startRot, Rotation endRot) {
        float yawDiff = AngleUtils.normalizeAngle(AngleUtils.normalizeAngle(endRot.getYaw()) - AngleUtils.normalizeAngle(startRot.getYaw()));
        return new Rotation(yawDiff, endRot.getPitch() - startRot.getPitch());
    }

    public void reset() {
        rotating = false;
        configuration = null;
        startTime = 0;
        endTime = 0;
    }

    private float interpolate(float start, float end, Function<Float, Float> function) {
        float t = (float) (System.currentTimeMillis() - startTime) / (endTime - startTime);
        return (end - start) * function.apply(Math.min(t, 1f)) + start;
    }

    private float easeOutQuart(float x) {
        return (float) (1 - Math.pow(1 - x, 4));
    }

    private float easeOutExpo(float x) {
        return x == 1 ? 1 : 1 - (float) Math.pow(2, -10 * x);
    }

    private float easeOutBack(float x) {
        float c1 = 1.70158f + easingModifier;
        float c3 = c1 + 1;
        return 1 + c3 * (float) Math.pow(x - 1, 3) + c1 * (float) Math.pow(x - 1, 2);
    }

    /** Every frame (FarmFrameMixin). */
    public void onFrame() {
        if (!rotating || configuration == null || mc.player == null) return;
        if (mc.screen != null) {
            endTime = System.currentTimeMillis() + configuration.time;
            return;
        }
        if (System.currentTimeMillis() >= endTime) {
            if (configuration.callback.isPresent()) {
                configuration.callback.get().run();
            } else {
                mc.player.setYRot(targetRotation.getYaw());
                mc.player.setXRot(targetRotation.getPitch());
                reset();
            }
            return;
        }
        boolean back = configuration.easeOutBack;
        mc.player.setYRot(interpolate(startRotation.getYaw(), targetRotation.getYaw(), back ? this::easeOutBack : this::easeOutExpo));
        mc.player.setXRot(interpolate(startRotation.getPitch(), targetRotation.getPitch(), back ? this::easeOutBack : this::easeOutQuart));
    }
}
