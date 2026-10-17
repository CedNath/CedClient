/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; no pest / visitor / scheduler features. See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.macro;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.failsafe.impl.RotationFailsafe;
import ced.cedclient.farmhelper.handler.GameStateHandler;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.handler.RotationHandler;
import ced.cedclient.farmhelper.util.*;
import ced.cedclient.features.impl.funqol.FarmHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

public abstract class AbstractMacro {
    public static final Minecraft mc = Minecraft.getInstance();
    private final RotationHandler rotation = RotationHandler.getInstance();
    private final Clock rewarpDelay = new Clock();
    private final Clock delayBeforeBreakTime = new Clock();
    private final Clock breakTime = new Clock();
    private final Clock checkOnSpawnClock = new Clock();

    public State currentState = State.NONE;
    public State previousState = State.NONE;
    private boolean enabled = false;
    private Optional<SavedState> savedState = Optional.empty();
    private boolean restoredState = false;
    private int layerY = 0;
    private Optional<Float> yaw = Optional.empty();
    private Optional<Float> pitch = Optional.empty();
    private Optional<Float> closest90Deg = Optional.empty();
    private boolean rotated = false;
    private RewarpState rewarpState = RewarpState.NONE;
    private WalkingDirection walkingDirection = WalkingDirection.X;
    private int previousWalkingCoord = 0;
    private boolean sentWarning = false;

