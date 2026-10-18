//#if CHEAT
/*
 * Ported from FarmHelper (https://github.com/JellyLabScripts/FarmHelper) by JellyLab and contributors.
 * Licensed under CC BY-NC-SA 4.0 (https://creativecommons.org/licenses/by-nc-sa/4.0/); this file stays under that
 * license. Changes: updated to Fabric / Minecraft 26.x for CedClient; detection only. See ../../NOTICE.md.
 */
package ced.cedclient.farmhelper.failsafe.impl;

import ced.cedclient.farmhelper.failsafe.Failsafe;
import ced.cedclient.farmhelper.failsafe.FailsafeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

public class CobwebFailsafe extends Failsafe {
    private static CobwebFailsafe instance;
    private final List<BlockPos> cobwebs = new ArrayList<>();

    public static CobwebFailsafe getInstance() {
        if (instance == null) instance = new CobwebFailsafe();
        return instance;
    }

    @Override public int getPriority() { return 3; }
    @Override public FailsafeManager.EmergencyType getType() { return FailsafeManager.EmergencyType.COBWEB_CHECK; }

    @Override
    public void onBlockChange(BlockPos pos, BlockState old, BlockState update) {
        if (update.getBlock() == Blocks.COBWEB && old.getBlock() != Blocks.COBWEB) cobwebs.add(pos.immutable());
    }

    public boolean hasCobwebs() {
        return !cobwebs.isEmpty();
    }

    public boolean isTouchingCobwebBlock() {
        BlockPos me = mc.player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(me.offset(-1, 0, -1), me.offset(1, 1, 1))) {
            if (mc.level.getBlockState(p).getBlock() == Blocks.COBWEB) return true;
        }
        return false;
    }

    @Override
    public void resetStates() {
        cobwebs.clear();
    }
}
//#endif
