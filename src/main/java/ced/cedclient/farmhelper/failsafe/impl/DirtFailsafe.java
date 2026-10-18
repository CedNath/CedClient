//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import ced.cedclient.farmhelper.util.BlockUtils;
import ced.cedclient.farmhelper.util.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/** Someone placed a solid block in your farm (dirt check): it's only flagged once you actually run into it. */
public class DirtFailsafe extends Failsafe {
    private static DirtFailsafe instance;
    private final List<Object[]> blocksDestroyedByPlayer = new ArrayList<>();
    private final List<Object[]> dirtBlocks = new ArrayList<>();

    public static DirtFailsafe getInstance() {
        if (instance == null) instance = new DirtFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 3; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.DIRT_CHECK; }

    @Override
    public void onBlockChange(BlockPos pos, BlockState old, BlockState update) {
        long now = System.currentTimeMillis();
        Block oldBlock = old.getBlock();
        Block newBlock = update.getBlock();
        if (newBlock == Blocks.AIR && !BlockUtils.isCrop(oldBlock)) blocksDestroyedByPlayer.add(new Object[]{pos, now});
        blocksDestroyedByPlayer.removeIf(e -> now - (long) e[1] > 10_000);
        boolean oldWasOpen = oldBlock == Blocks.AIR || BlockUtils.isCrop(oldBlock) || oldBlock == Blocks.WATER;
        boolean newIsSolid = newBlock != Blocks.AIR && !BlockUtils.isCrop(newBlock) && !(newBlock instanceof TrapDoorBlock)
                && !(newBlock instanceof LadderBlock) && newBlock != Blocks.WATER
                && update.isCollisionShapeFullBlock(mc.level, pos);
        if (oldWasOpen && newIsSolid) {
            if (blocksDestroyedByPlayer.stream().anyMatch(e -> e[0].equals(pos))) return;
            LogUtils.sendWarning("[Failsafe] Someone put a block on your garden! Block: " + newBlock.getName().getString() + " Pos: " + pos.toShortString());
            dirtBlocks.add(new Object[]{pos, now});
        }
    }

    // CedClient: blocks your own client broke (MultiPlayerGameModeMixin). Holding attack while walking can break the
    // dirt in front of you client-side; the server puts it back, which looked like "someone placed dirt".
    public void onPlayerBreak(BlockPos pos) {
        blocksDestroyedByPlayer.add(new Object[]{pos.immutable(), System.currentTimeMillis()});
    }

    public boolean hasDirtBlocks() {
        return !dirtBlocks.isEmpty();
    }

    public boolean isTouchingDirtBlock() {
        BlockPos me = mc.player.blockPosition();
        for (Object[] e : dirtBlocks) {
            BlockPos p = (BlockPos) e[0];
            if (Math.abs(p.getX() - me.getX()) <= 1 && Math.abs(p.getZ() - me.getZ()) <= 1 && p.getY() - me.getY() >= 0 && p.getY() - me.getY() <= 1) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void resetStates() {
        dirtBlocks.clear();
        blocksDestroyedByPlayer.clear();
    }
}
//#endif
