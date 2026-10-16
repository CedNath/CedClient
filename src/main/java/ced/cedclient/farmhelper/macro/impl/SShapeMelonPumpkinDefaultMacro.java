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
import ced.cedclient.farmhelper.macro.AntiStuck;
import ced.cedclient.farmhelper.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

public class SShapeMelonPumpkinDefaultMacro extends AbstractMacro {
    private static final int ROTATION_DEGREE = 45;
    public ChangeLaneDirection changeLaneDirection = null;

    private float base() {
        return getClosest90Deg().orElse(AngleUtils.getClosest());
    }

    private static boolean isMelonOrPumpkin(Block block) {
        return block == Blocks.MELON || block == Blocks.PUMPKIN;
    }

    private void turnTo(float yaw, boolean easeOutBack) {
        getRotation().easeTo(new RotationHandler.Configuration(new Rotation(yaw, getPitch()), FarmConfig.getRandomRotationTime(), null).easeOutBack(easeOutBack));
    }

    private void randomPitch() {
        setPitch(50 + (float) (Math.random() * 6 - 3));
    }

    @Override
    public void onEnable() {
        super.onEnable();
        if (!isPitchSet()) randomPitch();
        if (!isYawSet()) {
            setYaw(AngleUtils.getClosestDiagonal());
            setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
        }
        changeState(calculateDirection());
        float additionalRotation;
        switch (getCurrentState()) {
            case LEFT: additionalRotation = (float) (-ROTATION_DEGREE - (Math.random() * 2)); break;
            case RIGHT: additionalRotation = (float) (ROTATION_DEGREE + (Math.random() * 2)); break;
            default: additionalRotation = (float) (Math.random() * 2 - 1); break;
        }
        changeLaneDirection = null;
        if (MacroHandler.getInstance().isTeleporting()) return;
        setRestoredState(false);
        if (FarmConfig.dontFixAfterWarping() && Math.abs(getYaw() - AngleUtils.get360RotationYaw()) < 0.1) return;
        turnTo(base() + additionalRotation, false);
    }

    private boolean isHuggingBackWall() {
        return !GameStateHandler.getInstance().isBackWalkable();
    }

    @Override
    public void doAfterRewarpRotation() {
        if (getRotation().isRotating()) return;
        randomPitch();
        if (getCurrentState() == State.RIGHT) {
            turnTo((float) (base() + (ROTATION_DEGREE + (Math.random() * 2))), true);
        } else if (getCurrentState() == State.LEFT) {
            turnTo((float) (base() - (ROTATION_DEGREE + (Math.random() * 2))), true);
        }
    }

