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
import net.minecraft.core.Direction;

import java.util.Optional;

public class SShapeMushroomMacro extends AbstractMacro {
    @Override
    public void onEnable() {
        super.onEnable();
        if (!isPitchSet()) setPitch((float) (Math.random() * 2 - 1));
        if (!isYawSet()) {
            setYaw(AngleUtils.getClosestDiagonal());
            setClosest90Deg(Optional.of(AngleUtils.getClosest()));
        }
        if (MacroHandler.getInstance().isTeleporting()) return;
        setRestoredState(false);
        if (FarmConfig.dontFixAfterWarping() && Math.abs(getYaw() - AngleUtils.get360RotationYaw()) < 0.1) return;
        easeToMacroRotation(!MacroHandler.getInstance().isResume());
    }

    @Override
    public void actionAfterTeleport() {
        if (!isPitchSet()) setPitch((float) (Math.random() * 2 - 1));
    }

    @Override
    public void doAfterRewarpRotation() {
        setPitch((float) (Math.random() * 2 - 1));
        setClosest90Deg(Optional.of(AngleUtils.normalizeAngle(AngleUtils.getClosest(AngleUtils.getClosest() + 180))));
        setYaw(AngleUtils.getClosestDiagonal(getYaw() + 180));
    }

    @Override
    public void updateState() {
        if (getCurrentState() == null) changeState(State.NONE);
        GameStateHandler gs = GameStateHandler.getInstance();
        switch (getCurrentState()) {
            case LEFT:
                if (gs.isRightWalkable()) changeState(State.RIGHT);
                else if (!gs.isLeftWalkable()) changeState(State.LEFT);
                else changeState(calculateDirection());
                break;
            case RIGHT:
                if (gs.isLeftWalkable()) changeState(State.LEFT);
                else if (!gs.isRightWalkable()) changeState(State.RIGHT);
                else changeState(calculateDirection());
                break;
            case DROPPING:
                if (mc.player.onGround() && Math.abs(getLayerY() - mc.player.blockPosition().getY()) > 1.5) {
                    if (FarmConfig.rotateAfterDrop() && !getRotation().isRotating()) {
                        getRotation().reset();
                        setYaw(AngleUtils.getClosest(getYaw() + 180));
                        setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
                        getRotation().easeTo(new RotationHandler.Configuration(new Rotation(getYaw(), getPitch()), (long) (400 + Math.random() * 300), null));
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
                KeyBindUtils.holdThese(
                        FarmConfig.alwaysHoldW() ? mc.options.keyUp : mushroom45DegreeSide() == LookDirection.LEFT ? mc.options.keyRight : mc.options.keyUp,
                        mc.options.keyAttack);
                break;
            case LEFT:
                KeyBindUtils.holdThese(
                        FarmConfig.alwaysHoldW() ? mc.options.keyUp : mushroom45DegreeSide() == LookDirection.LEFT ? mc.options.keyUp : mc.options.keyLeft,
                        mc.options.keyAttack);
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

    private enum LookDirection { LEFT, RIGHT }

    private LookDirection mushroom45DegreeSide() {
        if (getClosest90Deg().isEmpty()) return LookDirection.LEFT;
        Direction facing = Direction.fromYRot(getClosest90Deg().get());
        float difference = AngleUtils.normalizeAngle(getYaw()) - 90f;
        switch (facing) {
            case EAST:
                if (difference < -220) return LookDirection.LEFT;
                if (difference > -140) return LookDirection.RIGHT;
                return LookDirection.LEFT;
            case WEST:
                return difference > 40 ? LookDirection.RIGHT : LookDirection.LEFT;
            case NORTH:
                if (difference > 40) return LookDirection.LEFT;
                if (difference < -40) return LookDirection.RIGHT;
                return LookDirection.LEFT;
            default: // SOUTH
                if (difference < -130) return LookDirection.LEFT;
                if (difference < -40) return LookDirection.RIGHT;
                return LookDirection.LEFT;
        }
    }

    @Override
    public State calculateDirection() {
        if (BlockUtils.rightCropIsReady()) return State.RIGHT;
        if (BlockUtils.leftCropIsReady()) return State.LEFT;
        float base = getClosest90Deg().orElse(AngleUtils.getClosest());
        for (int i = 1; i < 180; i++) {
            if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(i, 0, 0, base))) return State.LEFT;
            if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(-i, 0, 0, base))) return State.RIGHT;
        }
        return State.NONE;
    }
}
