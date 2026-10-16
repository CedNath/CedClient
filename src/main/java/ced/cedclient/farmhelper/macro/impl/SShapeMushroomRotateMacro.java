/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.macro.impl;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.handler.GameStateHandler;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.handler.RotationHandler;
import ced.cedclient.farmhelper.macro.AbstractMacro;
import ced.cedclient.farmhelper.util.*;

import java.util.Optional;

public class SShapeMushroomRotateMacro extends AbstractMacro {
    private float base() {
        return getClosest90Deg().orElse(AngleUtils.getClosest());
    }

    /** Faces 30 degrees into the side being walked (left = -30, right = +30), with a little jitter. */
    private void faceSide(boolean left, long time, boolean easeOutBack) {
        float yaw = (float) (base() + (left ? -30 : 30) + (Math.random() * 4 - 2));
        getRotation().easeTo(new RotationHandler.Configuration(new Rotation(yaw, getPitch()), time, null).easeOutBack(easeOutBack));
    }

    private static long quickTurn() {
        return (long) (400 + Math.random() * 300);
    }

    @Override
    public void onEnable() {
        super.onEnable();
        if (!isPitchSet()) setPitch((float) (Math.random() * 2 - 1));
        if (!isYawSet()) {
            setYaw(AngleUtils.getClosest());
            setClosest90Deg(Optional.of(AngleUtils.getClosest(AngleUtils.get360RotationYaw())));
        }
        if (MacroHandler.getInstance().isTeleporting()) return;
        setRestoredState(false);
        if (FarmConfig.dontFixAfterWarping() && Math.abs(getYaw() - AngleUtils.get360RotationYaw()) < 0.1) return;
        faceSide(getCurrentState() == State.LEFT, FarmConfig.getRandomRotationTime(), !MacroHandler.getInstance().isResume());
    }

    @Override
    public void actionAfterTeleport() {
        setPitch((float) (Math.random() * 2 - 1));
    }

    @Override
    public void doAfterRewarpRotation() {
        setClosest90Deg(Optional.of(AngleUtils.normalizeAngle(AngleUtils.getClosest(AngleUtils.getClosest() + 180))));
        setPitch((float) (Math.random() * 2 - 1));
    }

    @Override
    public void updateState() {
        if (getCurrentState() == null) changeState(State.NONE);
        GameStateHandler gs = GameStateHandler.getInstance();
        switch (getCurrentState()) {
            case LEFT:
                if (gs.isRightWalkable()) {
                    changeState(State.RIGHT);
                    faceSide(false, quickTurn(), true);
                } else if (gs.isLeftWalkable()) {
                    changeState(State.LEFT);
                    faceSide(true, quickTurn(), true);
                } else {
                    changeState(calculateDirection());
                }
                setPitch((float) (Math.random() * 2 - 1));
                break;
            case RIGHT:
                if (gs.isLeftWalkable()) {
                    changeState(State.LEFT);
                    faceSide(true, quickTurn(), true);
                } else if (gs.isRightWalkable()) {
                    changeState(State.RIGHT);
                    faceSide(false, quickTurn(), true);
                } else {
                    changeState(calculateDirection());
                }
                setPitch((float) (Math.random() * 2 - 1));
                break;
            case DROPPING:
                if (mc.player.onGround() && Math.abs(getLayerY() - mc.player.blockPosition().getY()) > 1.5) {
                    if (FarmConfig.rotateAfterDrop() && !getRotation().isRotating()) {
                        getRotation().reset();
                        setYaw(AngleUtils.getClosest(getYaw() + 180));
                        setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
                        faceSide(getCurrentState() == State.LEFT, quickTurn(), true);
                    }
                    KeyBindUtils.stopMovement();
                    setLayerY(mc.player.blockPosition().getY());
                    changeState(State.NONE);
                } else {
                    gs.scheduleNotMoving();
                }
                break;
            case NONE:
                changeState(calculateDirection());
                if (getCurrentState() == State.LEFT) faceSide(true, quickTurn(), true);
                else if (getCurrentState() == State.RIGHT) faceSide(false, quickTurn(), true);
                break;
            default:
                changeState(State.NONE);
        }
    }

    @Override
    public void invokeState() {
        if (getCurrentState() == null) return;
        switch (getCurrentState()) {
            case RIGHT:
            case LEFT:
                KeyBindUtils.holdThese(mc.options.keyUp, mc.options.keyAttack);
                break;
            case DROPPING:
                if (mc.player.onGround() && Math.abs(getLayerY() - mc.player.blockPosition().getY()) <= 1.5) {
                    setLayerY(mc.player.blockPosition().getY());
                    changeState(State.NONE);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public boolean shouldRotateAfterWarp() {
        return false;
    }

    @Override
    public State calculateDirection() {
        if (BlockUtils.rightCropIsReady()) return State.RIGHT;
        if (BlockUtils.leftCropIsReady()) return State.LEFT;
        for (int i = 1; i < 180; i++) {
            if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(i, 0, 0, base()))) return State.LEFT;
            if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(-i, 0, 0, base()))) return State.RIGHT;
        }
        return State.NONE;
    }
}
