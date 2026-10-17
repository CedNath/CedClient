/*
 * Written for CedClient on top of the FarmHelper port (https://github.com/JellyLabScripts/FarmHelper, JellyLab and
 * contributors). Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/), like the code it
 * builds on. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.macro.impl;

import ced.cedclient.farmhelper.handler.MacroHandler;
import ced.cedclient.farmhelper.macro.AbstractMacro;
import ced.cedclient.farmhelper.util.AngleUtils;
import ced.cedclient.features.impl.funqol.FarmHelper;

import java.util.Optional;

/**
 * The only farm layout: hold the crop's Lane 1 keys until you stop moving, then its Lane 2 keys, and repeat.
 * Yaw / pitch come from Crop Config (AbstractMacro's getters); rewarps, drops and failsafes are AbstractMacro's.
 * LEFT = lane 1, RIGHT = lane 2.
 */
public class LaneMacro extends AbstractMacro {
    private int lane = 1;

    @Override
    public void onEnable() {
        super.onEnable();
        // Base yaw = the direction you face; Crop Config turns it to your crop yaw in that direction.
        if (!isYawSet()) {
            setYaw(AngleUtils.getClosest());
            setClosest90Deg(Optional.of(AngleUtils.getClosest(getYaw())));
        }
        if (!isPitchSet()) setPitch(mc.player.getXRot());
        // Keep the lane from before it was turned off (one LaneMacro instance lives for the whole session).
        if (!isRestoredState()) changeState(lane == 1 ? State.LEFT : State.RIGHT);
        if (MacroHandler.getInstance().isTeleporting()) return;
        setRestoredState(false);
        easeToMacroRotation(!MacroHandler.getInstance().isResume());
    }

    /** You stopped moving: the lane (or a drop to the next layer) is over, so walk the other lane. */
    @Override
    public void updateState() {
        lane = 3 - lane;
        changeState(lane == 1 ? State.LEFT : State.RIGHT);
    }

    @Override
    public void invokeState() {
        if (getCurrentState() == State.LEFT || getCurrentState() == State.RIGHT) FarmHelper.INSTANCE.holdLane(lane);
    }

    @Override
    public void actionAfterTeleport() {
        lane = 1;
        changeState(State.LEFT);
    }

    @Override
    public State calculateDirection() {
        return lane == 1 ? State.LEFT : State.RIGHT;
    }
}
