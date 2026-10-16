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

public class SShapeSugarcaneMacro extends AbstractMacro {
    public double rowStartX = 0;
    public double rowStartZ = 0;

    @Override
    public void updateState() {
        if (currentState == null) changeState(State.NONE);
        switch (currentState) {
            case S:
                if (hasWall(0, -1, getYaw() - 45f) && hasWall(0, -1, getYaw() + 45f)) {
                    if (getNearestSideWall(getYaw() + 45, -1) == -999) changeState(State.A);
                    if (getNearestSideWall(getYaw() - 45, 1) == -999) changeState(State.D);
                }
                break;
            case A:
            case D:
                changeState(State.S);
                break;
            case DROPPING:
                if (mc.player.onGround() && Math.abs(getLayerY() - mc.player.blockPosition().getY()) > 1.5) {
                    if (FarmConfig.rotateAfterDrop() && !getRotation().isRotating()) {
                        getRotation().reset();
                        setYaw(AngleUtils.getClosestDiagonal(getYaw() + 180));
                        setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
                        getRotation().easeTo(new RotationHandler.Configuration(new Rotation(getYaw(), getPitch()), (long) (400 + Math.random() * 300), null).easeOutBack(true));
                    }
                    KeyBindUtils.stopMovement();
                    changeState(State.NONE);
                    setLayerY(mc.player.blockPosition().getY());
                } else {
                    GameStateHandler.getInstance().scheduleNotMoving();
                }
                break;
            case NONE:
                changeState(calculateDirection());
                break;
            default:
                changeState(State.NONE);
        }
    }

    @Override
    public void invokeState() {
        if (currentState == null) return;
        switch (currentState) {
            case A:
                KeyBindUtils.holdThese(mc.options.keyLeft, mc.options.keyAttack);
                break;
            case D:
                KeyBindUtils.holdThese(mc.options.keyRight, mc.options.keyAttack);
                break;
            case S:
                KeyBindUtils.holdThese(mc.options.keyDown, mc.options.keyAttack);
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
    public void actionAfterTeleport() {
        setLayerY(mc.player.blockPosition().getY());
        rowStartX = mc.player.getX();
        rowStartZ = mc.player.getZ();
    }

    @Override
    public void onEnable() {
        super.onEnable();
        if (!isPitchSet()) setPitch((float) (Math.random() * 1) - 0.5f);
        if (!isYawSet()) {
            setYaw(AngleUtils.getClosestDiagonal());
            setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
        }
        rowStartX = mc.player.getX();
        rowStartZ = mc.player.getZ();
        if (MacroHandler.getInstance().isTeleporting()) return;
        setRestoredState(false);
        if (FarmConfig.dontFixAfterWarping() && Math.abs(getYaw() - AngleUtils.get360RotationYaw()) < 0.1) return;
        easeToMacroRotation(!MacroHandler.getInstance().isResume());
    }

    @Override
    public State calculateDirection() {
        if (isWaterNear(getYaw() - 45f)) {
            if (!(hasWall(0, 1, getYaw() - 45f) && hasWall(-1, 0, getYaw() - 45f))) return State.A;
        } else if (isWaterNear(getYaw() + 45f)) {
            if (!(hasWall(0, 1, getYaw() + 45f) && hasWall(11, 0, getYaw() + 45f))) return State.D;
        }
        return State.S;
    }

    private boolean isWaterNear(float yaw) {
        return BlockUtils.isWater(BlockUtils.getRelativeBlock(2, -1, 1, yaw)) || BlockUtils.isWater(BlockUtils.getRelativeBlock(2, 0, 1, yaw))
                || BlockUtils.isWater(BlockUtils.getRelativeBlock(-1, -1, 1, yaw)) || BlockUtils.isWater(BlockUtils.getRelativeBlock(-1, 0, 1, yaw));
    }

    boolean hasWall(int rightOffset, int frontOffset, float yaw) {
        return !BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(rightOffset, 0, frontOffset, yaw));
    }

    int getNearestSideWall(float yaw, int dir) { // right = 1; left = -1
        for (int i = 0; i < 8; i++) {
            if (hasWall(i * dir, 0, yaw)) return i;
        }
        return -999;
    }
}
