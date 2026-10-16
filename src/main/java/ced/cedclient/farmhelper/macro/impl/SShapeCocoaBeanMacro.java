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
import ced.cedclient.farmhelper.util.AngleUtils;
import ced.cedclient.farmhelper.util.BlockUtils;
import ced.cedclient.farmhelper.util.KeyBindUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

public class SShapeCocoaBeanMacro extends AbstractMacro {
    @Override
    public void onEnable() {
        super.onEnable();
        if (!isPitchSet()) setPitch(-70f + (float) (Math.random() * 0.6));
        if (!isYawSet()) {
            setYaw(AngleUtils.getClosest());
            setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
        }
        if (MacroHandler.getInstance().isTeleporting()) return;
        setRestoredState(false);
        if (FarmConfig.dontFixAfterWarping() && Math.abs(getYaw() - AngleUtils.get360RotationYaw()) < 0.1) return;
        easeToMacroRotation(!MacroHandler.getInstance().isResume());
    }

    @Override
    public void actionAfterTeleport() {
    }

    @Override
    public void updateState() {
        if (getCurrentState() == null) changeState(State.NONE);
        GameStateHandler gs = GameStateHandler.getInstance();
        switch (getCurrentState()) {
            case BACKWARD:
                if (gs.isFrontWalkable() && !gs.isBackWalkable() && gs.isRightWalkable()) changeState(State.SWITCHING_LANE);
                break;
            case FORWARD:
                if (!gs.isFrontWalkable() && gs.isBackWalkable() && gs.isRightWalkable() && !gs.isLeftWalkable()) {
                    changeState(State.SWITCHING_SIDE);
                } else if (gs.isBackWalkable()) {
                    changeState(State.BACKWARD);
                } else if (gs.isFrontWalkable()) {
                    changeState(State.FORWARD);
                } else {
                    changeState(State.NONE);
                }
                break;
            case SWITCHING_SIDE:
                if (gs.isBackWalkable() && !gs.isRightWalkable() && gs.isLeftWalkable()) changeState(State.BACKWARD);
                break;
            case SWITCHING_LANE:
                if (!gs.isBackWalkable() && !gs.isRightWalkable() && gs.isLeftWalkable()) changeState(State.FORWARD);
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
            case BACKWARD:
                KeyBindUtils.holdThese(mc.options.keyDown, mc.options.keyAttack);
                break;
            case FORWARD:
                KeyBindUtils.holdThese(mc.options.keyUp, mc.options.keyAttack, shouldHugWall() ? mc.options.keyLeft : null);
                break;
            case SWITCHING_LANE:
                if (hasLineChanged() && !GameStateHandler.getInstance().isBackWalkable() && GameStateHandler.getInstance().isLeftWalkable()) {
                    changeState(State.FORWARD);
                    break;
                }
                KeyBindUtils.holdThese(mc.options.keyRight);
                break;
            case SWITCHING_SIDE:
                KeyBindUtils.holdThese(mc.options.keyRight);
                break;
            default:
                break;
        }
    }

    @Override
    public State calculateDirection() {
        GameStateHandler gs = GameStateHandler.getInstance();
        if (gs.isFrontWalkable() && gs.isRightWalkable()) return State.FORWARD;
        if (gs.isBackWalkable()) return State.BACKWARD;
        if (gs.isFrontWalkable()) return State.FORWARD;
        return State.NONE;
    }

    private boolean isSolid(BlockState state) {
        return state.isSolid();
    }

    private boolean hasLineChanged() {
        if (isSolid(BlockUtils.getBlockState(BlockUtils.getRelativeBlockPos(-1, 0, 1))) && GameStateHandler.getInstance().isFrontWalkable()) {
            double decimalPartX = Math.abs(mc.player.getX()) % 1;
            double decimalPartZ = Math.abs(mc.player.getZ()) % 1;
            float yaw = AngleUtils.get360RotationYaw(AngleUtils.getClosest(mc.player.getYRot()));
            if (yaw == 180f && decimalPartX > 0.488) return true;
            if (yaw == 270f && decimalPartZ > 0.488) return true;
            if (yaw == 90f && decimalPartZ < 0.512) return true;
            return yaw == 0f && decimalPartX < 0.512;
        }
        return false;
    }

    private boolean shouldHugWall() {
        BlockState left = BlockUtils.getBlockState(BlockUtils.getRelativeBlockPos(-1, 0, 0));
        if (isSolid(left) && left.getBlock() != Blocks.AIR) {
            double x = mc.player.getX() % 1;
            double z = mc.player.getZ() % 1;
            float yaw = AngleUtils.get360RotationYaw(AngleUtils.getClosest(mc.player.getYRot()));
            if (FarmConfig.getMacro() == FarmConfig.MacroEnum.S_COCOA_BEANS_TRAPDOORS) {
                if (yaw == 0) return (x > -0.9 && x < -0.5) || (x < 0.5 && x > 0.1);
                if (yaw == 90) return (z > -0.9 && z < -0.5) || (z < 0.5 && z > 0.1);
                if (yaw == 180) return (x > -0.5 && x < -0.1) || (x < 0.9 && x > 0.5);
                if (yaw == 270) return (z > -0.5 && z < -0.1) || (z < 0.9 && z > 0.5);
            } else {
                if (yaw == 0) return (x > -0.9 && x < -0.35) || (x < 0.65 && x > 0.1);
                if (yaw == 90) return (z > -0.9 && z < -0.35) || (z < 0.65 && z > 0.1);
                if (yaw == 180) return (x > -0.65 && x < -0.1) || (x < 0.9 && x > 0.35);
                if (yaw == 270) return (z > -0.65 && z < -0.1) || (z < 0.9 && z > 0.35);
            }
        }
        return false;
    }
}
