/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; trimmed to location, movement and the
 * walkable-side checks the crop macros need (location comes from CedClient's IslandState / Sidebar). See ../NOTICE.md.
 */
package ced.cedclient.farmhelper.handler;

import ced.cedclient.farmhelper.FarmConfig;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.failsafe.impl.CobwebFailsafe;
import ced.cedclient.farmhelper.failsafe.impl.DirtFailsafe;
import ced.cedclient.farmhelper.util.BlockUtils;
import ced.cedclient.farmhelper.util.KeyBindUtils;
import ced.cedclient.farmhelper.util.PlayerUtils;
import ced.cedclient.farmhelper.util.Timer;
import ced.cedclient.state.IslandState;
import ced.cedclient.state.Sidebar;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GameStateHandler {
    private static GameStateHandler instance;
    private final Minecraft mc = Minecraft.getInstance();
    private final Timer notMovingTimer = new Timer();
    private final Timer reWarpTimer = new Timer();
    private final Pattern serverClosingPattern = Pattern.compile("Server closing: (?<minutes>\\d+):(?<seconds>\\d+).*");

    public enum Location { GARDEN, LIMBO, OTHER, TELEPORTING }

    private Location location = Location.TELEPORTING;
    private boolean frontWalkable, rightWalkable, backWalkable, leftWalkable;
    private double dx, dy, dz;
    private long randomValueToWait = -1;
    private long randomValueToWaitNextTime = -1;
    private long randomRewarpValueToWait = -1;
    private boolean updatedState = false;
    private Optional<Integer> serverClosingSeconds = Optional.empty();
    private Object lastLevel;

    public static GameStateHandler getInstance() {
        if (instance == null) instance = new GameStateHandler();
        return instance;
    }

    public Location getLocation() { return location; }
    public boolean isFrontWalkable() { return frontWalkable; }
    public boolean isRightWalkable() { return rightWalkable; }
    public boolean isBackWalkable() { return backWalkable; }
    public boolean isLeftWalkable() { return leftWalkable; }
    public double getDx() { return dx; }
    public double getDz() { return dz; }
    public double getDy() { return dy; }
    public Optional<Integer> getServerClosingSeconds() { return serverClosingSeconds; }
    public void setUpdatedState(boolean value) { updatedState = value; }

    public void onChat(String message) {
        if (message.equals("You were spawned in Limbo.") || message.equals("You are AFK. Move around to return from AFK.")) {
            location = Location.LIMBO;
        }
    }

    /** Client tick (start). */
    public void onTick() {
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            location = Location.TELEPORTING;
            serverClosingSeconds = Optional.empty();
        }
        if (mc.level == null || mc.player == null) return;
        updateLocation();
        onTickCheckMoving();
        onTickCheckRewarp();
    }

    private void updateLocation() {
        if (location == Location.LIMBO) return;
        IslandState.Island island = IslandState.INSTANCE.getIsland();
        if (island == null) {
            location = Location.TELEPORTING;
        } else {
            location = island == IslandState.Island.GARDEN ? Location.GARDEN : Location.OTHER;
        }
        serverClosingSeconds = Optional.empty();
        for (String line : Sidebar.INSTANCE.getLines()) {
            Matcher m = serverClosingPattern.matcher(line.trim());
            if (m.matches()) {
                serverClosingSeconds = Optional.of(Integer.parseInt(m.group("minutes")) * 60 + Integer.parseInt(m.group("seconds")));
            }
        }
    }

    public void onTickCheckMoving() {
        dx = Math.abs(mc.player.getDeltaMovement().x);
        dy = Math.abs(mc.player.getDeltaMovement().y);
        dz = Math.abs(mc.player.getDeltaMovement().z);

        if (notMoving() && mc.screen == null) {
            if (hasPassedSinceStopped() && !PlayerUtils.isStandingOnRewarpLocation()) {
                if (DirtFailsafe.getInstance().hasDirtBlocks() && DirtFailsafe.getInstance().isTouchingDirtBlock()) {
                    FailsafeManager.getInstance().possibleDetection(DirtFailsafe.getInstance());
                } else if (!FailsafeManager.getInstance().firstCheckReturn()
                        && (CobwebFailsafe.getInstance().isTouchingCobwebBlock() || CobwebFailsafe.getInstance().hasCobwebs())) {
                    FailsafeManager.getInstance().possibleDetection(CobwebFailsafe.getInstance());
                } else if (notMovingTimer.isScheduled()) {
                    randomValueToWaitNextTime = -1;
                    notMovingTimer.reset();
                    randomValueToWait = FarmConfig.getRandomTimeBetweenChangingRows();
                    updatedState = false;
                }
            }
        } else {
            notMovingTimer.schedule();
        }

        float yaw;
        if (MacroHandler.getInstance().getCurrentMacro().isPresent() && MacroHandler.getInstance().getCurrentMacro().get().getClosest90Deg().isPresent()) {
            yaw = MacroHandler.getInstance().getCurrentMacro().get().getClosest90Deg().get();
        } else {
            yaw = mc.player.getYRot();
        }
        if (MacroHandler.getInstance().getCrop() == FarmConfig.CropEnum.CACTUS) {
            frontWalkable = BlockUtils.canWalkThroughDoor(BlockUtils.Direction2.FORWARD) && BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(0, 0, 1, yaw), BlockUtils.Direction2.FORWARD) && BlockUtils.getRelativeBlock(0, 0, 2, yaw) != Blocks.CACTUS;
            backWalkable = BlockUtils.canWalkThroughDoor(BlockUtils.Direction2.BACKWARD) && BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(0, 0, -1, yaw), BlockUtils.Direction2.BACKWARD) && BlockUtils.getRelativeBlock(0, 0, -2, yaw) != Blocks.CACTUS;
        } else {
            frontWalkable = BlockUtils.canWalkThroughDoor(BlockUtils.Direction2.FORWARD) && BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(0, 0, 1, yaw), BlockUtils.Direction2.FORWARD);
            backWalkable = BlockUtils.canWalkThroughDoor(BlockUtils.Direction2.BACKWARD) && BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(0, 0, -1, yaw), BlockUtils.Direction2.BACKWARD);
        }
        rightWalkable = BlockUtils.canWalkThroughDoor(BlockUtils.Direction2.RIGHT) && BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(1, 0, 0, yaw), BlockUtils.Direction2.RIGHT);
        leftWalkable = BlockUtils.canWalkThroughDoor(BlockUtils.Direction2.LEFT) && BlockUtils.canWalkThrough(BlockUtils.getRelativeBlockPos(-1, 0, 0, yaw), BlockUtils.Direction2.LEFT);
    }

    public void onTickCheckRewarp() {
        if (!MacroHandler.getInstance().isMacroToggled()) return;
        if (PlayerUtils.isStandingOnRewarpLocation()) {
            if (!reWarpTimer.isScheduled()) reWarpTimer.schedule();
        } else {
            reWarpTimer.reset();
        }
    }

    public boolean canRewarp() {
        if (randomRewarpValueToWait == -1) randomRewarpValueToWait = FarmConfig.getRandomRewarpDelay();
        return reWarpTimer.hasPassed(randomRewarpValueToWait);
    }

    public void scheduleRewarp() {
        randomRewarpValueToWait = FarmConfig.getRandomRewarpDelay();
        reWarpTimer.reset();
    }

    public boolean hasPassedSinceStopped() {
        if (randomValueToWait == -1) randomValueToWait = FarmConfig.getRandomTimeBetweenChangingRows();
        return notMovingTimer.hasPassed(randomValueToWaitNextTime != -1 ? randomValueToWaitNextTime : randomValueToWait);
    }

    public boolean notMoving() {
        if (dx < 0.01 && dz < 0.01 && dyIsRest() && mc.screen == null) return true;
        return !holdingKeybindIsWalkable() && (playerIsInFlowingWater(0) || playerIsInFlowingWater(1)) && mc.player.isInWater();
    }

    private boolean dyIsRest() {
        // Resting on a block gives motionY of about -0.0784; flying gives 0.
        return dy < 0.05 || dy <= 0.079 && dy >= 0.078;
    }

    public boolean playerIsInFlowingWater(int y) {
        BlockState state = mc.level.getBlockState(BlockUtils.getRelativeBlockPos(0, y, 0));
        if (state.getBlock() != Blocks.WATER) return false;
        return state.getValue(LiquidBlock.LEVEL) != 0;
    }

    public boolean holdingKeybindIsWalkable() {
        for (KeyMapping key : KeyBindUtils.getHoldingKeybinds()) {
            if (key == mc.options.keyUp && !frontWalkable && !FarmConfig.alwaysHoldW()) return false;
            if (key == mc.options.keyDown && !backWalkable) return false;
            if (key == mc.options.keyRight && !rightWalkable) return false;
            if (key == mc.options.keyLeft && !leftWalkable) return false;
        }
        return true;
    }

    public boolean canChangeDirection() {
        return !updatedState && !notMovingTimer.isScheduled();
    }

    public void scheduleNotMoving(int time) {
        randomValueToWaitNextTime = time;
        notMovingTimer.schedule();
    }

    public void scheduleNotMoving() {
        randomValueToWait = FarmConfig.getRandomTimeBetweenChangingRows();
        notMovingTimer.schedule();
    }

    public boolean inGarden() {
        return location == Location.GARDEN;
    }

    /** A visitor shows in the tab list's "Guests" line while they're in your garden. */
    public boolean isGuestInGarden() {
        return ced.cedclient.utils.TabListCache.INSTANCE.getEntries().stream()
                .anyMatch(e -> e.stripped.startsWith("Guests") && !e.stripped.contains("(0)"));
    }
}
