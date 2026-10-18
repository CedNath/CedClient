//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; no pest / visitor / composter / scheduler
 * features, no webhooks or analytics. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.handler;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.failsafe.impl.WorldChangeFailsafe;
import ced.cedclient.farmhelper.macro.AbstractMacro;
import ced.cedclient.farmhelper.macro.AntiStuck;
import ced.cedclient.farmhelper.macro.impl.LaneMacro;
import ced.cedclient.farmhelper.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.util.Optional;

public class MacroHandler {
    private static MacroHandler instance;
    private final Minecraft mc = Minecraft.getInstance();
    private final Timer macroingTimer = new Timer();
    private Optional<AbstractMacro> currentMacro = Optional.empty();
    private boolean isMacroToggled = false;
    private boolean startingUp = false;
    private final Clock startDelay = new Clock();
    private FarmConfig.CropEnum crop = FarmConfig.CropEnum.NONE;
    private boolean resume = false;
    private boolean screenWasOpen = false;
    private Optional<BlockPos> beforeTeleportationPos = Optional.empty();
    private final Clock afterRewarpDelay = new Clock();
    private boolean rewarpTeleport = false;
    private long lastTpTry = 0;

    public static MacroHandler getInstance() {
        if (instance == null) instance = new MacroHandler();
        return instance;
    }

    public Timer getMacroingTimer() { return macroingTimer; }
    public Optional<AbstractMacro> getCurrentMacro() { return currentMacro; }
    public boolean isMacroToggled() { return isMacroToggled; }
    public FarmConfig.CropEnum getCrop() { return crop; }
    public void setCrop(FarmConfig.CropEnum crop) { this.crop = crop; }
    public boolean isResume() { return resume; }
    public long getLastTpTry() { return lastTpTry; }

    public boolean isCurrentMacroEnabled() {
        return currentMacro.isPresent() && currentMacro.get().isEnabledAndNoFeature();
    }

    public boolean isCurrentMacroPaused() {
        return isMacroToggled && currentMacro.isPresent() && currentMacro.get().isPaused();
    }

    public boolean isTeleporting() {
        return currentMacro.isPresent() && (currentMacro.get().getRewarpState() != AbstractMacro.RewarpState.NONE || !afterRewarpDelay.passed());
    }

    // CedClient: one generic macro driven by Crop Config (lane keys + angles) instead of the farm-layout presets.
    private final AbstractMacro laneMacro = new LaneMacro();

    public AbstractMacro getMacro() {
        return laneMacro;
    }

    public void toggleMacro() {
        if (isMacroToggled) {
            disableMacro();
            return;
        }
        if (FailsafeManager.getInstance().isHadEmergency()) {
            if (FailsafeManager.getInstance().triggeredFailsafe.isPresent()) FailsafeManager.getInstance().stopFailsafes();
            FailsafeManager.getInstance().setHadEmergency(false);
            LogUtils.sendWarning("Farm manually and DO NOT restart the macro too soon! The staff might still be spectating you for a while!");
            return;
        }
        enableMacro();
    }

    public void enableMacro() {
        if (mc.player == null) return;
        if (!GameStateHandler.getInstance().inGarden()) {
            LogUtils.sendError("You must be in the garden to start the macro!");
            return;
        }
        currentMacro = Optional.of(getMacro());
        PlayerUtils.closeScreen();
        LogUtils.sendSuccess("Macro enabled!");
        if (!macroingTimer.isScheduled()) {
            macroingTimer.schedule();
            macroingTimer.pause();
        }
        isMacroToggled = true;
        enableCurrentMacro();
        currentMacro.ifPresent(cm -> cm.getCheckOnSpawnClock().reset());
    }

    public void disableMacro() {
        isMacroToggled = false;
        LogUtils.sendSuccess("Macro disabled!");
        currentMacro.ifPresent(m -> {
            m.setSavedState(Optional.empty());
            m.getRotation().reset();
        });
        macroingTimer.pause();
        crop = FarmConfig.CropEnum.NONE;
        FailsafeManager.getInstance().resetAfterMacroDisable();
        AntiStuck.getInstance().stop();
        disableCurrentMacro();
        currentMacro = Optional.empty();
        beforeTeleportationPos = Optional.empty();
        startingUp = false;
    }

    public void pauseMacro() {
        currentMacro.ifPresent(cm -> {
            KeyBindUtils.stopMovement();
            if (cm.isPaused()) return;
            cm.saveState();
            cm.onDisable();
            beforeTeleportationPos = Optional.empty();
            macroingTimer.pause();
        });
    }

    public void resumeMacro() {
        currentMacro.ifPresent(cm -> {
            if (!cm.isPaused()) return;
            resume = true;
            cm.onEnable();
            resume = false;
            PlayerUtils.getTool();
            macroingTimer.resume();
            afterRewarpDelay.reset();
        });
    }

    public void enableCurrentMacro() {
        if (currentMacro.isPresent() && !currentMacro.get().isEnabledAndNoFeature() && !startingUp) {
            mc.player.closeContainer();
            startingUp = true;
            PlayerUtils.itemChangedByStaff = false;
            PlayerUtils.changeItemEveryClock.reset();
            KeyBindUtils.holdThese(mc.player.getAbilities().flying ? mc.options.keyShift : null);
            startDelay.schedule(300);
        }
    }