    @Override
    public void updateState() {
        if (getCurrentState() == null) changeState(State.NONE);
        GameStateHandler gs = GameStateHandler.getInstance();
        switch (getCurrentState()) {
            case RIGHT:
            case LEFT: {
                Block blockLeft = BlockUtils.getBlock(BlockUtils.getRelativeBlockPos(-1, 0, 0, base()));
                Block blockRight = BlockUtils.getBlock(BlockUtils.getRelativeBlockPos(1, 0, 0, base()));
                if (isMelonOrPumpkin(blockLeft)) {
                    changeState(State.LEFT);
                } else if (isMelonOrPumpkin(blockRight)) {
                    changeState(State.RIGHT);
                } else if (gs.isFrontWalkable() || gs.isBackWalkable()) {
                    ChangeLaneDirection next = gs.isFrontWalkable() ? ChangeLaneDirection.FORWARD : ChangeLaneDirection.BACKWARD;
                    if (changeLaneDirection != null && changeLaneDirection != next) {
                        // Probably stuck in dirt.
                        AntiStuck.getInstance().start();
                        return;
                    }
                    changeLaneDirection = next;
                    randomPitch();
                    float additionalRotation = 0;
                    if (getCurrentState() == State.RIGHT) additionalRotation = -((float) (Math.random() * 0.4 + 0.2));
                    else if (getCurrentState() == State.LEFT) additionalRotation = (float) (Math.random() * 0.4 + 0.2);
                    changeState(State.SWITCHING_LANE);
                    turnTo(base() + additionalRotation, false);
                } else if (gs.isLeftWalkable()) {
                    changeState(State.LEFT);
                } else if (gs.isRightWalkable()) {
                    changeState(State.RIGHT);
                } else {
                    changeState(State.NONE);
                }
                break;
            }
            case SWITCHING_LANE:
                if (gs.isFrontWalkable() && changeLaneDirection == ChangeLaneDirection.FORWARD && (gs.isRightWalkable() || gs.isLeftWalkable())) {
                    AntiStuck.getInstance().start();
                    changeState(State.SWITCHING_LANE);
                } else if (gs.isBackWalkable() && changeLaneDirection == ChangeLaneDirection.BACKWARD && (gs.isRightWalkable() || gs.isLeftWalkable())) {
                    AntiStuck.getInstance().start();
                    changeState(State.SWITCHING_LANE);
                } else if (gs.isRightWalkable()) {
                    changeState(State.RIGHT);
                    randomPitch();
                    turnTo((float) (base() + (ROTATION_DEGREE + (Math.random() * 2))), false);
                } else if (gs.isLeftWalkable()) {
                    changeState(State.LEFT);
                    randomPitch();
                    turnTo((float) (base() - (ROTATION_DEGREE + (Math.random() * 2))), false);
                } else if (gs.isFrontWalkable()) {
                    if (changeLaneDirection == ChangeLaneDirection.BACKWARD) {
                        AntiStuck.getInstance().start();
                        return;
                    }
                    changeState(State.SWITCHING_LANE);
                } else if (gs.isBackWalkable()) {
                    if (changeLaneDirection == ChangeLaneDirection.FORWARD) {
                        AntiStuck.getInstance().start();
                        return;
                    }
                    changeState(State.SWITCHING_LANE);
                } else {
                    changeState(State.NONE);
                }
                break;
            case DROPPING:
                if (mc.player.onGround() && Math.abs(getLayerY() - mc.player.blockPosition().getY()) > 1.5) {
                    changeLaneDirection = null;
                    if (FarmConfig.rotateAfterDrop() && !getRotation().isRotating()) {
                        getRotation().reset();
                        setYaw(AngleUtils.getClosest(getYaw() + 180));
                        setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
                        turnTo(getYaw(), false);
                    }
                    KeyBindUtils.stopMovement();
                    setLayerY(mc.player.blockPosition().getY());
                    changeState(State.NONE);
                } else if (mc.player.onGround()) {
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
                KeyBindUtils.holdThese(isHuggingBackWall() ? mc.options.keyUp : null, mc.options.keyRight, mc.options.keyAttack);
                break;
            case LEFT:
                KeyBindUtils.holdThese(isHuggingBackWall() ? mc.options.keyUp : null, mc.options.keyLeft, mc.options.keyAttack);
                break;
            case SWITCHING_LANE: {
                double velocity = Math.abs(mc.player.getDeltaMovement().x) + Math.abs(mc.player.getDeltaMovement().z);
                if (velocity < 0.15 && !GameStateHandler.getInstance().isFrontWalkable()) {
                    KeyBindUtils.stopMovement(FarmConfig.holdLeftClickWhenChangingRow());
                    break;
                }
                KeyBindUtils.holdThese(mc.options.keyUp, mc.options.keySprint);
                GameStateHandler.getInstance().scheduleNotMoving(25);
                break;
            }
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
        changeLaneDirection = null;
    }

    @Override
    public State calculateDirection() {
        for (int i = 0; i < 180; i++) {
            Block blockRight = BlockUtils.getRelativeBlock(i, 0, 0);
            Block blockLeft = BlockUtils.getRelativeBlock(-i, 0, 0);
            if (isMelonOrPumpkin(blockRight)) return State.RIGHT;
            if (isMelonOrPumpkin(blockLeft)) return State.LEFT;
            if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(i, 0, 0, base()))) return State.LEFT;
            if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(-i, 0, 0, base()))) return State.RIGHT;
        }
        return State.NONE;
    }

    public enum ChangeLaneDirection { FORWARD, BACKWARD }
}
