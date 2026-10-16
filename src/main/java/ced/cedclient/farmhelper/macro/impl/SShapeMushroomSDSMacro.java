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

import java.util.Optional;

public class SShapeMushroomSDSMacro extends AbstractMacro {
    private float base() {
        return getClosest90Deg().orElse(AngleUtils.getClosest());
    }

    @Override
    public void onEnable() {
        super.onEnable();
        if (!isPitchSet()) setPitch((float) (6.5f + Math.random() * 1f));
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
            case LEFT:
                if (gs.isBackWalkable() && !FarmConfig.alwaysHoldW()) changeState(State.SWITCHING_LANE);
                else changeState(gs.isLeftWalkable() ? State.LEFT : State.NONE);
                break;
            case RIGHT:
                if (gs.isBackWalkable() && !FarmConfig.alwaysHoldW()) changeState(State.SWITCHING_LANE);
                else changeState(gs.isRightWalkable() ? State.RIGHT : State.NONE);
                break;
            case DROPPING:
                if (mc.player.onGround()) changeState(State.NONE);
                else gs.scheduleNotMoving();
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
                KeyBindUtils.holdThese(mc.options.keyRight, mc.options.keyAttack);
                break;
            case LEFT:
                KeyBindUtils.holdThese(mc.options.keyLeft, mc.options.keyAttack);
                break;
            case SWITCHING_LANE:
                if (!BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(0, 0, -1, base()))) {
                    changeState(State.NONE);
                    KeyBindUtils.stopMovement();
                } else {
                    KeyBindUtils.holdThese(mc.options.keyDown, mc.options.keyAttack);
                }
                break;
            default:
                break;
        }
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