    public void disableCurrentMacro() {
        currentMacro.ifPresent(AbstractMacro::onDisable);
    }

    /** Client tick (start). */
    public void onTick() {
        if (startingUp && startDelay.passed()) {
            startingUp = false;
            KeyBindUtils.stopMovement();
            if (isMacroToggled) currentMacro.ifPresent(AbstractMacro::onEnable);
            macroingTimer.resume();
        }
        if (!isMacroToggled) return;
        if (mc.player == null || mc.level == null) return;
        if (mc.screen != null) {
            KeyBindUtils.stopMovement();
            screenWasOpen = true;
            return;
        }
        // CedClient: closing a menu re-grabs the mouse, which sets Minecraft.missTime = 10000: no breaking until attack
        // is released once. The macro never lets go of attack, so it stood still -- release everything for one tick.
        if (screenWasOpen) {
            screenWasOpen = false;
            KeyBindUtils.stopMovement();
            return;
        }
        if (!GameStateHandler.getInstance().inGarden() && !isTeleporting()) {
            if (FailsafeManager.getInstance().triggeredFailsafe.isEmpty()) {
                FailsafeManager.getInstance().possibleDetection(WorldChangeFailsafe.getInstance());
            }
            return;
        }
        checkForTeleport();
        AntiStuck.getInstance().onTick();
        if (AntiStuck.getInstance().isRunning()) return;
        currentMacro.ifPresent(cm -> {
            if (!cm.isEnabledAndNoFeature()) return;
            cm.onTick();
        });
    }

    public void onChat(String message) {
        if (!message.contains(":") && GameStateHandler.getInstance().inGarden() && message.equals("Your spawn location has been set!")) {
            PlayerUtils.setSpawnLocation();
            BlockPos spawn = ced.cedclient.features.impl.funqol.FarmHelper.spawn();
            for (BlockPos rewarp : ced.cedclient.features.impl.funqol.FarmHelper.rewarps()) {
                if (spawn != null && rewarp.distSqr(spawn) < 4) {
                    LogUtils.sendWarning("Spawn location is close to a rewarp point! Removing that rewarp point...");
                    ced.cedclient.features.impl.funqol.FarmHelper.removeRewarp(rewarp);
                    break;
                }
            }
            LogUtils.sendSuccess("Spawn point saved.");
        }
    }

    public void onWorldChange() {
        if (!isMacroToggled) return;
        currentMacro.ifPresent(m -> {
            m.setCurrentState(AbstractMacro.State.NONE);
            m.getSavedState().ifPresent(ss -> ss.state = AbstractMacro.State.NONE);
        });
    }

    private void checkForTeleport() {
        if (beforeTeleportationPos.isEmpty()) return;
        if (mc.player.blockPosition().distSqr(beforeTeleportationPos.get()) > 2 || PlayerUtils.isStandingOnSpawnPoint()) {
            if (PlayerUtils.isPlayerSuffocating()) return;
            if (!mc.player.getAbilities().flying && !mc.player.onGround()) return;
            if (mc.player.getAbilities().flying && !mc.player.onGround() && rewarpTeleport) {
                if (!mc.options.keyShift.isDown()) KeyBindUtils.holdThese(mc.options.keyShift);
                return;
            }
            afterRewarpDelay.schedule(1_500);
            LogUtils.sendDebug("Teleported!");
            currentMacro.ifPresent(cm -> {
                if (cm.isPaused() && rewarpTeleport) resumeMacro();
                cm.changeState(AbstractMacro.State.NONE);
                cm.setRotated(false);
                cm.getRewarpDelay().schedule(FarmConfig.getRandomTimeBetweenChangingRows());
                if (rewarpTeleport) {
                    cm.setRewarpState(AbstractMacro.RewarpState.TELEPORTED);
                    cm.actionAfterTeleport();
                } else {
                    cm.setRewarpState(AbstractMacro.RewarpState.NONE);
                }
            });
            beforeTeleportationPos = Optional.empty();
            rewarpTeleport = false;
        } else if (System.currentTimeMillis() - lastTpTry > 5_000) {
            LogUtils.sendDebug("Teleporting again");
            triggerWarpGarden(true, rewarpTeleport);
        }
    }

    public void triggerWarpGarden(boolean force, boolean rewarpTeleport) {
        if (GameStateHandler.getInstance().notMoving()) KeyBindUtils.stopMovement();
        if (force || GameStateHandler.getInstance().canRewarp() && beforeTeleportationPos.isEmpty()) {
            lastTpTry = System.currentTimeMillis();
            beforeTeleportationPos = Optional.of(mc.player.blockPosition());
            mc.player.connection.sendCommand("warp garden");
            this.rewarpTeleport = rewarpTeleport;
            AntiStuck.getInstance().setLagBackCounter(0);
            GameStateHandler.getInstance().scheduleRewarp();
            currentMacro.ifPresent(cm -> cm.setRewarpState(AbstractMacro.RewarpState.TELEPORTING));
        }
    }
}
//#endif
