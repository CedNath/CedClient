/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.macro.impl;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.handler.GameStateHandler;
import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.macro.AbstractMacro;
import ced.cedclient.farmhelper.macro.AntiStuck;
import ced.cedclient.farmhelper.util.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Optional;

public class SShapeVerticalCropMacro extends AbstractMacro {
    public ChangeLaneDirection changeLaneDirection = null;

    @Override
    public void updateState() {
        if (getCurrentState() == null) changeState(State.NONE);
        GameStateHandler gs = GameStateHandler.getInstance();
        switch (getCurrentState()) {
            case LEFT:
            case RIGHT: {
                if (gs.isLeftWalkable() && getCurrentState() == State.LEFT) {
                    // Probably stuck in dirt, keep going left.
                    setCurrentState(State.LEFT);
                    AntiStuck.getInstance().start();
                    return;
                }
                if (gs.isRightWalkable() && getCurrentState() == State.RIGHT) {
                    setCurrentState(State.RIGHT);
                    AntiStuck.getInstance().start();
                    return;
                }
                FarmConfig.MacroEnum macro = FarmConfig.getMacro();
                if (gs.isFrontWalkable() && !FarmConfig.alwaysHoldW()) {
                    if (stuckInMelonsOrPumpkins()) {
                        AntiStuck.getInstance().start();
                        return;
                    }
                    changeState(State.SWITCHING_LANE);
                    setWalkingDirection();
                } else if (macro != FarmConfig.MacroEnum.S_CACTUS && macro != FarmConfig.MacroEnum.S_CACTUS_SUNTZU && gs.isBackWalkable() && !FarmConfig.alwaysHoldW()) {
                    if (stuckInMelonsOrPumpkins()) {
                        AntiStuck.getInstance().start();
                        return;
                    }
                    changeState(State.SWITCHING_LANE);
                    setWalkingDirection();
                } else if (gs.isLeftWalkable()) {
                    changeState(State.LEFT);
                } else if (gs.isRightWalkable()) {
                    changeState(State.RIGHT);
                } else {
                    changeState(State.NONE);
                    LogUtils.sendDebug("Can't go forward or backward!");
                }
                break;
            }
            case SWITCHING_LANE: {
                if (getWalkingDirection() == WalkingDirection.X) {
                    if (lagBackDetected(mc.player.blockPosition().getZ())) break;
                } else if (lagBackDetected(mc.player.blockPosition().getX())) {
                    break;
                }
                if (gs.isLeftWalkable()) {
                    changeState(State.LEFT);
                } else if (gs.isRightWalkable()) {
                    changeState(State.RIGHT);
                } else {
                    changeState(State.NONE);
                }
                break;
            }
            case DROPPING: {
                if (mc.player.onGround() && Math.abs(getLayerY() - mc.player.blockPosition().getY()) > 1.5) {
                    changeLaneDirection = null;
                    if (FarmConfig.rotateAfterDrop()) {
                        setYaw(AngleUtils.getClosest(getYaw() + 180));
                        setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
                        easeToMacroRotation(true);
                    }
                    KeyBindUtils.stopMovement(FarmConfig.holdLeftClickWhenChangingRow());
                    setLayerY(mc.player.blockPosition().getY());
                    changeState(State.NONE);
                } else if (mc.player.onGround()) {
                    setLayerY(mc.player.blockPosition().getY());
                    changeState(State.NONE);
                } else {
                    gs.scheduleNotMoving();
                }
                break;
            }
            case NONE:
                changeState(calculateDirection());
                break;
            default:
                changeState(State.NONE);
        }
    }

    private boolean stuckInMelonsOrPumpkins() {
        Block left = BlockUtils.getBlock(BlockUtils.getRelativeBlockPos(-1, 0, 0, getYaw()));
        Block right = BlockUtils.getBlock(BlockUtils.getRelativeBlockPos(1, 0, 0, getYaw()));
        return left == Blocks.PUMPKIN || left == Blocks.MELON || right == Blocks.PUMPKIN || right == Blocks.MELON;
    }

    private boolean lagBackDetected(int currentCoord) {
        if (Math.abs(currentCoord - getPreviousWalkingCoord()) < 1) {
            AntiStuck.getInstance().start();
            LogUtils.sendWarning("Probability of lag back detected!");
            AntiStuck.getInstance().setLagBackCounter(AntiStuck.getInstance().getLagBackCounter() + 1);
            if (AntiStuck.getInstance().getLagBackCounter() >= 3) {
                LogUtils.sendWarning("Lag back stuck detected! Resetting state...");
                changeState(State.NONE);
                AntiStuck.getInstance().setLagBackCounter(0);
                AntiStuck.getInstance().stop();
            }
            return true;
        }
        return false;
    }