    public RotationHandler getRotation() { return rotation; }
    public Clock getRewarpDelay() { return rewarpDelay; }
    public Clock getCheckOnSpawnClock() { return checkOnSpawnClock; }
    public State getCurrentState() { return currentState; }
    public void setCurrentState(State state) { currentState = state; }
    public State getPreviousState() { return previousState; }
    public void setPreviousState(State state) { previousState = state; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isEnabledAndNoFeature() { return enabled; }
    public boolean isPaused() { return !enabled; }
    public Optional<SavedState> getSavedState() { return savedState; }
    public void setSavedState(Optional<SavedState> state) { savedState = state; }
    public boolean isRestoredState() { return restoredState; }
    public void setRestoredState(boolean value) { restoredState = value; }
    public int getLayerY() { return layerY; }
    public void setLayerY(int value) { layerY = value; }
    public Optional<Float> getClosest90Deg() { return closest90Deg; }
    public void setClosest90Deg(Optional<Float> value) { closest90Deg = value; }
    public void setRotated(boolean value) { rotated = value; }
    public RewarpState getRewarpState() { return rewarpState; }
    public void setRewarpState(RewarpState state) { rewarpState = state; }
    public WalkingDirection getWalkingDirection() { return walkingDirection; }
    public void setWalkingDirection(WalkingDirection d) { walkingDirection = d; }
    public int getPreviousWalkingCoord() { return previousWalkingCoord; }
    public void setPreviousWalkingCoord(int value) { previousWalkingCoord = value; }

    public boolean isYawSet() { return yaw.isPresent(); }
    public boolean isPitchSet() { return pitch.isPresent(); }
    // CedClient: Custom Angles (FarmHelper module) swaps in the player's own yaw / pitch for the current crop.
    public float getYaw() { return yaw.isPresent() ? FarmHelper.INSTANCE.customYaw(MacroHandler.getInstance().getCrop(), yaw.get()) : 0f; }
    public float getPitch() { return pitch.isPresent() ? FarmHelper.INSTANCE.customPitch(MacroHandler.getInstance().getCrop(), pitch.get()) : 0f; }
    public void setYaw(float value) { yaw = Optional.of(value); }
    public void setPitch(float value) { pitch = Optional.of(value); }

    public void onTick() {
        if (FailsafeManager.getInstance().triggeredFailsafe.isPresent() || FailsafeManager.getInstance().getChooseEmergencyDelay().isScheduled()) {
            if (!sentWarning) {
                LogUtils.sendWarning("Failsafe is running! Blocking main onTick event!");
                sentWarning = true;
            }
            return;
        }
        if (!delayBeforeBreakTime.passed()) return;
        if (!breakTime.passed()) {
            KeyBindUtils.stopMovement();
            GameStateHandler.getInstance().scheduleNotMoving();
            return;
        }
        if (mc.player.getAbilities().flying) {
            KeyBindUtils.holdThese(mc.options.keyShift);
            return;
        }
        // CedClient: rewarp points are optional; without any, the macro just keeps alternating lanes.

        if (PlayerUtils.isStandingOnRewarpLocation() && FailsafeManager.getInstance().triggeredFailsafe.isEmpty()) {
            if (GameStateHandler.getInstance().notMoving() && KeyBindUtils.getHoldingKeybinds().length > 0) {
                KeyBindUtils.stopMovement();
            }
            if (!GameStateHandler.getInstance().notMoving()) return;
            if (GameStateHandler.getInstance().canRewarp()) {
                MacroHandler.getInstance().triggerWarpGarden(false, true);
                checkOnSpawnClock.schedule(5000);
            }
            return;
        }

        if (mc.player.getY() < 0) {
            LogUtils.sendError("Build a wall between the rewarp point and the void to prevent falling out of the garden!");
            MacroHandler.getInstance().triggerWarpGarden(true, false);
            FailsafeManager.getInstance().possibleDetection(RotationFailsafe.getInstance());
            return;
        }

        if (getRotation().isRotating()) {
            if (!mc.options.keyShift.isDown()) KeyBindUtils.stopMovement();
            GameStateHandler.getInstance().scheduleNotMoving();
            return;
        }

        if (FarmConfig.autoSwitchTool()) {
            FarmConfig.CropEnum crop = PlayerUtils.getCropBasedOnMouseOver();
            if (crop != FarmConfig.CropEnum.NONE && crop != MacroHandler.getInstance().getCrop()) {
                LogUtils.sendWarning("Crop changed from " + MacroHandler.getInstance().getCrop() + " to " + crop);
                MacroHandler.getInstance().setCrop(crop);
            }
        }

        if (rewarpDelay.isScheduled() && !rewarpDelay.passed()) return;

        if (rewarpState == RewarpState.TELEPORTED) {
            if (rotated && mc.player.onGround()) {
                rewarpState = RewarpState.POST_REWARP;
                rewarpDelay.schedule(FarmConfig.getRandomTimeBetweenChangingRows());
                return;
            } else if (rotated) {
                KeyBindUtils.holdThese(mc.options.keyShift);
                return;
            }
            if (FarmConfig.rotateAfterWarped()) doAfterRewarpRotation();
            if (shouldRotateAfterWarp()) {
                Rotation newRotation = new Rotation(getYaw(), getPitch());
                Rotation current = new Rotation(mc.player.getYRot(), mc.player.getXRot());
                Rotation neededChange = RotationHandler.getInstance().getNeededChange(current, newRotation);
                if (FarmConfig.dontFixAfterWarping()) {
                    float pythagoras = (float) Math.sqrt(Math.pow(neededChange.getYaw(), 2) + Math.pow(neededChange.getPitch(), 2));
                    if (pythagoras < 1) {
                        rotated = true;
                        setLayerY(mc.player.blockPosition().getY());
                        return;
                    }
                }
                rotation.easeTo(new RotationHandler.Configuration(newRotation,
                        FarmConfig.getRandomRotationTime() * (Math.abs(neededChange.getYaw()) > 90 ? 2 : 1), null).easeOutBack(true));
            }
            rotated = true;
            setLayerY(mc.player.blockPosition().getY());
            return;
        } else if (rewarpState == RewarpState.POST_REWARP) {
            rewarpState = RewarpState.NONE;
        } else if (rewarpState == RewarpState.TELEPORTING) {
            return;
        }

        if (FailsafeManager.getInstance().triggeredFailsafe.isPresent()) return;

        PlayerUtils.getTool();

        // Update or invoke state, based on whether the player is moving.
        if (GameStateHandler.getInstance().canChangeDirection()) {
            KeyBindUtils.stopMovement(FarmConfig.holdLeftClickWhenChangingRow());
            if (getCurrentState() == State.DROPPING && !mc.player.onGround()) return;
            updateState();
            if (getCurrentState() == State.NONE) return;
            GameStateHandler.getInstance().setUpdatedState(true);
        } else {
            if (!mc.player.onGround() && Math.abs(layerY - mc.player.getY()) > 0.75 && mc.player.getY() < 80 && getCurrentState() != State.DROPPING) {
                changeState(State.DROPPING);
                GameStateHandler.getInstance().setUpdatedState(true);
                GameStateHandler.getInstance().scheduleNotMoving();
            }
            invokeState();
        }
    }

    public abstract void updateState();

    public abstract void invokeState();

    public void onEnable() {
        GameStateHandler.getInstance().scheduleRewarp();
        FarmConfig.CropEnum crop;
        if (savedState.isPresent()) {
            changeState(savedState.get().state);
            setYaw(savedState.get().yaw);
            setPitch(savedState.get().pitch);
            setClosest90Deg(savedState.get().closest90Deg);
            crop = savedState.get().crop;
            restoredState = true;
            savedState = Optional.empty();
            GameStateHandler.getInstance().setUpdatedState(true);
            float randomTime = FarmConfig.getRandomTimeBetweenChangingRows();
            GameStateHandler.getInstance().scheduleNotMoving((int) Math.max(randomTime, 150));
        } else {
            crop = PlayerUtils.getFarmingCrop();
        }
        MacroHandler.getInstance().setCrop(crop);
        PlayerUtils.getTool();
        if (closest90Deg.isEmpty()) setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
        setEnabled(true);
        setLayerY(mc.player.blockPosition().getY());
        if (getCurrentState() == null) changeState(State.NONE);
    }

    public void onDisable() {
        KeyBindUtils.stopMovement();
        changeState(State.NONE);
        setRewarpState(RewarpState.NONE);
        setClosest90Deg(Optional.empty());
        rotation.reset();
        rewarpDelay.reset();
        sentWarning = false;
        yaw = Optional.empty();
        pitch = Optional.empty();
        setEnabled(false);
    }

    public void saveState() {
        if (savedState.isEmpty()) {
            savedState = Optional.of(new SavedState(currentState, getYaw(), getPitch(), closest90Deg.orElse(AngleUtils.getClosest()), MacroHandler.getInstance().getCrop()));
        }
    }

    public void changeState(State state) {
        LogUtils.sendDebug("Changing state from " + currentState + " to " + state);
        setPreviousState(currentState);
        setCurrentState(state);
    }

    public abstract void actionAfterTeleport();

    public State calculateDirection() {
        if (BlockUtils.getRelativeBlock(-1, 0, 0) == Blocks.AIR && BlockUtils.getRelativeBlock(-1, -1, 0) == Blocks.AIR) return State.RIGHT;
        if (BlockUtils.getRelativeBlock(1, 0, 0) == Blocks.AIR && BlockUtils.getRelativeBlock(1, -1, 0) == Blocks.AIR) return State.LEFT;
        return State.NONE;
    }

    public void doAfterRewarpRotation() {
        setYaw(AngleUtils.get360RotationYaw(getYaw() + 180));
        setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
    }

    protected void setWalkingDirection() {
        int currentX = BlockUtils.getRelativeBlockPos(0, 0, 0, getYaw()).getX();
        int leftX = BlockUtils.getRelativeBlockPos(1, 0, 0, getYaw()).getX();
        int rightX = BlockUtils.getRelativeBlockPos(-1, 0, 0, getYaw()).getX();
        if (currentX == leftX || currentX == rightX) {
            setWalkingDirection(WalkingDirection.Z);
        } else {
            setWalkingDirection(WalkingDirection.X);
        }
        setPreviousWalkingCoord(getWalkingDirection() == WalkingDirection.X ? mc.player.blockPosition().getZ() : currentX);
    }

    public boolean shouldRotateAfterWarp() {
        return true;
    }

    public void setBreakTime(double time, double timeBefore) {
        breakTime.schedule((long) time);
        delayBeforeBreakTime.schedule((long) timeBefore);
    }

    /** Turns to the macro's yaw / pitch the way every macro does on enable. */
    protected void easeToMacroRotation(boolean easeOutBack) {
        getRotation().easeTo(new RotationHandler.Configuration(new Rotation(getYaw(), getPitch()), FarmConfig.getRandomRotationTime(), null).easeOutBack(easeOutBack));
    }

    public enum State {
        NONE, DROPPING, SWITCHING_SIDE, SWITCHING_LANE, LEFT, RIGHT, BACKWARD, FORWARD, A, D, S, W
    }

    public enum RewarpState { NONE, TELEPORTING, TELEPORTED, POST_REWARP }

    public enum WalkingDirection { X, Z }

    public static class SavedState {
        public State state;
        public final float yaw;
        public final float pitch;
        public final Optional<Float> closest90Deg;
        public final FarmConfig.CropEnum crop;

        public SavedState(State state, float yaw, float pitch, float closest90Deg, FarmConfig.CropEnum crop) {
            this.state = state;
            this.yaw = yaw;
            this.pitch = pitch;
            this.closest90Deg = Optional.of(closest90Deg);
            this.crop = crop;
        }
    }
}