    @Override
    public void invokeState() {
        if (getCurrentState() == null) return;
        boolean forward = PlayerUtils.shouldWalkForwards() || FarmConfig.alwaysHoldW();
        switch (getCurrentState()) {
            case LEFT:
                KeyBindUtils.holdThese(mc.options.keyLeft, mc.options.keyAttack, forward ? mc.options.keyUp : null);
                break;
            case RIGHT:
                KeyBindUtils.holdThese(mc.options.keyRight, mc.options.keyAttack, forward ? mc.options.keyUp : null);
                break;
            case SWITCHING_LANE:
                if (changeLaneDirection == null) {
                    if (GameStateHandler.getInstance().isFrontWalkable()) {
                        changeLaneDirection = ChangeLaneDirection.FORWARD;
                    } else if (GameStateHandler.getInstance().isBackWalkable()) {
                        changeLaneDirection = ChangeLaneDirection.BACKWARD;
                    } else {
                        changeState(State.NONE);
                        return;
                    }
                }
                if (changeLaneDirection == ChangeLaneDirection.FORWARD) {
                    KeyBindUtils.holdThese(mc.options.keyUp, mc.options.keySprint, FarmConfig.holdLeftClickWhenChangingRow() ? mc.options.keyAttack : null);
                } else {
                    KeyBindUtils.holdThese(mc.options.keyDown, FarmConfig.holdLeftClickWhenChangingRow() ? mc.options.keyAttack : null);
                }
                break;
            case DROPPING:
                if (mc.player.onGround() && Math.abs(getLayerY() - mc.player.blockPosition().getY()) <= 1.5) {
                    setLayerY(mc.player.blockPosition().getY());
                    changeState(State.NONE);
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
    public void onEnable() {
        super.onEnable();
        changeLaneDirection = null;
        FarmConfig.MacroEnum macro = FarmConfig.getMacro();
        if (!isPitchSet()) {
            if (macro == FarmConfig.MacroEnum.S_V_NORMAL_TYPE) {
                setPitch((float) (2.8f + Math.random() * 0.5f));
            } else if (macro == FarmConfig.MacroEnum.S_PUMPKIN_MELON) {
                setPitch(28 + (float) (Math.random() * 2));
            } else if (macro == FarmConfig.MacroEnum.S_PUMPKIN_MELON_MELONGKINGDE) {
                setPitch((float) (-59.2f + Math.random()));
            } else if (MacroHandler.getInstance().getCrop() == FarmConfig.CropEnum.NETHER_WART || macro == FarmConfig.MacroEnum.S_CACTUS) {
                setPitch((float) (Math.random() * 0.5f));
            } else if (macro == FarmConfig.MacroEnum.S_CACTUS_SUNTZU) {
                setPitch((float) (-38 - Math.random() * 1.5f));
            } else if (macro == FarmConfig.MacroEnum.S_COCOA_BEANS_LEFT_RIGHT) {
                setPitch(-90f);
            }
        }
        if (!isYawSet()) {
            setYaw(AngleUtils.getClosest());
            setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
        }
        setRestoredState(false);
        if (MacroHandler.getInstance().isTeleporting()) return;
        if (FarmConfig.dontFixAfterWarping() && Math.abs(getYaw() - AngleUtils.get360RotationYaw()) < 0.1) return;
        easeToMacroRotation(!MacroHandler.getInstance().isResume() && macro != FarmConfig.MacroEnum.S_COCOA_BEANS_LEFT_RIGHT);
    }

    @Override
    public void actionAfterTeleport() {
        changeLaneDirection = null;
    }

    @Override
    public State calculateDirection() {
        State voidCheck = super.calculateDirection();
        if (voidCheck != State.NONE) return voidCheck;
        if (BlockUtils.rightCropIsReady()) return State.RIGHT;
        if (BlockUtils.leftCropIsReady()) return State.LEFT;

        float yaw;
        if (MacroHandler.getInstance().getCurrentMacro().isPresent() && MacroHandler.getInstance().getCurrentMacro().get().getClosest90Deg().isPresent()) {
            yaw = MacroHandler.getInstance().getCurrentMacro().get().getClosest90Deg().get();
        } else {
            yaw = mc.player.getYRot();
        }
        for (int i = 1; i < 180; i++) {
            if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(i, 0, 0, yaw))) {
                if (BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(i - 1, -1, 1, yaw)) || BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(i - 1, -1, 0, yaw))) {
                    return State.RIGHT;
                }
                return State.LEFT;
            } else if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(-i, 0, 0, yaw))) {
                if (BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(-i + 1, 0, 1, yaw)) || BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(-i + 1, -1, 0, yaw))) {
                    return State.LEFT;
                }
                return State.RIGHT;
            }
        }
        LogUtils.sendDebug("Couldn't find a direction! Length > 180. Defaulting to RIGHT...");
        return State.RIGHT;
    }

    public enum ChangeLaneDirection { FORWARD, BACKWARD }
}
